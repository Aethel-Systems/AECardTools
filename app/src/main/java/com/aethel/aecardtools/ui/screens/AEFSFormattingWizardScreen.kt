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

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.AEFSSovereignIdentity
import com.aethel.aecardtools.data.NfcRuntimeContext
import com.aethel.aecardtools.data.model.*
import com.aethel.aecardtools.ui.viewmodel.ExtendedCardToolsViewModel

/**
 * AEFS 格式化向导屏幕 - 全屏模态底板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AEFSFormattingWizardScreen(
    viewModel: ExtendedCardToolsViewModel,
    cardUid: String,
    extendedState: com.aethel.aecardtools.ui.viewmodel.ExtendedUIState,
    onDismiss: () -> Unit,
    onSuccess: (AEFSFormattingResult) -> Unit
) {
    var wizardStep by remember { mutableStateOf(0) }
    var cardAlias by remember { mutableStateOf("") }
    var sipLevel by remember { mutableStateOf(SIPLevel.ARCHITECT) }
    var encryptionPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswordFields by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var showFormatResultDialog by remember { mutableStateOf(false) }
    var formatResultForDialog by remember { mutableStateOf<AEFSFormattingResult?>(null) }
    var lastHandledFormatResultTs by remember { mutableStateOf(0L) }

    LaunchedEffect(extendedState.aefsFormattingResult) {
        val result = extendedState.aefsFormattingResult ?: return@LaunchedEffect
        if (result.timestamp != lastHandledFormatResultTs) {
            lastHandledFormatResultTs = result.timestamp
            formatResultForDialog = result
            showFormatResultDialog = true
        }
    }
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 标题栏
            AEFSWizardTopBar(
                step = wizardStep,
                totalSteps = 4,
                onClose = {
                    if (wizardStep == 0) {
                        onDismiss()
                    } else {
                        wizardStep = 0
                    }
                }
            )
            
            // 内容区域
            if (extendedState.errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = extendedState.errorMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (extendedState.successMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        text = extendedState.successMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (wizardStep) {
                    0 -> AEFSWizardStep1_Alias(
                        alias = cardAlias,
                        onAliasChange = { cardAlias = it },
                        onNext = { wizardStep = 1 }
                    )
                    
                    1 -> AEFSWizardStep2_SIPLevel(
                        selectedLevel = sipLevel,
                        onLevelSelect = { sipLevel = it },
                        onNext = { wizardStep = 2 }
                    )
                    
                    2 -> AEFSWizardStep3_Encryption(
                        password = encryptionPassword,
                        confirmPassword = confirmPassword,
                        showPasswordFields = showPasswordFields,
                        onPasswordChange = { encryptionPassword = it },
                        onConfirmPasswordChange = { confirmPassword = it },
                        onShowPasswordsChange = { showPasswordFields = it },
                        onNext = { wizardStep = 3 }
                    )
                    
                    3 -> AEFSWizardStep4_ConfirmAndExecute(
                        cardUid = cardUid,
                        alias = cardAlias,
                        sipLevel = sipLevel,
                        isConfirmed = confirmOverwrite,
                        onConfirmChange = { confirmOverwrite = it },
                        isFormatting = extendedState.isAEFSFormatting,
                        formattingResult = extendedState.aefsFormattingResult,
                        formatStep = extendedState.formatStep,
                        onExecute = {
                            val params = AEFSInitializationParams(
                                cardAlias = cardAlias,
                                sipLevel = sipLevel,
                                encryptionPassword = encryptionPassword,
                                createBackup = true
                            )
                            viewModel.initializeAEFSCard(cardUid, params)
                        }
                    )
                }
            }
            
            // 底部按钮栏
            AEFSWizardBottomBar(
                step = wizardStep,
                totalSteps = 3,
                isFormatting = extendedState.isAEFSFormatting,
                canProceed = when (wizardStep) {
                    0 -> cardAlias.isNotBlank()
                    1 -> true
                    2 -> encryptionPassword == confirmPassword && (encryptionPassword.isNotBlank() || !showPasswordFields)
                    3 -> confirmOverwrite
                    else -> false
                },
                onPrevious = { if (wizardStep > 0) wizardStep-- },
                onNext = {
                    when (wizardStep) {
                        0 -> wizardStep = 1
                        1 -> wizardStep = 2
                        2 -> wizardStep = 3
                    }
                },
                onFinish = onDismiss
            )
        }
    }

    if (showFormatResultDialog && formatResultForDialog != null) {
        val result = formatResultForDialog!!
        AlertDialog(
            onDismissRequest = {
                showFormatResultDialog = false
            },
            title = {
                Text(if (result.success) stringResource(R.string.aefs_wizard_result_success_title) else stringResource(R.string.aefs_wizard_result_failed_title))
            },
            text = {
                Text(
                    if (result.success) {
                        stringResource(R.string.aefs_wizard_result_success_message, result.duration)
                    } else {
                        stringResource(R.string.aefs_wizard_result_failed_message, result.error ?: stringResource(R.string.aefs_init_unknown_error))
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFormatResultDialog = false
                        if (result.success) {
                            onSuccess(result)
                        }
                    }
                ) {
                    Text(if (result.success) stringResource(R.string.aefs_wizard_finish_and_home) else stringResource(R.string.aefs_wizard_acknowledge))
                }
            }
        )
    }
}

/**
 * 向导顶部栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AEFSWizardTopBar(
    step: Int,
    totalSteps: Int,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.aefs_wizard_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White
        )
    )
    
    // 进度指示器
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.aefs_wizard_step_progress, step + 1, totalSteps + 1),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LinearProgressIndicator(
            progress = (step + 1) / (totalSteps + 2f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/**
 * 第 1 步：设置别名
 */
