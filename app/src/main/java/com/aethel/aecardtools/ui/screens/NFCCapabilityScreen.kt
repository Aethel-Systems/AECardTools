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

import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.nfc.UniversalProtocolManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCCapabilityScreen(
    manager: UniversalProtocolManager?,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val adapter = NfcAdapter.getDefaultAdapter(context)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_nfc_capability), color = Color.White) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_device_nfc), when {
                        adapter == null -> stringResource(R.string.nfc_capability_unsupported)
                        adapter.isEnabled -> stringResource(R.string.nfc_capability_enabled)
                        else -> stringResource(R.string.nfc_capability_disabled)
                    })
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_current_tag), if (manager != null) stringResource(R.string.nfc_capability_detected) else stringResource(R.string.nfc_capability_not_detected))
                    Text(
                        if (manager == null) {
                            stringResource(R.string.nfc_capability_real_status_hint)
                        } else {
                            stringResource(R.string.nfc_capability_live_measurement_hint)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            val capability = manager?.getCapabilityResult()
            if (capability == null) {
                return@Column
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_device_model), capability.deviceModel)
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_vendor_inferred), capability.vendorLikelihood.name)
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_level), capability.capabilityLevel.name)
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_supports_mifare), if (capability.supportsMifareClassic) stringResource(R.string.yes) else stringResource(R.string.no))
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_supports_extended_apdu), if (capability.supportsExtendedAPDU) stringResource(R.string.yes) else stringResource(R.string.no))
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_max_transceive), stringResource(R.string.nfc_capability_bytes, capability.maxTransceiveLength))
                    CapabilityInfoRow(stringResource(R.string.nfc_capability_non_standard_score), stringResource(R.string.nfc_capability_score, capability.nonStandardCardSupportScore))
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nfc_capability_details), style = MaterialTheme.typography.labelLarge)
                    Divider()
                    capability.detailedCapabilities.forEach { (k, v) ->
                        CapabilityInfoRow(k, v)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CapabilityInfoRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp)
    }
}
