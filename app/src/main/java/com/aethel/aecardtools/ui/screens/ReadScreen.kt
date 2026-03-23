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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.CardExportManager
import com.aethel.aecardtools.data.ExportFileManager
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import com.chaquo.python.Python
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<CardExportManager.PreparedExport?>(null) }
    var selectedSector by remember { mutableStateOf(0) }
    var selectedBlockInSector by remember { mutableStateOf(0) }
    var ffiBlockData by remember { mutableStateOf("") }
    val sectorCount = uiState.currentCard?.sectorCount?.takeIf { it > 0 } ?: 16
    val blockCountInSelectedSector = uiState.sectors[selectedSector]?.blocks?.size?.takeIf { it > 0 }
        ?: if (uiState.currentCard?.cardType == com.aethel.aecardtools.data.model.CardType.CLASSIC_4K && selectedSector >= 32) 16 else 4

    LaunchedEffect(sectorCount) {
        if (selectedSector >= sectorCount) selectedSector = 0
    }
    LaunchedEffect(blockCountInSelectedSector) {
        if (selectedBlockInSector >= blockCountInSelectedSector) selectedBlockInSector = 0
    }

    LaunchedEffect(selectedSector, selectedBlockInSector, uiState.currentCard?.uid) {
        ffiBlockData = loadBlockDataFromFfi(selectedSector, selectedBlockInSector)
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
            .background(Color(0xFFFAFAFA))
    ) {
        // 顶部标题栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1976D2))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Text(stringResource(R.string.read_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 卡片信息
            item {
                if (uiState.currentCard != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(stringResource(R.string.read_detected_card), style = MaterialTheme.typography.labelMedium)
                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("UID:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    uiState.currentCard.uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.read_type), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    uiState.currentCard.cardType.name,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.read_sector_count), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    uiState.currentCard.sectorCount.toString(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFF1976D2),
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                stringResource(R.string.read_hold_card),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // 十六进制编辑器
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.read_hex_editor),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        // 扇区选择
                        Column {
                            Text(
                                stringResource(R.string.read_sector_picker, sectorCount - 1),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (i in 0 until sectorCount) {
                                    FilterChip(
                                        selected = selectedSector == i,
                                        onClick = { selectedSector = i },
                                        label = { Text(i.toString(), fontSize = 10.sp) },
                                        modifier = Modifier.size(width = 40.dp, height = 32.dp)
                                    )
                                }
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 6.dp))

                        // 块选择
                        Column {
                            Text(
                                stringResource(R.string.read_block_picker, blockCountInSelectedSector - 1),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (i in 0 until blockCountInSelectedSector) {
                                    FilterChip(
                                        selected = selectedBlockInSector == i,
                                        onClick = { selectedBlockInSector = i },
                                        label = { Text(i.toString()) }
                                    )
                                }
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 6.dp))

                        // HEX数据显示（真实数据，从当前卡片读取）
                        Text(
                            stringResource(R.string.read_current_block_hex),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // 读取实际块数据
                        val blockHexData = remember(selectedSector, selectedBlockInSector, uiState.currentCard, uiState.sectors, ffiBlockData) {
                            // 从当前卡片数据中读取实际的块数据
                            val card = uiState.currentCard
                            if (card != null && selectedSector < card.sectorCount) {
                                val sectorData = uiState.sectors[selectedSector]
                                val blockData = sectorData?.blocks?.getOrNull(selectedBlockInSector)
                                blockData?.data?.takeIf { it.isNotBlank() }
                                    ?: ffiBlockData.takeIf { it.isNotBlank() }
                                    ?: findFirstReadableBlockData(uiState.sectors)
                                    ?: context.getString(R.string.read_unread_data)
                            } else {
                                context.getString(R.string.read_no_card_data)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 120.dp)
                                .background(Color(0xFFF5F5F5))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                            color = Color(0xFFFAFAFA)
                        ) {
                            Text(
                                blockHexData,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.DarkGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }

            // 扫描进度
            if (uiState.nfcIsScanning) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.read_scan_progress),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Text(
                                stringResource(R.string.read_scanning_hint),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // 导出选项
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.read_export_options),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ElevatedButton(
                                onClick = {
                                    try {
                                        val export = prepareCurrentCardDataExport(uiState, "hex")
                                        pendingExport = export
                                        exportLauncher.launch(export.suggestedFileName)
                                    } catch (e: Exception) {
                                        viewModel.updateUIMessage(context.getString(R.string.read_export_failed, e.message), isError = true)
                                    }
                                },
                                enabled = uiState.currentCard != null
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("HEX", fontSize = 12.sp)
                            }

                            ElevatedButton(
                                onClick = {
                                    try {
                                        val export = prepareCurrentCardDataExport(uiState, "bin")
                                        pendingExport = export
                                        exportLauncher.launch(export.suggestedFileName)
                                    } catch (e: Exception) {
                                        viewModel.updateUIMessage(context.getString(R.string.read_export_failed, e.message), isError = true)
                                    }
                                },
                                enabled = uiState.currentCard != null
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("BIN", fontSize = 12.sp)
                            }

                            ElevatedButton(
                                onClick = {
                                    try {
                                        val export = prepareCurrentCardDataExport(uiState, "json")
                                        pendingExport = export
                                        exportLauncher.launch(export.suggestedFileName)
                                    } catch (e: Exception) {
                                        viewModel.updateUIMessage(context.getString(R.string.read_export_failed, e.message), isError = true)
                                    }
                                },
                                enabled = uiState.currentCard != null
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("JSON", fontSize = 12.sp)
                            }

                            ElevatedButton(
                                onClick = {
                                    try {
                                        val export = prepareCurrentCardDataExport(uiState, "md")
                                        pendingExport = export
                                        exportLauncher.launch(export.suggestedFileName)
                                    } catch (e: Exception) {
                                        viewModel.updateUIMessage(context.getString(R.string.read_export_failed, e.message), isError = true)
                                    }
                                },
                                enabled = uiState.currentCard != null
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("MD", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 底部操作栏
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
                enabled = !uiState.nfcIsScanning
            ) {
                Text(stringResource(R.string.back))
            }

            Button(
                onClick = {
                    // 启动全局扫描状态，实际卡片检测由 MainActivity.ReaderCallback 处理
                    viewModel.setCurrentActionMode("read_scan")
                    viewModel.setNFCScanning(true)
                    viewModel.updateUIMessage(context.getString(R.string.read_start_scan))
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                enabled = !uiState.nfcIsScanning
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.read_scan_button))
            }
        }
    }
}

private fun loadBlockDataFromFfi(sector: Int, block: Int): String {
    return try {
        val py = Python.getInstance()
        val module = py.getModule("ffi_bridge")
        val result = module.callAttr("get_block_data", sector, block).toString()
        val json = JSONObject(result)
        if (json.optBoolean("success", false)) json.optString("data_hex", "") else ""
    } catch (_: Exception) {
        ""
    }
}

private fun findFirstReadableBlockData(sectors: Map<Int, com.aethel.aecardtools.data.model.SectorData>): String? {
    return sectors.toSortedMap()
        .values
        .asSequence()
        .flatMap { it.blocks.asSequence() }
        .map { it.data }
        .firstOrNull { it.isNotBlank() }
}

private fun prepareCurrentCardDataExport(
    uiState: UIState,
    format: String
): CardExportManager.PreparedExport {
    return CardExportManager.prepareCurrentCardExport(
        card = uiState.currentCard,
        sectors = uiState.sectors,
        ultralightPages = uiState.ultralightPages,
        format = format
    )
}
