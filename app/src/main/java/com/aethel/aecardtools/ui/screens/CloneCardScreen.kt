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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.model.BlockData
import com.aethel.aecardtools.data.model.CardIdentity
import com.aethel.aecardtools.data.model.SectorData
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

private data class CloneSourceSnapshot(
    val card: CardIdentity,
    val sectors: Map<Int, SectorData>
)

private data class CloneRunResult(
    val successCount: Int,
    val totalBlocks: Int,
    val failedBlocks: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneCardScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val sourceCard = uiState.currentCard
    val scope = rememberCoroutineScope()
    val waitingStart = stringResource(R.string.clone_waiting_start)
    val defaultPlan = stringResource(R.string.clone_default_plan)
    val noSectorSelected = stringResource(R.string.clone_no_sector_selected)
    val phasePrepareTarget = stringResource(R.string.clone_phase_prepare_target)

    var showPlanDialog by remember { mutableStateOf(false) }
    var isCloning by remember { mutableStateOf(false) }
    var cloneProgress by remember { mutableStateOf(0f) }
    var clonePhase by remember { mutableStateOf(waitingStart) }
    var lastPlanSummary by remember { mutableStateOf(defaultPlan) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.clone_title), color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.clone_source_card), fontWeight = FontWeight.Bold)
                        if (sourceCard == null || uiState.sectors.isEmpty()) {
                            Text(stringResource(R.string.clone_source_missing), color = MaterialTheme.colorScheme.error)
                        } else {
                            KeyValueRow(stringResource(R.string.card_detail_uid), sourceCard.uid)
                            KeyValueRow(stringResource(R.string.clone_info_type), sourceCard.cardType.name)
                            KeyValueRow(stringResource(R.string.clone_info_sector_count), sourceCard.sectorCount.toString())
                            KeyValueRow(stringResource(R.string.clone_info_cached_sectors), uiState.sectors.keys.sorted().joinToString(", "))
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.clone_write_strategy), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.clone_write_strategy_desc))
                        Text(stringResource(R.string.clone_current_plan, lastPlanSummary), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF57C00))
                        Column {
                            Text(stringResource(R.string.clone_target_hint_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.clone_target_hint_desc),
                                style = MaterialTheme.typography.bodySmall
                            )
                            uiState.currentCard?.let {
                                Text(stringResource(R.string.clone_current_uid, it.uid), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (isCloning) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.clone_progress), fontWeight = FontWeight.Bold)
                            Text(clonePhase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(progress = { cloneProgress }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.clone_progress_percent, (cloneProgress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF57F17))
                        Column {
                            Text(stringResource(R.string.clone_warning_title), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.clone_warning_desc), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = !isCloning
            ) {
                Text(stringResource(R.string.back))
            }
            Button(
                onClick = { showPlanDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = !isCloning && sourceCard != null && uiState.sectors.isNotEmpty()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text(stringResource(R.string.clone_start))
            }
        }
    }

    if (showPlanDialog && sourceCard != null) {
        val snapshot = remember(sourceCard.uid, uiState.sectors) { CloneSourceSnapshot(sourceCard, uiState.sectors) }
        CloneSectorPlanDialog(
            snapshot = snapshot,
            liveCard = uiState.currentCard,
            onDismiss = { showPlanDialog = false },
            onStart = { selectedSectors ->
                showPlanDialog = false
                lastPlanSummary = selectedSectors.sorted().joinToString(", ").ifBlank { noSectorSelected }
                isCloning = true
                cloneProgress = 0f
                clonePhase = phasePrepareTarget

                scope.launch {
                    try {
                        val result = performClone(
                            context = context,
                            snapshot = snapshot,
                            selectedSectors = selectedSectors.sorted(),
                            onPhase = { clonePhase = it },
                            onProgress = { cloneProgress = it }
                        )
                        isCloning = false
                        cloneProgress = 1f
                        if (result.failedBlocks.isEmpty()) {
                            viewModel.updateUIMessage(
                                context.getString(
                                    R.string.clone_result_success,
                                    result.successCount,
                                    result.totalBlocks
                                )
                            )
                        } else {
                            viewModel.updateUIMessage(
                                context.getString(
                                    R.string.clone_result_partial,
                                    result.successCount,
                                    result.totalBlocks,
                                    result.failedBlocks.joinToString()
                                ),
                                isError = false
                            )
                        }
                    } catch (e: Exception) {
                        isCloning = false
                        viewModel.updateUIMessage(
                            context.getString(
                                R.string.clone_result_failed,
                                e.message ?: ""
                            ),
                            isError = true
                        )
                        Timber.e(e, "克隆失败")
                    }
                }
            }
        )
    }
}

