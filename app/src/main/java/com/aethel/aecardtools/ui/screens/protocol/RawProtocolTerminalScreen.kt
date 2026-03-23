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

package com.aethel.aecardtools.ui.screens.protocol

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aethel.aecardtools.R
import com.aethel.aecardtools.nfc.UniversalProtocolManager
import com.aethel.aecardtools.nfc.protocol.NFCProtocolDetector
import com.aethel.aecardtools.nfc.protocol.NFCProtocolHandler
import com.aethel.aecardtools.nfc.protocol.NFCProtocolType
import com.aethel.aecardtools.nfc.protocol.toHexString
import com.aethel.aecardtools.nfc.safety.RiskWarningContent
import com.aethel.aecardtools.nfc.safety.RiskWarningDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RawProtocolTerminalScreen(
    manager: UniversalProtocolManager?, // 👈【修改1】改为可空
    viewModel: RawTerminalViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(manager) {
        if (manager != null) { // 👈【修改2】只在非空时初始化
            viewModel.setManager(manager)
        }
    }
    val warningState = remember(uiState.selectedProtocol) {
        when (uiState.selectedProtocol) {
            NFCProtocolType.ISO_DEP -> RiskWarningContent.getCPUCardWarning(context)
            NFCProtocolType.NFC_A -> RiskWarningContent.getMifareClassicWarning(context)
            else -> RiskWarningContent.getGenericRawWarning(context)
        }
    }

    if (uiState.showRiskDialog) {
        RiskSlideConfirmDialog(
            warning = warningState,
            onConfirm = { viewModel.confirmRiskForCurrentProtocol(context) },
            onCancel = { viewModel.dismissRiskDialog() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        val supportedProtocols = manager?.getDetectionResult()?.supportedProtocols.orEmpty()
        val availableProtocols = remember(supportedProtocols) {
            val allProtocols = listOf(
                NFCProtocolType.ISO_DEP,
                NFCProtocolType.NFC_A,
                NFCProtocolType.NFC_V,
                NFCProtocolType.NFC_F,
                NFCProtocolType.NFC_B
            )
            val supported = allProtocols.filter { it in supportedProtocols }
            val unsupported = allProtocols.filterNot { it in supportedProtocols }
            supported + unsupported
        }

        TerminalHeader(
            detectionResult = manager?.getDetectionResult(),
            routeHint = manager?.suggestRoute()?.name ?: context.getString(R.string.raw_terminal_wait_for_card),
            lastStatus = uiState.lastStatusMessage
        )

        ProtocolSelectorTabs(
            selectedProtocol = uiState.selectedProtocol,
            availableProtocols = availableProtocols,
            onProtocolSelected = { viewModel.selectProtocol(it) }
        )

        Divider()

        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            TerminalOutputPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                entries = uiState.commandHistory,
                onClear = { viewModel.clearHistory() }
            )

            VerticalDivider(modifier = Modifier.fillMaxHeight())

            CommandInputPane(
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight(),
                selectedProtocol = uiState.selectedProtocol,
                recentSentCommands = uiState.commandHistory
                    .map { it.sentCommand }
                    .distinct()
                    .takeLast(6)
                    .reversed(),
                onSendCommand = { command ->
                    // 👇【修改5】如果此时卡片仍未贴紧，通过 ViewModel 记录一个本地的错误提示，不致于崩溃
                    if (manager != null) {
                        viewModel.sendCommand(command, manager, context)
                    } else {
                        viewModel.appendHistoryError(command, context.getString(R.string.raw_terminal_link_not_ready))
                    }
                },
                isBusy = uiState.isBusy,
                isRiskConfirmed = uiState.isRiskConfirmed,
                onRequestRiskConfirm = { viewModel.requestRiskDialog() }
            )
        }

        Divider()

        TerminalStatusBar(
            isConnected = uiState.isConnected,
            selectedProtocol = uiState.selectedProtocol,
            lastMessage = uiState.lastStatusMessage
        )
    }
}

