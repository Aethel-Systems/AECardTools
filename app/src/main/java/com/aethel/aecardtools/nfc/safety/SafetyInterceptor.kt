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

package com.aethel.aecardtools.nfc.safety

import android.content.Context
import com.aethel.aecardtools.R
import com.aethel.aecardtools.nfc.protocol.NFCProtocolType
import com.aethel.aecardtools.nfc.protocol.calculateMifareBlockCheckCharacter
import timber.log.Timber

/**
 * 敏感指令拦截器 (Safety Interceptor)
 *
 * 核心能力：
 * 1. 发送前检查：识别高风险指令并做拦截/警告
 * 2. 响应后检查：根据状态字动态熔断危险会话
 * 3. 审计日志：完整记录允许/拦截/熔断事件
 */
class SensitiveInstructionInterceptor(
    var enableInterception: Boolean = true
) {

    private val operationHistory = mutableListOf<OperationRecord>()

    // CPU 卡 PIN 风险状态（按卡片 UID 跟踪）
    private val verifyPinConsecutiveFailures = mutableMapOf<String, Int>()
    private val verifyPinSessionBlocked = mutableMapOf<String, Boolean>()

    // NfcA Block 0 写入流程状态
    private val pendingBlock0DataFrame = mutableMapOf<String, Boolean>()

    private data class OperationRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val operationType: String,
        val cardUID: String,
        val details: String,
        val allowed: Boolean
    )

    data class PrecheckResult(
        val allowed: Boolean,
        val message: String = "",
        val requiresAttention: Boolean = false
    )

    data class PostcheckResult(
        val forceTerminateSession: Boolean,
        val message: String = ""
    )

    /**
     * 发送前检查。
     */
    fun checkInstruction(
        cardUID: String,
        protocol: NFCProtocolType,
        command: ByteArray
    ): PrecheckResult {
        if (!enableInterception) {
            return PrecheckResult(allowed = true)
        }

        return when (protocol) {
            NFCProtocolType.ISO_DEP -> checkIsoDepPreflight(cardUID, command)
            NFCProtocolType.NFC_A -> checkNfcAPreflight(cardUID, command)
            else -> PrecheckResult(allowed = true)
        }
    }

    /**
     * 响应后检查。
     */
    fun inspectResponse(
        cardUID: String,
        protocol: NFCProtocolType,
        command: ByteArray,
        response: ByteArray
    ): PostcheckResult {
        if (!enableInterception) {
            return PostcheckResult(forceTerminateSession = false)
        }

        return when (protocol) {
            NFCProtocolType.ISO_DEP -> inspectIsoDepResponse(cardUID, command, response)
            NFCProtocolType.NFC_A -> inspectNfcAResponse(cardUID, command, response)
            else -> PostcheckResult(forceTerminateSession = false)
        }
    }

    private fun checkIsoDepPreflight(cardUID: String, apdu: ByteArray): PrecheckResult {
        if (apdu.size < 2) return PrecheckResult(allowed = true)

        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val isVerifyPin = cla == 0x00 && ins == 0x20

        if (isVerifyPin && verifyPinSessionBlocked[cardUID] == true) {
            recordOperation(
                cardUID = cardUID,
                operationType = "VERIFY_PIN_BLOCKED",
                details = "会话已熔断，拒绝继续发送 VERIFY PIN",
                allowed = false
            )
            return PrecheckResult(
                allowed = false,
                message = "❌ 已熔断：检测到连续 PIN 失败，请移除卡片并重新评估后再操作",
                requiresAttention = true
            )
        }

        if (isVerifyPin) {
            val failures = verifyPinConsecutiveFailures.getOrDefault(cardUID, 0)
            val msg = if (failures > 0) {
                "⚠️ 风险提示：本会话已有 $failures 次 VERIFY PIN 失败"
            } else {
                "⚠️ 高风险：VERIFY PIN 指令可能导致卡片锁死"
            }
            return PrecheckResult(allowed = true, message = msg, requiresAttention = true)
        }

        if (cla == 0x00 && ins == 0x24) {
            return PrecheckResult(
                allowed = true,
                message = "⚠️ 高风险：CHANGE PIN 指令",
                requiresAttention = true
            )
        }

        if (cla == 0x00 && ins == 0x2C) {
            return PrecheckResult(
                allowed = true,
                message = "✋ 极高风险：UNBLOCK PIN 指令",
                requiresAttention = true
            )
        }

        return PrecheckResult(allowed = true)
    }

    private fun checkNfcAPreflight(cardUID: String, command: ByteArray): PrecheckResult {
        if (command.isEmpty()) return PrecheckResult(allowed = true)

        val cmd = command[0].toInt() and 0xFF

        // 先发 A0 00，再发 16 字节数据
        if (cmd == 0xA0 && command.size >= 2) {
            val blockNum = command[1].toInt() and 0xFF
            if (blockNum == 0) {
                pendingBlock0DataFrame[cardUID] = true
                recordOperation(cardUID, "BLOCK0_WRITE_ARMED", "已进入 Block 0 写入流程", true)
                return PrecheckResult(
                    allowed = true,
                    message = "✋ 极高风险：即将写入 Block 0，请确保下一帧 16 字节数据的 BCC 正确",
                    requiresAttention = true
                )
            }
            if (blockNum % 4 == 3) {
                return PrecheckResult(
                    allowed = true,
                    message = "⚠️ 你正在写入控制块 Block $blockNum",
                    requiresAttention = true
                )
            }
        }

        // 检测 Block 0 数据帧并执行 BCC 校验
        if (pendingBlock0DataFrame[cardUID] == true) {
            pendingBlock0DataFrame.remove(cardUID)
            if (command.size != 16) {
                recordOperation(cardUID, "BLOCK0_DATA_INVALID", "Block0 数据帧长度不是16字节", false)
                return PrecheckResult(
                    allowed = false,
                    message = "❌ Block 0 数据帧必须是 16 字节",
                    requiresAttention = true
                )
            }
            val (ok, msg) = validateBlock0BCC(cardUID, command)
            if (!ok) {
                return PrecheckResult(
                    allowed = false,
                    message = msg,
                    requiresAttention = true
                )
            }
            return PrecheckResult(
                allowed = true,
                message = "✅ BCC 校验通过，允许发送 Block 0 数据帧",
                requiresAttention = true
            )
        }

        if (cmd == 0x40 || cmd == 0x43) {
            recordOperation(cardUID, "MAGIC_BACKDOOR_ATTEMPT", "发送后门指令 0x%02X".format(cmd), true)
            return PrecheckResult(
                allowed = true,
                message = "⚠️ 后门指令已发送，不同卡厂响应可能不同",
                requiresAttention = true
            )
        }

        return PrecheckResult(allowed = true)
    }

    private fun inspectIsoDepResponse(
        cardUID: String,
        command: ByteArray,
        response: ByteArray
    ): PostcheckResult {
        if (command.size < 2 || response.size < 2) {
            return PostcheckResult(forceTerminateSession = false)
        }

        val cla = command[0].toInt() and 0xFF
        val ins = command[1].toInt() and 0xFF
        val isVerifyPin = cla == 0x00 && ins == 0x20
        if (!isVerifyPin) {
            return PostcheckResult(forceTerminateSession = false)
        }

        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF

        // 9000 成功，重置失败计数
        if (sw1 == 0x90 && sw2 == 0x00) {
            verifyPinConsecutiveFailures.remove(cardUID)
            verifyPinSessionBlocked.remove(cardUID)
            recordOperation(cardUID, "VERIFY_PIN_SUCCESS", "SW=9000", true)
            return PostcheckResult(forceTerminateSession = false)
        }

        // 63Cx => 剩余次数减少
        val is63Cx = sw1 == 0x63 && (sw2 and 0xF0) == 0xC0
        if (is63Cx) {
            val remaining = sw2 and 0x0F
            val failures = verifyPinConsecutiveFailures.getOrDefault(cardUID, 0) + 1
            verifyPinConsecutiveFailures[cardUID] = failures

            val shouldFuse = failures >= 2 || remaining <= 2
            if (shouldFuse) {
                verifyPinSessionBlocked[cardUID] = true
                recordOperation(
                    cardUID,
                    "VERIFY_PIN_SESSION_FUSED",
                    "连续失败 $failures 次，剩余尝试 $remaining",
                    false
                )
                return PostcheckResult(
                    forceTerminateSession = true,
                    message = "❌ 已强制中断会话：VERIFY PIN 连续失败，卡片剩余尝试可能不足"
                )
            }

            recordOperation(
                cardUID,
                "VERIFY_PIN_WARNING",
                "连续失败 $failures 次，SW=63C$remaining",
                true
            )
            return PostcheckResult(
                forceTerminateSession = false,
                message = "⚠️ VERIFY PIN 失败，剩余尝试约 $remaining 次，建议立即停止"
            )
        }

        // 其他失败：维持计数增长
        val failures = verifyPinConsecutiveFailures.getOrDefault(cardUID, 0) + 1
        verifyPinConsecutiveFailures[cardUID] = failures
        return PostcheckResult(
            forceTerminateSession = false,
            message = "⚠️ VERIFY PIN 未成功 (SW=%02X%02X)".format(sw1, sw2)
        )
    }

    private fun inspectNfcAResponse(
        cardUID: String,
        command: ByteArray,
        response: ByteArray
    ): PostcheckResult {
        if (command.isEmpty()) {
            return PostcheckResult(forceTerminateSession = false)
        }

        // 对 A0 写流程记录 ACK 提示，不熔断
        val cmd = command[0].toInt() and 0xFF
        if (cmd == 0xA0) {
            if (response.isNotEmpty() && response[0] != 0x0A.toByte()) {
                return PostcheckResult(
                    forceTerminateSession = false,
                    message = "⚠️ 写命令未返回 ACK(0A): ${response.toHexString()}"
                )
            }
        }

        return PostcheckResult(forceTerminateSession = false)
    }

    fun validateBlock0BCC(cardUID: String, block0Data: ByteArray): Pair<Boolean, String> {
        if (block0Data.size < 5) {
            return Pair(false, "Block 0 数据不完整 (<5 字节)")
        }

        val bccExpected = calculateMifareBlockCheckCharacter(
            block0Data[0],
            block0Data[1],
            block0Data[2],
            block0Data[3]
        )
        val bccProvided = block0Data[4]

        return if (bccExpected == bccProvided) {
            recordOperation(cardUID, "BLOCK0_BCC_OK", "BCC=%02X".format(bccProvided), true)
            Pair(true, "")
        } else {
            recordOperation(
                cardUID,
                "BLOCK0_BCC_REJECTED",
                "期望 %02X, 提供 %02X".format(bccExpected, bccProvided),
                false
            )
            Pair(
                false,
                "❌ BCC 校验失败：应为 %02X，当前为 %02X。已拒绝发送，避免砖卡".format(
                    bccExpected,
                    bccProvided
                )
            )
        }
    }

    fun resetCardSession(cardUID: String) {
        verifyPinConsecutiveFailures.remove(cardUID)
        verifyPinSessionBlocked.remove(cardUID)
        pendingBlock0DataFrame.remove(cardUID)
        recordOperation(cardUID, "SESSION_RESET", "已重置风险会话状态", true)
    }

    fun getOperationLog(): List<Map<String, Any>> {
        return operationHistory.map { record ->
            mapOf(
                "时间戳" to record.timestamp,
                "操作类型" to record.operationType,
                "卡片UID" to record.cardUID,
                "详情" to record.details,
                "是否允许" to record.allowed
            )
        }
    }

    private fun recordOperation(
        cardUID: String,
        operationType: String,
        details: String,
        allowed: Boolean
    ) {
        operationHistory.add(
            OperationRecord(
                operationType = operationType,
                cardUID = cardUID,
                details = details,
                allowed = allowed
            )
        )
        Timber.i("[${if (allowed) "ALLOW" else "BLOCK"}] $operationType: $details (${cardUID.take(8)})")
    }
}

