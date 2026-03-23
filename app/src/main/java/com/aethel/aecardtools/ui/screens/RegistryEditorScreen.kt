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

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.PathPersonalityMode
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "RegistryEditor"
private const val ROOT_PATH = "AEFS://"

data class RegistryTreeNode(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val value: String = "",
    val type: String = "",
    val isWritable: Boolean = false,
    val children: List<RegistryTreeNode> = emptyList()
)

data class FlatTreeNode(
    val node: RegistryTreeNode,
    val depth: Int
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VirtualRegistryEditorScreen(onBackClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expandedPaths = remember { mutableStateListOf(ROOT_PATH) }

    var pathMode by remember { mutableStateOf(PathPersonalityMode.AETHEL) }
    var treeRoot by remember { mutableStateOf<RegistryTreeNode?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPath by remember { mutableStateOf(ROOT_PATH) }
    var editTarget by remember { mutableStateOf<RegistryTreeNode?>(null) }
    var editValue by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newKeyName by remember { mutableStateOf("") }
    var newKeyValue by remember { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        isLoading = true
        treeRoot = loadRegistryTree(ROOT_PATH)
        isLoading = false
    }

    val flatNodes = remember(treeRoot, expandedPaths.toList()) {
        flattenTree(treeRoot, expandedPaths.toSet())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.registry_editor_title), fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                val canCreate = selectedPath == "AEFS://Payload/Data_Records" ||
                    selectedPath.startsWith("AEFS://Payload/Data_Records/User")
                if (canCreate) {
                    IconButton(onClick = {
                        showCreateDialog = true
                        newKeyName = ""
                        newKeyValue = ""
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.registry_editor_add_entry))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PathPersonalityMode.entries.forEach { mode ->
                        FilterChip(
                            selected = pathMode == mode,
                            onClick = { pathMode = mode },
                            label = { Text(mode.name) }
                        )
                    }
                }
                Text(stringResource(R.string.registry_editor_current_path), style = MaterialTheme.typography.labelSmall)
                Text(
                    text = toDisplayPath(selectedPath, pathMode),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    flatNodes.forEach { entry ->
                        RegistryTreeRow(
                            entry = entry,
                            expanded = expandedPaths.contains(entry.node.path),
                            selected = selectedPath == entry.node.path,
                            onClick = {
                                selectedPath = entry.node.path
                                if (entry.node.isFolder) {
                                    if (expandedPaths.contains(entry.node.path)) {
                                        expandedPaths.remove(entry.node.path)
                                    } else {
                                        expandedPaths.add(entry.node.path)
                                    }
                                } else {
                                    editTarget = entry.node
                                    editValue = entry.node.value
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (editTarget != null) {
        val target = editTarget!!
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.registry_editor_edit_entry, target.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.registry_editor_type, target.type), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.registry_editor_path, toDisplayPath(target.path, pathMode)), style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        enabled = target.isWritable,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    if (!target.isWritable) {
                        Text(stringResource(R.string.registry_editor_read_only_node), color = Color(0xFFD32F2F), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val saved = saveRegistryValue(target.path, editValue)
                            if (saved) {
                                reloadToken += 1
                            }
                            editTarget = null
                        }
                    },
                    enabled = target.isWritable
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.registry_editor_save))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editTarget = null }) {
                    Text(stringResource(R.string.home_close))
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.registry_editor_create_entry)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.registry_editor_create_desc),
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedTextField(
                        value = newKeyName,
                        onValueChange = { newKeyName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.registry_editor_key_path_label)) }
                    )
                    OutlinedTextField(
                        value = newKeyValue,
                        onValueChange = { newKeyValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.registry_editor_value_label)) },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val base = when {
                                selectedPath.startsWith("AEFS://Payload/Data_Records/User") -> selectedPath
                                else -> "AEFS://Payload/Data_Records/User"
                            }
                            val cleaned = newKeyName.trim().trim('/').replace("\\", "/")
                            val fullPath = if (cleaned.isEmpty()) base else {
                                val prefix = if (base.endsWith("/")) base.dropLast(1) else base
                                "$prefix/$cleaned"
                            }
                            val saved = saveRegistryValue(fullPath, newKeyValue)
                            if (saved) {
                                reloadToken += 1
                            }
                            showCreateDialog = false
                        }
                    },
                    enabled = newKeyName.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.registry_editor_create))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RegistryTreeRow(
    entry: FlatTreeNode,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val node = entry.node
    val startPadding = (entry.depth * 16).dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, top = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (node.isFolder) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (node.isFolder) Color(0xFFFB8C00) else Color(0xFF2E7D32)
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.name, fontWeight = FontWeight.SemiBold)
                if (!node.isFolder) {
                    val preview = node.value.take(56) + if (node.value.length > 56) "..." else ""
                    Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    Text(
                        stringResource(if (node.isWritable) R.string.registry_editor_writable else R.string.registry_editor_read_only),
                        color = if (node.isWritable) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (node.isFolder) {
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

private fun flattenTree(root: RegistryTreeNode?, expandedPaths: Set<String>): List<FlatTreeNode> {
    if (root == null) return emptyList()
    val output = mutableListOf<FlatTreeNode>()

    fun walk(node: RegistryTreeNode, depth: Int) {
        output.add(FlatTreeNode(node = node, depth = depth))
        if (!node.isFolder || !expandedPaths.contains(node.path)) return
        node.children
            .sortedWith(compareBy<RegistryTreeNode> { !it.isFolder }.thenBy { it.name })
            .forEach { walk(it, depth + 1) }
    }

    walk(root, 0)
    return output
}

private fun toDisplayPath(canonicalPath: String, mode: PathPersonalityMode): String {
    val logical = canonicalPath.removePrefix("AEFS://")
    return when (mode) {
        PathPersonalityMode.AETHEL -> {
            if (logical.isBlank()) ">|aefs->:registry"
            else ">|aefs->:registry-${logical.replace("/", "-")}"
        }
        PathPersonalityMode.UNIX -> {
            if (logical.isBlank()) "/mnt/aefs/registry"
            else "/mnt/aefs/registry/$logical"
        }
        PathPersonalityMode.WIN -> {
            if (logical.isBlank()) "A:\\registry"
            else "A:\\registry\\${logical.replace("/", "\\\\")}"
        }
    }
}

private suspend fun loadRegistryTree(path: String): RegistryTreeNode? = withContext(Dispatchers.IO) {
    try {
        val py = Python.getInstance()
        val module = py.getModule("ffi_bridge")

        val listResult = JSONObject(module.callAttr("registry_list_children", path).toString())
        if (listResult.optBoolean("success", false)) {
            val childrenArray = listResult.getJSONArray("children")
            val children = mutableListOf<RegistryTreeNode>()
            for (i in 0 until childrenArray.length()) {
                val childName = childrenArray.getString(i)
                val childPath = if (path.endsWith("/")) path + childName else "$path/$childName"
                loadRegistryTree(childPath)?.let { children.add(it) }
            }

            return@withContext RegistryTreeNode(
                path = path,
                name = if (path == ROOT_PATH) "AEFS://" else path.substringAfterLast('/'),
                isFolder = true,
                children = children
            )
        }

        val readResult = JSONObject(module.callAttr("registry_read_value", path).toString())
        if (!readResult.optBoolean("success", false)) {
            return@withContext null
        }

        RegistryTreeNode(
            path = path,
            name = path.substringAfterLast('/'),
            isFolder = false,
            value = readResult.opt("value")?.toString() ?: "",
            type = readResult.optString("type", "unknown"),
            isWritable = readResult.optBoolean("writable", false)
        )
    } catch (e: Exception) {
        Log.e(TAG, "loadRegistryTree 失败: $path", e)
        null
    }
}

private suspend fun saveRegistryValue(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val py = Python.getInstance()
        val module = py.getModule("ffi_bridge")
        val result = JSONObject(module.callAttr("registry_set_value", path, value).toString())
        result.optBoolean("success", false)
    } catch (e: Exception) {
        Log.e(TAG, "saveRegistryValue 失败", e)
        false
    }
}