@Composable
private fun TerminalHeader(
    detectionResult: NFCProtocolDetector.DetectionResult?,
    routeHint: String,
    lastStatus: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D47A1))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cable,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.raw_terminal_title),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.raw_terminal_route_hint, routeHint),
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp
                )
            }
            if (detectionResult != null) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (lastStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastStatus,
                color = Color(0xFFE3F2FD),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ProtocolSelectorTabs(
    selectedProtocol: NFCProtocolType?,
    availableProtocols: List<NFCProtocolType>,
    onProtocolSelected: (NFCProtocolType) -> Unit
) {
    if (availableProtocols.isEmpty()) return

    val selectedIndex = availableProtocols.indexOf(selectedProtocol).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
    ) {
        availableProtocols.forEach { protocol ->
            val isSelected = selectedProtocol == protocol
            Tab(
                selected = isSelected,
                onClick = { onProtocolSelected(protocol) },
                text = {
                    Text(
                        protocol.name.replace("_", " "),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

@Composable
private fun TerminalOutputPane(
    modifier: Modifier = Modifier,
    entries: List<TerminalEntry>,
    onClear: () -> Unit
) {
    Column(modifier = modifier.background(Color(0xFF1E1E1E))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.raw_terminal_history),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.clear),
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Divider(color = Color(0xFF3A3A3A))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            items(entries) { entry ->
                TerminalEntryItem(entry)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TerminalEntryItem(entry: TerminalEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF505050), shape = MaterialTheme.shapes.small)
            .background(Color(0xFF101010))
            .padding(8.dp)
    ) {
        Text(
            text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs)),
            color = Color(0xFF90A4AE),
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                stringResource(R.string.raw_terminal_sent_prefix, entry.sentCommand),
                color = Color(0xFF81C784),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.raw_terminal_received_prefix, entry.receivedResponse),
                color = if (entry.success) Color(0xFF64B5F6) else Color(0xFFEF9A9A),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (entry.note.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                entry.note,
                color = Color(0xFFFFCC80),
                fontSize = 9.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.raw_terminal_entry_status,
                if (entry.success) stringResource(R.string.raw_terminal_entry_success) else stringResource(R.string.raw_terminal_entry_failure),
                entry.elapsedTimeMs
            ),
            color = if (entry.success) Color(0xFF66BB6A) else Color(0xFFEF5350),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun CommandInputPane(
    modifier: Modifier = Modifier,
    selectedProtocol: NFCProtocolType?,
    recentSentCommands: List<String>,
    onSendCommand: (ByteArray) -> Unit,
    isBusy: Boolean,
    isRiskConfirmed: Boolean,
    onRequestRiskConfirm: () -> Unit
) {
    val context = LocalContext.current
    var commandInput by remember { mutableStateOf("") }
    var showCommandPicker by remember { mutableStateOf(false) }
    val commandCatalog = remember(selectedProtocol, context) { protocolCommandCatalog(context, selectedProtocol) }
    val parsedResult = remember(commandInput, context) { parseHexCommand(context, commandInput) }

    if (showCommandPicker) {
        CommandPickerDialog(
            selectedProtocol = selectedProtocol,
            commands = commandCatalog,
            onDismiss = { showCommandPicker = false },
            onSelect = { preset ->
                commandInput = preset.commandHex
                showCommandPicker = false
            }
        )
    }

    Column(
        modifier = modifier
            .background(Color.White)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (isRiskConfirmed) {
                stringResource(R.string.raw_terminal_risk_passed)
            } else {
                stringResource(R.string.raw_terminal_risk_pending)
            },
            color = if (isRiskConfirmed) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        if (!isRiskConfirmed) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestRiskConfirm, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.raw_terminal_review_risk))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedProtocol == NFCProtocolType.ISO_DEP) {
            Text(
                stringResource(R.string.raw_terminal_cpu_risk),
                color = Color(0xFFF57F17),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Text(stringResource(R.string.raw_terminal_input), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showCommandPicker = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && commandCatalog.isNotEmpty()
            ) {
                Text(stringResource(R.string.raw_terminal_select_mapped))
            }
            OutlinedButton(
                onClick = { commandInput = "" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && commandInput.isNotBlank()
            ) {
                Text(stringResource(R.string.clear))
            }
        }

        if (commandCatalog.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(R.string.raw_terminal_no_catalog), fontSize = 11.sp, color = Color(0xFF607D8B))
        }

        if (recentSentCommands.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.raw_terminal_recent_resend), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            recentSentCommands.forEach { recent ->
                TextButton(
                    onClick = { commandInput = recent },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        recent,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = commandInput,
            onValueChange = { commandInput = it.uppercase() },
            label = { Text(stringResource(R.string.raw_terminal_input_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            enabled = !isBusy,
            isError = parsedResult.isFailure && commandInput.isNotBlank(),
            supportingText = {
                if (parsedResult.isFailure && commandInput.isNotBlank()) {
                    Text(parsedResult.exceptionOrNull()?.message ?: stringResource(R.string.raw_terminal_invalid_hex))
                } else {
                    Text(stringResource(R.string.raw_terminal_passthrough))
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                parsedResult.getOrNull()?.let { cmdBytes ->
                    if (cmdBytes.isNotEmpty()) {
                        onSendCommand(cmdBytes)
                        commandInput = ""
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = parsedResult.getOrNull()?.isNotEmpty() == true && !isBusy && isRiskConfirmed,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isBusy) stringResource(R.string.raw_terminal_sending) else stringResource(R.string.ultralight_send))
        }
    }
}

@Composable
private fun TerminalStatusBar(
    isConnected: Boolean,
    selectedProtocol: NFCProtocolType?,
    lastMessage: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2E2E2E))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isConnected) Icons.Default.Circle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isConnected) Color(0xFF66BB6A) else Color(0xFFFFB74D),
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isConnected) stringResource(R.string.raw_terminal_connected) else stringResource(R.string.raw_terminal_disconnected), color = Color.White, fontSize = 11.sp)
        }

        Text(
            text = selectedProtocol?.name ?: stringResource(R.string.raw_terminal_unselected),
            color = Color(0xFFCFD8DC),
            fontSize = 10.sp
        )

        Text(
            text = lastMessage,
            color = Color(0xFFB0BEC5),
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RiskSlideConfirmDialog(
    warning: RiskWarningDialogState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(warning.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(warning.message, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.raw_terminal_slide_confirm), fontSize = 12.sp, color = Color(0xFFD32F2F))
                Slider(value = sliderValue, onValueChange = { sliderValue = it })
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = sliderValue > 0.98f) {
                Text(stringResource(R.string.raw_terminal_confirm_enter))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

class RawTerminalViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RawTerminalState())
    val uiState: StateFlow<RawTerminalState> = _uiState.asStateFlow()

    private val confirmedProtocols = mutableSetOf<NFCProtocolType>()

    fun setManager(manager: UniversalProtocolManager) {
        val firstProtocol = manager.getDetectionResult()?.primaryProtocol
            ?: manager.getAllHandlers().keys.firstOrNull()
        _uiState.value = _uiState.value.copy(
            isConnected = true,
            selectedProtocol = firstProtocol,
            isRiskConfirmed = firstProtocol != null && confirmedProtocols.contains(firstProtocol),
            showRiskDialog = firstProtocol != null && !confirmedProtocols.contains(firstProtocol)
        )
    }

    fun selectProtocol(protocol: NFCProtocolType) {
        _uiState.value = _uiState.value.copy(
            selectedProtocol = protocol,
            isRiskConfirmed = confirmedProtocols.contains(protocol),
            showRiskDialog = !confirmedProtocols.contains(protocol)
        )
    }

    fun requestRiskDialog() {
        _uiState.value = _uiState.value.copy(showRiskDialog = true)
    }

    fun dismissRiskDialog() {
        _uiState.value = _uiState.value.copy(showRiskDialog = false)
    }

    fun confirmRiskForCurrentProtocol(context: Context) {
        val protocol = _uiState.value.selectedProtocol ?: return
        confirmedProtocols.add(protocol)
        _uiState.value = _uiState.value.copy(
            isRiskConfirmed = true,
            showRiskDialog = false,
            lastStatusMessage = context.getString(R.string.raw_terminal_risk_confirmed)
        )
    }

    fun sendCommand(command: ByteArray, manager: UniversalProtocolManager, context: Context) {
        viewModelScope.launch {
            val protocol = _uiState.value.selectedProtocol
            if (protocol == null) {
                appendHistory(
                    TerminalEntry(
                        sentCommand = command.toHexString(),
                        receivedResponse = context.getString(R.string.raw_terminal_no_protocol_response),
                        success = false,
                        elapsedTimeMs = 0,
                        note = context.getString(R.string.raw_terminal_no_protocol_note)
                    )
                )
                _uiState.value = _uiState.value.copy(lastStatusMessage = context.getString(R.string.raw_terminal_no_protocol_status))
                return@launch
            }
            if (!_uiState.value.isRiskConfirmed) {
                _uiState.value = _uiState.value.copy(
                    lastStatusMessage = context.getString(R.string.raw_terminal_risk_required),
                    showRiskDialog = true
                )
                return@launch
            }

            val handler: NFCProtocolHandler? = manager.getHandler(protocol)
            if (handler == null) {
                appendHistory(
                    TerminalEntry(
                        sentCommand = command.toHexString(),
                        receivedResponse = context.getString(R.string.raw_terminal_no_handler_response),
                        success = false,
                        elapsedTimeMs = 0,
                        note = context.getString(R.string.raw_terminal_handler_missing_note, protocol.name)
                    )
                )
                _uiState.value = _uiState.value.copy(lastStatusMessage = context.getString(R.string.raw_terminal_handler_missing_status))
                return@launch
            }
            val provider = handler.getRawTransceiveProvider()
            val uid = manager.getCardUidHex()
            val interceptor = manager.getSafetyInterceptor()

            _uiState.value = _uiState.value.copy(isBusy = true)

            try {
                val pre = interceptor.checkInstruction(uid, protocol, command)
                if (!pre.allowed) {
                    appendHistory(
                        TerminalEntry(
                            sentCommand = command.toHexString(),
                            receivedResponse = context.getString(R.string.raw_terminal_blocked_response),
                            success = false,
                            elapsedTimeMs = 0,
                            note = pre.message
                        )
                    )
                    _uiState.value = _uiState.value.copy(lastStatusMessage = pre.message)
                    return@launch
                }

                val result = provider.transceive(command)
                val responseHex = if (result.response.isEmpty()) context.getString(R.string.raw_terminal_empty_response) else result.response.toHexString()
                val responseExplain = explainRawResponse(context, protocol, result.response)

                var note = pre.message
                var connected = _uiState.value.isConnected

                if (result.isSuccess) {
                    val post = interceptor.inspectResponse(uid, protocol, command, result.response)
                    if (post.message.isNotBlank()) {
                        note = listOf(note, post.message).filter { it.isNotBlank() }.joinToString(" | ")
                    }
                    if (post.forceTerminateSession) {
                        provider.disconnect()
                        connected = false
                    }
                }

                appendHistory(
                    TerminalEntry(
                        sentCommand = command.toHexString(),
                        receivedResponse = responseHex,
                        success = result.isSuccess,
                        elapsedTimeMs = result.elapsedTimeMs,
                        note = if (result.exception != null) {
                            listOf(
                                note,
                                responseExplain,
                                context.getString(
                                    R.string.raw_terminal_exception_note,
                                    result.exception.message ?: context.getString(R.string.raw_terminal_exception_unknown)
                                )
                            )
                                .filter { it.isNotBlank() }
                                .joinToString(context.getString(R.string.raw_terminal_note_joiner))
                        } else {
                            listOf(note, responseExplain)
                                .filter { it.isNotBlank() }
                                .joinToString(context.getString(R.string.raw_terminal_note_joiner))
                        }
                    )
                )

                _uiState.value = _uiState.value.copy(
                    isConnected = connected,
                    lastStatusMessage = when {
                        !result.isSuccess -> context.getString(R.string.raw_terminal_status_failed)
                        !connected -> context.getString(R.string.raw_terminal_status_fused)
                        else -> context.getString(R.string.raw_terminal_status_success)
                    }
                )
            } catch (e: Exception) {
                appendHistory(
                    TerminalEntry(
                        sentCommand = command.toHexString(),
                        receivedResponse = context.getString(R.string.raw_terminal_exception_response),
                        success = false,
                        elapsedTimeMs = 0,
                        note = context.getString(
                            R.string.raw_terminal_exception_note,
                            e.message ?: context.getString(R.string.raw_terminal_exception_unknown)
                        )
                    )
                )
                _uiState.value = _uiState.value.copy(
                    lastStatusMessage = context.getString(
                        R.string.raw_terminal_exception_status,
                        e.message ?: context.getString(R.string.raw_terminal_exception_unknown)
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    private fun appendHistory(entry: TerminalEntry) {
        _uiState.value = _uiState.value.copy(
            commandHistory = _uiState.value.commandHistory + entry
        )
    }

    fun clearHistory() {
        _uiState.value = _uiState.value.copy(commandHistory = emptyList())
    }

    fun appendHistoryError(command: ByteArray, message: String) {
        val entry = TerminalEntry(
            sentCommand = command.toHexString(),
            receivedResponse = "DISCONNECTED",
            success = false,
            elapsedTimeMs = 0,
            note = message
        )
        _uiState.value = _uiState.value.copy(
            commandHistory = _uiState.value.commandHistory + entry,
            lastStatusMessage = message
        )
    }
}

data class RawTerminalState(
    val selectedProtocol: NFCProtocolType? = null,
    val commandHistory: List<TerminalEntry> = emptyList(),
    val isConnected: Boolean = false,
    val isBusy: Boolean = false,
    val lastStatusMessage: String = "",
    val showRiskDialog: Boolean = false,
    val isRiskConfirmed: Boolean = false
)

data class TerminalEntry(
    val sentCommand: String,
    val receivedResponse: String,
    val success: Boolean,
    val elapsedTimeMs: Long,
    val note: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

private data class CommandPreset(
    val category: String,
    val labelZh: String,
    val labelEn: String,
    val commandHex: String,
    val description: String = ""
)

private fun protocolCommandCatalog(context: Context, protocol: NFCProtocolType?): List<CommandPreset> {
    return when (protocol) {
        NFCProtocolType.ISO_DEP -> listOf(
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_select_pse_label), "Select PSE", "00 A4 04 00 07 A0 00 00 00 04 10 10"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_select_master_file_label), "Select Master File", "00 A4 00 00 02 3F 00"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_get_processing_options_label), "Get Processing Options", "80 A8 00 00 02 83 00"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_read_record_label), "Read Record", "00 B2 01 0C 00"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_get_challenge_label), "Get Challenge", "00 84 00 00 08"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_get_atc_label), "Get Data (ATC)", "80 CA 9F 36 00"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_get_balance_label), "Get Data (Balance)", "80 CA 9F 79 00"),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_iso_dep), context.getString(R.string.raw_terminal_preset_external_auth_label), "External Authenticate", "00 82 00 00 08 00 00 00 00 00 00 00 00")
        )

        NFCProtocolType.NFC_A -> listOf(
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_reqa_label), "REQA", "26", context.getString(R.string.raw_terminal_preset_desc_len_1)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_wupa_label), "WUPA", "52", context.getString(R.string.raw_terminal_preset_desc_len_1)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_anticoll_label), "ANTICOLL", "93 20", context.getString(R.string.raw_terminal_preset_desc_len_2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_select_sample_label), "SELECT (Sample)", "93 70 00 00 00 00 00 00 00", context.getString(R.string.raw_terminal_preset_desc_len_9_select)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_read_page_label), "READ", "30 00", context.getString(R.string.raw_terminal_preset_desc_len_2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_write_page_label), "WRITE (Sample)", "A2 04 00 00 00 00", context.getString(R.string.raw_terminal_preset_desc_len_6_page_write)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_comp_write_label), "COMP_WRITE", "A0 04", context.getString(R.string.raw_terminal_preset_desc_comp_write)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_basic), context.getString(R.string.raw_terminal_preset_halt_label), "HALT", "50 00", context.getString(R.string.raw_terminal_preset_desc_len_2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ulc), context.getString(R.string.raw_terminal_preset_auth_step1_label), "AUTHENTICATE 1", "1A 00", context.getString(R.string.raw_terminal_preset_desc_auth_step1)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ulc), context.getString(R.string.raw_terminal_preset_auth_step2_label), "AUTHENTICATE 2", "AF 00 00 00 00 00 00 00 00", context.getString(R.string.raw_terminal_preset_desc_auth_step2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_get_version_label), "GET_VERSION", "60", context.getString(R.string.raw_terminal_preset_desc_len_1)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_fast_read_label), "FAST_READ", "3A 04 0F", context.getString(R.string.raw_terminal_preset_desc_len_3_page_range)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_read_cnt_label), "READ_CNT", "39 02", context.getString(R.string.raw_terminal_preset_desc_len_2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_incr_cnt_label), "INCR_CNT", "A5 02 00 00 01", context.getString(R.string.raw_terminal_preset_desc_len_5)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_pwd_auth_label), "PWD_AUTH", "1B 00 00 00 00", context.getString(R.string.raw_terminal_preset_desc_len_5)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_read_sig_label), "READ_SIG", "3C 00", context.getString(R.string.raw_terminal_preset_desc_len_2)),
            CommandPreset(context.getString(R.string.raw_terminal_preset_category_ev1), context.getString(R.string.raw_terminal_preset_vcsl_label), "VCSL (Sample)", "4B 00 00 00 00", context.getString(R.string.raw_terminal_preset_desc_vcsl))
        )

        NFCProtocolType.NFC_V -> listOf(
            CommandPreset("NFC-V", context.getString(R.string.raw_terminal_preset_inventory_label), "Inventory", "26 01 00"),
            CommandPreset("NFC-V", context.getString(R.string.raw_terminal_preset_read_single_block_label), "Read Single Block", "22 20 00"),
            CommandPreset("NFC-V", context.getString(R.string.raw_terminal_preset_read_multiple_block_label), "Read Multiple Blocks (Sample)", "22 23 00 03"),
            CommandPreset("NFC-V", context.getString(R.string.raw_terminal_preset_write_single_block_label), "Write Single Block (Sample)", "22 21 00 00 00 00 00 00"),
            CommandPreset("NFC-V", context.getString(R.string.raw_terminal_preset_stay_quiet_label), "Stay Quiet", "02")
        )

        NFCProtocolType.NFC_F -> listOf(
            CommandPreset("NFC-F", context.getString(R.string.raw_terminal_preset_polling_label), "Polling", "06 00 FF FF 00 00"),
            CommandPreset("NFC-F", context.getString(R.string.raw_terminal_preset_request_system_label), "Request System Code (Sample)", "0A 0C 01 00 00 00 00 00 00 00")
        )

        NFCProtocolType.NFC_B -> listOf(
            CommandPreset("NFC-B", context.getString(R.string.raw_terminal_preset_reqb_label), "REQB", "05 00 08"),
            CommandPreset("NFC-B", context.getString(R.string.raw_terminal_preset_wupb_label), "WUPB", "05 00 00"),
            CommandPreset("NFC-B", context.getString(R.string.raw_terminal_preset_hltb_label), "HLTB", "50 00")
        )

        else -> emptyList()
    }
}

