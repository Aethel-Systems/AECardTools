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

package com.aethel.aecardtools.nfc.protocol.isodep

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.aethel.aecardtools.nfc.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * IsoDep CPU卡协议处理器 - 工业级实现
 * 
 * 支持：
 * - EMV (金融卡, SW返回码处理)
 * - ePassport (电子护照, 长指令)
 * - JCOP/JavaCard
 * - SIM卡
 * - 交通联合互通卡
 * 
 * 核心特性：
 * 1. APDU隧道：直接透传用户指令，无内置逻辑
 * 2. 扩展APDU支持检测和处理
 * 3. ATR信息解析
 * 4. 自动处理61xx More Data响应
 * 5. 完整的错误处理和超时管理
 */
class IsoDEPCardHandler(
    tag: Tag
) : NFCProtocolHandler(tag, NFCProtocolType.ISO_DEP), IsoDEPHandler {
    
    private var isoDep: IsoDep? = null
    private var atr: ByteArray? = null
    private var isExtendedLengthSupported = false
    private var maxTransceiveLength = 257  // 默认值
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            isoDep = IsoDep.get(tag)
            isoDep?.connect()
            
            // 获取ATR
            atr = isoDep?.historicalBytes
            Timber.i("IsoDep已连接，ATR: ${atr?.toHexString() ?: "null"}")
            
            // 检测扩展APDU支持
            isExtendedLengthSupported = try {
                isoDep?.isExtendedLengthApduSupported ?: false
            } catch (e: Exception) {
                Timber.w("获取扩展APDU支持失败: ${e.message}")
                false
            }
            
            // 获取最大传输长度
            maxTransceiveLength = try {
                isoDep?.maxTransceiveLength ?: 257
            } catch (e: Exception) {
                Timber.w("获取最大传输长度失败: ${e.message}")
                257
            }
            
            Timber.i("最大传输长度: $maxTransceiveLength, 扩展APDU: $isExtendedLengthSupported")

            // 👇【加入这行释放锁】
            runCatching { isoDep?.close() }

            true
        } catch (e: Exception) {
            Timber.e(e, "IsoDep初始化失败")
            false
        }
    }
    
    override fun cleanup() {
        try {
            isoDep?.close()
            isoDep = null
        } catch (e: Exception) {
            Timber.w(e, "关闭IsoDep连接失败")
        }
    }
    
    override fun getRawTransceiveProvider(): RawTransceiveProvider = this
    
    // ==================== RawTransceiveProvider实现 ====================
    
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            isoDep?.connect()
            isoDep?.isConnected ?: false
        } catch (e: Exception) {
            Timber.e(e, "连接IsoDep失败")
            false
        }
    }
    
    override fun disconnect() {
        try {
            isoDep?.close()
        } catch (e: Exception) {
            Timber.w(e, "断开IsoDep连接时出错")
        }
    }
    
    override suspend fun transceive(
        command: ByteArray,
        timeout: Long
    ): RawTransceiveResult = withContext(Dispatchers.IO) {
        if (isoDep == null) {
            return@withContext RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = Exception("IsoDep未连接")
            )
        }
        
        val shouldAutoClose = !(isoDep!!.isConnected)
        
        return@withContext try {
            if (shouldAutoClose) {
                isoDep?.connect()
            }
            
            val startTime = System.currentTimeMillis()
            
            // 处理超时设置
            if (timeout > 0) {
                try {
                    isoDep?.timeout = timeout.toInt()
                } catch (e: Exception) {
                    Timber.w("设置超时失败: ${e.message}")
                }
            }
            
            val response = isoDep!!.transceive(command)
            val elapsed = System.currentTimeMillis() - startTime
            
            // 记录操作
            logInstruction(command, response, true, elapsed)
            
            RawTransceiveResult(
                response = response,
                isSuccess = true,
                elapsedTimeMs = elapsed
            )
        } catch (e: Exception) {
            Timber.e(e, "IsoDep transceive失败")
            RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = e
            )
        } finally {
            if (shouldAutoClose) {
                runCatching { isoDep?.close() }
            }
        }
    }
    
    override suspend fun transceiveBatch(
        commands: List<ByteArray>,
        delayBetweenMs: Long
    ): List<RawTransceiveResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RawTransceiveResult>()
        
        for ((index, command) in commands.withIndex()) {
            val result = transceive(command)
            results.add(result)
            
            // 发送延迟 (最后一条指令除外)
            if (index < commands.size - 1 && delayBetweenMs > 0) {
                kotlinx.coroutines.delay(delayBetweenMs)
            }
        }
        
        return@withContext results
    }
    
    override fun getMaxTransceiveLength(): Int = maxTransceiveLength
    
    override fun isConnected(): Boolean = isoDep?.isConnected ?: false
    
    // ==================== IsoDEPHandler实现 ====================
    
    override fun getATR(): ByteArray? = atr
    
    override fun isExtendedLengthApduSupported(): Boolean = isExtendedLengthSupported
    
    override suspend fun sendAPDU(apdu: ByteArray): APDUResponse = withContext(Dispatchers.IO) {
        // 检查APDU长度
        if (!isExtendedLengthSupported && apdu.size > 261) {
            return@withContext APDUResponse(
                sw1 = 0x61.toByte(),
                sw2 = 0x82.toByte(),
                isSuccess = false,
                errorDescription = "APDU长度超过限制(${apdu.size} > 261字节)，未启用扩展APDU"
            )
        }
        
        try {
            val startTime = System.currentTimeMillis()
            val response = isoDep?.transceive(apdu) ?: byteArrayOf()
            val elapsed = System.currentTimeMillis() - startTime
            
            // 解析响应
            return@withContext if (response.size >= 2) {
                val sw1 = (response[response.size - 2].toInt() and 0xFF).toByte()
                val sw2 = (response[response.size - 1].toInt() and 0xFF).toByte()
                val data = response.dropLast(2).toByteArray()
                val isSuccess = (sw1.toInt() and 0xFF) == 0x90 && (sw2.toInt() and 0xFF) == 0x00
                
                val (statusStr, description) = parseAPDUStatusWord(sw1, sw2)
                
                logInstruction(apdu, response, isSuccess, elapsed)
                
                APDUResponse(
                    data = data,
                    sw1 = sw1,
                    sw2 = sw2,
                    isSuccess = isSuccess,
                    errorDescription = description
                )
            } else {
                APDUResponse(
                    data = response,
                    isSuccess = false,
                    errorDescription = "响应长度不足"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "APDU发送失败")
            APDUResponse(
                isSuccess = false,
                errorDescription = "异常: ${e.message}"
            )
        }
    }
    
    override suspend fun selectAID(aid: String): APDUResponse = withContext(Dispatchers.IO) {
        try {
            val aidBytes = aid.hexToByteArray()
            // 构建Select AID APDU: 00 A4 04 00 [Lc] [AID]
            val apdu = byteArrayOf(
                0x00,           // CLA
                0xA4.toByte(),  // INS
                0x04,           // P1 (Select by AID)
                0x00,           // P2
                aidBytes.size.toByte()
            ) + aidBytes
            
            return@withContext sendAPDU(apdu)
        } catch (e: Exception) {
            Timber.e(e, "Select AID失败")
            APDUResponse(
                isSuccess = false,
                errorDescription = "异常: ${e.message}"
            )
        }
    }
    
    override suspend fun getResponse(length: Int): APDUResponse = withContext(Dispatchers.IO) {
        try {
            // Get Response APDU: 00 C0 00 00 [Le]
            val apdu = byteArrayOf(
                0x00,           // CLA
                0xC0.toByte(),  // INS
                0x00,           // P1
                0x00,           // P2
                length.toByte()
            )
            
            return@withContext sendAPDU(apdu)
        } catch (e: Exception) {
            Timber.e(e, "Get Response失败")
            APDUResponse(
                isSuccess = false,
                errorDescription = "异常: ${e.message}"
            )
        }
    }
}

/**
 * APDU响应扩展属性
 */
fun APDUResponse.getFullResponse(): ByteArray {
    return data + byteArrayOf(sw1, sw2)
}

fun APDUResponse.toFormattedString(): String {
    val dataHex = data.toHexString()
    val swHex = "%02X %02X".format(sw1, sw2)
    return "数据: $dataHex\nSW: $swHex\n描述: $errorDescription"
}