/**
 * 风险警告对话框数据模型
 */
data class RiskWarningDialogState(
    val title: String,
    val message: String,
    val riskLevel: RiskLevel,
    val requiresSlideConfirm: Boolean = false,
    val buttons: List<DialogButton> = listOf()
)

enum class RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class DialogButton(
    val label: String,
    val action: String,
    val isDangerous: Boolean = false
)

/**
 * 风险警告内容管理
 */
object RiskWarningContent {

    fun getCPUCardWarning(context: Context): RiskWarningDialogState {
        return RiskWarningDialogState(
            title = context.getString(R.string.raw_terminal_warning_cpu_title),
            message = context.getString(R.string.raw_terminal_warning_cpu_message),
            riskLevel = RiskLevel.CRITICAL,
            requiresSlideConfirm = true,
            buttons = listOf(
                DialogButton(context.getString(R.string.raw_terminal_warning_confirm), "CONFIRM", false),
                DialogButton(context.getString(R.string.raw_terminal_warning_cancel), "CANCEL", false)
            )
        )
    }

    fun getMifareClassicWarning(context: Context): RiskWarningDialogState {
        return RiskWarningDialogState(
            title = context.getString(R.string.raw_terminal_warning_mifare_title),
            message = context.getString(R.string.raw_terminal_warning_mifare_message),
            riskLevel = RiskLevel.CRITICAL,
            requiresSlideConfirm = true,
            buttons = listOf(
                DialogButton(context.getString(R.string.raw_terminal_warning_confirm), "CONFIRM", false),
                DialogButton(context.getString(R.string.raw_terminal_warning_cancel), "CANCEL", false)
            )
        )
    }

    fun getGenericRawWarning(context: Context): RiskWarningDialogState {
        return RiskWarningDialogState(
            title = context.getString(R.string.raw_terminal_warning_generic_title),
            message = context.getString(R.string.raw_terminal_warning_generic_message),
            riskLevel = RiskLevel.HIGH,
            requiresSlideConfirm = true,
            buttons = listOf(
                DialogButton(context.getString(R.string.raw_terminal_warning_generic_confirm), "CONFIRM", false),
                DialogButton(context.getString(R.string.raw_terminal_warning_cancel), "CANCEL", false)
            )
        )
    }
}

private fun ByteArray.toHexString(separator: String = " "): String {
    return joinToString(separator) { "%02X".format(it) }
}
