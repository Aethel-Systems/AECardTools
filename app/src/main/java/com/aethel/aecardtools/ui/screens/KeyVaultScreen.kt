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

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.KeyVaultManager
import com.aethel.aecardtools.ui.theme.TerminalGreenStyle
import timber.log.Timber

/**
 * 密钥库屏幕 (Key Vault - c.md 2.3 节)
 * 管理 MIFARE Classic 密钥，包含公开的 AEFS 密钥库
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyVaultScreen(
    onBackClick: () -> Unit = {},
    refreshNonce: Long = 0L,
    statusMessage: String? = null
) {
    var selectedTab = remember { mutableStateOf(0) }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // TopAppBar
        TopAppBar(
            title = {
                Text(stringResource(R.string.key_vault_title), color = Color.White)
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
        
        // 标签栏 《重要修复》改善色彩对比度，解决"灰底灰字"问题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { selectedTab.value = 0 },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab.value == 0)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFFE0E0E0),
                    contentColor = if (selectedTab.value == 0)
                        Color.White
                    else
                        Color.Black
                )
            ) {
                Text(stringResource(R.string.key_vault_tab_builtin), style = MaterialTheme.typography.labelSmall, fontWeight = if (selectedTab.value == 0) FontWeight.Bold else FontWeight.Normal)
            }
            
            Button(
                onClick = { selectedTab.value = 1 },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab.value == 1)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFFE0E0E0),
                    contentColor = if (selectedTab.value == 1)
                        Color.White
                    else
                        Color.Black
                )
            ) {
                Text(stringResource(R.string.key_vault_tab_imported), style = MaterialTheme.typography.labelSmall, fontWeight = if (selectedTab.value == 1) FontWeight.Bold else FontWeight.Normal)
            }
            
            Button(
                onClick = { selectedTab.value = 2 },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab.value == 2)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFFE0E0E0),
                    contentColor = if (selectedTab.value == 2)
                        Color.White
                    else
                        Color.Black
                )
            ) {
                Text(stringResource(R.string.key_vault_tab_aefs), style = MaterialTheme.typography.labelSmall, fontWeight = if (selectedTab.value == 2) FontWeight.Bold else FontWeight.Normal)
            }
        }

        statusMessage?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (it.contains("失败") || it.contains("错误")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        // 内容区
        when (selectedTab.value) {
            0 -> DefaultKeysPanel()
            1 -> UserImportedKeysPanel(refreshNonce = refreshNonce)
            2 -> AEFSKeyVaultPanel(refreshNonce = refreshNonce)
        }
    }
}

/**
 * 系统内置密钥面板
 */
