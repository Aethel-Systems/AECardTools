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

package com.aethel.aecardtools.nfc.protocol.other

import android.nfc.Tag
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.aethel.aecardtools.nfc.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NfcB (ISO 14443-3B) 协议处理器
 * 支持：二代证底层、老式门禁等类型B卡片
 */
class NfcBCardHandler(
    tag: Tag
) : NFCProtocolHandler(tag, NFCProtocolType.NFC_B), NfcBHandler {
    
    private var nfcB: NfcB? = null
    private var atqb: ByteArray? = null
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            nfcB = NfcB.get(tag)
            nfcB?.connect()
            
            atqb = fetchATQBSafely()
            Timber.i("NfcB已连接，ATQB: ${atqb?.toHexString() ?: "null"}")
            
            runCatching { nfcB?.close() }
            true
        } catch (e: Exception) {
            Timber.e(e, "NfcB初始化失败")
            false
        }
    }
    
    private fun fetchATQBSafely(): ByteArray? {
        return try {
            val appData = nfcB?.applicationData ?: byteArrayOf()
            val protocolInfo = nfcB?.protocolInfo ?: byteArrayOf()
            appData + protocolInfo
        } catch (e: Exception) {
            Timber.w("无法获取ATQB: ${e.message}")
            null
        }
    }
    
    override fun cleanup() {
        try {
            nfcB?.close()
            nfcB = null
        } catch (e: Exception) {
            Timber.w(e, "关闭NfcB连接失败")
        }
    }
    
    override fun getRawTransceiveProvider(): RawTransceiveProvider = this
    
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcB?.connect()
            nfcB?.isConnected ?: false
        } catch (e: Exception) {
            Timber.e(e, "连接NfcB失败")
            false
        }
    }
    
    override fun disconnect() {
        try {
            nfcB?.close()
        } catch (e: Exception) {
            Timber.w(e, "断开NfcB连接时出错")
        }
    }
    
    override suspend fun transceive(
        command: ByteArray,
        timeout: Long
    ): RawTransceiveResult = withContext(Dispatchers.IO) {
        if (nfcB == null) {
            return@withContext RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = Exception("NfcB未连接")
            )
        }
        
        val shouldAutoClose = !(nfcB!!.isConnected)
        
        return@withContext try {
            if (shouldAutoClose) {
                nfcB?.connect()
            }
            
            val startTime = System.currentTimeMillis()
            val response = nfcB!!.transceive(command)
            val elapsed = System.currentTimeMillis() - startTime
            
            logInstruction(command, response, true, elapsed)
            
            RawTransceiveResult(
                response = response,
                isSuccess = true,
                elapsedTimeMs = elapsed
            )
        } catch (e: Exception) {
            Timber.e(e, "NfcB transceive失败")
            RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = e
            )
        } finally {
            if (shouldAutoClose) {
                runCatching { nfcB?.close() }
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
            if (index < commands.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }
        }
        return@withContext results
    }
    
    override fun getMaxTransceiveLength(): Int = nfcB?.maxTransceiveLength ?: 257
    
    override fun isConnected(): Boolean = nfcB?.isConnected ?: false
    
    override fun getATQB(): ByteArray? = atqb
    
    override suspend fun sendTypeB(command: ByteArray): RawTransceiveResult {
        return transceive(command)
    }
}

/**
 * NfcF (FeliCa) 协议处理器
 * 支持：Sony FeliCa系统、日系卡片等
 */