@Composable
private fun CloneSectorPlanDialog(
    snapshot: CloneSourceSnapshot,
    liveCard: CardIdentity?,
    onDismiss: () -> Unit,
    onStart: (Set<Int>) -> Unit
) {
    val availableSectors = remember(snapshot) { snapshot.sectors.keys.sorted() }
    val defaultSelection = remember(availableSectors) { availableSectors.filter { it in 1..15 }.toSet() }
    var selectedSectors by remember(snapshot.card.uid) { mutableStateOf(defaultSelection) }
    var sector0Unlocked by remember(snapshot.card.uid) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clone_select_sectors)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.clone_source_uid, snapshot.card.uid), fontFamily = FontFamily.Monospace)
                liveCard?.let {
                    Text(stringResource(R.string.clone_current_uid, it.uid), fontFamily = FontFamily.Monospace)
                }
                Text(stringResource(R.string.clone_sector_default_desc), style = MaterialTheme.typography.bodySmall)

                SectorSelectionRow(
                    label = stringResource(R.string.clone_allow_sector0),
                    checked = sector0Unlocked,
                    onCheckedChange = { checked ->
                        sector0Unlocked = checked
                        if (!checked) {
                            selectedSectors = selectedSectors - 0
                        }
                    }
                )

                if (sector0Unlocked && 0 in availableSectors) {
                    SectorSelectionRow(
                        label = stringResource(R.string.clone_sector0),
                        checked = 0 in selectedSectors,
                        onCheckedChange = { checked ->
                            selectedSectors = if (checked) selectedSectors + 0 else selectedSectors - 0
                        }
                    )
                }

                availableSectors.filter { it != 0 }.forEach { sector ->
                    SectorSelectionRow(
                        label = stringResource(R.string.clone_sector_label, sector),
                        checked = sector in selectedSectors,
                        onCheckedChange = { checked ->
                            selectedSectors = if (checked) selectedSectors + sector else selectedSectors - sector
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(selectedSectors) },
                enabled = selectedSectors.isNotEmpty()
            ) {
                Text(stringResource(R.string.clone_start_write))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.back))
            }
        }
    )
}

@Composable
private fun SectorSelectionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private suspend fun performClone(
    context: Context,
    snapshot: CloneSourceSnapshot,
    selectedSectors: List<Int>,
    onPhase: (String) -> Unit,
    onProgress: (Float) -> Unit
): CloneRunResult = withContext(Dispatchers.IO) {
    if (selectedSectors.isEmpty()) {
        throw IllegalArgumentException(context.getString(R.string.clone_error_no_sector))
    }

    val writePlan = selectedSectors.flatMap { sector ->
        val sectorData = snapshot.sectors[sector]
            ?: throw IllegalStateException(context.getString(R.string.clone_error_missing_sector_cache, sector))
        sectorData.blocks.sortedBy(BlockData::block).map { block -> sector to block }
    }

    val py = Python.getInstance()
    val ffiModule = py.getModule("ffi_bridge")
    val failedBlocks = mutableListOf<String>()
    var successCount = 0

    onPhase(context.getString(R.string.clone_phase_locked_source))
    writePlan.forEachIndexed { index, (sector, block) ->
        onPhase(context.getString(R.string.clone_phase_write_block, sector, block.block))
        val response = ffiModule.callAttr("write_block_data", sector, block.block, block.data).toString()
        val result = JSONObject(response)
        if (result.optBoolean("success", false)) {
            successCount++
        } else {
            failedBlocks += "S${sector}B${block.block}"
        }
        onProgress((index + 1).toFloat() / writePlan.size.toFloat())
    }

    CloneRunResult(
        successCount = successCount,
        totalBlocks = writePlan.size,
        failedBlocks = failedBlocks
    )
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}