@Composable
private fun CommandPickerDialog(
    selectedProtocol: NFCProtocolType?,
    commands: List<CommandPreset>,
    onDismiss: () -> Unit,
    onSelect: (CommandPreset) -> Unit
) {
    val protocolName = selectedProtocol?.name ?: stringResource(R.string.raw_terminal_unknown_protocol)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.raw_terminal_select_mapped_title, protocolName),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (commands.isEmpty()) {
                Text(stringResource(R.string.raw_terminal_no_catalog_for_protocol))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(commands) { preset ->
                        OutlinedButton(
                            onClick = { onSelect(preset) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(
                                        R.string.raw_terminal_picker_item,
                                        preset.category,
                                        preset.labelZh,
                                        preset.labelEn
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = preset.commandHex,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF37474F)
                                )
                                if (preset.description.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = preset.description,
                                        fontSize = 10.sp,
                                        color = Color(0xFF607D8B)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_close))
            }
        }
    )
}

private fun parseHexCommand(context: Context, rawInput: String): Result<ByteArray> {
    val cleanHex = rawInput
        .replace(Regex("\\s+"), "")
        .uppercase()

    if (cleanHex.isEmpty()) return Result.success(byteArrayOf())
    if (!cleanHex.all { it in '0'..'9' || it in 'A'..'F' }) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.raw_terminal_hex_input_charset_error)))
    }
    if (cleanHex.length % 2 != 0) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.raw_terminal_hex_input_even_error)))
    }

    return runCatching {
        ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

private fun explainRawResponse(context: Context, protocol: NFCProtocolType, response: ByteArray): String {
    if (response.isEmpty()) return context.getString(R.string.raw_terminal_empty_response)

    return when (protocol) {
        NFCProtocolType.NFC_A -> {
            // Ultralight/MIFARE Raw 常见 ACK/NAK
            if (response.size == 1) {
                when (response[0].toInt() and 0xFF) {
                    0x0A -> context.getString(R.string.raw_terminal_ack_success)
                    0x00 -> context.getString(R.string.raw_terminal_nak_invalid_command)
                    0x01 -> context.getString(R.string.raw_terminal_nak_crc)
                    0x04 -> context.getString(R.string.raw_terminal_nak_protected)
                    0x05 -> context.getString(R.string.raw_terminal_nak_locked)
                    else -> context.getString(R.string.raw_terminal_single_byte_response, response[0].toInt() and 0xFF)
                }
            } else {
                context.getString(R.string.raw_terminal_response_size, response.size)
            }
        }

        NFCProtocolType.ISO_DEP -> {
            if (response.size >= 2) {
                val sw1 = response[response.lastIndex - 1].toInt() and 0xFF
                val sw2 = response[response.lastIndex].toInt() and 0xFF
                val sw = (sw1 shl 8) or sw2
                when (sw) {
                    0x9000 -> context.getString(R.string.raw_terminal_sw_9000)
                    0x6982 -> context.getString(R.string.raw_terminal_sw_6982)
                    0x6983 -> context.getString(R.string.raw_terminal_sw_6983)
                    0x6A82 -> context.getString(R.string.raw_terminal_sw_6a82)
                    0x6D00 -> context.getString(R.string.raw_terminal_sw_6d00)
                    else -> context.getString(R.string.raw_terminal_sw_generic, sw)
                }
            } else {
                context.getString(R.string.raw_terminal_response_size, response.size)
            }
        }

        else -> context.getString(R.string.raw_terminal_response_size, response.size)
    }
}
