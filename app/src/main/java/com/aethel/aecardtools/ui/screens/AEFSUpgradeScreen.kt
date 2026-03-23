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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.ui.viewmodel.ExtendedCardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.ExtendedUIState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AEFSUpgradeScreen(
    viewModel: ExtendedCardToolsViewModel,
    extendedState: ExtendedUIState,
    cardUid: String,
    onBackClick: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmDialogVisible by remember { mutableStateOf(false) }

    if (confirmDialogVisible) {
        AlertDialog(
            onDismissRequest = { confirmDialogVisible = false },
            title = { Text(stringResource(R.string.aefs_upgrade_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.aefs_upgrade_confirm_desc)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDialogVisible = false
                        viewModel.upgradeAEFSCardToLatest(cardUid, passphrase)
                    }
                ) {
                    Text(stringResource(R.string.aefs_upgrade_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialogVisible = false }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aefs_upgrade_title)) },
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.aefs_upgrade_target), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.aefs_upgrade_uid, cardUid))
                    Text(stringResource(R.string.aefs_upgrade_line1))
                    Text(stringResource(R.string.aefs_upgrade_line2))
                    Text(stringResource(R.string.aefs_upgrade_line3))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.aefs_upgrade_passphrase_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.aefs_upgrade_passphrase_hint)) }
                    )
                }
            }

            extendedState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            extendedState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            extendedState.mountedAEFSPayload?.let { mounted ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.aefs_upgrade_mounted_payload), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.aefs_mounted_alias, mounted.alias))
                        Text(stringResource(R.string.aefs_mounted_record_type, mounted.recordType))
                        Text(stringResource(R.string.aefs_mounted_sequence, mounted.transactionSequence))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.readMountedAEFSPayload(cardUid, passphrase) },
                    enabled = !extendedState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (extendedState.isLoading) stringResource(R.string.aefs_loading_read) else stringResource(R.string.aefs_read_current_card))
                }

                Button(
                    onClick = { confirmDialogVisible = true },
                    enabled = !extendedState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (extendedState.isLoading) stringResource(R.string.status_processing) else stringResource(R.string.aefs_upgrade_confirm_button))
                }
            }
        }
    }
}
