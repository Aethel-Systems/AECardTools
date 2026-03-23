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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R

private data class AccessConditionProfile(
    val bits: String,
    val titleRes: Int,
    val readRes: Int,
    val writeRes: Int,
    val incrementRes: Int,
    val decrementRes: Int
)

private data class SectorTrailerProfile(
    val bits: String,
    val titleRes: Int,
    val keyAReadRes: Int,
    val keyAWriteRes: Int,
    val accessBitsReadRes: Int,
    val accessBitsWriteRes: Int,
    val keyBReadRes: Int,
    val keyBWriteRes: Int
)

private data class ParsedAccessBlock(
    val blockIndex: Int,
    val bits: String,
    val summary: String,
    val detailRows: List<Pair<String, String>>
)

private data class ParsedAccessResult(
    val accessBytesHex: String,
    val isValid: Boolean,
    val validationMessage: String,
    val blocks: List<ParsedAccessBlock>
)

private data class EncodedAccessResult(
    val accessBytesHex: String,
    val byte6: String,
    val byte7: String,
    val byte8: String,
    val blocks: List<ParsedAccessBlock>
)

private val dataProfiles = listOf(
    AccessConditionProfile("000", R.string.access_profile_transport, R.string.access_perm_key_ab, R.string.access_perm_key_ab, R.string.access_perm_key_ab, R.string.access_perm_key_ab),
    AccessConditionProfile("010", R.string.access_profile_read_only, R.string.access_perm_key_ab, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never),
    AccessConditionProfile("100", R.string.access_profile_key_b_write, R.string.access_perm_key_ab, R.string.access_perm_key_b, R.string.access_perm_never, R.string.access_perm_never),
    AccessConditionProfile("110", R.string.access_profile_value_block, R.string.access_perm_key_ab, R.string.access_perm_key_b, R.string.access_perm_key_b, R.string.access_perm_key_ab),
    AccessConditionProfile("001", R.string.access_profile_reversible_value_block, R.string.access_perm_key_ab, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_ab),
    AccessConditionProfile("011", R.string.access_profile_key_b_rw, R.string.access_perm_key_b, R.string.access_perm_key_b, R.string.access_perm_never, R.string.access_perm_never),
    AccessConditionProfile("101", R.string.access_profile_key_b_read_only, R.string.access_perm_key_b, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never),
    AccessConditionProfile("111", R.string.access_profile_locked, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never)
) 

