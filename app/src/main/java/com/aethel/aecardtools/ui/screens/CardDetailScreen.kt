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
import androidx.compose.foundation.lazy.items
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
import com.aethel.aecardtools.data.model.*
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import com.aethel.aecardtools.ui.viewmodel.ViewMode

/**
 * 主卡片详情屏幕 - 支持 4 种视图模式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onBackClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val cardTitle = uiState.currentCard?.uid ?: stringResource(R.string.card_detail_card_not_loaded)
    val cardSubtitle = uiState.currentCard?.cardType?.name.orEmpty()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            TopAppBar(
                title = {
                    Column {
                        Text(cardTitle)
                        Text(
                            text = "($cardSubtitle)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    ViewModeSelector(
                        currentMode = uiState.viewMode,
                        onModeChange = { viewModel.switchViewMode(it) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )

            // 状态指示器
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 错误/成功消息
            if (uiState.errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFCDD2)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = Color.Red)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_close))
                        }
                    }
                }
            }

            if (uiState.successMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFC8E6C9)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Green)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            uiState.successMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearSuccess() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_close))
                        }
                    }
                }
            }

            // 内容区域 - 根据视图模式切换
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                when (uiState.viewMode) {
                    ViewMode.HEX_CLASSIC -> HexClassicView(viewModel, uiState)
                    ViewMode.AEFS_SEMANTIC -> AEFSSemanticView(viewModel, uiState)
                    ViewMode.DUAL_PANE -> DualPaneView(viewModel, uiState)
                    ViewMode.RAW_TERMINAL -> RawTerminalView(viewModel, uiState)
                }
            }
        }
    }
}

/**
 * 视图模式选择器 - 下拉菜单
 */
@Composable
fun ViewModeSelector(
    currentMode: ViewMode,
    onModeChange: (ViewMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.card_detail_more_options))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ViewMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(getModeLabel(mode)) },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == currentMode) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 获取视图模式标签
 */
@Composable
fun getModeLabel(mode: ViewMode): String {
    return when (mode) {
        ViewMode.HEX_CLASSIC -> stringResource(R.string.card_detail_mode_hex_classic)
        ViewMode.AEFS_SEMANTIC -> stringResource(R.string.card_detail_mode_aefs_semantic)
        ViewMode.DUAL_PANE -> stringResource(R.string.card_detail_mode_dual_pane)
        ViewMode.RAW_TERMINAL -> stringResource(R.string.card_detail_mode_raw_terminal)
    }
}

/**
 * 1. 经典 Hex 视图（传统 MCT 风格）
 */
@Composable
fun HexClassicView(
    viewModel: CardToolsViewModel,
    uiState: UIState
) {
    val selectedSector by viewModel.selectedSector.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            stringResource(R.string.card_detail_sector_data_hex),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(uiState.currentCard?.sectorCount ?: 16) { sectorIdx ->
                val sector = uiState.sectors[sectorIdx]

                SectorCard(
                    sectorIdx = sectorIdx,
                    sector = sector,
                    onSectorClick = { viewModel.selectSector(sectorIdx) },
                    isSelected = selectedSector == sectorIdx
                )
            }
        }
    }
}

/**
 * 扇区卡片 - Hex 视图
 */
