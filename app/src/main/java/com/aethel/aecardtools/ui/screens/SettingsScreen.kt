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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.AppLanguage
import com.aethel.aecardtools.data.AppSettings
import com.aethel.aecardtools.data.AppSettingsManager
import com.aethel.aecardtools.data.PathPersonalityMode
import com.aethel.aecardtools.data.PathTransformationEngine
import com.aethel.aecardtools.ui.theme.ThemeMode
import timber.log.Timber

/**
 * 系统设置屏幕 (Settings - c.md 2.4 节)
 * 具有实际功能的设置面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onThemeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onLanguageChange: (AppLanguage) -> Unit = {},
    onCreateFullBackup: () -> Unit = {},
    onImportFullBackup: () -> Unit = {},
    onClearAllData: () -> Unit = {},
    dataSafetyStatus: String? = null
) {
    val context = LocalContext.current
    val pathEngine = remember { PathTransformationEngine.getInstance() }
    var settings by remember { mutableStateOf(AppSettingsManager.load(context)) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun persist(newSettings: AppSettings) {
        settings = newSettings
        AppSettingsManager.save(context, newSettings)
        pathEngine.switchMode(newSettings.pathMode)
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // TopAppBar
        TopAppBar(
            title = {
                Text(stringResource(R.string.settings_title), color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSectionHeader(stringResource(R.string.language_settings))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeOption(
                        label = stringResource(R.string.language_follow_system),
                        description = stringResource(R.string.language_follow_system_desc),
                        selected = settings.appLanguage == AppLanguage.SYSTEM,
                        onClick = {
                            val updated = settings.copy(appLanguage = AppLanguage.SYSTEM)
                            persist(updated)
                            onLanguageChange(updated.appLanguage)
                        }
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    ThemeOption(
                        label = stringResource(R.string.language_zh_cn),
                        description = stringResource(R.string.language_zh_cn_desc),
                        selected = settings.appLanguage == AppLanguage.ZH_CN,
                        onClick = {
                            val updated = settings.copy(appLanguage = AppLanguage.ZH_CN)
                            persist(updated)
                            onLanguageChange(updated.appLanguage)
                        }
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    ThemeOption(
                        label = stringResource(R.string.language_en_us),
                        description = stringResource(R.string.language_en_us_desc),
                        selected = settings.appLanguage == AppLanguage.EN_US,
                        onClick = {
                            val updated = settings.copy(appLanguage = AppLanguage.EN_US)
                            persist(updated)
                            onLanguageChange(updated.appLanguage)
                        }
                    )
                }
            }

            // ===== 视觉与主题 =====
            SettingsSectionHeader(stringResource(R.string.visual_theme))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_theme_mode),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // 草绿色主题
                    ThemeOption(
                        label = stringResource(R.string.settings_theme_standard),
                        description = stringResource(R.string.settings_theme_standard_desc),
                        selected = settings.themeMode == ThemeMode.GRASS_GREEN,
                        onClick = {
                            persist(settings.copy(themeMode = ThemeMode.GRASS_GREEN))
                            onThemeChange(ThemeMode.GRASS_GREEN)
                            Timber.i("切换主题: 草绿色")
                        }
                    )
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // 淡蓝色主题
                    ThemeOption(
                        label = stringResource(R.string.settings_theme_aefs),
                        description = stringResource(R.string.settings_theme_aefs_desc),
                        selected = settings.themeMode == ThemeMode.PALE_BLUE,
                        onClick = {
                            persist(settings.copy(themeMode = ThemeMode.PALE_BLUE))
                            onThemeChange(ThemeMode.PALE_BLUE)
                            Timber.i("切换主题: 淡蓝色")
                        }
                    )
                }
            }
            
            // 动态取色开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_dynamic_color),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.settings_dynamic_color_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Checkbox(
                        checked = settings.dynamicColor,
                        onCheckedChange = {
                            persist(settings.copy(dynamicColor = it))
                            onDynamicColorChange(it)
                            Timber.i("动态取色: ${if (it) "已启用" else "已禁用"}")
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            
            // ===== 物理层参数 =====
            SettingsSectionHeader(stringResource(R.string.settings_physical_layer))
            
            // NFC 唤醒锁
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_nfc_wakelock),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.settings_nfc_wakelock_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Checkbox(
                        checked = settings.nfcWakeLock,
                        onCheckedChange = {
                            persist(settings.copy(nfcWakeLock = it))
                            Timber.i("NFC 唤醒锁: ${if (it) "已启用" else "已禁用"}")
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
            
            // BCC 自动修正
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_bcc_auto_correct),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.settings_bcc_auto_correct_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Checkbox(
                        checked = settings.enableBccAutoCorrect,
                        onCheckedChange = {
                            persist(settings.copy(enableBccAutoCorrect = it))
                            Timber.i("BCC 自动修正: ${if (it) "已启用" else "已禁用"}")
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // 安全拦截器
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_safety_interceptor),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(R.string.settings_safety_interceptor_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Checkbox(
                        checked = settings.enableSafetyInterceptor,
                        onCheckedChange = {
                            persist(settings.copy(enableSafetyInterceptor = it))
                            Timber.i("敏感指令拦截器: ${if (it) "已启用" else "已禁用"}")
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
            
            // ===== NFC 配置 =====
            SettingsSectionHeader(stringResource(R.string.settings_nfc_config))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSlider(
                        label = stringResource(R.string.settings_nfc_timeout),
                        description = stringResource(R.string.settings_milliseconds),
                        value = settings.nfcTimeoutMs,
                        range = 500..5000,
                        step = 100,
                        onValueChange = {
                            persist(settings.copy(nfcTimeoutMs = it))
                            Timber.i("NFC 超时时间设置: ${it}ms")
                        }
                    )
                    
                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    SettingSlider(
                        label = stringResource(R.string.settings_retry_count),
                        description = stringResource(R.string.settings_times),
                        value = settings.retryCount,
                        range = 1..10,
                        step = 1,
                        onValueChange = {
                            persist(settings.copy(retryCount = it))
                            Timber.i("重试次数设置: $it")
                        }
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_path_personality))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeOption(
                        label = stringResource(R.string.settings_path_aethel),
                        description = ">|aefs->:registry-System-Header",
                        selected = settings.pathMode == PathPersonalityMode.AETHEL,
                        onClick = { persist(settings.copy(pathMode = PathPersonalityMode.AETHEL)) }
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    ThemeOption(
                        label = stringResource(R.string.settings_path_unix),
                        description = "/mnt/aefs/registry/System/Header",
                        selected = settings.pathMode == PathPersonalityMode.UNIX,
                        onClick = { persist(settings.copy(pathMode = PathPersonalityMode.UNIX)) }
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    ThemeOption(
                        label = stringResource(R.string.settings_path_win),
                        description = "A:\\registry\\System\\Header",
                        selected = settings.pathMode == PathPersonalityMode.WIN,
                        onClick = { persist(settings.copy(pathMode = PathPersonalityMode.WIN)) }
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_data_lifecycle))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_data_lifecycle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    dataSafetyStatus?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.contains("失败") || it.contains("错误")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(onClick = onCreateFullBackup, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Backup, contentDescription = null)
                        Text(" ${stringResource(R.string.settings_full_backup)}")
                    }
                    OutlinedButton(onClick = onImportFullBackup, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.RestorePage, contentDescription = null)
                        Text(" ${stringResource(R.string.settings_import_backup)}")
                    }
                    OutlinedButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" ${stringResource(R.string.settings_clear_all_data)}")
                    }
                }
            }
            
            // ===== 关于 =====
            SettingsSectionHeader(stringResource(R.string.settings_about))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingRow(stringResource(R.string.settings_app_name_label), "AECardTools")
                    SettingRow(stringResource(R.string.settings_version_label), "6.0.0 Sovereign")
                    SettingRow(stringResource(R.string.settings_build_type_label), stringResource(R.string.settings_build_type_industrial))
                    SettingRow(stringResource(R.string.settings_philosophy_label), stringResource(R.string.settings_philosophy_value))
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_confirm_clear_title)) },
            text = {
                Text(stringResource(R.string.settings_confirm_clear_message))
            },
            confirmButton = {
                Button(onClick = {
                    showClearDialog = false
                    onClearAllData()
                }) {
                    Text(stringResource(R.string.settings_confirm_clear_action))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }
}

/**
 * 设置部分标题
 */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

/**
 * 主题选项项
 */
@Composable
private fun ThemeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 滑块设置项
 */
@Composable
private fun SettingSlider(
    label: String,
    description: String,
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("$value $description", style = MaterialTheme.typography.labelSmall)
        }
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            // 工业级实现：使用真实的Material3 Slider组件
            Slider(
                value = value.toFloat(),
                onValueChange = { newValue ->
                    onValueChange(newValue.toInt())
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            
            // 显示当前值和范围信息
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.settings_min_value, range.first / step),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_current_value, value, description),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.settings_max_value, range.last / step),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 设置信息行
 */
@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