@Composable
fun AEFSWizardStep1_Alias(
    alias: String,
    onAliasChange: (String) -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.aefs_wizard_alias_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Text(
                stringResource(R.string.aefs_wizard_alias_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        item {
            OutlinedTextField(
                value = alias,
                onValueChange = onAliasChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.aefs_alias)) },
                placeholder = { Text(stringResource(R.string.aefs_wizard_alias_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                maxLines = 1,
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
        
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                stringResource(R.string.aefs_wizard_alias_help_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.aefs_wizard_alias_help_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

/**
 * 第 2 步：选择安全级别
 */
@Composable
fun AEFSWizardStep2_SIPLevel(
    selectedLevel: SIPLevel,
    onLevelSelect: (SIPLevel) -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.aefs_wizard_sip_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Text(
                stringResource(R.string.aefs_wizard_sip_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        item {
            for (level in listOf(SIPLevel.SANDBOX, SIPLevel.ARCHITECT, SIPLevel.SOVEREIGN)) {
                SIPLevelCard(
                    level = level,
                    isSelected = selectedLevel == level,
                    onSelect = { onLevelSelect(level) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }
        }
        
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Column {
                            Text(
                                stringResource(R.string.aefs_wizard_recommended_level),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                stringResource(R.string.aefs_wizard_recommended_level_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

/**
 * SIP 级别卡片
 */
@Composable
fun SIPLevelCard(
    level: SIPLevel,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (color, description) = when (level) {
        SIPLevel.SANDBOX -> Color(0xFF81C784) to stringResource(R.string.aefs_wizard_sip_sandbox_desc)
        SIPLevel.ARCHITECT -> Color(0xFF42A5F5) to stringResource(R.string.aefs_wizard_sip_architect_desc)
        SIPLevel.SOVEREIGN -> Color(0xFFAB47BC) to stringResource(R.string.aefs_wizard_sip_sovereign_desc)
        else -> Color.Gray to stringResource(R.string.aefs_wizard_sip_custom_desc)
    }
    
    Card(
        modifier = modifier
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = color,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                color = color.copy(alpha = 0.3f),
                shape = CircleShape
            ) {
                Icon(
                    when (level) {
                        SIPLevel.SANDBOX -> Icons.Filled.Security
                        SIPLevel.ARCHITECT -> Icons.Filled.VerifiedUser
                        SIPLevel.SOVEREIGN -> Icons.Filled.Lock
                        else -> Icons.Filled.Help
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    tint = color
                )
            }
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    level.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = color
                )
            }
        }
    }
}

/**
 * 第 3 步：加密设置
 */
@Composable
fun AEFSWizardStep3_Encryption(
    password: String,
    confirmPassword: String,
    showPasswordFields: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onShowPasswordsChange: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.aefs_wizard_password_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Text(
                stringResource(R.string.aefs_wizard_password_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onShowPasswordsChange(!showPasswordFields) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Checkbox(
                    checked = showPasswordFields,
                    onCheckedChange = { onShowPasswordsChange(it) }
                )
                Text(
                    stringResource(R.string.aefs_wizard_enable_password),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        if (showPasswordFields) {
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.aefs_wizard_password_label)) },
                    placeholder = { Text(stringResource(R.string.aefs_wizard_password_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )
            }
            
            item {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.aefs_wizard_confirm_password_label)) },
                    placeholder = { Text(stringResource(R.string.aefs_wizard_confirm_password_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                    shape = RoundedCornerShape(12.dp)
                )
                
                if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                    Text(
                        stringResource(R.string.aefs_wizard_password_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
            
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    color = Color(0xFFFFF8E1).copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFFF9800)
                            )
                            Column {
                                Text(
                                    stringResource(R.string.aefs_wizard_password_requirements_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF9800)
                                )
                                Text(
                                    stringResource(R.string.aefs_wizard_password_requirements),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

/**
 * 第 4 步：确认并执行
 */
@Composable
fun AEFSWizardStep4_ConfirmAndExecute(
    cardUid: String,
    alias: String,
    sipLevel: SIPLevel,
    isConfirmed: Boolean,
    onConfirmChange: (Boolean) -> Unit,
    isFormatting: Boolean,
    formattingResult: AEFSFormattingResult?,
    formatStep: FormatStep?,
    onExecute: () -> Unit
) {
    var showPreviewDialog by remember { mutableStateOf(false) }
    val physicalUid = remember(cardUid) {
        val runtimeUid = NfcRuntimeContext.getCurrentTag()
            ?.id
            ?.joinToString("") { "%02X".format(it) }
            ?.uppercase()
        runtimeUid ?: cardUid.uppercase()
    }
    val sovereignUid = AEFSSovereignIdentity.SOVEREIGN_UID
    val calculatedBcc = AEFSSovereignIdentity.SOVEREIGN_BCC
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.aefs_wizard_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Text(
                stringResource(R.string.aefs_wizard_confirm_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryItem(stringResource(R.string.aefs_wizard_current_physical_uid), physicalUid)
                    Divider()
                    SummaryItem(stringResource(R.string.aefs_wizard_target_sovereign_uid), sovereignUid)
                    Divider()
                    SummaryItem(stringResource(R.string.aefs_wizard_target_bcc), calculatedBcc)
                    Divider()
                    SummaryItem(stringResource(R.string.aefs_alias), alias)
                    Divider()
                    SummaryItem(stringResource(R.string.aefs_wizard_security_level), sipLevel.name)
                }
            }
        }
        
        if (!isFormatting && formattingResult == null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                        .clickable { onConfirmChange(!isConfirmed) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Checkbox(
                        checked = isConfirmed,
                        onCheckedChange = { onConfirmChange(it) }
                    )
                    Text(
                        stringResource(R.string.aefs_wizard_confirm_checkbox),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            item {
                Button(
                    onClick = { showPreviewDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = isConfirmed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.aefs_wizard_start_initialize), fontWeight = FontWeight.Bold)
                }
            }
        }
        
        if (isFormatting) {
            item {
                FormattingProgressSection(
                    formatStep = formatStep
                )
            }
        }
        
        if (formattingResult != null) {
            item {
                if (formattingResult.success) {
                    FormattingSuccessSection(result = formattingResult)
                } else {
                    FormattingErrorSection(error = formattingResult.error)
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
    
    // 写入前预览对话框  - 工业级安全检查
    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = {
                Text(
                    stringResource(R.string.aefs_wizard_final_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.aefs_wizard_final_confirm_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.aefs_wizard_current_physical_uid_colon), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    physicalUid,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Divider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.aefs_wizard_target_sovereign_uid_colon), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    sovereignUid,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Divider()
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.aefs_wizard_target_bcc_colon), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    calculatedBcc,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Divider()
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.aefs_wizard_alias_colon), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    alias,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                    
                    Text(
                        stringResource(R.string.aefs_wizard_bcc_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPreviewDialog = false
                        onExecute()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.aefs_wizard_confirm_write))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPreviewDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SummaryItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun FormattingProgressSection(formatStep: FormatStep?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            stringResource(R.string.aefs_wizard_initializing),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (formatStep != null) {
            Text(
                stringResource(R.string.aefs_wizard_current_step, formatStep.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FormattingSuccessSection(result: AEFSFormattingResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color.Green
        )
        
        Text(
            stringResource(R.string.aefs_wizard_success),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.Green
        )
        
        Text(
            stringResource(R.string.aefs_wizard_success_desc, result.duration),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (result.sector0Data != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.aefs_wizard_sector0_data),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Magic: ${result.sector0Data.magicUid}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "BCC: ${result.sector0Data.bcc}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Version: ${result.sector0Data.version}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "TxSeq: ${result.sector0Data.transactionSequence}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun FormattingErrorSection(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Text(
            stringResource(R.string.aefs_wizard_failure),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

/**
 * 底部按钮栏
 */
@Composable
fun AEFSWizardBottomBar(
    step: Int,
    totalSteps: Int,
    isFormatting: Boolean,
    canProceed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .shadow(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = step > 0 && !isFormatting,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.aefs_wizard_previous))
                Text(stringResource(R.string.aefs_wizard_previous))
            }
            
            if (step < totalSteps) {
                Button(
                    onClick = onNext,
                    enabled = canProceed && !isFormatting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.aefs_wizard_next))
                }
            } else {
                Button(
                    onClick = onFinish,
                    enabled = !isFormatting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.home_close))
                }
            }
        }
    }
}

// 添加一个 warningContainer 颜色便利属性
val warning_container_color: Color
    @Composable
    get() = Color(0xFFFFF8E1)
