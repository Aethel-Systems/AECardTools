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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import com.aethel.aecardtools.data.WriteControlPolicy
import com.chaquo.python.Python
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val invalidLengthMessage = stringResource(R.string.write_invalid_length)
    val mustAcceptDisclaimerMessage = stringResource(R.string.write_must_accept_disclaimer)
    var selectedSector by remember { mutableStateOf(0) }
    var selectedBlockInSector by remember { mutableStateOf(0) }
    var hexDataToWrite by remember { mutableStateOf("") }
    var isWriting by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showWriteResultDialog by remember { mutableStateOf(false) }
    var writeResultIsError by remember { mutableStateOf(false) }
    var writeResultMessage by remember { mutableStateOf("") }
    var lastHandledWriteMessage by remember { mutableStateOf("") }
    var ffiBlockData by remember { mutableStateOf("") }
    val sectorCount = uiState.currentCard?.sectorCount?.takeIf { it > 0 } ?: 16
    val blockCountInSelectedSector = uiState.sectors[selectedSector]?.blocks?.size?.takeIf { it > 0 }
        ?: if (uiState.currentCard?.cardType == com.aethel.aecardtools.data.model.CardType.CLASSIC_4K && selectedSector >= 32) 16 else 4
    val isAEFSCard = uiState.currentCard?.isAEFS == true
    val normalizedHexData = remember(hexDataToWrite) {
        hexDataToWrite.uppercase().filter { it.isDigit() || it in 'A'..'F' }
    }
    val currentBlockData = remember(selectedSector, selectedBlockInSector, uiState.sectors, ffiBlockData) {
        uiState.sectors[selectedSector]
            ?.blocks
            ?.firstOrNull { it.block == selectedBlockInSector }
            ?.data
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?: ffiBlockData.takeIf { it.isNotBlank() }
            ?: findFirstReadableBlockData(uiState.sectors)
            ?: ""
    }

    LaunchedEffect(sectorCount) {
        if (selectedSector >= sectorCount) selectedSector = 0
    }
    LaunchedEffect(blockCountInSelectedSector) {
        if (selectedBlockInSector >= blockCountInSelectedSector) selectedBlockInSector = 0
    }

    LaunchedEffect(selectedSector, selectedBlockInSector, uiState.currentCard?.uid) {
        ffiBlockData = loadBlockDataFromFfi(selectedSector, selectedBlockInSector)
    }

    LaunchedEffect(selectedSector, selectedBlockInSector, currentBlockData) {
        if (currentBlockData.isNotBlank()) {
            hexDataToWrite = currentBlockData
        }
    }

    LaunchedEffect(uiState.successMessage, uiState.errorMessage) {
        val success = uiState.successMessage.orEmpty()
        val error = uiState.errorMessage.orEmpty()
        if (isWriting && (success.contains("写入") || error.contains("写入"))) {
            isWriting = false
            ffiBlockData = loadBlockDataFromFfi(selectedSector, selectedBlockInSector)
        }

        val errorMsg = if (error.contains("写入")) error else ""
        val successMsg = if (success.contains("写入")) success else ""
        val picked = if (errorMsg.isNotBlank()) errorMsg else successMsg
        if (picked.isNotBlank() && picked != lastHandledWriteMessage) {
            lastHandledWriteMessage = picked
            writeResultIsError = errorMsg.isNotBlank()
            writeResultMessage = picked
            showWriteResultDialog = true
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (isWriting && !uiState.isLoading) {
            isWriting = false
            ffiBlockData = loadBlockDataFromFfi(selectedSector, selectedBlockInSector)
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
                Text(stringResource(R.string.write_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
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
            // 警告卡片
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
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF57F17),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                stringResource(R.string.write_warning_irreversible),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.write_warning_card_unusable),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }
            }

            if (uiState.errorMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Text(
                            uiState.errorMessage ?: "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }
            if (uiState.successMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                    ) {
                        Text(
                            uiState.successMessage ?: "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            if (isAEFSCard) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Text(
                            stringResource(R.string.write_aefs_disabled_notice),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF0D47A1)
                        )
                    }
                }
            }

            // 当前卡片信息
            item {
                if (uiState.currentCard != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.write_detected_card), style = MaterialTheme.typography.labelLarge)
                            Divider(modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.write_uid), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    uiState.currentCard.uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.write_type), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    uiState.currentCard.cardType.name,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = Color.Red,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                stringResource(R.string.write_no_card_detected),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // 十六进制编辑器
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.write_hex_editor),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 扇区选择
                        Column {
                            Text(
                                stringResource(R.string.write_sector_range, sectorCount - 1),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
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

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        // 块选择
                        Column {
                            Text(
                                stringResource(R.string.write_block_range, blockCountInSelectedSector - 1),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
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

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            stringResource(R.string.write_current_block_data),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF5F5F5))
                                .padding(8.dp),
                            color = Color(0xFFFAFAFA)
                        ) {
                            Text(
                                if (currentBlockData.isNotBlank()) currentBlockData else stringResource(R.string.write_current_block_data_empty),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color.DarkGray
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        // HEX输入框
                        Text(
                            stringResource(R.string.write_modified_data_hex),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = hexDataToWrite,
                            onValueChange = { newValue ->
                                hexDataToWrite = newValue.uppercase().filter { c ->
                                    c.isDigit() || c in 'A'..'F' || c.isWhitespace()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            label = { Text(stringResource(R.string.write_hex_label)) },
                            placeholder = { Text(stringResource(R.string.write_hex_placeholder)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = false,
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            stringResource(R.string.write_length_status, normalizedHexData.length),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (normalizedHexData.length == 32) Color(0xFF2E7D32) else Color.Gray
                        )
                    }
                }
            }

            // 写入进度
            if (isWriting) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.write_progress_title),
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
                                stringResource(R.string.write_progress_hint),
                                style = MaterialTheme.typography.labelSmall
                            )
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
                enabled = !isWriting
            ) {
                Text(stringResource(R.string.cancel))
            }

            Button(
                onClick = {
                    if (normalizedHexData.length == 32) {
                        showConfirmDialog = true
                    } else {
                        viewModel.updateUIMessage(invalidLengthMessage, isError = true)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00)),
                enabled = !isWriting && uiState.currentCard != null && !isAEFSCard
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.write_confirm_and_write))
            }
        }
    }

    // 写入风险免责声明对话框
    if (showConfirmDialog) {
        var disclaimerAccepted by remember { mutableStateOf(false) }
        var showWarnings by remember { mutableStateOf(true) }
        var controlZonePhrase by remember { mutableStateOf("") }
        val requiresControlZoneOverride = selectedSector == 0
        
        AlertDialog(
            onDismissRequest = { 
                showConfirmDialog = false
                disclaimerAccepted = false
            },
            title = { 
                Text(
                    stringResource(R.string.write_risk_title),
                    color = Color(0xFFF57F17),
                    fontWeight = FontWeight.Bold
                ) 
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 风险说明
                    if (showWarnings) {
                        Text(
                            stringResource(R.string.write_risk_intro),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.write_risk_card_damage_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Red
                                )
                                Text(
                                    stringResource(R.string.write_risk_card_damage_body),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFD32F2F),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.write_risk_data_loss_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF57F17)
                                )
                                Text(
                                    stringResource(R.string.write_risk_data_loss_body),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE65100),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.write_risk_phone_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1976D2)
                                )
                                Text(
                                    stringResource(R.string.write_risk_phone_body),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF0D47A1),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    // 待写入数据预览
                    Text(
                        stringResource(R.string.write_pending_data),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                stringResource(R.string.write_sector_block, selectedSector, selectedBlockInSector),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                            Text(
                                hexDataToWrite,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = Color.DarkGray
                            )
                        }
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    // 责任声明
                    Text(
                        stringResource(R.string.write_legal_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                    
                    Text(
                        stringResource(R.string.write_legal_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp
                    )
                    
                    // 接受框
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { disclaimerAccepted = !disclaimerAccepted }
                            .background(Color(0xFFFAFAFA))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = disclaimerAccepted,
                            onCheckedChange = { disclaimerAccepted = it }
                        )
                        Text(
                            stringResource(R.string.write_accept_all_risks),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (requiresControlZoneOverride) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            stringResource(R.string.write_control_zone_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828)
                        )
                        Text(
                            stringResource(R.string.write_control_zone_phrase, WriteControlPolicy.OVERRIDE_PHRASE),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC62828)
                        )
                        OutlinedTextField(
                            value = controlZonePhrase,
                            onValueChange = { controlZonePhrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.write_confirmation_phrase)) },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (disclaimerAccepted) {
                            showConfirmDialog = false
                            disclaimerAccepted = false
                            isWriting = true

                            // 执行真实的写入操作（调用底层NFC接口）
                            viewModel.validateWriteOperation(
                                sectorIdx = selectedSector,
                                blockIdx = selectedBlockInSector,
                                dataHex = normalizedHexData,
                                overrideAcknowledgement = controlZonePhrase,
                                isAEFSCard = isAEFSCard
                            )
                        } else {
                            viewModel.updateUIMessage(mustAcceptDisclaimerMessage, isError = true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00)),
                    enabled = disclaimerAccepted &&
                        (!requiresControlZoneOverride || controlZonePhrase.trim() == WriteControlPolicy.OVERRIDE_PHRASE)
                ) {
                    Text(stringResource(R.string.write_accept_risk))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { 
                    showConfirmDialog = false
                    disclaimerAccepted = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            modifier = Modifier.fillMaxWidth(0.95f)
        )
    }

    if (showWriteResultDialog) {
        AlertDialog(
            onDismissRequest = { showWriteResultDialog = false },
            title = {
                Text(if (writeResultIsError) stringResource(R.string.write_result_failed) else stringResource(R.string.write_result_success))
            },
            text = {
                Text(
                    if (writeResultIsError) {
                        stringResource(R.string.write_result_failed_body, writeResultMessage)
                    } else {
                        stringResource(R.string.write_result_success_body, writeResultMessage)
                    }
                )
            },
            confirmButton = {
                Button(onClick = { showWriteResultDialog = false }) {
                    Text(stringResource(R.string.aefs_wizard_acknowledge))
                }
            }
        )
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
        .map { it.data.uppercase() }
        .firstOrNull { it.isNotBlank() }
}
