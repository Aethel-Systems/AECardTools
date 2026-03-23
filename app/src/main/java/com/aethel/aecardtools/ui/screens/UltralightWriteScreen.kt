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

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.model.CardType
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import kotlinx.coroutines.launch

private enum class UltralightSecretDialog {
    NONE,
    PWD_AUTH,
    UL_C_AUTH_2,
    RAW_COMMAND
}

@Composable
fun UltralightWriteScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val card = uiState.currentCard
    val pageCount = remember(card?.cardType, card?.sectorCount) {
        when (card?.cardType) {
            CardType.ULTRALIGHT -> card.sectorCount.takeIf { it > 0 } ?: 16
            CardType.ULTRALIGHT_C -> card.sectorCount.takeIf { it > 0 } ?: 48
            CardType.NTAG -> card?.sectorCount?.takeIf { it > 0 } ?: 45
            else -> 16
        }
    }

    var selectedPage by remember { mutableIntStateOf(0) }
    var currentPageHex by remember { mutableStateOf("") }
    var editHex by remember { mutableStateOf("") }
    var compatibilityHex by remember { mutableStateOf("") }
    var fastReadStart by remember { mutableIntStateOf(4) }
    var fastReadEnd by remember { mutableIntStateOf(15) }
    var counterIndex by remember { mutableIntStateOf(0) }
    var counterIncrement by remember { mutableStateOf("1") }
    var commandDialog by remember { mutableStateOf(UltralightSecretDialog.NONE) }
    var secretInput by remember { mutableStateOf("") }
    var lastOperation by remember { mutableStateOf("") }
    var lastResponse by remember { mutableStateOf("") }
    var showWriteConfirm by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }

    val normalized = remember(editHex) { normalizeHex(editHex) }
    val normalizedCompat = remember(compatibilityHex) { normalizeHex(compatibilityHex) }
    val noCommandYet = stringResource(R.string.ultralight_no_command_yet)

    fun launchAndCapture(label: String, block: suspend () -> Result<String>) {
        scope.launch {
            lastOperation = label
            block()
                .onSuccess { lastResponse = it.ifBlank { "<empty>" } }
                .onFailure { lastResponse = "ERROR: ${it.message}" }
        }
    }

    LaunchedEffect(card?.uid, selectedPage, refreshToken) {
        if (card == null) return@LaunchedEffect
        val result = viewModel.readUltralightPage(selectedPage)
        if (result.isSuccess) {
            currentPageHex = result.getOrNull().orEmpty().take(8).padEnd(8, '0')
            if (editHex.isBlank()) editHex = currentPageHex
            if (compatibilityHex.isBlank()) compatibilityHex = currentPageHex.repeat(4)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
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
            Text(stringResource(R.string.ultralight_console_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF57C00))
                    Text(stringResource(R.string.ultralight_console_notice), color = Color(0xFFE65100))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_page_selector), style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 0 until pageCount) {
                            FilterChip(
                                selected = selectedPage == i,
                                onClick = {
                                    selectedPage = i
                                    editHex = ""
                                },
                                label = { Text(i.toString()) }
                            )
                        }
                    }
                    Text(stringResource(R.string.ultralight_current_page, if (currentPageHex.isBlank()) "--" else currentPageHex), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_single_page_section), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = editHex,
                        onValueChange = { editHex = it },
                        label = { Text(stringResource(R.string.ultralight_write_data_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                    Text("${normalized.length} / 8", style = MaterialTheme.typography.labelSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                launchAndCapture("READ Page $selectedPage") {
                                    viewModel.readUltralightPage(selectedPage)
                                }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ultralight_read_page))
                        }
                        Button(
                            onClick = { showWriteConfirm = true },
                            enabled = !uiState.isLoading && normalized.length == 8,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ultralight_write_page))
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_extended_commands), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = fastReadStart.toString(),
                            onValueChange = { fastReadStart = it.toIntOrNull() ?: fastReadStart },
                            label = { Text(stringResource(R.string.ultralight_start_page)) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fastReadEnd.toString(),
                            onValueChange = { fastReadEnd = it.toIntOrNull() ?: fastReadEnd },
                            label = { Text(stringResource(R.string.ultralight_end_page)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                launchAndCapture("FAST_READ $fastReadStart-$fastReadEnd") {
                                    viewModel.fastReadUltralight(fastReadStart, fastReadEnd)
                                }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("FAST_READ")
                        }
                        Button(
                            onClick = {
                                launchAndCapture("GET_VERSION") { viewModel.getUltralightVersion() }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GET_VERSION")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                launchAndCapture("READ_SIG") { viewModel.readUltralightSignature() }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("READ_SIG")
                        }
                        Button(
                            onClick = { commandDialog = UltralightSecretDialog.RAW_COMMAND; secretInput = "" },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.ultralight_raw_command))
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_counter_section), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = counterIndex.toString(),
                            onValueChange = { counterIndex = it.toIntOrNull() ?: counterIndex },
                            label = { Text(stringResource(R.string.ultralight_counter_index)) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = counterIncrement,
                            onValueChange = { counterIncrement = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.ultralight_increment)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                launchAndCapture("READ_CNT $counterIndex") {
                                    viewModel.readUltralightCounter(counterIndex)
                                }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("READ_CNT")
                        }
                        Button(
                            onClick = {
                                launchAndCapture("INCR_CNT $counterIndex") {
                                    viewModel.incrementUltralightCounter(counterIndex, counterIncrement.toIntOrNull() ?: 1)
                                }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("INCR_CNT")
                        }
                    }
                    OutlinedTextField(
                        value = compatibilityHex,
                        onValueChange = { compatibilityHex = it },
                        label = { Text(stringResource(R.string.ultralight_compat_write_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                lastOperation = "COMPATIBILITY_WRITE Page $selectedPage"
                                viewModel.compatibilityWriteUltralight(selectedPage, normalizedCompat)
                                    .onSuccess { result -> lastResponse = result.toString() }
                                    .onFailure { lastResponse = "ERROR: ${it.message}" }
                            }
                        },
                        enabled = !uiState.isLoading && normalizedCompat.length == 32,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("COMPATIBILITY_WRITE")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_auth_section), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                commandDialog = UltralightSecretDialog.PWD_AUTH
                                secretInput = ""
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PWD_AUTH")
                        }
                        Button(
                            onClick = {
                                launchAndCapture("UL-C AUTH STEP 1") { viewModel.authenticateUltralightCStep1() }
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ultralight_ucl_step1))
                        }
                    }
                    Button(
                        onClick = {
                            commandDialog = UltralightSecretDialog.UL_C_AUTH_2
                            secretInput = ""
                        },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ultralight_ucl_step2))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_recent_result), style = MaterialTheme.typography.labelLarge)
                    Text(lastOperation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (lastResponse.isBlank()) noCommandYet else lastResponse,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showWriteConfirm) {
        AlertDialog(
            onDismissRequest = { showWriteConfirm = false },
            title = { Text(stringResource(R.string.ultralight_write_confirm_title, selectedPage)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ultralight_write_confirm_target, selectedPage))
                    Text(stringResource(R.string.ultralight_write_confirm_value, normalized), fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.ultralight_write_confirm_warning), color = Color(0xFFE65100))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWriteConfirm = false
                        scope.launch {
                            lastOperation = "WRITE Page $selectedPage"
                            viewModel.writeUltralightPage(selectedPage, normalized)
                                .onSuccess {
                                    lastResponse = "WRITE_OK"
                                    refreshToken += 1
                                }
                                .onFailure { lastResponse = "ERROR: ${it.message}" }
                        }
                    }
                ) {
                    Text(stringResource(R.string.ultralight_write_page))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWriteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (commandDialog != UltralightSecretDialog.NONE) {
        val dialogType = commandDialog
        val title = when (dialogType) {
            UltralightSecretDialog.PWD_AUTH -> stringResource(R.string.ultralight_pwd_dialog_title)
            UltralightSecretDialog.UL_C_AUTH_2 -> stringResource(R.string.ultralight_ulc2_dialog_title)
            UltralightSecretDialog.RAW_COMMAND -> stringResource(R.string.ultralight_raw_dialog_title)
            UltralightSecretDialog.NONE -> ""
        }
        val hint = when (dialogType) {
            UltralightSecretDialog.PWD_AUTH -> stringResource(R.string.ultralight_pwd_dialog_hint)
            UltralightSecretDialog.UL_C_AUTH_2 -> stringResource(R.string.ultralight_ulc2_dialog_hint)
            UltralightSecretDialog.RAW_COMMAND -> stringResource(R.string.ultralight_raw_dialog_hint)
            UltralightSecretDialog.NONE -> ""
        }
        AlertDialog(
            onDismissRequest = { commandDialog = UltralightSecretDialog.NONE },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text(hint) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val payload = normalizeHex(secretInput)
                        commandDialog = UltralightSecretDialog.NONE
                        when (dialogType) {
                            UltralightSecretDialog.PWD_AUTH -> {
                                launchAndCapture("PWD_AUTH") { viewModel.authenticateUltralightPassword(payload) }
                            }
                            UltralightSecretDialog.UL_C_AUTH_2 -> {
                                launchAndCapture("UL-C AUTH STEP 2") { viewModel.authenticateUltralightCStep2(payload) }
                            }
                            UltralightSecretDialog.RAW_COMMAND -> {
                                launchAndCapture("RAW_COMMAND") { viewModel.transceiveUltralightCommand(payload, "RAW_COMMAND") }
                            }
                            UltralightSecretDialog.NONE -> Unit
                        }
                    }
                ) {
                    Text(stringResource(R.string.ultralight_send))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { commandDialog = UltralightSecretDialog.NONE }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun normalizeHex(raw: String): String {
    return raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }
}
