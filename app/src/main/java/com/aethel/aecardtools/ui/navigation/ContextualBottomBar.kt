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

package com.aethel.aecardtools.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aethel.aecardtools.R

/**
 * 上下文感知的动态底部栏
 * 根据当前导航项实时更新显示的操作按钮
 * 符合 c.md v6.0 Sovereign 规范
 */
@Composable
fun ContextualBottomBar(
    currentRoute: String,
    onButtonClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val buttons = when (currentRoute) {
        "home" -> {
            // 场景 A：首页时显示"识别"和"工具"按钮
            listOf(
                Triple("identify", stringResource(R.string.bottom_identify), Icons.Default.Search),
                Triple("toolkit", stringResource(R.string.bottom_toolkit), Icons.Default.Delete)
            )
        }
        "key_vault" -> {
            // 场景 C：密钥库时显示"导入"、"导出"、"扫描"
            listOf(
                Triple("import", stringResource(R.string.bottom_import), Icons.Default.CloudDownload),
                Triple("export", stringResource(R.string.bottom_export), Icons.Default.CloudUpload),
                Triple("scan", stringResource(R.string.bottom_scan), Icons.Default.Search)
            )
        }
        else -> emptyList()
    }
    
    if (buttons.isNotEmpty()) {
        BottomAppBar(
            modifier = modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                buttons.forEach { (action, label, icon) ->
                    Button(
                        onClick = { onButtonClick(action) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