private val trailerProfiles = listOf(
    SectorTrailerProfile("000", R.string.access_profile_transport, R.string.access_perm_never, R.string.access_perm_key_a, R.string.access_perm_key_a, R.string.access_perm_never, R.string.access_perm_key_a, R.string.access_perm_key_a),
    SectorTrailerProfile("010", R.string.access_trailer_lock_write, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_a, R.string.access_perm_never, R.string.access_perm_key_a, R.string.access_perm_never),
    SectorTrailerProfile("100", R.string.access_trailer_key_b_manage, R.string.access_perm_never, R.string.access_perm_key_b, R.string.access_perm_key_ab, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_b),
    SectorTrailerProfile("110", R.string.access_trailer_read_only, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_ab, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never),
    SectorTrailerProfile("001", R.string.access_trailer_key_a_full, R.string.access_perm_never, R.string.access_perm_key_a, R.string.access_perm_key_a, R.string.access_perm_key_a, R.string.access_perm_key_a, R.string.access_perm_key_a),
    SectorTrailerProfile("011", R.string.access_trailer_key_b_full, R.string.access_perm_never, R.string.access_perm_key_b, R.string.access_perm_key_ab, R.string.access_perm_key_b, R.string.access_perm_never, R.string.access_perm_key_b),
    SectorTrailerProfile("101", R.string.access_trailer_key_b_access_bits, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_ab, R.string.access_perm_key_b, R.string.access_perm_never, R.string.access_perm_never),
    SectorTrailerProfile("111", R.string.access_trailer_frozen, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_key_ab, R.string.access_perm_never, R.string.access_perm_never, R.string.access_perm_never)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessControlCalculatorScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var trailerHex by remember { mutableStateOf("") }
    var parseResult by remember { mutableStateOf<ParsedAccessResult?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    var block0Profile by remember { mutableStateOf(dataProfiles.first()) }
    var block1Profile by remember { mutableStateOf(dataProfiles.first()) }
    var block2Profile by remember { mutableStateOf(dataProfiles.first()) }
    var trailerProfile by remember { mutableStateOf(trailerProfiles.first()) }
    var encodedResult by remember { mutableStateOf(encodeAccessBits(context, block0Profile, block1Profile, block2Profile, trailerProfile)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.access_control_title), fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.access_control_parse_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.access_control_parse_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = trailerHex,
                        onValueChange = { trailerHex = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' } },
                        label = { Text("Trailer Hex") },
                        placeholder = { Text("FFFFFFFFFFFF FF078069 FFFFFFFFFFFF") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val result = parseTrailerAccess(trailerHex, context)
                            parseResult = result
                            parseError = if (result == null) context.getString(R.string.access_control_parse_error) else null
                        }
                    ) {
                        Text(stringResource(R.string.access_control_parse_action))
                    }
                    parseError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    parseResult?.let { result ->
                        if (!result.isValid) {
                            ValidationBanner(result.validationMessage)
                        } else {
                            Text(stringResource(R.string.access_control_access_bytes, result.accessBytesHex), fontFamily = FontFamily.Monospace)
                        }
                        result.blocks.forEach { block ->
                            ParsedBlockCard(block = block)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.access_control_encode_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.access_control_encode_desc),
                        style = MaterialTheme.typography.bodySmall
                    )

                    DataProfileDropdown(stringResource(R.string.access_control_block_0), block0Profile, dataProfiles) {
                        block0Profile = it
                        encodedResult = encodeAccessBits(context, block0Profile, block1Profile, block2Profile, trailerProfile)
                    }
                    DataProfileDropdown(stringResource(R.string.access_control_block_1), block1Profile, dataProfiles) {
                        block1Profile = it
                        encodedResult = encodeAccessBits(context, block0Profile, block1Profile, block2Profile, trailerProfile)
                    }
                    DataProfileDropdown(stringResource(R.string.access_control_block_2), block2Profile, dataProfiles) {
                        block2Profile = it
                        encodedResult = encodeAccessBits(context, block0Profile, block1Profile, block2Profile, trailerProfile)
                    }
                    TrailerProfileDropdown(stringResource(R.string.access_control_trailer), trailerProfile, trailerProfiles) {
                        trailerProfile = it
                        encodedResult = encodeAccessBits(context, block0Profile, block1Profile, block2Profile, trailerProfile)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small)
                        .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.access_control_result), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.access_control_result_bytes, encodedResult.accessBytesHex), fontFamily = FontFamily.Monospace)
                        Text(
                            stringResource(R.string.access_control_result_b6_b8, encodedResult.byte6, encodedResult.byte7, encodedResult.byte8),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    encodedResult.blocks.forEach { block ->
                        ParsedBlockCard(block)
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFC62828), shape = MaterialTheme.shapes.small)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun ParsedBlockCard(block: ParsedAccessBlock) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.access_control_block_bits, block.blockIndex, block.bits), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(block.summary, style = MaterialTheme.typography.bodySmall)
            block.detailRows.forEach { (key, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DataProfileDropdown(
    label: String,
    selected: AccessConditionProfile,
    options: List<AccessConditionProfile>,
    onSelect: (AccessConditionProfile) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${selected.bits}  ${context.getString(selected.titleRes)}", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.bits}  ${context.getString(option.titleRes)}") },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun TrailerProfileDropdown(
    label: String,
    selected: SectorTrailerProfile,
    options: List<SectorTrailerProfile>,
    onSelect: (SectorTrailerProfile) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${selected.bits}  ${context.getString(selected.titleRes)}", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.bits}  ${context.getString(option.titleRes)}") },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private fun parseTrailerAccess(
    trailerHex: String,
    context: android.content.Context
): ParsedAccessResult? {
    val normalized = trailerHex.filterNot(Char::isWhitespace).uppercase()
    if (normalized.length != 32) return null
    val bytes = normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val byte6 = bytes[6].toInt() and 0xFF
    val byte7 = bytes[7].toInt() and 0xFF
    val byte8 = bytes[8].toInt() and 0xFF

    val blocks = mutableListOf<ParsedAccessBlock>()
    val validationErrors = mutableListOf<String>()

    for (blockIndex in 0..3) {
        val c1 = (byte7 shr (4 + blockIndex)) and 0x01
        val c2 = (byte8 shr blockIndex) and 0x01
        val c3 = (byte8 shr (4 + blockIndex)) and 0x01
        val invC1 = (byte6 shr blockIndex) and 0x01
        val invC2 = (byte6 shr (4 + blockIndex)) and 0x01
        val invC3 = (byte7 shr blockIndex) and 0x01
        if (invC1 != (c1 xor 0x01) || invC2 != (c2 xor 0x01) || invC3 != (c3 xor 0x01)) {
            validationErrors += context.getString(R.string.access_control_validation_failed, blockIndex)
        }

        val bits = "$c1$c2$c3"
        blocks += if (blockIndex < 3) {
            val profile = dataProfiles.first { it.bits == bits }
            ParsedAccessBlock(
                blockIndex = blockIndex,
                bits = bits,
                summary = context.getString(profile.titleRes),
                detailRows = listOf(
                    context.getString(R.string.access_control_perm_read) to context.getString(profile.readRes),
                    context.getString(R.string.access_control_perm_write) to context.getString(profile.writeRes),
                    context.getString(R.string.access_control_perm_increment) to context.getString(profile.incrementRes),
                    context.getString(R.string.access_control_perm_decrement_transfer_restore) to context.getString(profile.decrementRes)
                )
            )
        } else {
            val profile = trailerProfiles.first { it.bits == bits }
            ParsedAccessBlock(
                blockIndex = blockIndex,
                bits = bits,
                summary = context.getString(profile.titleRes),
                detailRows = listOf(
                    context.getString(R.string.access_control_perm_read_key_a) to context.getString(profile.keyAReadRes),
                    context.getString(R.string.access_control_perm_write_key_a) to context.getString(profile.keyAWriteRes),
                    context.getString(R.string.access_control_perm_read_access_bits) to context.getString(profile.accessBitsReadRes),
                    context.getString(R.string.access_control_perm_write_access_bits) to context.getString(profile.accessBitsWriteRes),
                    context.getString(R.string.access_control_perm_read_key_b) to context.getString(profile.keyBReadRes),
                    context.getString(R.string.access_control_perm_write_key_b) to context.getString(profile.keyBWriteRes)
                )
            )
        }
    }

    return ParsedAccessResult(
        accessBytesHex = "%02X %02X %02X".format(byte6, byte7, byte8),
        isValid = validationErrors.isEmpty(),
        validationMessage = validationErrors.joinToString("；"),
        blocks = blocks
    )
}

