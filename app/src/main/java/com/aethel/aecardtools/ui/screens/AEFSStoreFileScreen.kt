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

import android.net.Uri
import com.aethel.aecardtools.data.ExportFileManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.ui.viewmodel.ExtendedCardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.ExtendedUIState
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AEFSStoreFileScreen(
    viewModel: ExtendedCardToolsViewModel,
    extendedState: ExtendedUIState,
    cardUid: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var alias by remember { mutableStateOf("AEFS File Volume") }
    var passphrase by remember { mutableStateOf("") }
    var plaintextMode by remember { mutableStateOf(false) }
    var allowTruncation by remember { mutableStateOf(false) }
    var selectedName by remember { mutableStateOf("") }
    var selectedMime by remember { mutableStateOf("application/octet-stream") }
    var selectedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var probeInfo by remember { mutableStateOf(context.getString(R.string.aefs_file_not_selected)) }
    var exportInfo by remember { mutableStateOf("") }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching {
            ExportFileManager.writeBytesToUri(context, uri, bytes)
            exportInfo = context.getString(R.string.aefs_file_export_user_location)
        }.onFailure {
            exportInfo = context.getString(R.string.export_failed_generic, it.message ?: "")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { input ->
            selectedBytes = input.readBytes()
        }
        selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "payload.bin"
        selectedMime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val payload = JSONObject().apply {
            put("schema", "AEFS.v6")
            put("alias", alias)
            put("card_uid", cardUid)
            put("record_type", "RAW_FILE")
            put("raw_file", JSONObject().apply {
                put("name", selectedName)
                put("mime_type", selectedMime)
                put("size", selectedBytes?.size ?: 0)
                put("data_hex", selectedBytes?.joinToString("") { "%02X".format(it) } ?: "")
            })
        }
        viewModel.probeAEFSPayload(payload.toString(), "RAW_FILE") { result ->
            probeInfo = if (result == null) {
                context.getString(R.string.aefs_probe_failed)
            } else {
                context.getString(
                    R.string.aefs_probe_result,
                    if (result.optBoolean("fits", false)) {
                        context.getString(R.string.aefs_probe_fit_ok)
                    } else {
                        context.getString(R.string.aefs_probe_fit_warn)
                    },
                    result.optInt("original_size"),
                    result.optInt("compressed_size"),
                    result.optInt("projected_payload_size")
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aefs_store_file_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(alias, { alias = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.aefs_volume_alias)) })
                    OutlinedTextField(passphrase, { passphrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.aefs_sovereign_passphrase)) }, enabled = !plaintextMode)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.aefs_plaintext_mode))
                        Switch(checked = plaintextMode, onCheckedChange = { plaintextMode = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = allowTruncation, onCheckedChange = { allowTruncation = it })
                        Text(stringResource(R.string.aefs_allow_truncation))
                    }
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.aefs_select_file))
                    }
                }
            }

            Text(stringResource(R.string.aefs_selected_file, if (selectedName.isBlank()) stringResource(R.string.aefs_not_selected) else selectedName))
            Text(stringResource(R.string.aefs_selected_mime, selectedMime))
            Text(probeInfo, style = MaterialTheme.typography.bodySmall)
            extendedState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            extendedState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (exportInfo.isNotBlank()) {
                Text(exportInfo, color = MaterialTheme.colorScheme.primary)
            }
            extendedState.recoveryDiagnostic?.let { diagnostic ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.aefs_recovery_diagnostic), style = MaterialTheme.typography.titleMedium)
                        Text(diagnostic.summary)
                        diagnostic.candidates.forEach { candidate ->
                            val recommendedPrefix = if (candidate.recommended) stringResource(R.string.aefs_candidate_prefix_recommended) else ""
                            val observedTx = candidate.observedTransactionSequence?.let {
                                stringResource(R.string.aefs_candidate_observed_tx, it)
                            }.orEmpty()
                            val snapshotLabel = if (candidate.snapshotAvailable) {
                                stringResource(R.string.aefs_candidate_snapshot_yes)
                            } else {
                                stringResource(R.string.aefs_candidate_snapshot_no)
                            }
                            Text(
                                text = stringResource(
                                    R.string.aefs_candidate_summary,
                                    recommendedPrefix,
                                    candidate.packageState,
                                    candidate.transactionSequence,
                                    observedTx,
                                    candidate.aliasHash.take(12),
                                    snapshotLabel,
                                    candidate.message
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            extendedState.mountedAEFSPayload?.let { mounted ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.aefs_current_card_file), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.aefs_mounted_alias, mounted.alias))
                        Text(stringResource(R.string.aefs_mounted_record_type, mounted.recordType))
                        Text(stringResource(R.string.aefs_mounted_file_name, mounted.rawFileName ?: stringResource(R.string.aefs_none)))
                        Text(stringResource(R.string.aefs_selected_mime, mounted.rawFileMimeType ?: stringResource(R.string.aefs_unknown)))
                        Text(stringResource(R.string.aefs_mounted_file_size, mounted.rawFileSize ?: 0))
                        Text(
                            mounted.rawFileHex?.take(96)?.let { if (it.length == 96) "$it..." else it }
                                ?: stringResource(R.string.aefs_non_file_payload),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.readMountedAEFSPayload(cardUid, passphrase) },
                    enabled = !extendedState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (extendedState.isLoading) stringResource(R.string.aefs_loading_read) else stringResource(R.string.aefs_read_current_card))
                }
                Button(
                    onClick = {
                        val mounted = extendedState.mountedAEFSPayload ?: return@Button
                        val rawFileHex = mounted.rawFileHex ?: return@Button
                        runCatching {
                            pendingExportBytes = rawFileHex.hexToBytes()
                            exportLauncher.launch(mounted.rawFileName ?: "aefs_payload.bin")
                        }.onFailure {
                            exportInfo = context.getString(R.string.export_failed_generic, it.message ?: "")
                        }
                    },
                    enabled = !extendedState.isLoading && extendedState.mountedAEFSPayload?.rawFileHex?.isNotBlank() == true,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.aefs_export_file))
                }
            }

            Button(
                onClick = { viewModel.diagnoseAEFSRecovery(cardUid, passphrase) },
                enabled = !extendedState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (extendedState.isLoading) stringResource(R.string.aefs_diagnosing) else stringResource(R.string.aefs_recovery_diagnostic))
            }

            Button(
                onClick = { viewModel.recoverAEFSCard(cardUid, passphrase) },
                enabled = !extendedState.isLoading && extendedState.recoveryDiagnostic?.candidates?.any { it.snapshotAvailable } == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (extendedState.isLoading) stringResource(R.string.aefs_recovering) else stringResource(R.string.aefs_execute_recovery))
            }

            Button(
                onClick = {
                    val bytes = selectedBytes ?: return@Button
                    viewModel.storeFilePayload(
                        cardUid = cardUid,
                        alias = alias,
                        passphrase = passphrase,
                        fileName = selectedName.ifBlank { "payload.bin" },
                        mimeType = selectedMime,
                        fileBytes = bytes,
                        plaintextMode = plaintextMode,
                        allowTruncation = allowTruncation
                    )
                },
                enabled = selectedBytes != null && !extendedState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (extendedState.isLoading) stringResource(R.string.aefs_writing) else stringResource(R.string.aefs_force_or_normal_write))
            }
        }
    }
}

private fun String.hexToBytes(): ByteArray {
    val clean = trim().replace(" ", "").uppercase()
    return ByteArray(clean.length / 2) { idx ->
        clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
    }
}
