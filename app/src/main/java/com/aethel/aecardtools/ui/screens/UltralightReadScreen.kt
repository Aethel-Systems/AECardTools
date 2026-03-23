/*
 * Copyright (C) 2025-2026  Aethel-Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aethel.aecardtools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.CardExportManager
import com.aethel.aecardtools.data.ExportFileManager
import com.aethel.aecardtools.data.model.CardType
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState

@Composable
fun UltralightReadScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<CardExportManager.PreparedExport?>(null) }
    val card = uiState.currentCard
    val pageCount = remember(card?.cardType, card?.sectorCount) {
        when (card?.cardType) {
            CardType.ULTRALIGHT -> card.sectorCount.takeIf { it > 0 } ?: 16
            CardType.ULTRALIGHT_C -> card.sectorCount.takeIf { it > 0 } ?: 48
            CardType.NTAG -> card?.sectorCount?.takeIf { it > 0 } ?: 45
            else -> 16
        }
    }

    var selectedPage by remember { mutableStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val pageHex = uiState.ultralightPages[selectedPage].orEmpty()
    val blockHex = uiState.ultralightReadWindows[selectedPage].orEmpty()

    LaunchedEffect(card?.uid) {
        selectedPage = 0
        refreshToken = 0
    }

    LaunchedEffect(card?.uid, selectedPage, refreshToken) {
        if (card == null) return@LaunchedEffect
        if (refreshToken == 0 && uiState.ultralightPages.containsKey(selectedPage)) {
            return@LaunchedEffect
        }
        reading = true
        runCatching { viewModel.readUltralightPage(selectedPage) }
            .onFailure { }
        reading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        runCatching {
            ExportFileManager.writeBytesToUri(context, uri, export.bytes)
            viewModel.updateUIMessage(
                context.getString(
                    R.string.export_status_success,
                    export.format.uppercase(),
                    if (export.isPartial) context.getString(R.string.export_partial_suffix) else ""
                )
            )
        }.onFailure {
            viewModel.updateUIMessage(
                context.getString(R.string.export_failed_generic, it.message ?: ""),
                isError = true
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F9FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1976D2))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(stringResource(R.string.ultralight_read_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ultralight_read_detected_card), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ultralight_read_uid, card?.uid ?: "--"), fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.ultralight_read_type, card?.cardType?.name ?: "UNKNOWN"))
                    Text(stringResource(R.string.ultralight_read_page_count, pageCount))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ultralight_page_selector), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 0 until pageCount) {
                            FilterChip(
                                selected = selectedPage == i,
                                onClick = { selectedPage = i },
                                label = { Text(i.toString()) }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFF1565C0))
                        Text(stringResource(R.string.ultralight_read_current_page_data), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        text = if (pageHex.isBlank()) "--" else pageHex,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.ultralight_read_response_label), style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = if (blockHex.isBlank()) "--" else blockHex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (reading || uiState.isLoading) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ultralight_read_loading))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    refreshToken += 1
                },
                enabled = card != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(" ${stringResource(R.string.ultralight_reread_current)}")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.read_export_options), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("HEX", "BIN", "JSON", "MD").forEach { formatLabel ->
                            ElevatedButton(
                                onClick = {
                                    try {
                                        val export = CardExportManager.prepareCurrentCardExport(
                                            card = uiState.currentCard,
                                            sectors = uiState.sectors,
                                            ultralightPages = uiState.ultralightPages,
                                            format = formatLabel.lowercase()
                                        )
                                        pendingExport = export
                                        exportLauncher.launch(export.suggestedFileName)
                                    } catch (e: Exception) {
                                        viewModel.updateUIMessage(
                                            context.getString(R.string.export_failed_generic, e.message ?: ""),
                                            isError = true
                                        )
                                    }
                                },
                                enabled = card != null
                            ) {
                                Text(formatLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}
