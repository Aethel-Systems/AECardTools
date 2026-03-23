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

package com.aethel.aecardtools.nfc.protocol.nfca

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import com.aethel.aecardtools.nfc.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NfcA 协议处理器 - 工业级实现
 * 
 * 支持完整的MIFARE生态：
 * - MIFARE Classic 1K/4K (标准)
 * - MIFARE Classic Gen 1a (魔术卡 - 后门支持)
 * - MIFARE Classic Gen 2 CUID (Block 0强制写入)
 * - MIFARE Classic FUID/UFUID (一次性写入)
 * - MIFARE Ultralight/Ultralight C
 * - NTAG系列
 * - 各种非标卡和国产变种
 * 
 * 核心特性：
 * 1. 后门指令透传 (绕过Android逻辑层限制)
 * 2. Block级别直接读写 (无认证或强制读写)
 * 3. 魔术卡检测和自动激活
 * 4. BCC校验位自动计算
 * 5. 非标SAK/ATQA处理
 * 6. 超时和重试机制
 */
class NfcACardHandler(
    tag: Tag
) : NFCProtocolHandler(tag, NFCProtocolType.NFC_A), NfcAHandler {
    
    private var nfcA: NfcA? = null
    private var atqa: ByteArray? = null
    private var sak: Byte = 0x00
    private var mifareSubType = MifareCardSubType.UNKNOWN_MIFARE
    private var isMagicBackdoorActive = false
    private val MAX_RETRY_ATTEMPTS = 3
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcA = NfcA.get(tag)
            nfcA?.connect()
            
            // 获取ATQA和SAK
            atqa = nfcA?.atqa
            sak = (nfcA?.sak?.toByte()) ?: 0x00
            
            Timber.i("NfcA已连接，ATQA: ${atqa?.toHexString()}, SAK: %02X".format(sak))
            
            // 推断MIFARE子类型
            detectMifareSubType()
            
            // 👇【核心修复2】读完特征立刻释放，把锁还给系统
            runCatching { nfcA?.close() }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "NfcA初始化失败")
            false
        }
    }
    
    override fun cleanup() {
        try {
            nfcA?.close()
            nfcA = null
            isMagicBackdoorActive = false
        } catch (e: Exception) {
            Timber.w(e, "关闭NfcA连接失败")
        }
    }
    
    override fun getRawTransceiveProvider(): RawTransceiveProvider = this
    
    // ==================== 子类型检测 ====================
    
    private suspend fun detectMifareSubType() = withContext(Dispatchers.IO) {
        // 首先尝试读Block 0来推断卡片类型
        try {
            // 尝试使用MifareClassic API获取信息
            val mifareClassic = MifareClassic.get(tag)
            if (mifareClassic != null) {
                mifareSubType = when(mifareClassic.type) {
                    0 -> MifareCardSubType.CLASSIC_1K
                    1 -> MifareCardSubType.CLASSIC_4K
                    2 -> MifareCardSubType.MAGIC_GEN_2_CUID
                    3 -> MifareCardSubType.MAGIC_GEN_2_CUID
                    else -> MifareCardSubType.UNKNOWN_MIFARE
                }
                Timber.i("系统识别为: $mifareSubType")
            }
        } catch (e: Exception) {
            Timber.w("MifareClassic识别失败，使用SAK推断")
            mifareSubType = inferSubTypeFromSAK()
        }
    }
    
    private fun inferSubTypeFromSAK(): MifareCardSubType {
        return when (sak.toInt() and 0xFF) {
            0x08 -> MifareCardSubType.CLASSIC_1K
            0x18 -> MifareCardSubType.CLASSIC_4K
            0x28 -> MifareCardSubType.CLASSIC_4K
            0x88 -> MifareCardSubType.UNKNOWN_MIFARE  // 可能是非标卡
            0x04 -> MifareCardSubType.ULTRALIGHT
            0x44 -> MifareCardSubType.ULTRALIGHT_C
            0x00 -> MifareCardSubType.NTAG
            else -> MifareCardSubType.UNKNOWN_MIFARE
        }
    }
    
    // ==================== RawTransceiveProvider实现 ====================
    
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcA?.connect()
            nfcA?.isConnected ?: false
        } catch (e: Exception) {
            Timber.e(e, "连接NfcA失败")
            false
        }
    }
    
    override fun disconnect() {
        try {
            nfcA?.close()
        } catch (e: Exception) {
            Timber.w(e, "断开NfcA连接时出错")
        }
    }
    
    override suspend fun transceive(
        command: ByteArray,
        timeout: Long
    ): RawTransceiveResult = withContext(Dispatchers.IO) {
        if (nfcA == null) {
            return@withContext RawTransceiveResult(response = byteArrayOf(), isSuccess = false, exception = Exception("NfcA未连接"))
        }
        
        var lastException: Exception? = null
        
        // 👇【核心修复3】判断是否需要临时建立连接
        val shouldAutoClose = !(nfcA!!.isConnected)
        
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                if (!nfcA!!.isConnected) {
                    try {
                        nfcA?.connect()
                    } catch (e: IllegalStateException) {
                        // 【修复】：如果之前有别的协议在占用连接，强行关闭它们以抢占信道
                        runCatching { android.nfc.tech.MifareClassic.get(tag)?.close() }
                        runCatching { android.nfc.tech.MifareUltralight.get(tag)?.close() }
                        runCatching { android.nfc.tech.IsoDep.get(tag)?.close() }
                        nfcA?.connect()
                    }
                }
                
                val startTime = System.currentTimeMillis()
                if (timeout > 0) {
                    nfcA?.timeout = timeout.toInt()
                }
                val response = nfcA!!.transceive(command)
                val elapsed = System.currentTimeMillis() - startTime
                
                logInstruction(command, response, true, elapsed)
                
                // 【修复】：移除 runCatching { nfcA?.close() }
                return@withContext RawTransceiveResult(response = response, isSuccess = true, elapsedTimeMs = elapsed)
                
            } catch (e: Exception) {
                lastException = e
                Timber.w("NfcA transceive尝试 ${attempt + 1} 失败: ${e.message}")
                // 【修复】：移除 runCatching { nfcA?.close() }
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(200)
                }
            }
        }
        
        return@withContext RawTransceiveResult(response = byteArrayOf(), isSuccess = false, exception = lastException ?: Exception("NfcA transceive失败"))
    }
    
    override suspend fun transceiveBatch(
        commands: List<ByteArray>,
        delayBetweenMs: Long
    ): List<RawTransceiveResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RawTransceiveResult>()
        
        for ((index, command) in commands.withIndex()) {
            val result = transceive(command)
            results.add(result)
            
            if (index < commands.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }
        }
        
        return@withContext results
    }
    
    override fun getMaxTransceiveLength(): Int {
        return try {
            nfcA?.maxTransceiveLength ?: 257
        } catch (e: Exception) {
            257
        }
    }
    
    override fun isConnected(): Boolean = nfcA?.isConnected ?: false
    
    // ==================== NfcAHandler实现 ====================
    
    override fun getATQA(): ByteArray = atqa ?: byteArrayOf()
    
    override fun getSAK(): Byte = sak
    
    override suspend fun readBlock(blockNum: Int): MifareBlockResult = withContext(Dispatchers.IO) {
        try {
            // 构建Read命令: 30 [BlockNumber]
            val command = byteArrayOf(
                0x30,
                blockNum.toByte()
            )
            
            val result = transceive(command)
            
            return@withContext if (result.isSuccess && result.response.size == 16) {
                MifareBlockResult(
                    blockNum = blockNum,
                    data = result.response,
                    isSuccess = true
                )
            } else {
                MifareBlockResult(
                    blockNum = blockNum,
                    data = byteArrayOf(),
                    isSuccess = false,
                    errorMessage = "读取失败或数据长度错误"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "readBlock异常")
            MifareBlockResult(
                blockNum = blockNum,
                data = byteArrayOf(),
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }
    
    override suspend fun writeBlock(blockNum: Int, data: ByteArray): MifareBlockResult = withContext(Dispatchers.IO) {
        if (data.size != 16) {
            return@withContext MifareBlockResult(
                blockNum = blockNum,
                data = byteArrayOf(),
                isSuccess = false,
                errorMessage = "数据长度必须为16字节，收到 ${data.size}"
            )
        }
        
        try {
            // 构建Write命令: A0 [BlockNumber]
            val writeCmd = byteArrayOf(
                0xA0.toByte(),
                blockNum.toByte()
            )
            
            // 第一步：发送写命令
            val cmdResult = transceive(writeCmd)
            if (!cmdResult.isSuccess) {
                return@withContext MifareBlockResult(
                    blockNum = blockNum,
                    isSuccess = false,
                    errorMessage = "无法进入写模式"
                )
            }
            
            // 检查ACK响应 (0x0A)
            if (cmdResult.response.isNotEmpty() && cmdResult.response[0] != 0x0A.toByte()) {
                Timber.w("写命令未返回ACK，响应: ${cmdResult.response.toHexString()}")
            }
            
            // 延迟
            delay(50)
            
            // 第二步：发送数据
            val dataResult = transceive(data)
            if (!dataResult.isSuccess) {
                return@withContext MifareBlockResult(
                    blockNum = blockNum,
                    isSuccess = false,
                    errorMessage = "写入数据失败"
                )
            }
            
            // 检查ACK响应
            if (dataResult.response.isNotEmpty() && dataResult.response[0] != 0x0A.toByte()) {
                Timber.w("数据帧未返回ACK，响应: ${dataResult.response.toHexString()}")
            }
            
            return@withContext MifareBlockResult(
                blockNum = blockNum,
                data = data,
                isSuccess = true
            )
        } catch (e: Exception) {
            Timber.e(e, "writeBlock异常")
            MifareBlockResult(
                blockNum = blockNum,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }
    
    override suspend fun triggerMagicBackdoor(commandByte: Byte): Boolean = withContext(Dispatchers.IO) {
        try {
            // 对于Gen 1a卡，发送0x40 (停止) 或0x43 (解锁)
            // 对于Gen 2卡，某些需要特定的解锁流程
            
            val command = byteArrayOf(commandByte)
            val result = transceive(command)
            
            // 检查是否得到ACK (0x0A) 或其他指示成功的响应
            val success = result.isSuccess && (
                result.response.isEmpty() ||
                result.response[0] == 0x0A.toByte()
            )
            
            if (success) {
                isMagicBackdoorActive = true
                Timber.i("魔术卡后门已激活 (命令: 0x%02X)".format(commandByte))
                delay(100)  // 等待卡片进入后门模式
            } else {
                Timber.w("魔术卡后门激活失败，响应: ${result.response.toHexString()}")
            }
            
            return@withContext success
        } catch (e: Exception) {
            Timber.e(e, "启用后门失败")
            return@withContext false
        }
    }
    
    override suspend fun authenticateMifareClassic(
        sector: Int,
        key: ByteArray,
        useKeyA: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (key.size != 6) {
            return@withContext false
        }
        
        try {
            val mifareClassic = MifareClassic.get(tag)
            if (mifareClassic == null) {
                return@withContext authenticateMifareRaw(sector, key, useKeyA)
            }
            
            var success = false
            try {
                if (!mifareClassic.isConnected) {
                    mifareClassic.connect()
                }
                success = if (useKeyA) {
                    mifareClassic.authenticateSectorWithKeyA(sector, key)
                } else {
                    mifareClassic.authenticateSectorWithKeyB(sector, key)
                }
                
                if (!success) {
                    runCatching { mifareClassic.close() }
                }
                
                return@withContext success
            } catch (e: Exception) {
                runCatching { mifareClassic.close() }
                return@withContext false
            }
        } catch (e: Exception) {
            return@withContext try {
                authenticateMifareRaw(sector, key, useKeyA)
            } catch (ex: Exception) {
                false
            }
        }
    }
    
    /**
     * 底层MIFARE认证 (使用原始指令)
     */
    private suspend fun authenticateMifareRaw(
        sector: Int,
        key: ByteArray,
        useKeyA: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val blockNumber = sector * 4  // 假设512字节扇区（标准1K卡）
            
            // MIFARE认证命令: 60 (KeyA) 或 61 (KeyB) [BlockNumber] [Key6Bytes]
            val cmd = byteArrayOf(
                if (useKeyA) 0x60.toByte() else 0x61.toByte(),
                blockNumber.toByte()
            ) + key
            
            val result = transceive(cmd)
            
            // 认证成功通常返回4字节的随机数
            val success = result.isSuccess && result.response.size == 4
            Timber.i("底层MIFARE认证: ${if (success) "成功" else "失败"}")
            
            return@withContext success
        } catch (e: Exception) {
            Timber.e(e, "底层认证异常")
            return@withContext false
        }
    }
}

/**
 * MIFARE Block 0写入时的BCC校验
 * 
 * Block 0 结构：
 * - Byte 0-3: UID (4字节)
 * - Byte 4: BCC (Block Check Character) = UID0 ^ UID1 ^ UID2 ^ UID3
 * - Byte 5-7: Manufacturer数据
 * 
 * 构造合法的Block 0数据
 */
fun constructValidMifareBlock0(
    uid: ByteArray
): ByteArray {
    if (uid.size < 4) {
        throw IllegalArgumentException("UID必须至少4字节")
    }
    
    val block0 = ByteArray(16)
    // 复制UID
    uid.take(4).forEachIndexed { i, b -> block0[i] = b }
    
    // 计算并设置BCC
    val bcc = calculateMifareBlockCheckCharacter(
        block0[0], block0[1], block0[2], block0[3]
    )
    block0[4] = bcc
    
    // 填充制造商数据 (通常为0xFF或特定值)
    for (i in 5..15) {
        block0[i] = 0x00
    }
    
    return block0
}

/**
 * 验证Block 0的BCC校验
 */
fun verifyMifareBlock0BCC(block0: ByteArray): Boolean {
    if (block0.size < 5) return false
    
    val calculatedBCC = calculateMifareBlockCheckCharacter(
        block0[0], block0[1], block0[2], block0[3]
    )
    
    return calculatedBCC == block0[4]
}
