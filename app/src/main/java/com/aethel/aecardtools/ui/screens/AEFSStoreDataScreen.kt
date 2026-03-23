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
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AEFSStoreDataScreen(
    viewModel: ExtendedCardToolsViewModel,
    extendedState: ExtendedUIState,
    cardUid: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var alias by remember { mutableStateOf("AEFS Volume") }
    var passphrase by remember { mutableStateOf("") }
    var plaintextMode by remember { mutableStateOf(false) }
    var allowTruncation by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var urls by remember { mutableStateOf("") }
    var probeInfo by remember { mutableStateOf("") }
    val failedText = stringResource(R.string.status_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aefs_store_data_title)) },
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
                    OutlinedTextField(
                        passphrase,
                        { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.aefs_sovereign_passphrase)) },
                        enabled = !plaintextMode
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.aefs_plaintext_mode))
                        Switch(checked = plaintextMode, onCheckedChange = { plaintextMode = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = allowTruncation, onCheckedChange = { allowTruncation = it })
                        Text(stringResource(R.string.aefs_allow_truncation))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.aefs_structured_records), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(wifiSsid, { wifiSsid = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.aefs_wifi_ssid)) })
                    OutlinedTextField(wifiPassword, { wifiPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.aefs_wifi_password)) })
                    OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text(stringResource(R.string.aefs_private_note)) })
                    OutlinedTextField(urls, { urls = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.aefs_urls_multiline)) })
                }
            }

            Text(if (probeInfo.isBlank()) stringResource(R.string.aefs_probe_not_run) else probeInfo, style = MaterialTheme.typography.bodySmall)
            extendedState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            extendedState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
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
                        Text(stringResource(R.string.aefs_current_payload), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.aefs_mounted_alias, mounted.alias))
                        Text(stringResource(R.string.aefs_mounted_record_type, mounted.recordType))
                        Text(stringResource(R.string.aefs_mounted_sequence, mounted.transactionSequence))
                        Text(stringResource(R.string.aefs_integrity, mounted.integrityMessage.ifBlank { stringResource(R.string.aefs_integrity_verified) }))
                        OutlinedTextField(
                            value = mounted.payloadDocumentJson,
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            minLines = 8,
                            maxLines = 14,
                            label = { Text(stringResource(R.string.aefs_payload_json)) }
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val payload = buildStructuredPayload(alias, cardUid, wifiSsid, wifiPassword, note, urls)
                        viewModel.probeAEFSPayload(payload.toString(), "MIXED_DATA") { result ->
                            probeInfo = if (result == null) {
                                failedText
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
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.aefs_probe_sandbox))
                }
                Button(
                    onClick = { viewModel.readMountedAEFSPayload(cardUid, passphrase) },
                    enabled = !extendedState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (extendedState.isLoading) stringResource(R.string.aefs_loading_read) else stringResource(R.string.aefs_read_current_card))
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val payload = buildStructuredPayload(alias, cardUid, wifiSsid, wifiPassword, note, urls)
                        viewModel.storeStructuredPayload(
                            cardUid = cardUid,
                            alias = alias,
                            passphrase = passphrase,
                            payloadJson = payload.toString(),
                            plaintextMode = plaintextMode,
                            allowTruncation = allowTruncation
                        )
                    },
                    enabled = !extendedState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (extendedState.isLoading) stringResource(R.string.aefs_writing) else stringResource(R.string.home_tool_write))
                }
            }
        }
    }
}

private fun buildStructuredPayload(
    alias: String,
    cardUid: String,
    wifiSsid: String,
    wifiPassword: String,
    note: String,
    urlsRaw: String
): JSONObject {
    return JSONObject().apply {
        put("schema", "AEFS.v6")
        put("alias", alias)
        put("card_uid", cardUid)
        put("record_type", "MIXED_DATA")
        put("data_records", JSONObject().apply {
            put("wifi_home", JSONObject().apply {
                put("ssid", wifiSsid)
                put("password", wifiPassword)
            })
            put("secure_note", note)
            put(
                "urls",
                JSONArray(
                    urlsRaw.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                )
            )
        })
    }
}
