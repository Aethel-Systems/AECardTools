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

package com.aethel.aecardtools.nfc

import android.content.Context
import android.nfc.Tag
import com.aethel.aecardtools.nfc.capability.NFCCapabilityProber
import com.aethel.aecardtools.nfc.protocol.NFCCapabilityProbeResult
import com.aethel.aecardtools.nfc.protocol.NFCProtocolDetector
import com.aethel.aecardtools.nfc.protocol.NFCProtocolHandler
import com.aethel.aecardtools.nfc.protocol.NFCProtocolType
import com.aethel.aecardtools.nfc.protocol.toHexString
import com.aethel.aecardtools.nfc.protocol.isodep.IsoDEPCardHandler
import com.aethel.aecardtools.nfc.protocol.nfca.NfcACardHandler
import com.aethel.aecardtools.nfc.protocol.other.NfcBCardHandler
import com.aethel.aecardtools.nfc.protocol.other.NfcFCardHandler
import com.aethel.aecardtools.nfc.protocol.other.NfcVCardHandler
import com.aethel.aecardtools.nfc.safety.SensitiveInstructionInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NFC协议统一管理器 (Universal Protocol Manager)
 * 
 * 职责：
 * 1. 检测卡片的所有支持协议
 * 2. 选择最佳的协议处理器
 * 3. 管理协议处理器的生命周期
 * 4. 实施安全策略和风险控制
 * 5. 记录所有操作日志
 * 
 * 设计原则：
 * - 协议自动检测和路由
 * - 透明的底层实现
 * - 完整的审计和可追溯性
 * - 工业级的错误处理和恢复
 */