private fun encodeAccessBits(
    context: android.content.Context,
    block0: AccessConditionProfile,
    block1: AccessConditionProfile,
    block2: AccessConditionProfile,
    trailer: SectorTrailerProfile
): EncodedAccessResult {
    val triplets = listOf(block0.bits, block1.bits, block2.bits, trailer.bits)
    val c1 = triplets.map { it[0].digitToInt() }
    val c2 = triplets.map { it[1].digitToInt() }
    val c3 = triplets.map { it[2].digitToInt() }

    var byte6 = 0
    var byte7 = 0
    var byte8 = 0
    for (blockIndex in 0..3) {
        byte6 = byte6 or (((c2[blockIndex] xor 0x01) and 0x01) shl (4 + blockIndex))
        byte6 = byte6 or (((c1[blockIndex] xor 0x01) and 0x01) shl blockIndex)
        byte7 = byte7 or ((c1[blockIndex] and 0x01) shl (4 + blockIndex))
        byte7 = byte7 or (((c3[blockIndex] xor 0x01) and 0x01) shl blockIndex)
        byte8 = byte8 or ((c3[blockIndex] and 0x01) shl (4 + blockIndex))
        byte8 = byte8 or ((c2[blockIndex] and 0x01) shl blockIndex)
    }

    return EncodedAccessResult(
        accessBytesHex = "%02X %02X %02X".format(byte6, byte7, byte8),
        byte6 = "%02X".format(byte6),
        byte7 = "%02X".format(byte7),
        byte8 = "%02X".format(byte8),
        blocks = listOf(
            ParsedAccessBlock(0, block0.bits, context.getString(block0.titleRes), listOf(context.getString(R.string.access_control_perm_read) to context.getString(block0.readRes), context.getString(R.string.access_control_perm_write) to context.getString(block0.writeRes), context.getString(R.string.access_control_perm_increment) to context.getString(block0.incrementRes), context.getString(R.string.access_control_perm_decrement_transfer_restore) to context.getString(block0.decrementRes))),
            ParsedAccessBlock(1, block1.bits, context.getString(block1.titleRes), listOf(context.getString(R.string.access_control_perm_read) to context.getString(block1.readRes), context.getString(R.string.access_control_perm_write) to context.getString(block1.writeRes), context.getString(R.string.access_control_perm_increment) to context.getString(block1.incrementRes), context.getString(R.string.access_control_perm_decrement_transfer_restore) to context.getString(block1.decrementRes))),
            ParsedAccessBlock(2, block2.bits, context.getString(block2.titleRes), listOf(context.getString(R.string.access_control_perm_read) to context.getString(block2.readRes), context.getString(R.string.access_control_perm_write) to context.getString(block2.writeRes), context.getString(R.string.access_control_perm_increment) to context.getString(block2.incrementRes), context.getString(R.string.access_control_perm_decrement_transfer_restore) to context.getString(block2.decrementRes))),
            ParsedAccessBlock(3, trailer.bits, context.getString(trailer.titleRes), listOf(context.getString(R.string.access_control_perm_read_key_a) to context.getString(trailer.keyAReadRes), context.getString(R.string.access_control_perm_write_key_a) to context.getString(trailer.keyAWriteRes), context.getString(R.string.access_control_perm_read_access_bits) to context.getString(trailer.accessBitsReadRes), context.getString(R.string.access_control_perm_write_access_bits) to context.getString(trailer.accessBitsWriteRes), context.getString(R.string.access_control_perm_read_key_b) to context.getString(trailer.keyBReadRes), context.getString(R.string.access_control_perm_write_key_b) to context.getString(trailer.keyBWriteRes)))
        )
    )
}
