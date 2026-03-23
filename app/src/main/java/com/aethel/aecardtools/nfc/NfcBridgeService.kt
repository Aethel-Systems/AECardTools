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

import com.aethel.aecardtools.nfc.protocol.NFCProtocolType
import com.aethel.aecardtools.nfc.protocol.NfcAHandler
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * NFC Kotlin-Python 桥接服务
 *
 * 职责：
 * 1. 将 Kotlin 的 NFC 硬件操作能力（认证、读/写、Transceive）暴露给 Python
 * 2. 管理 Kotlin → Python 的回调函数注册
 * 3. 确保所有 NFC 操作通过 UniversalProtocolManager 执行
 * 4. 提供工业级的错误处理和日志记录
 *
 * 架构：
 *   Python (FFI 调用)
 *      ↓
 *   nfc_interface.py (NFC接口)
 *      ↓
 *   这个Service (Kotlin回调)
 *      ↓
 *   UniversalProtocolManager (协议处理)
 *      ↓
 *   NfcACardHandler (硬件操作)
 */
class NfcBridgeService(
    private val universalProtocolManager: UniversalProtocolManager
) {
    
    private var callbacksRegistered = false
    private val operationLog = ConcurrentHashMap<String, Any>()
    
    /**
     * 初始化桥接服务：
     * 1. 获取 Python 环境和 nfc_interface 模块
     * 2. 创建四个回调函数
     * 3. 调用 Python 的 register_nfc_callbacks 函数
     */
    fun initializePythonBridge(): Boolean {
        return try {
            Timber.i("=== 开始初始化 NFC 桥接服务 ===")
            
            // 获取 Python 环境
            val python = Python.getInstance()
                ?: return false.also { Timber.e("Python 环境未初始化") }
            
            // 导入 nfc_interface 模块
            val nfcModule = python.getModule("nfc_interface")
                ?: return false.also { Timber.e("无法导入 nfc_interface 模块") }
            
            // 获取 Python 的回调注册函数
            val registerFunc = nfcModule.get("register_nfc_callbacks")
                ?: return false.also { Timber.e("无法获取 register_nfc_callbacks 函数") }
            
            // 创建四个Python callable
            val authenticateCallback = AuthenticateCallable(universalProtocolManager)
            val readCallback = ReadCallable(universalProtocolManager)
            val writeCallback = WriteCallable(universalProtocolManager)
            val transceiveCallback = TransceiveCallable(universalProtocolManager)
            
            // 调用 Python 函数进行回调注册
            return try {
                val result = registerFunc.call(
                    authenticateCallback,
                    readCallback,
                    writeCallback,
                    transceiveCallback
                )
                
                val success = result?.toBoolean() ?: false
                if (success) {
                    Timber.i("NFC 回调函数已成功注册到 Python")
                    callbacksRegistered = true
                } else {
                    Timber.w("Python register_nfc_callbacks 返回 false")
                }
                success
            } catch (e: Exception) {
                Timber.e(e, "调用 Python register_nfc_callbacks 失败")
                false
            }
            
        } catch (e: Exception) {
            Timber.e(e, "NFC 桥接服务初始化失败")
            false
        }
    }
    
    /**
     * 检查回调是否已注册
     */
    fun isCallbacksRegistered(): Boolean = callbacksRegistered
    
    /**
     * 获取初始化状态报告
     */
    fun getInitializationReport(): String {
        return buildString {
            appendLine("=== NFC 桥接服务状态 ===")
            appendLine("回调已注册: $callbacksRegistered")
            appendLine("UPT 管理器: ${universalProtocolManager.getPrimaryHandler()?.let { "已初始化" } ?: "未初始化"}")
            appendLine("服务就绪: ${isCallbacksRegistered()}")
        }
    }
    
    // ============================================================
    // === 内部回调类 - 实现Python可调用的逻辑
    // ============================================================
    
    /**
     * 认证回调 - Python 调用格式：
     * authenticate_callback(sector: int, key: bytes, key_type: str) -> {success: bool, ...}
     */
    private class AuthenticateCallable(
        private val manager: UniversalProtocolManager
    ) {
        fun authenticate(sector: Int, key: ByteArray, keyType: String): Map<String, Any> {
            return runBlocking(Dispatchers.Default) {
                try {
                    Timber.i("Python→Kotlin 认证调用：扇区=$sector, 密钥长度=${key.size}, 类型=$keyType")
                    
                    if (key.size != 6) {
                        return@runBlocking mapOf(
                            "success" to false,
                            "error" to "密钥长度必须是 6 字节，收到 ${key.size} 字节"
                        )
                    }
                    
                    val nfcAHandler = manager.getHandler(NFCProtocolType.NFC_A) as? NfcAHandler
                        ?: return@runBlocking mapOf(
                            "success" to false,
                            "error" to "NFC_A 处理器未初始化"
                        )
                    
                    val useKeyA = keyType.uppercase() == "A"
                    val startTime = System.currentTimeMillis()
                    val success = nfcAHandler.authenticateMifareClassic(sector, key, useKeyA)
                    val duration = System.currentTimeMillis() - startTime
                    
                    Timber.i("认证完成：成功=$success，耗时=${duration}ms")
                    
                    if (success) {
                        mapOf(
                            "success" to true,
                            "sector" to sector,
                            "key_type" to keyType,
                            "duration_ms" to duration
                        )
                    } else {
                        mapOf(
                            "success" to false,
                            "sector" to sector,
                            "key_type" to keyType,
                            "error" to "认证失败",
                            "duration_ms" to duration
                        )
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "认证回调异常")
                    mapOf("success" to false, "error" to "异常：${e.message}")
                }
            }
        }
    }
    
    /**
     * 读取回调 - Python 调用格式：
     * read_callback(sector: int, block: int) -> {success: bool, data: bytes, ...}
     */
    private class ReadCallable(
        private val manager: UniversalProtocolManager
    ) {
        fun read(sector: Int, block: Int): Map<String, Any> {
            return runBlocking(Dispatchers.Default) {
                try {
                    Timber.i("Python→Kotlin 读取调用：扇区=$sector，块=$block")
                    
                    val nfcAHandler = manager.getHandler(NFCProtocolType.NFC_A) as? NfcAHandler
                        ?: return@runBlocking mapOf(
                            "success" to false,
                            "error" to "NFC_A 处理器未初始化"
                        )
                    
                    val blockNum = sector * 4 + block
                    val startTime = System.currentTimeMillis()
                    val result = nfcAHandler.readBlock(blockNum)
                    val duration = System.currentTimeMillis() - startTime
                    
                    if (result.isSuccess) {
                        Timber.i("读取成功：${result.data.size} 字节，耗时=${duration}ms")
                        mapOf(
                            "success" to true,
                            "sector" to sector,
                            "block" to block,
                            "data" to result.data,
                            "duration_ms" to duration
                        )
                    } else {
                        mapOf(
                            "success" to false,
                            "sector" to sector,
                            "block" to block,
                            "error" to (result.errorMessage ?: "读取失败"),
                            "duration_ms" to duration
                        )
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "读取回调异常")
                    mapOf("success" to false, "error" to "异常：${e.message}")
                }
            }
        }
    }
    
    /**
     * 写入回调 - Python 调用格式：
     * write_callback(sector: int, block: int, data: bytes) -> {success: bool, ...}
     */
    private class WriteCallable(
        private val manager: UniversalProtocolManager
    ) {
        fun write(sector: Int, block: Int, data: ByteArray): Map<String, Any> {
            return runBlocking(Dispatchers.Default) {
                try {
                    Timber.i("Python→Kotlin 写入调用：扇区=$sector，块=$block，长度=${data.size}")
                    
                    if (data.size != 16) {
                        return@runBlocking mapOf(
                            "success" to false,
                            "error" to "数据长度必须是 16 字节，收到 ${data.size} 字节"
                        )
                    }
                    
                    val nfcAHandler = manager.getHandler(NFCProtocolType.NFC_A) as? NfcAHandler
                        ?: return@runBlocking mapOf(
                            "success" to false,
                            "error" to "NFC_A 处理器未初始化"
                        )
                    
                    val blockNum = sector * 4 + block
                    val startTime = System.currentTimeMillis()
                    val result = nfcAHandler.writeBlock(blockNum, data)
                    val duration = System.currentTimeMillis() - startTime
                    
                    if (result.isSuccess) {
                        Timber.i("写入成功：${result.data.size} 字节，耗时=${duration}ms")
                        mapOf(
                            "success" to true,
                            "sector" to sector,
                            "block" to block,
                            "bytes_written" to 16,
                            "duration_ms" to duration
                        )
                    } else {
                        mapOf(
                            "success" to false,
                            "sector" to sector,
                            "block" to block,
                            "error" to (result.errorMessage ?: "写入失败"),
                            "duration_ms" to duration
                        )
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "写入回调异常")
                    mapOf("success" to false, "error" to "异常：${e.message}")
                }
            }
        }
    }
    
    /**
     * Transceive 回调 - Python 调用格式：
     * transceive_callback(apdu: bytes) -> bytes (响应数据)
     */
    private class TransceiveCallable(
        private val manager: UniversalProtocolManager
    ) {
        fun transceive(apdu: ByteArray): ByteArray {
            return runBlocking(Dispatchers.Default) {
                try {
                    val activeProtocol = when {
                        manager.getHandler(NFCProtocolType.ISO_DEP) != null -> NFCProtocolType.ISO_DEP
                        manager.getHandler(NFCProtocolType.NFC_A) != null -> NFCProtocolType.NFC_A
                        manager.getPrimaryHandler() != null -> manager.getDetectionResult()?.primaryProtocol
                        else -> null
                    }

                    if (activeProtocol == null) {
                        Timber.w("Python→Kotlin Transceive 失败：无可用协议处理器")
                        return@runBlocking byteArrayOf(0x6F, 0x00)
                    }

                    val handler = manager.getHandler(activeProtocol)
                        ?: manager.getPrimaryHandler()
                        ?: return@runBlocking byteArrayOf(0x6F, 0x00)

                    Timber.i("Python→Kotlin Transceive 调用：协议=$activeProtocol, 长度=${apdu.size}")

                    val interceptor = manager.getSafetyInterceptor()
                    val uid = manager.getCardUidHex()
                    val preCheck = interceptor.checkInstruction(uid, activeProtocol, apdu)
                    if (!preCheck.allowed) {
                        Timber.w("Transceive 被安全拦截: ${preCheck.message}")
                        return@runBlocking byteArrayOf(0x69, 0x85.toByte())
                    }

                    val result = handler.getRawTransceiveProvider().transceive(apdu)

                    if (!result.isSuccess) {
                        Timber.w("Transceive 失败：${result.exception?.message}")
                        return@runBlocking byteArrayOf(0x6F, 0x00)
                    }

                    val post = interceptor.inspectResponse(uid, activeProtocol, apdu, result.response)
                    if (post.forceTerminateSession) {
                        handler.getRawTransceiveProvider().disconnect()
                        Timber.w("安全熔断触发: ${post.message}")
                        return@runBlocking byteArrayOf(0x69, 0x83.toByte())
                    }

                    Timber.i("Transceive 成功：${result.response.size} 字节")
                    result.response
                } catch (e: Exception) {
                    Timber.e(e, "Transceive 回调异常")
                    byteArrayOf(0x6F, 0x00)
                }
            }
        }
    }
}