@Composable
fun SectorCard(
    sectorIdx: Int,
    sector: SectorData?,
    onSectorClick: () -> Unit,
    isSelected: Boolean
) {
    var expanded by remember { mutableStateOf(isSelected) }

    LaunchedEffect(isSelected) {
        if (isSelected) {
            expanded = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                onSectorClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = when (sector?.authStatus) {
                AuthStatus.SUCCESS_A, AuthStatus.SUCCESS_B -> Color(0xFFE8F5E9)
                AuthStatus.FAILED -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 扇区标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.card_detail_sector_title, sectorIdx),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    sector?.authStatus?.name ?: stringResource(R.string.card_detail_not_available),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (sector?.authStatus) {
                        AuthStatus.SUCCESS_A, AuthStatus.SUCCESS_B -> Color.Green
                        AuthStatus.FAILED -> Color.Red
                        else -> Color.Gray
                    }
                )

                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            // 展开的块数据
            if (expanded && sector != null) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(12.dp)) {
                    sector.blocks.forEach { block ->
                        BlockRow(block)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 尾块信息
                    Text(
                        stringResource(R.string.card_detail_trailer_block),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.card_detail_key_a, sector.keyA),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                    Text(
                        stringResource(R.string.card_detail_key_b, sector.keyB),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

/**
 * 块数据显示
 */
@Composable
fun BlockRow(block: BlockData) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "[${block.block}]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(30.dp)
            )

            Text(
                block.data,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

/**
 * 2. AEFS 语义视图 - 完全实现
 */
@Composable
fun AEFSSemanticView(
    viewModel: CardToolsViewModel,
    uiState: UIState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            stringResource(R.string.card_detail_aefs_card_info),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 基本卡片信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.card_detail_basic_info), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                uiState.currentCard?.let { card ->
                    InfoRow(stringResource(R.string.card_detail_uid), card.uid)
                    InfoRow(stringResource(R.string.card_detail_card_type), card.cardType.name)
                    InfoRow(stringResource(R.string.card_detail_total_sectors), card.sectorCount.toString())
                    InfoRow(
                        stringResource(R.string.card_detail_aefs_card),
                        if (card.isAEFS) stringResource(R.string.card_detail_yes) else stringResource(R.string.card_detail_no)
                    )
                    InfoRow(stringResource(R.string.card_detail_detected_time), formatTimestamp(card.detectedAt))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // AEFS 扇区数据解析
        if (uiState.currentCard?.isAEFS == true && uiState.sectors.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.card_detail_aefs_metadata), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // 从扇区 6 解析 AEFS 信息
                    uiState.sectors[6]?.let { sector ->
                        Text(
                            stringResource(R.string.card_detail_sector6_signature),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        
                        val keyA = sector.keyA.uppercase()
                        val keyB = sector.keyB.uppercase()
                        val isAEFSKeyA = keyA.endsWith("5601")
                        val isAEFSKeyB = keyB.endsWith("AE04")
                        
                        if (isAEFSKeyA || isAEFSKeyB) {
                            Text(
                                stringResource(R.string.card_detail_signature_success),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Green
                            )
                        } else {
                            Text(
                                stringResource(R.string.card_detail_signature_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF57F17)
                            )
                        }
                        
                        Text(
                            stringResource(
                                R.string.card_detail_key_suffix,
                                "A",
                                keyA.takeLast(4),
                                if (isAEFSKeyA) stringResource(R.string.card_detail_key_aefs) else stringResource(R.string.card_detail_key_non_aefs)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                        Text(
                            stringResource(
                                R.string.card_detail_key_suffix,
                                "B",
                                keyB.takeLast(4),
                                if (isAEFSKeyB) stringResource(R.string.card_detail_key_aefs) else stringResource(R.string.card_detail_key_non_aefs)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 扇区信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.card_detail_auth_status), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                val successSectors = uiState.sectors.values.count { 
                    it.authStatus == AuthStatus.SUCCESS_A || it.authStatus == AuthStatus.SUCCESS_B 
                }
                val failedSectors = uiState.sectors.values.count { it.authStatus == AuthStatus.FAILED }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBox(stringResource(R.string.card_detail_stat_authenticated), successSectors.toString(), Color.Green)
                    StatBox(stringResource(R.string.card_detail_stat_auth_failed), failedSectors.toString(), Color.Red)
                    StatBox(stringResource(R.string.card_detail_stat_total_sectors), uiState.sectors.size.toString(), Color.Blue)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 详细扇区列表
        if (uiState.sectors.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.card_detail_sector_details), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(uiState.sectors.entries.toList()) { (sectorIdx, sector) ->
                            SectorDetailRow(sectorIdx, sector)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 统计框
 */
@Composable
fun StatBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * 扇区详细信息行
 */
@Composable
fun SectorDetailRow(sectorIdx: Int, sector: SectorData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                when (sector.authStatus) {
                    AuthStatus.SUCCESS_A, AuthStatus.SUCCESS_B -> Color.Green.copy(alpha = 0.1f)
                    AuthStatus.FAILED -> Color.Red.copy(alpha = 0.1f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.card_detail_sector_short, sectorIdx), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
        Text(
            sector.authStatus.name.take(3),
            style = MaterialTheme.typography.labelSmall,
            color = when (sector.authStatus) {
                AuthStatus.SUCCESS_A, AuthStatus.SUCCESS_B -> Color.Green
                AuthStatus.FAILED -> Color.Red
                else -> Color.Gray
            },
            modifier = Modifier.width(40.dp)
        )
        Text(sector.keyA.take(8), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 8.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * 格式化时间戳
 */
fun formatTimestamp(timestamp: Long): String {
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

/**
 * 信息行
 */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(100.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 3. 双窗格视图
 */
@Composable
fun DualPaneView(
    viewModel: CardToolsViewModel,
    uiState: UIState
) {
    Row(
        modifier = Modifier.fillMaxSize()
    ) {
        // 左窗格 - Hex
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                Text(
                    stringResource(R.string.card_detail_dual_hex_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5))
                        .padding(8.dp)
                ) {
                    items(uiState.currentCard?.sectorCount ?: 16) { sectorIdx ->
                        val sector = uiState.sectors[sectorIdx]
                        if (sector != null) {
                            // 扇区标题
                            Text(
                                stringResource(R.string.card_detail_dual_sector_header, sectorIdx, sector.authStatus.name),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )

                            // 完整的块数据显示
                            sector.blocks.forEachIndexed { blockIdx, block ->
                                Text(
                                    stringResource(R.string.card_detail_dual_block_line, blockIdx, block.data),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    maxLines = 2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(
                                            if (blockIdx == 3) Color(0xFFFFE4B5) else Color.White,
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                        .padding(4.dp)
                                )
                            }

                            // 密钥信息
                            Text(
                                stringResource(R.string.card_detail_dual_keys, sector.keyA, sector.keyB),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))

        // 右窗格 - 语义
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                Text(
                    stringResource(R.string.card_detail_dual_semantic_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                uiState.currentCard?.let { card ->
                    InfoRow(stringResource(R.string.card_detail_uid), card.uid)
                    InfoRow(stringResource(R.string.card_detail_type), card.cardType.name)
                    InfoRow(stringResource(R.string.card_detail_sectors), card.sectorCount.toString())
                }
            }
        }
    }
}

/**
 * 4. 原始命令终端 - 完全实现
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawTerminalView(
    viewModel: CardToolsViewModel,
    uiState: UIState
) {
    val terminalRunningText = stringResource(R.string.card_detail_terminal_running)
    var commandInput by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf(listOf<String>()) }
    var selectedCommand by remember { mutableStateOf<String?>(null) }
    var showCommandHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 输出区域 - 黑色终端风格
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            ),
            border = BorderStroke(1.dp, Color(0xFF00FF00))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                // 欢迎信息
                Text(
                    "═══════════════════════════════════════",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF00FF00)
                )
                Text(
                    stringResource(R.string.card_detail_terminal_banner_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF00FF00),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.card_detail_terminal_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF00FF00)
                )
                Text(
                    "═══════════════════════════════════════",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF00FF00)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 命令输出历史
                terminalOutput.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = when {
                            line.startsWith(">") -> Color(0xFF64DD17)  // 绿色 - 命令
                            line.startsWith("[ERROR]") -> Color(0xFFFF5252)  // 红色 - 错误
                            line.startsWith("[OK]") -> Color(0xFF00FF00)  // 亮绿色 - 成功
                            line.startsWith("[INFO]") -> Color(0xFF82B1FF)  // 蓝色 - 信息
                            else -> Color(0xFF00FF00)
                        }
                    )
                }
            }
        }

        // 预设命令按钮
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.card_detail_quick_commands), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val presetCommands = listOf(
                    "SELECT PPSE" to "60A4040007A000000004",
                    "AUTHENTICATE" to "60 00 00 00",
                    "READ BINARY" to "00B0000010",
                    "UPDATE BINARY" to "00D0000010"
                )

                presetCommands.forEach { (name, cmd) ->
                    FilterChip(
                        selected = selectedCommand == cmd,
                        onClick = { 
                            selectedCommand = cmd
                            commandInput = cmd
                        },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        // 输入区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, Color(0xFF666666))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(">", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)

                    TextField(
                        value = commandInput,
                        onValueChange = { newValue ->
                            // 仅允许十六进制字符
                            if (newValue.all { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' || c == ' ' }) {
                                commandInput = newValue.uppercase()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        placeholder = { Text(stringResource(R.string.card_detail_hex_command_placeholder), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        )
                    )

                    IconButton(
                        onClick = {
                            if (commandInput.isNotEmpty()) {
                                // 添加命令到输出
                                terminalOutput = terminalOutput + "> ${commandInput.trim()}"
                                
                                // 执行真实的APDU命令 - 工业级实现
                                val command = commandInput.trim().replace(" ", "").uppercase()
                                viewModel.executeRawCommand(command)
                                
                                // 显示执行中的状态
                                terminalOutput = terminalOutput + terminalRunningText
                                commandInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = stringResource(R.string.ultralight_send))
                    }
                }

                // 命令帮助链接
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showCommandHelp = !showCommandHelp }) {
                        Text(stringResource(R.string.card_detail_command_help), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { terminalOutput = emptyList() }) {
                        Text(stringResource(R.string.card_detail_clear_output), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    // 命令帮助对话框
    if (showCommandHelp) {
        AlertDialog(
            onDismissRequest = { showCommandHelp = false },
            title = { Text(stringResource(R.string.card_detail_raw_help_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(stringResource(R.string.card_detail_common_commands), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.card_detail_command_example_1), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.card_detail_command_example_2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.card_detail_command_example_3), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(stringResource(R.string.card_detail_format_notes), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.card_detail_format_note_1), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.card_detail_format_note_2), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.card_detail_format_note_3), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showCommandHelp = false }) {
                    Text(stringResource(R.string.home_close))
                }
            }
        )
    }
}
