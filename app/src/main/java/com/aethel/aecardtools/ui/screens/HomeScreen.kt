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

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.model.CardIdentity
import com.aethel.aecardtools.data.model.NFCStatus
import com.aethel.aecardtools.data.model.RecentCardHistory
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState

/**
 * 首页屏幕 - 完整工业级NFC卡片管理界面
 * 符合 c.md v6.0 Sovereign 规范
 * 双态视图：识别模式 + 工具模式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onScanClick: () -> Unit,
    onNavigateToCard: (CardIdentity) -> Unit,
    onNavigateToKeys: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWrite: () -> Unit = {},
    onNavigateToRead: () -> Unit = {},
    onNavigateToACCalculator: () -> Unit = {},
    onNavigateToRegistryEditor: () -> Unit = {},
    onNavigateToHexCanvas: () -> Unit = {},
    onNavigateToKeyVault: () -> Unit = {},
    onNavigateToAefsWizard: () -> Unit = {},
    onNavigateToAefsStoreData: () -> Unit = {},
    onNavigateToAefsStoreFile: () -> Unit = {},
    onNavigateToAefsUpgrade: () -> Unit = {},
    onNavigateToCloneCard: () -> Unit = {},
    onNavigateToRawTerminal: () -> Unit = {},
    onMenuClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    // 《重要修复》现在从 ViewModel 获取 isToolkitMode 状态，防止状态丢失
    val isToolkitMode = uiState.isToolkitMode
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 主内容区域
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (!isToolkitMode) {
                    // ===== 模式A：识别模式 =====
                    IdentificationModeView(
                        viewModel = viewModel,
                        uiState = uiState,
                        onScanClick = onScanClick,
                        onToggleMode = { viewModel.toggleToolkitMode() }
                    )
                } else {
                    // ===== 模式B：工具模式 =====
                    ToolkitModeView(
                        viewModel = viewModel,
                        currentCard = uiState.currentCard,
                        onNavigateToKeys = onNavigateToKeys,
                        onNavigateToWrite = onNavigateToWrite,
                        onNavigateToRead = onNavigateToRead,
                        onNavigateToACCalculator = onNavigateToACCalculator,
                        onNavigateToRegistryEditor = onNavigateToRegistryEditor,
                        onNavigateToHexCanvas = onNavigateToHexCanvas,
                        onNavigateToKeyVault = onNavigateToKeyVault,
                        onNavigateToAefsWizard = onNavigateToAefsWizard,
                        onNavigateToAefsStoreData = onNavigateToAefsStoreData,
                        onNavigateToAefsStoreFile = onNavigateToAefsStoreFile,
                        onNavigateToAefsUpgrade = onNavigateToAefsUpgrade,
                        onNavigateToCloneCard = onNavigateToCloneCard,
                        onNavigateToRawTerminal = onNavigateToRawTerminal,
                        onToggleMode = { viewModel.toggleToolkitMode() }
                    )
                }
                
                // 最近卡片区域
                if (uiState.recentCards.isNotEmpty()) {
                    RecentCardsArea(
                        recentCards = uiState.recentCards,
                        onCardClick = { history ->
                            viewModel.selectRecentCard(history)
                            onNavigateToCard(history.cardIdentity)
                        },
                        onCardDelete = { uid ->
                            viewModel.deleteRecentCard(uid)
                        }
                    )
                }
                
                // 关于部分
                AboutFooterSection()
                
                // 底部留白
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

/**
 * 识别模式视图 - 中心脉冲圆环，极简设计
 * 用于被动监听和卡片检测
 */
@Composable
fun IdentificationModeView(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    onScanClick: () -> Unit,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // NFC 状态大卡片
        NFCStatusLargeCard(
            nfcStatus = uiState.nfcStatus,
            isScanning = uiState.nfcIsScanning,
            card = uiState.currentCard,
            hasData = uiState.sectors.isNotEmpty(),
            onStatusChange = { viewModel.updateNFCStatus(it) }
        )
        
        // 超大扫描按钮
        LargeActionButton(
            onClick = {
                viewModel.setNFCScanning(true)
                onScanClick()
            },
            icon = Icons.Filled.NearbyOff,
            label = stringResource(R.string.home_start_scan),
            isScanning = uiState.nfcIsScanning
        )
        
        // 工具模式切换按钮
        ElevatedButton(
            onClick = onToggleMode,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.home_enter_toolkit))
        }
    }
}