class NfcFCardHandler(
    tag: Tag
) : NFCProtocolHandler(tag, NFCProtocolType.NFC_F), NfcFHandler {
    
    private var nfcF: NfcF? = null
    private var systemCode: ByteArray? = null
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcF = NfcF.get(tag)
            nfcF?.connect()
            systemCode = nfcF?.systemCode
            Timber.i("NfcF已连接，SystemCode: ${systemCode?.toHexString() ?: "null"}")
            
            runCatching { nfcF?.close() }
            true
        } catch (e: Exception) {
            Timber.e(e, "NfcF初始化失败")
            false
        }
    }
    
    override fun cleanup() {
        try {
            nfcF?.close()
            nfcF = null
        } catch (e: Exception) {
            Timber.w(e, "关闭NfcF连接失败")
        }
    }
    
    override fun getRawTransceiveProvider(): RawTransceiveProvider = this
    
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcF?.connect()
            nfcF?.isConnected ?: false
        } catch (e: Exception) {
            Timber.e(e, "连接NfcF失败")
            false
        }
    }
    
    override fun disconnect() {
        try {
            nfcF?.close()
        } catch (e: Exception) {
            Timber.w(e, "断开NfcF连接时出错")
        }
    }
    
    override suspend fun transceive(
        command: ByteArray,
        timeout: Long
    ): RawTransceiveResult = withContext(Dispatchers.IO) {
        if (nfcF == null) {
            return@withContext RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = Exception("NfcF未连接")
            )
        }
        
        val shouldAutoClose = !(nfcF!!.isConnected)
        
        return@withContext try {
            if (shouldAutoClose) {
                nfcF?.connect()
            }
            
            val startTime = System.currentTimeMillis()
            val response = nfcF!!.transceive(command)
            val elapsed = System.currentTimeMillis() - startTime
            
            logInstruction(command, response, true, elapsed)
            
            RawTransceiveResult(
                response = response,
                isSuccess = true,
                elapsedTimeMs = elapsed
            )
        } catch (e: Exception) {
            Timber.e(e, "NfcF transceive失败")
            RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = e
            )
        } finally {
            if (shouldAutoClose) {
                runCatching { nfcF?.close() }
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
            if (index < commands.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }
        }
        return@withContext results
    }
    
    override fun getMaxTransceiveLength(): Int = nfcF?.maxTransceiveLength ?: 254
    
    override fun isConnected(): Boolean = nfcF?.isConnected ?: false
    
    override fun getSystemCode(): ByteArray? = systemCode
    
    override suspend fun sendFeliCa(command: ByteArray): RawTransceiveResult {
        return transceive(command)
    }
}

/**
 * NfcV (ISO 15693) 协议处理器
 * 支持：物流标签、工业卡片等同步类卡片
 */
class NfcVCardHandler(
    tag: Tag
) : NFCProtocolHandler(tag, NFCProtocolType.NFC_V), NfcVHandler {
    
    private var nfcV: NfcV? = null
    private var dsf: Byte = 0x00
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            nfcV = NfcV.get(tag)
            nfcV?.connect()
            
            dsf = fetchDSFSafely()
            Timber.i("NfcV已连接，DSF: 0x%02X".format(dsf))
            
            runCatching { nfcV?.close() }
            true
        } catch (e: Exception) {
            Timber.e(e, "NfcV初始化失败")
            false
        }
    }
    
    private fun fetchDSFSafely(): Byte {
        return try {
            nfcV?.dsfId ?: 0x00
        } catch (e: Exception) {
            Timber.w("无法获取DSF: ${e.message}")
            0x00
        }
    }
    
    override fun cleanup() {
        try {
            nfcV?.close()
            nfcV = null
        } catch (e: Exception) {
            Timber.w(e, "关闭NfcV连接失败")
        }
    }
    
    override fun getRawTransceiveProvider(): RawTransceiveProvider = this
    
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            nfcV?.connect()
            nfcV?.isConnected ?: false
        } catch (e: Exception) {
            Timber.e(e, "连接NfcV失败")
            false
        }
    }
    
    override fun disconnect() {
        try {
            nfcV?.close()
        } catch (e: Exception) {
            Timber.w(e, "断开NfcV连接时出错")
        }
    }
    
    override suspend fun transceive(
        command: ByteArray,
        timeout: Long
    ): RawTransceiveResult = withContext(Dispatchers.IO) {
        if (nfcV == null) {
            return@withContext RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = Exception("NfcV未连接")
            )
        }
        
        val shouldAutoClose = !(nfcV!!.isConnected)
        
        return@withContext try {
            if (shouldAutoClose) {
                nfcV?.connect()
            }
            
            val startTime = System.currentTimeMillis()
            val response = nfcV!!.transceive(command)
            val elapsed = System.currentTimeMillis() - startTime
            
            logInstruction(command, response, true, elapsed)
            
            RawTransceiveResult(
                response = response,
                isSuccess = true,
                elapsedTimeMs = elapsed
            )
        } catch (e: Exception) {
            Timber.e(e, "NfcV transceive失败")
            RawTransceiveResult(
                response = byteArrayOf(),
                isSuccess = false,
                exception = e
            )
        } finally {
            if (shouldAutoClose) {
                runCatching { nfcV?.close() }
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
            if (index < commands.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }
        }
        return@withContext results
    }
    
    override fun getMaxTransceiveLength(): Int = nfcV?.maxTransceiveLength ?: 254
    
    override fun isConnected(): Boolean = nfcV?.isConnected ?: false
    
    override fun getDSF(): Byte = dsf
    
    override suspend fun sendISO15693Command(command: ByteArray): RawTransceiveResult {
        return transceive(command)
    }
}