@Composable
private fun DefaultKeysPanel() {
    val defaultKeys = KeyVaultManager.getBuiltInKeys().map {
        when (it) {
            "FFFFFFFFFFFF" -> KeyInfo(it, stringResource(R.string.key_vault_builtin_factory), "Universal")
            "000000000000" -> KeyInfo(it, stringResource(R.string.key_vault_builtin_zero), "Classic")
            "A0A1A2A3A4A5" -> KeyInfo(it, "MAD (Mifare Application Directory)", "System")
            "B0B1B2B3B4B5" -> KeyInfo(it, stringResource(R.string.key_vault_builtin_backup1), "Reserve")
            "010101010101" -> KeyInfo(it, stringResource(R.string.key_vault_builtin_backup2), "Reserve")
            "D3F7D3F7D3F7" -> KeyInfo(it, stringResource(R.string.key_vault_builtin_common_access), "Common")
            else -> KeyInfo(it, stringResource(R.string.key_vault_builtin_system), "BuiltIn")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.key_vault_builtin_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        defaultKeys.forEach { keyInfo ->
            KeyItemCard(keyInfo)
        }
    }
}

/**
 * 用户导入密钥面板
 */
@Composable
private fun UserImportedKeysPanel(refreshNonce: Long) {
    val context = LocalContext.current
    var newKeyInput = remember { mutableStateOf("") }
    val importedKeys = remember { mutableStateListOf<KeyInfo>() }
    val importedDesc = stringResource(R.string.key_vault_imported_desc)

    LaunchedEffect(refreshNonce) {
        importedKeys.clear()
        importedKeys.addAll(
            KeyVaultManager.getImportedKeys(context).map {
                KeyInfo(it, importedDesc, "Custom")
            }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 导入输入框
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.key_vault_add_new),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newKeyInput.value,
                        onValueChange = { newKeyInput.value = it.uppercase() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        label = { Text(stringResource(R.string.key_vault_key_input_label), fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        singleLine = true
                    )
                    
                    Button(
                        onClick = {
                            val normalized = newKeyInput.value.replace(":", "").replace(" ", "").uppercase()
                            if (
                                normalized.length == 12 &&
                                normalized.all { it.isDigit() || it in 'A'..'F' }
                            ) {
                                val added = KeyVaultManager.addImportedKey(context, normalized)
                                if (added) {
                                    importedKeys.add(
                                        KeyInfo(
                                            normalized,
                                            context.getString(R.string.key_vault_imported_desc),
                                            "Custom"
                                        )
                                    )
                                }
                                newKeyInput.value = ""
                                Timber.i("添加密钥成功")
                            }
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(stringResource(R.string.key_vault_add), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        
        // 已导入的密钥列表
        if (importedKeys.isNotEmpty()) {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                stringResource(R.string.key_vault_imported_count, importedKeys.size),
                style = MaterialTheme.typography.labelLarge
            )
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(importedKeys) { keyInfo ->
                    KeyItemCardWithDelete(keyInfo) {
                        KeyVaultManager.removeImportedKey(context, keyInfo.value)
                        importedKeys.remove(keyInfo)
                    }
                }
            }
        }
    }
}

/**
 * AEFS 密钥库面板
 */
@Composable
private fun AEFSKeyVaultPanel(refreshNonce: Long) {
    val context = LocalContext.current
    val entries = remember { mutableStateListOf<KeyVaultManager.AEFSVaultEntry>() }
    val expandedIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(refreshNonce) {
        KeyVaultManager.syncPublicAefsKeys(context)
        entries.clear()
        entries.addAll(KeyVaultManager.getAefsVaultEntries(context))
    }

    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.key_vault_aefs_empty), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.key_vault_aefs_empty_desc),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = entries,
            key = { index, entry ->
                "${entry.state}|${entry.physicalUid}|${entry.aliasHash}|${entry.transactionSequence}|${entry.createdAt}|$index"
            }
        ) { index, entry ->
            val itemId =
                "${entry.state}|${entry.physicalUid}|${entry.aliasHash}|${entry.transactionSequence}|${entry.createdAt}|$index"
            val expanded = expandedIds.contains(itemId)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable {
                        if (expanded) {
                            expandedIds.remove(itemId)
                        } else {
                            expandedIds.add(itemId)
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.physicalUid,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.key_vault_logical_uid, entry.logicalUid, entry.transactionSequence, entry.state),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "AliasHash: ${entry.aliasHash}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) stringResource(R.string.key_vault_collapse) else stringResource(R.string.key_vault_expand)
                        )
                    }

                    if (expanded) {
                        entry.rootKeyHex?.let {
                            Text("ROOT: $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                        entry.saltHex?.let {
                            Text("SALT: $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                        entry.sectorKeys.forEach { (label, key) ->
                            Text(
                                text = "$label  $key",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 密钥项卡片
 */
@Composable
private fun KeyItemCard(keyInfo: KeyInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = keyInfo.value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = keyInfo.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = keyInfo.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(4.dp, 2.dp)
                )
            }
        }
    }
}

/**
 * 带删除按钮的密钥项卡片
 */
@Composable
private fun KeyItemCardWithDelete(keyInfo: KeyInfo, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = keyInfo.value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = keyInfo.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = { onDelete() }, modifier = Modifier.padding(0.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.home_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 密钥信息数据类
 */
data class KeyInfo(
    val value: String,
    val description: String,
    val tag: String
)