/**
 * 工具模式视图 - 分类卡片容器
 * 包含标准工具、数据工程、AEFS专家
 */
@Composable
fun ToolkitModeView(
    viewModel: CardToolsViewModel,
    currentCard: CardIdentity?,
    onNavigateToKeys: () -> Unit = {},
    onNavigateToWrite: () -> Unit = {},
    onNavigateToRead: () -> Unit = {},
    onNavigateToACCalculator: () -> Unit = {},
    onNavigateToRegistryEditor: () -> Unit = {},
    onNavigateToHexCanvas: () -> Unit = {},
    onNavigateToKeyVault: () -> Unit = {},
    onNavigateToAefsWizard: () -> Unit = {},
    onNavigateToAefsStoreData: () -> Unit = {},
    onNavigateToAefsStoreFile: () -> Unit = {},
    onNavigateToAefsUpgrade: () -> Unit = {},
    onNavigateToCloneCard: () -> Unit = {},
    onNavigateToRawTerminal: () -> Unit = {},
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 切换回识别模式
        ElevatedButton(
            onClick = onToggleMode,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Icon(Icons.Filled.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.home_back_to_identify))
        }
        
        // ===== 分类1：标准工具 =====
        ToolCategoryCard(
            title = stringResource(R.string.home_tool_standard_title),
            description = stringResource(R.string.home_tool_standard_desc),
            items = listOf(
                stringResource(R.string.home_tool_read) to { onNavigateToRead() },
                stringResource(R.string.home_tool_write) to { onNavigateToWrite() }
            )
        )
        
        // ===== 分类2：AEFS专家 =====
        ToolCategoryCard(
            title = stringResource(R.string.home_tool_aefs_title),
            description = stringResource(R.string.home_tool_aefs_desc),
            items = listOf(
                stringResource(R.string.home_tool_format) to { onNavigateToAefsWizard() },
                stringResource(R.string.home_tool_registry) to { onNavigateToRegistryEditor() },
                stringResource(R.string.home_tool_store_data) to { onNavigateToAefsStoreData() },
                stringResource(R.string.home_tool_store_file) to { onNavigateToAefsStoreFile() },
                stringResource(R.string.home_tool_upgrade) to { onNavigateToAefsUpgrade() }
            )
        )
        
        // ===== 高级功能 =====
        ToolCategoryCard(
            title = stringResource(R.string.home_tool_advanced_title),
            description = stringResource(R.string.home_tool_advanced_desc),
            items = listOf(
                stringResource(R.string.home_tool_access_bits) to { onNavigateToACCalculator() },
                stringResource(R.string.home_tool_hex_editor) to { onNavigateToHexCanvas() },
                stringResource(R.string.home_tool_clone) to {
                    if (currentCard != null) {
                        onNavigateToCloneCard()
                    } else {
                        viewModel.updateUIMessage(viewModel.repository.getContext().getString(R.string.home_error_scan_card_first), isError = true)
                    }
                }
            )
        )
        
        // ===== 系统工具 =====
        ToolCategoryCard(
            title = stringResource(R.string.home_tool_system_title),
            description = stringResource(R.string.home_tool_system_desc),
            items = listOf(
                stringResource(R.string.home_tool_key_vault) to { onNavigateToKeyVault() },
                stringResource(R.string.home_tool_protocol_passthrough) to { 
                    if (currentCard != null) {
                        onNavigateToRawTerminal()
                    } else {
                        viewModel.updateUIMessage(viewModel.repository.getContext().getString(R.string.home_error_scan_card_first), isError = true)
                    }
                }
            )
        )
    }
}

/**
 * 工具分类卡片
 */