class UniversalProtocolManager(
    private val context: Context,
    private val tag: Tag
) {
    
    private val detector = NFCProtocolDetector(tag)
    private val capabilityProber = NFCCapabilityProber(context)
    private val safetyInterceptor = SensitiveInstructionInterceptor(enableInterception = true)
    
    private var detectionResult: NFCProtocolDetector.DetectionResult? = null
    private var capabilityResult: NFCCapabilityProbeResult? = null
    
    private val handlers = mutableMapOf<NFCProtocolType, NFCProtocolHandler>()
    
    /**
     * 初始化管理器
     * - 探测协议
     * - 探测硬件能力
     * - 初始化相应的处理器
     */
    suspend fun initialize(): InitializationResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("=== 开始UPT初始化 ===")
            
            // 步骤1: 协议检测
            Timber.i("步骤1: 检测卡片协议...")
            detectionResult = detector.detect()
            if (detectionResult == null) {
                return@withContext InitializationResult(
                    success = false,
                    errorMessage = "协议检测失败"
                )
            }
            
            val detection = detectionResult!!
            Timber.i("检测结果: 主协议=${detection.primaryProtocol}, 支持=${detection.supportedProtocols}")
            
            // 步骤2: 硬件能力探测
            Timber.i("步骤2: 探测硬件能力...")
            capabilityResult = capabilityProber.probe(tag)
            val capability = capabilityResult!!
            Timber.i("硬件能力: 级别=${capability.capabilityLevel}, 厂商=${capability.vendorLikelihood}")
            
            // 步骤3: 初始化协议处理器
            Timber.i("步骤3: 初始化协议处理器...")
            val initializeResult = initializeProtocolHandlers(detection)
            if (!initializeResult) {
                return@withContext InitializationResult(
                    success = false,
                    errorMessage = "处理器初始化失败"
                )
            }
            
            Timber.i("=== UPT初始化成功 ===")
            
            return@withContext InitializationResult(
                success = true,
                detectionResult = detection,
                capabilityResult = capability,
                errorMessage = null
            )
        } catch (e: Exception) {
            Timber.e(e, "UPT初始化异常")
            return@withContext InitializationResult(
                success = false,
                errorMessage = "初始化异常: ${e.message}"
            )
        }
    }
    
    /**
     * 初始化所有支持的协议处理器
     */
    private suspend fun initializeProtocolHandlers(
        detection: NFCProtocolDetector.DetectionResult
    ): Boolean = withContext(Dispatchers.IO) {
        for (protocol in detection.supportedProtocols) {
            try {
                val handler = createProtocolHandler(protocol)
                if (handler.initialize()) {
                    handlers[protocol] = handler
                    Timber.i("${protocol.name} 处理器已初始化")
                } else {
                    Timber.w("${protocol.name} 处理器初始化失败")
                }
            } catch (e: Exception) {
                Timber.w(e, "${protocol.name} 处理器初始化异常")
            }
        }
        
        return@withContext handlers.isNotEmpty()
    }
    
    /**
     * 创建指定协议的处理器
     */
    private fun createProtocolHandler(protocol: NFCProtocolType): NFCProtocolHandler {
        return when (protocol) {
            NFCProtocolType.ISO_DEP -> IsoDEPCardHandler(tag)
            NFCProtocolType.NFC_A -> NfcACardHandler(tag)
            NFCProtocolType.NFC_B -> NfcBCardHandler(tag)
            NFCProtocolType.NFC_F -> NfcFCardHandler(tag)
            NFCProtocolType.NFC_V -> NfcVCardHandler(tag)
            else -> throw IllegalArgumentException("未知协议: $protocol")
        }
    }
    
    /**
     * 获取指定协议的处理器
     */
    fun getHandler(protocol: NFCProtocolType): NFCProtocolHandler? {
        return handlers[protocol]
    }
    
    /**
     * 获取主协议处理器
     */
    fun getPrimaryHandler(): NFCProtocolHandler? {
        val primaryProtocol = detectionResult?.primaryProtocol ?: return null
        return handlers[primaryProtocol]
    }
    
    /**
     * 获取所有初始化成功的处理器
     */
    fun getAllHandlers(): Map<NFCProtocolType, NFCProtocolHandler> {
        return handlers.toMap()
    }
    
    /**
     * 获取检测结果
     */
    fun getDetectionResult(): NFCProtocolDetector.DetectionResult? = detectionResult
    
    /**
     * 获取硬件能力探测结果
     */
    fun getCapabilityResult(): NFCCapabilityProbeResult? = capabilityResult
    
    /**
     * 获取安全拦截器
     */
    fun getSafetyInterceptor(): SensitiveInstructionInterceptor = safetyInterceptor

    fun getCardUidHex(): String = tag.id?.toHexString(separator = "") ?: "UNKNOWN"

    fun suggestRoute(): SuggestedProtocolRoute {
        val detection = detectionResult ?: return SuggestedProtocolRoute.GENERIC_ANALYZER
        return when {
            detection.isStandardMifare -> SuggestedProtocolRoute.M1_EXPERT_MODE
            detection.supportedProtocols.contains(NFCProtocolType.ISO_DEP) &&
                !detection.isStandardMifare -> SuggestedProtocolRoute.CPU_TERMINAL
            detection.supportedProtocols.contains(NFCProtocolType.NFC_A) &&
                !detection.isStandardMifare -> SuggestedProtocolRoute.RAW_NFCA_MODE
            detection.supportedProtocols.any {
                it == NFCProtocolType.NFC_B || it == NFCProtocolType.NFC_F || it == NFCProtocolType.NFC_V
            } -> SuggestedProtocolRoute.GENERIC_ANALYZER
            else -> SuggestedProtocolRoute.GENERIC_ANALYZER
        }
    }
    
    /**
     * 清理所有资源
     */
    fun cleanup() {
        Timber.i("清理UPT资源...")
        handlers.forEach { (protocol, handler) ->
            try {
                handler.cleanup()
                Timber.i("${protocol.name} 处理器已清理")
            } catch (e: Exception) {
                Timber.w(e, "${protocol.name} 处理器清理失败")
            }
        }
        handlers.clear()
    }
    
    /**
     * 生成完整的诊断报告
     */
    fun generateDiagnosticReport(): String {
        val sb = StringBuilder()
        
        sb.append("=== AECardTools NFC诊断报告 ===\n\n")
        
        // 卡片检测信息
        detectionResult?.let { detection ->
            sb.append("【卡片检测信息】\n")
            sb.append("- 主协议: ${detection.primaryProtocol.name}\n")
            sb.append("- 支持的协议: ${detection.supportedProtocols.joinToString(", ") { it.name }}\n")
            sb.append("- MIFARE子类型: ${detection.mifareSubType.name}\n")
            sb.append("- 是否CPU卡: ${detection.isCPUCard}\n")
            sb.append("- 是否标准M1: ${detection.isStandardMifare}\n\n")
            sb.append("- 智能路由建议: ${suggestRoute().name}\n\n")
        }
        
        // 硬件能力信息
        capabilityResult?.let { capability ->
            sb.append("【硬件能力信息】\n")
            sb.append("- 设备型号: ${capability.deviceModel}\n")
            sb.append("- 控制器厂商: ${capability.vendorLikelihood.name}\n")
            sb.append("- 能力级别: ${capability.capabilityLevel.name}\n")
            sb.append("- 最大传输长度: ${capability.maxTransceiveLength} 字节\n")
            sb.append("- 扩展APDU支持: ${capability.supportsExtendedAPDU}\n")
            sb.append("- MIFARE Classic支持: ${capability.supportsMifareClassic}\n")
            sb.append("- 非标卡支持评分: ${capability.nonStandardCardSupportScore}/100\n\n")
            
            if (capability.detailedCapabilities.isNotEmpty()) {
                sb.append("【详细能力】\n")
                capability.detailedCapabilities.forEach { (key, value) ->
                    sb.append("- $key: $value\n")
                }
                sb.append("\n")
            }
        }
        
        // 协议处理器初始化状态
        if (handlers.isNotEmpty()) {
            sb.append("【已初始化的协议处理器】\n")
            handlers.forEach { (protocol, handler) ->
                sb.append("- ${protocol.name}: ${handler.javaClass.simpleName}\n")
                val opLog = handler.getOperationLog()
                if (opLog.isNotEmpty()) {
                    sb.append("  操作数: ${opLog.size}\n")
                }
            }
            sb.append("\n")
        }
        
        // 安全审计日志
        val auditLog = safetyInterceptor.getOperationLog()
        if (auditLog.isNotEmpty()) {
            sb.append("【安全审计日志】\n")
            auditLog.takeLast(10).forEach { log ->
                sb.append("- ${log["操作类型"]}: ${log["详情"]} (${log["是否允许"]})\n")
            }
            if (auditLog.size > 10) {
                sb.append("... 以及 ${auditLog.size - 10} 条其他记录\n")
            }
            sb.append("\n")
        }
        
        sb.append("=== 诊断报告结束 ===")
        
        return sb.toString()
    }
}

/**
 * UPT初始化结果
 */
data class InitializationResult(
    val success: Boolean,
    val detectionResult: NFCProtocolDetector.DetectionResult? = null,
    val capabilityResult: NFCCapabilityProbeResult? = null,
    val errorMessage: String? = null
)

enum class SuggestedProtocolRoute {
    M1_EXPERT_MODE,
    CPU_TERMINAL,
    RAW_NFCA_MODE,
    GENERIC_ANALYZER
}

/**
 * 协议操作结果包装
 */
sealed class ProtocolOperationResult {
    data class Success(val data: ByteArray) : ProtocolOperationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
    
    data class Error(val message: String, val exception: Exception? = null) : ProtocolOperationResult()
    
    data class Warning(val message: String, val data: ByteArray? = null) : ProtocolOperationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Warning) return false
            if (message != other.message) return false
            if (data != null && other.data != null && !data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = message.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }
    }
}