@Composable
fun ToolCategoryCard(
    title: String,
    description: String,
    items: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            Divider()
            
            // 按钮网格
            for (i in items.indices step 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (label1, action1) = items[i]
                    OutlinedButton(
                        onClick = action1,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(label1, style = MaterialTheme.typography.labelSmall)
                    }
                    
                    if (i + 1 < items.size) {
                        val (label2, action2) = items[i + 1]
                        OutlinedButton(
                            onClick = action2,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(label2, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


/**
 * 顶部App Bar - 包含自定义App图标和菜单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopAppBar(
    onMenuClick: () -> Unit,
    recentCards: List<RecentCardHistory> = emptyList(),
    onCardSelected: (RecentCardHistory) -> Unit = {}
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    
    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 自定义 App 图标
                AECardToolsAppIcon(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                
                // 标题
                Column {
                    Text(
                        "AECardTools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        stringResource(R.string.home_app_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        },
        actions = {
            // 搜索按钮
            IconButton(onClick = { showSearchDialog = true }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_search), tint = Color.White)
            }
            
            // 菜单按钮
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_more_menu), tint = Color.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        ),
        modifier = Modifier.height(64.dp)
    )
    
    if (showSearchDialog) {
        SearchCardsDialog(
            recentCards = recentCards,
            onCardSelected = {
                onCardSelected(it)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

/**
 * 自定义 AECardTools App 图标
 */
@Composable
fun AECardToolsAppIcon(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Æ",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "41454653",
                fontSize = 4.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp)
            )
            Text(
                "AEFS",
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * NFC 状态大卡片 - 醒目的主要控制区域
 */
@Composable
fun NFCStatusLargeCard(
    nfcStatus: NFCStatus,
    isScanning: Boolean,
    card: CardIdentity?,
    hasData: Boolean,
    onStatusChange: (NFCStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    // 脉动动画
    val infiniteTransition = rememberInfiniteTransition(label = "nfc_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_rotation"
    )
    
    val backgroundColor = when {
        !nfcStatus.isEnabled() -> Color(0xFFFFEBEE)  // 浅红
        isScanning -> Color(0xFFFFF8E1)  // 浅黄
        nfcStatus == NFCStatus.CARD_DETECTED -> Color(0xFFE8F5E9)  // 浅绿
        else -> Color(0xFFE3F2FD)  // 浅蓝
    }

    val hasCardDetected = nfcStatus == NFCStatus.CARD_DETECTED
    val isReading = isScanning && hasCardDetected
    val isAEFSRecognized = card?.isAEFS == true
    
    val statusText = when {
        nfcStatus == NFCStatus.NOT_SUPPORTED -> stringResource(R.string.home_nfc_not_supported)
        nfcStatus == NFCStatus.DISABLED -> stringResource(R.string.home_nfc_enable)
        isReading -> stringResource(R.string.home_status_reading)
        isScanning -> stringResource(R.string.home_status_scanning)
        hasData -> stringResource(R.string.home_status_read_complete)
        hasCardDetected -> stringResource(R.string.home_status_card_detected)
        isAEFSRecognized -> stringResource(R.string.home_status_aefs_recognized)
        else -> stringResource(R.string.home_status_ready)
    }
    
    val statusColor = when {
        nfcStatus == NFCStatus.NOT_SUPPORTED || nfcStatus == NFCStatus.DISABLED -> Color.Red
        nfcStatus == NFCStatus.CARD_DETECTED -> Color.Green
        isScanning -> Color(0xFFF57C00)
        else -> Color(0xFF1976D2)
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 280.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // NFC 波纹图标（动画）
            if (isScanning || nfcStatus == NFCStatus.ENABLED_NO_CARD) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .rotate(rotation),
                    tint = statusColor
                )
            } else {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = statusColor
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 主状态文字
            Text(
                statusText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 说明文字
            Text(
                when {
                    isReading -> stringResource(R.string.home_hint_keep_card_close)
                    isScanning -> stringResource(R.string.home_hint_scan_card)
                    hasData -> stringResource(R.string.home_hint_read_complete)
                    hasCardDetected -> stringResource(R.string.home_hint_detected_card)
                    isAEFSRecognized -> stringResource(R.string.home_hint_aefs_ready)
                    nfcStatus == NFCStatus.DISABLED -> stringResource(R.string.home_hint_enable_nfc)
                    else -> stringResource(R.string.home_hint_scan_or_tap)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 状态指示标签
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isReading) Icons.Filled.HourglassEmpty
                    else if (hasData || hasCardDetected || nfcStatus.isReady()) Icons.Filled.Check
                    else Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    when {
                        isReading -> stringResource(R.string.home_chip_reading)
                        hasData -> stringResource(R.string.home_chip_read)
                        hasCardDetected -> stringResource(R.string.home_chip_detected)
                        nfcStatus.isReady() -> stringResource(R.string.home_chip_ready)
                        else -> stringResource(R.string.home_chip_error)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 超大扫描按钮
 */
@Composable
fun LargeActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isScanning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "button_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "button_scale"
    )
    
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 120.dp)
            .scale(if (isScanning) scale else 1f),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(50.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .padding(end = 16.dp),
                    color = Color.White,
                    strokeWidth = 4.dp
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(end = 16.dp),
                    tint = Color.White
                )
            }
            
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 快速操作区域
 */
@Composable
fun QuickActionArea(
    viewModel: CardToolsViewModel,
    currentCard: CardIdentity? = null,
    onNavigateToKeys: () -> Unit,
    onNavigateToWrite: () -> Unit = {},
    onNavigateToRead: () -> Unit = {},
    onNavigateToACCalculator: () -> Unit = {},
    onNavigateToRegistryEditor: () -> Unit = {},
    onNavigateToHexCanvas: () -> Unit = {},
    onNavigateToKeyVault: () -> Unit = {},
    onNavigateToAefsWizard: () -> Unit = {},
    onNavigateToCloneCard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_quick_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // 基础操作: 2×2 网格布局
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButtonItem(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.home_tool_write),
                    modifier = Modifier.weight(1f),
                    enabled = currentCard != null,
                    onClick = {
                        if (currentCard != null) {
                            onNavigateToWrite()
                        }
                    }
                )
                
                QuickActionButtonItem(
                    icon = Icons.Filled.Upload,
                    label = stringResource(R.string.home_tool_read),
                    modifier = Modifier.weight(1f),
                    enabled = currentCard != null,
                    onClick = {
                        if (currentCard != null) {
                            onNavigateToRead()
                        }
                    }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButtonItem(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.home_tool_clone),
                    modifier = Modifier.weight(1f),
                    enabled = currentCard != null,
                    onClick = {
                        if (currentCard != null) {
                            onNavigateToCloneCard()
                        }
                    }
                )
                
                QuickActionButtonItem(
                    icon = Icons.Filled.VpnKey,
                    label = stringResource(R.string.key_manager_title),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToKeys
                )
            }
            
            // AEFS 高级功能分隔符
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                stringResource(R.string.home_aefs_tools),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // AEFS 工具: 3×1 网格布局
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButtonItem(
                    icon = Icons.Filled.CheckCircle,
                    label = stringResource(R.string.home_tool_access_bits),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToACCalculator
                )
                
                QuickActionButtonItem(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.home_registry_editor),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToRegistryEditor
                )
                
                QuickActionButtonItem(
                    icon = Icons.Filled.GridOn,
                    label = stringResource(R.string.home_tool_hex_editor),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToHexCanvas
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButtonItem(
                    icon = Icons.Filled.Key,
                    label = stringResource(R.string.home_tool_key_vault),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToKeyVault
                )

                QuickActionButtonItem(
                    icon = Icons.Filled.AutoFixHigh,
                    label = stringResource(R.string.home_aefs_wizard),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToAefsWizard
                )

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 快速操作按钮项
 */
@Composable
fun QuickActionButtonItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 64.dp, max = 96.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        enabled = enabled
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) 
                    MaterialTheme.colorScheme.onSurfaceVariant 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) 
                    MaterialTheme.colorScheme.onSurfaceVariant 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 最近卡片区域
 */
@Composable
fun RecentCardsArea(
    recentCards: List<RecentCardHistory>,
    onCardClick: (RecentCardHistory) -> Unit,
    onCardDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_recent_cards_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 200.dp)
        ) {
            items(recentCards) { history ->
                RecentCardItem(
                    history = history,
                    onClick = { onCardClick(history) },
                    onDelete = { onCardDelete(history.cardIdentity.uid) }
                )
            }
        }
    }
}

/**
 * 单张最近卡片项
 */
@Composable
fun RecentCardItem(
    history: RecentCardHistory,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .width(180.dp)
            .height(160.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // UID 和删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    history.cardIdentity.uid.take(10) + "...",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.home_delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // 中间信息
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${history.cardIdentity.cardType.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                if (history.alias.isNotEmpty()) {
                    Text(
                        "> ${history.alias}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // 底部时间和模式标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    formatTimeAgo(history.lastDetectedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (history.cardIdentity.isAEFS)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (history.cardIdentity.isAEFS) {
                            stringResource(R.string.home_recent_tag_aefs)
                        } else {
                            stringResource(R.string.home_recent_tag_classic)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(4.dp, 2.dp),
                        color = if (history.cardIdentity.isAEFS)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.home_delete_recent_card)) },
            text = { Text(stringResource(R.string.home_delete_recent_card_confirm, history.cardIdentity.uid)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.home_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }
}

/**
 * 格式化时间 - 显示"X小时前"或"X天前"
 */
@Composable
fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> stringResource(R.string.home_time_just_now)
        diff < 3600_000 -> stringResource(R.string.home_time_minutes_ago, (diff / 60_000).toInt())
        diff < 86400_000 -> stringResource(R.string.home_time_hours_ago, (diff / 3600_000).toInt())
        else -> stringResource(R.string.home_time_days_ago, (diff / 86400_000).toInt())
    }
}

/**
 * 关于部分 - 底部信息
 */
@Composable
fun AboutFooterSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Divider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        
        Text(
            stringResource(R.string.home_footer_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            stringResource(R.string.home_footer_subtitle),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Text(
            stringResource(R.string.home_footer_capability),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * 底部导航栏
 */
@Composable
fun HomeBottomNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.nav_home)) },
            label = { Text(stringResource(R.string.nav_home)) },
            selected = currentRoute == "home",
            onClick = { onNavigate("home") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        NavigationBarItem(
            icon = { Icon(Icons.Filled.VpnKey, contentDescription = stringResource(R.string.home_nav_keys)) },
            label = { Text(stringResource(R.string.home_nav_keys)) },
            selected = currentRoute == "keys",
            onClick = { onNavigate("keys") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        NavigationBarItem(
            icon = { Icon(Icons.Filled.History, contentDescription = stringResource(R.string.home_nav_logs)) },
            label = { Text(stringResource(R.string.home_nav_logs)) },
            selected = currentRoute == "logs",
            onClick = { onNavigate("logs") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) },
            selected = currentRoute == "settings",
            onClick = { onNavigate("settings") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/**
 * NFCStatus 扩展函数
 */
fun NFCStatus.isEnabled(): Boolean {
    return this == NFCStatus.ENABLED_NO_CARD || this == NFCStatus.CARD_DETECTED
}

fun NFCStatus.isReady(): Boolean {
    return this == NFCStatus.CARD_DETECTED || this == NFCStatus.ENABLED_NO_CARD
}
/**
 * 搜索卡片对话框
 */
@Composable
fun SearchCardsDialog(
    recentCards: List<RecentCardHistory>,
    onCardSelected: (RecentCardHistory) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCards = remember(searchQuery, recentCards) {
        if (searchQuery.isBlank()) {
            recentCards
        } else {
            recentCards.filter { card ->
                card.cardIdentity.uid.contains(searchQuery, ignoreCase = true) ||
                card.alias.contains(searchQuery, ignoreCase = true) ||
                card.cardIdentity.cardType.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_search_cards)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // 搜索输入框
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_clear_search))
                            }
                        }
                    }
                )
                
                // 搜索结果
                if (filteredCards.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isBlank()) stringResource(R.string.home_no_recent_cards) else stringResource(R.string.home_no_matching_cards),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredCards) { card ->
                            SearchResultItem(
                                card = card,
                                onSelect = { onCardSelected(card) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_close))
            }
        }
    )
}


/**
 * 搜索结果项
 */
@Composable
fun SearchResultItem(
    card: RecentCardHistory,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    card.cardIdentity.uid,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${card.cardIdentity.cardType.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            if (card.alias.isNotEmpty()) {
                Text(
                    card.alias,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
