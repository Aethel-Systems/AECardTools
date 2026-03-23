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

package com.aethel.aecardtools.data.repository

import android.content.Context
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import com.aethel.aecardtools.data.AEFSSovereignKeyVault
import com.aethel.aecardtools.data.KeyVaultManager
import com.aethel.aecardtools.data.NfcRuntimeContext
import com.aethel.aecardtools.data.WriteControlPolicy
import com.aethel.aecardtools.data.model.*
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BlockWriteRequest(
    val sectorIdx: Int,
    val blockIdx: Int,
    val dataHex: String
)

/**
 * 卡片数据仓库 - 负责所有数据操作和 Python FFI 调用
 */
class CardRepository(private val context: Context) {
    
    private val python: Python = Python.getInstance()
    private var ffiBridge: PyObject? = null
    
    init {
        try {
            ffiBridge = python.getModule("ffi_bridge")
            Timber.i("FFI Bridge 已初始化")
        } catch (e: Exception) {
            Timber.e(e, "FFI Bridge 初始化失败")
        }
    }
    
    /**
     * 检查 FFI Bridge 是否就绪
     */
    fun isFFIReady(): Boolean = ffiBridge != null

    fun getContext(): Context = context
    
    /**
     * 当卡片被检测到时调用
     */
    suspend fun onCardDetected(uid: String, sak: String, atqa: String): Result<CardIdentity> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("on_card_detected", uid, sak, atqa)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val cardType = when (json.optString("card_type", "UNKNOWN")) {
                        "Classic 1K (16 sectors)" -> CardType.CLASSIC_1K
                        "Classic 4K (40 sectors)" -> CardType.CLASSIC_4K
                        "MIFARE Ultralight" -> CardType.ULTRALIGHT
                        "MIFARE Ultralight C" -> CardType.ULTRALIGHT_C
                        "NTAG" -> CardType.NTAG
                        else -> CardType.UNKNOWN
                    }
                    
                    val cardIdentity = CardIdentity(
                        uid = uid,
                        sak = sak,
                        atqa = atqa,
                        cardType = cardType,
                        sectorCount = json.optInt("sector_count", 16)
                    )
                    
                    Timber.i("卡片检测成功: $uid")
                    // 静默注入：如果是 AEFS 卡，尝试根据 S0:B1 的 AliasHash 自动导入密钥包并解锁全盘
                    runCatching {
                        withContext(Dispatchers.IO) {
                            silentInjectSovereignKeysIfPossible()
                        }
                    }.onFailure { Timber.w(it, "SovereignKeyVault 静默注入失败") }
                    Result.success(cardIdentity)
                } else {
                    Result.failure(Exception(json.optString("error", "未知错误")))
                }
            } catch (e: Exception) {
                Timber.e(e, "卡片检测失败")
                Result.failure(e)
            }
        }
    }

    private suspend fun silentInjectSovereignKeysIfPossible() {
        val headerBytes = readClassicBlockBytes(0, 1) ?: return
        val headerHex = headerBytes.joinToString("") { "%02X".format(it) }
        if (!headerHex.startsWith("41454653")) return

        val parsed = runCatching {
            val result = ffiBridge!!.callAttr("parse_aefs_s0b1_header", headerHex)
            JSONObject(result?.toString() ?: "{}")
        }.getOrNull() ?: return

        if (!parsed.optBoolean("success", false)) return
        if (!parsed.optBoolean("is_aefs", false)) return
        val physicalUid = NfcRuntimeContext.getCurrentTag()?.id
            ?.joinToString("") { "%02X".format(it) }
            ?.uppercase()
            ?: return

        val aliasHash = parsed.optString("alias_hash", "").uppercase()
        val pkg = AEFSSovereignKeyVault.getPackageByCardUid(context, physicalUid)
            ?: AEFSSovereignKeyVault.getPackageByAliasHash(context, aliasHash)
            ?: return
        val keyHexes = pkg.keys.values.flatMap { listOf(it.first, it.second) }
        val added = KeyVaultManager.addImportedKeys(context, keyHexes)
        Timber.i(
            "SovereignKeyVault 静默注入完成: card_uid=$physicalUid, aliasHash=$aliasHash, " +
                "sectors=${pkg.keys.size}, keys_added=$added"
        )
    }

    private suspend fun readClassicBlockBytes(sectorIdx: Int, blockIdx: Int): ByteArray? {
        val tag = NfcRuntimeContext.getCurrentTag() ?: return null
        val mifare = MifareClassic.get(tag) ?: return null
        try {
            if (!mifare.isConnected) mifare.connect()

            val blockCountInSector = mifare.getBlockCountInSector(sectorIdx)
            if (blockIdx !in 0 until blockCountInSector) return null

            val auth = authenticateSectorForRead(mifare, sectorIdx)
            if (!auth.first) return null

            val absoluteBlockIndex = mifare.sectorToBlock(sectorIdx) + blockIdx
            return mifare.readBlock(absoluteBlockIndex)
        } catch (e: Exception) {
            Timber.w(e, "读块失败: S$sectorIdx:B$blockIdx")
            return null
        } finally {
            runCatching { if (mifare.isConnected) mifare.close() }
        }
    }

    private fun authenticateSectorForRead(mifare: MifareClassic, sectorIdx: Int): Pair<Boolean, Int> {
        val keyCandidates = KeyVaultManager.getAllAuthKeys(context)
        var attempts = 0
        for (key in keyCandidates) {
            attempts++
            val okA = try {
                mifare.authenticateSectorWithKeyA(sectorIdx, key)
            } catch (_: Exception) {
                false
            }
            if (okA) return Pair(true, attempts)

            val okB = try {
                mifare.authenticateSectorWithKeyB(sectorIdx, key)
            } catch (_: Exception) {
                false
            }
            if (okB) return Pair(true, attempts)
        }
        return Pair(false, attempts)
    }

    suspend fun readUltralightPage(pageIdx: Int): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                if (pageIdx < 0 || pageIdx > 255) {
                    return@withContext Result.failure(Exception("页号无效: $pageIdx"))
                }

                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))

                val ultralightResult = runCatching {
                    val ultralight = MifareUltralight.get(tag)
                        ?: error("当前卡片不支持 MIFARE Ultralight 页读取")
                    try {
                        if (!ultralight.isConnected) {
                            ultralight.connect()
                        }
                        ultralight.readPages(pageIdx)
                    } finally {
                        runCatching {
                            if (ultralight.isConnected) ultralight.close()
                        }.onFailure { Timber.w(it, "关闭 MifareUltralight 连接失败") }
                    }
                }

                val data = ultralightResult.getOrElse { ultralightError ->
                    Timber.w(ultralightError, "MifareUltralight 读取失败，回退到 NfcA READ 命令: page=$pageIdx")
                    val nfcA = NfcA.get(tag)
                        ?: return@withContext Result.failure(Exception("当前卡片不支持 Ultralight/NfcA 页读取"))
                    try {
                        if (!nfcA.isConnected) {
                            nfcA.connect()
                        }
                        nfcA.transceive(byteArrayOf(0x30.toByte(), pageIdx.toByte()))
                    } finally {
                        runCatching {
                            if (nfcA.isConnected) nfcA.close()
                        }.onFailure { Timber.w(it, "关闭 NfcA 连接失败") }
                    }
                }

                if (data.size < 4) {
                    return@withContext Result.failure(Exception("读取失败: 响应长度异常(${data.size})"))
                }
                Timber.i("Ultralight 读取成功: Page=$pageIdx bytes=${data.size}")
                Result.success(data)
            } catch (e: Exception) {
                Timber.e(e, "Ultralight 页读取异常")
                Result.failure(e)
            }
        }
    }

    suspend fun writeUltralightPage(pageIdx: Int, dataHex: String): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                if (pageIdx < 0 || pageIdx > 255) {
                    return@withContext Result.failure(Exception("页号无效: $pageIdx"))
                }

                val cleaned = dataHex.trim().replace(" ", "").uppercase()
                if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
                    return@withContext Result.failure(Exception("数据包含非法的16进制字符"))
                }
                if (cleaned.length != 8) {
                    return@withContext Result.failure(Exception("Ultralight 页写入必须为 8 个十六进制字符（4字节）"))
                }
                val payload = cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))

                val ultralight = MifareUltralight.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 MIFARE Ultralight 页写入"))

                val startedAt = System.currentTimeMillis()
                if (!ultralight.isConnected) {
                    ultralight.connect()
                }
                ultralight.writePage(pageIdx, payload)
                val readBack = ultralight.readPages(pageIdx).copyOfRange(0, 4)
                val verified = readBack.contentEquals(payload)
                if (!verified) {
                    return@withContext Result.failure(Exception("写后校验失败：读回数据与写入数据不一致"))
                }
                Result.success(
                    mapOf(
                        "success" to true,
                        "page" to pageIdx,
                        "bytes_written" to 4,
                        "verified" to true,
                        "duration_ms" to (System.currentTimeMillis() - startedAt)
                    )
                )
                // 【修复】：删除了 finally { ultralight.close() }
            } catch (e: Exception) {
                Timber.e(e, "Ultralight 页写入异常")
                Result.failure(e)
            }
        }
    }

    suspend fun transceiveUltralightCommand(commandHex: String): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val command = commandHex.cleanHexToBytes()
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))
                
                // 【修复】优先复用已经可能保持连接的 MifareUltralight
                val ultralight = MifareUltralight.get(tag)
                if (ultralight != null) {
                    if (!ultralight.isConnected) ultralight.connect()
                    val response = ultralight.transceive(command)
                    return@withContext Result.success(response)
                }

                val nfcA = NfcA.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 NfcA 原始收发"))
                if (!nfcA.isConnected) {
                    nfcA.connect()
                }
                val response = nfcA.transceive(command)
                Result.success(response)
                // 【修复】：删除了 finally { nfcA.close() }
            } catch (e: Exception) {
                Timber.e(e, "Ultralight 原始命令执行异常")
                Result.failure(e)
            }
        }
    }

    suspend fun fastReadUltralight(startPage: Int, endPage: Int): Result<ByteArray> {
        return transceiveUltralightCommand(
            "3A%02X%02X".format(startPage and 0xFF, endPage and 0xFF)
        )
    }

    suspend fun getUltralightVersion(): Result<ByteArray> {
        return transceiveUltralightCommand("60")
    }

    suspend fun readUltralightSignature(): Result<ByteArray> {
        return transceiveUltralightCommand("3C00")
    }

    suspend fun readUltralightCounter(counterIndex: Int): Result<ByteArray> {
        return transceiveUltralightCommand("39%02X".format(counterIndex and 0xFF))
    }

    suspend fun incrementUltralightCounter(counterIndex: Int, increment: Int): Result<ByteArray> {
        require(increment in 0..0xFFFFFF) { "计数器增量必须在 0..16777215 之间" }
        return transceiveUltralightCommand(
            "A5%02X%02X%02X%02X".format(
                counterIndex and 0xFF,
                increment and 0xFF,
                (increment shr 8) and 0xFF,
                (increment shr 16) and 0xFF
            )
        )
    }

    suspend fun authenticateUltralightPassword(passwordHex: String): Result<ByteArray> {
        val password = passwordHex.trim().replace(" ", "").uppercase()
        require(password.length == 8) { "密码必须为 8 个十六进制字符" }
        return transceiveUltralightCommand("1B$password")
    }

    suspend fun authenticateUltralightCStep1(): Result<ByteArray> {
        return transceiveUltralightCommand("1A00")
    }

    suspend fun authenticateUltralightCStep2(encryptedResponseHex: String): Result<ByteArray> {
        val payload = encryptedResponseHex.trim().replace(" ", "").uppercase()
        require(payload.length == 16) { "第二步认证数据必须为 16 个十六进制字符" }
        return transceiveUltralightCommand("AF$payload")
    }

    suspend fun compatibilityWriteUltralight(pageIdx: Int, dataHex: String): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val payload = dataHex.cleanHexToBytes(expectedBytes = 16)
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))
                
                // 【修复】优先复用 MifareUltralight
                val ultralight = MifareUltralight.get(tag)
                if (ultralight != null) {
                    if (!ultralight.isConnected) ultralight.connect()
                    val commandAck = ultralight.transceive(byteArrayOf(0xA0.toByte(), pageIdx.toByte()))
                    val dataAck = ultralight.transceive(payload)
                    return@withContext Result.success(
                        mapOf(
                            "command_ack" to commandAck.toHexString(),
                            "data_ack" to dataAck.toHexString(),
                            "page" to pageIdx,
                            "bytes_written" to payload.size
                        )
                    )
                }

                val nfcA = NfcA.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 NfcA 原始收发"))

                if (!nfcA.isConnected) {
                    nfcA.connect()
                }
                val commandAck = nfcA.transceive(byteArrayOf(0xA0.toByte(), pageIdx.toByte()))
                val dataAck = nfcA.transceive(payload)
                Result.success(
                    mapOf(
                        "command_ack" to commandAck.toHexString(),
                        "data_ack" to dataAck.toHexString(),
                        "page" to pageIdx,
                        "bytes_written" to payload.size
                    )
                )
                // 【修复】：删除了 finally { nfcA.close() }
            } catch (e: Exception) {
                Timber.e(e, "Ultralight 兼容写异常")
                Result.failure(e)
            }
        }
    }

    private fun String.cleanHexToBytes(expectedBytes: Int? = null): ByteArray {
        val cleaned = trim().replace(" ", "").uppercase()
        require(cleaned.isNotBlank()) { "命令不能为空" }
        require(cleaned.length % 2 == 0) { "十六进制长度必须为偶数" }
        require(cleaned.all { it in '0'..'9' || it in 'A'..'F' }) { "存在非法十六进制字符" }
        val bytes = cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (expectedBytes != null) {
            require(bytes.size == expectedBytes) { "数据长度必须为 $expectedBytes 字节" }
        }
        return bytes
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    suspend fun writeBlocksBatch(
        requests: List<BlockWriteRequest>,
        overrideAcknowledgement: String = "",
        isAEFSCard: Boolean = false
    ): Result<List<Map<String, Any>>> {
        return withContext(Dispatchers.IO) {
            try {
                if (requests.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))
                val mifare = MifareClassic.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 MIFARE Classic 写入"))

                val normalizedRequests = requests.map { request ->
                    val policy = WriteControlPolicy.canWriteSector(request.sectorIdx, overrideAcknowledgement)
                    if (!policy.first) {
                        throw Exception(policy.second)
                    }
                    if (request.sectorIdx == 0 && request.blockIdx == 0) {
                        val overrideOk = overrideAcknowledgement.trim() == WriteControlPolicy.OVERRIDE_PHRASE
                        if (!(overrideOk && WriteControlPolicy.isFormattingSession())) {
                            throw Exception("已拦截危险写入：S0:B0（制造商块）默认永不写入。仅允许在格式化流程中输入确认短语后越权。")
                        }
                    }
                    if (isAEFSCard && !WriteControlPolicy.isFormattingSession()) {
                        throw Exception("检测到 AEFS 卡片：已阻止常规物理写入。请改用 AEFS 高级头信息解释器（注册表/LCOW 事务）进行修改。")
                    }
                    val cleanedData = request.dataHex.trim().replace(" ", "").uppercase()
                    if (cleanedData.length != 32 || cleanedData.any { it !in '0'..'9' && it !in 'A'..'F' }) {
                        throw Exception("S${request.sectorIdx}:B${request.blockIdx} 数据长度或格式无效")
                    }
                    request.copy(dataHex = cleanedData)
                }

                val results = mutableListOf<Map<String, Any>>()
                var currentAuthenticatedSector: Int? = null
                try {
                    if (!mifare.isConnected) {
                        mifare.connect()
                    }

                    normalizedRequests.forEach { request ->
                        val start = System.currentTimeMillis()
                        val blockCountInSector = mifare.getBlockCountInSector(request.sectorIdx)
                        if (request.blockIdx !in 0 until blockCountInSector) {
                            throw Exception("无效块号: S${request.sectorIdx} 仅有 $blockCountInSector 个块")
                        }

                        if (currentAuthenticatedSector != request.sectorIdx) {
                            val auth = authenticateSectorForWrite(mifare, request.sectorIdx)
                            if (!auth.first) {
                                throw Exception("扇区认证失败: S${request.sectorIdx}（已尝试 ${auth.second} 个密钥）")
                            }
                            currentAuthenticatedSector = request.sectorIdx
                        }

                        val dataBytes = request.dataHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val absoluteBlockIndex = mifare.sectorToBlock(request.sectorIdx) + request.blockIdx
                        val isTrailerBlock = request.blockIdx == blockCountInSector - 1
                        val payloadToWrite = if (isTrailerBlock) {
                            mergeTrailerControlBits(
                                existingBlock = mifare.readBlock(absoluteBlockIndex),
                                requestedBlock = dataBytes
                            )
                        } else {
                            dataBytes
                        }

                        mifare.writeBlock(absoluteBlockIndex, payloadToWrite)
                        val readBack = mifare.readBlock(absoluteBlockIndex)
                        val verified = if (isTrailerBlock) {
                            verifyTrailerWrite(
                                mifare = mifare,
                                sectorIdx = request.sectorIdx,
                                expectedTrailer = payloadToWrite,
                                readBack = readBack
                            )
                        } else {
                            readBack.contentEquals(payloadToWrite)
                        }
                        if (!verified) {
                            throw Exception("写后校验失败：S${request.sectorIdx}:B${request.blockIdx}")
                        }

                        results += mapOf(
                            "success" to true,
                            "sector" to request.sectorIdx,
                            "block" to request.blockIdx,
                            "bytes_written" to payloadToWrite.size,
                            "duration_ms" to (System.currentTimeMillis() - start),
                            "status_word" to "9000",
                            "verified" to true,
                            "control_bits_preserved" to isTrailerBlock,
                            "verification_mode" to if (isTrailerBlock) "trailer_reauth" else "byte_compare",
                            "timestamp" to System.currentTimeMillis()
                        )
                    }
                    Result.success(results)
                } finally {
                    runCatching {
                        if (mifare.isConnected) {
                            mifare.close()
                        }
                    }.onFailure { Timber.w(it, "关闭 MifareClassic 连接失败") }
                }
            } catch (e: Exception) {
                Timber.e(e, "批量块写入异常")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 更新扇区数据
     */
    suspend fun updateSectorData(
        sectorIdx: Int,
        blocks: List<String>,
        keyA: String,
        keyB: String,
        authStatus: AuthStatus
    ): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                val blocksJson = "[${blocks.joinToString(",") { "\"$it\"" }}]"
                val statusStr = authStatus.name
                
                val result = ffiBridge!!.callAttr(
                    "update_sector_data",
                    sectorIdx,
                    blocksJson,
                    keyA,
                    keyB,
                    statusStr
                )
                
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    Timber.i("扇区 $sectorIdx 已更新")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(json.optString("error", "更新失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "扇区更新失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 写入单个块 - 工业级真实实现（直接执行硬件写操作）
     */
	    suspend fun writeBlock(
	        sectorIdx: Int,
	        blockIdx: Int,
	        dataHex: String,
	        overrideAcknowledgement: String = "",
	        isAEFSCard: Boolean = false
	    ): Result<Map<String, Any>> {
	        return withContext(Dispatchers.IO) {
	            try {
	                // 永久保护：S0:B0（制造商块）默认永不写入，避免“砖卡”风险。
	                // 仅允许在格式化会话中，且用户输入精确确认短语后越权写入。
	                if (sectorIdx == 0 && blockIdx == 0) {
	                    val overrideOk = overrideAcknowledgement.trim() == WriteControlPolicy.OVERRIDE_PHRASE
	                    if (!(overrideOk && WriteControlPolicy.isFormattingSession())) {
	                        return@withContext Result.failure(
	                            Exception("已拦截危险写入：S0:B0（制造商块）默认永不写入。仅允许在格式化流程中输入确认短语后越权。")
	                        )
	                    }
	                }

	                val writePolicy = WriteControlPolicy.canWriteSector(sectorIdx, overrideAcknowledgement)
	                if (!writePolicy.first) {
	                    return@withContext Result.failure(Exception(writePolicy.second))
	                }

                if (isAEFSCard && !WriteControlPolicy.isFormattingSession()) {
                    return@withContext Result.failure(
                        Exception("检测到 AEFS 卡片：已阻止常规物理写入。请改用 AEFS 高级头信息解释器（注册表/LCOW 事务）进行修改。")
                    )
                }

                val cleanedData = dataHex.trim().replace(" ", "").uppercase()
                if (cleanedData.isEmpty()) {
                    return@withContext Result.failure(Exception("数据不能为空"))
                }
                if (!cleanedData.all { it in '0'..'9' || it in 'A'..'F' }) {
                    return@withContext Result.failure(Exception("数据包含非法的16进制字符"))
                }
                if (cleanedData.length != 32) {
                    return@withContext Result.failure(Exception("数据长度必须为 32 个十六进制字符（16字节）"))
                }

                    val dataBytes = cleanedData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val tag = NfcRuntimeContext.getCurrentTag()
                        ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描卡片"))

                val mifare = MifareClassic.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 MIFARE Classic 写入"))

                val start = System.currentTimeMillis()
                try {
                    if (!mifare.isConnected) {
                        mifare.connect()
                    }

                    val blockCountInSector = mifare.getBlockCountInSector(sectorIdx)
                    if (blockIdx !in 0 until blockCountInSector) {
                        return@withContext Result.failure(Exception("无效块号: S$sectorIdx 仅有 $blockCountInSector 个块"))
                    }

                    val auth = authenticateSectorForWrite(mifare, sectorIdx)
                    if (!auth.first) {
                        return@withContext Result.failure(
                            Exception("扇区认证失败: S$sectorIdx（已尝试 ${auth.second} 个密钥）")
                        )
                    }

                    val absoluteBlockIndex = mifare.sectorToBlock(sectorIdx) + blockIdx
                    val isTrailerBlock = blockIdx == blockCountInSector - 1
                    val payloadToWrite = if (isTrailerBlock) {
                        mergeTrailerControlBits(
                            existingBlock = mifare.readBlock(absoluteBlockIndex),
                            requestedBlock = dataBytes
                        )
                    } else {
                        dataBytes
                    }

                    mifare.writeBlock(absoluteBlockIndex, payloadToWrite)

                    val readBack = mifare.readBlock(absoluteBlockIndex)
                    val verified = if (isTrailerBlock) {
                        verifyTrailerWrite(
                            mifare = mifare,
                            sectorIdx = sectorIdx,
                            expectedTrailer = payloadToWrite,
                            readBack = readBack
                        )
                    } else {
                        readBack.contentEquals(payloadToWrite)
                    }
                    if (!verified) {
                        return@withContext Result.failure(Exception("写后校验失败：读回数据与写入数据不一致"))
                    }

                    val resultMap = mutableMapOf<String, Any>()
                    resultMap["success"] = true
                    resultMap["sector"] = sectorIdx
                    resultMap["block"] = blockIdx
                    resultMap["bytes_written"] = payloadToWrite.size
                    resultMap["duration_ms"] = System.currentTimeMillis() - start
                    resultMap["status_word"] = "9000"
                    resultMap["verified"] = true
                    resultMap["control_bits_preserved"] = isTrailerBlock
                    resultMap["verification_mode"] = if (isTrailerBlock) "trailer_reauth" else "byte_compare"
                    resultMap["timestamp"] = System.currentTimeMillis()

                    Timber.i("块写入成功(硬件直写): Sector=$sectorIdx Block=$blockIdx")
                    Result.success(resultMap)
                } finally {
                    try {
                        if (mifare.isConnected) {
                            mifare.close()
                        }
                    } catch (closeEx: Exception) {
                        Timber.w(closeEx, "关闭 MifareClassic 连接失败")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "块写入操作异常")
                Result.failure(e)
            }
        }
    }

    private fun authenticateSectorForWrite(mifare: MifareClassic, sectorIdx: Int): Pair<Boolean, Int> {
        val keyCandidates = KeyVaultManager.getAllAuthKeys(context)
        var attempts = 0
        for (key in keyCandidates) {
            attempts++
            val okA = try {
                mifare.authenticateSectorWithKeyA(sectorIdx, key)
            } catch (_: Exception) {
                false
            }
            if (okA) return Pair(true, attempts)

            val okB = try {
                mifare.authenticateSectorWithKeyB(sectorIdx, key)
            } catch (_: Exception) {
                false
            }
            if (okB) return Pair(true, attempts)
        }
        return Pair(false, attempts)
    }

    private fun mergeTrailerControlBits(
        existingBlock: ByteArray,
        requestedBlock: ByteArray
    ): ByteArray {
        if (existingBlock.size != 16 || requestedBlock.size != 16) {
            return requestedBlock
        }
        return requestedBlock.copyOf().also { merged ->
            for (offset in 6..9) {
                merged[offset] = existingBlock[offset]
            }
        }
    }

    private fun verifyTrailerWrite(
        mifare: MifareClassic,
        sectorIdx: Int,
        expectedTrailer: ByteArray,
        readBack: ByteArray
    ): Boolean {
        if (readBack.contentEquals(expectedTrailer)) {
            return true
        }
        if (readBack.size != 16 || expectedTrailer.size != 16) {
            Timber.w(
                "Trailer 写后校验失败: S%s readSize=%s expectedSize=%s",
                sectorIdx,
                readBack.size,
                expectedTrailer.size
            )
            return false
        }

        val expectedControl = expectedTrailer.copyOfRange(6, 10)
        val actualControl = readBack.copyOfRange(6, 10)
        if (!expectedControl.contentEquals(actualControl)) {
            Timber.w(
                "Trailer 控制位不一致: S%s expected=%s actual=%s",
                sectorIdx,
                expectedControl.toHexString(),
                actualControl.toHexString()
            )
            return false
        }

        val expectedKeyA = expectedTrailer.copyOfRange(0, 6)
        val expectedKeyB = expectedTrailer.copyOfRange(10, 16)

        runCatching {
            if (mifare.isConnected) {
                mifare.close()
            }
            mifare.connect()
        }.onFailure {
            Timber.w(it, "Trailer 校验前重新连接失败: S%s", sectorIdx)
            return false
        }

        val authWithExpectedKeys = tryAuthenticate(mifare, sectorIdx, expectedKeyA, expectedKeyB)
        if (!authWithExpectedKeys) {
            Timber.w(
                "Trailer 重认证失败: S%s readBack=%s expected=%s",
                sectorIdx,
                readBack.toHexString(),
                expectedTrailer.toHexString()
            )
            return false
        }

        Timber.i(
            "Trailer 读回与写入不完全一致，但控制位一致且新密钥认证成功: S%s readBack=%s expected=%s",
            sectorIdx,
            readBack.toHexString(),
            expectedTrailer.toHexString()
        )
        return true
    }

    private fun tryAuthenticate(
        mifare: MifareClassic,
        sectorIdx: Int,
        keyA: ByteArray,
        keyB: ByteArray
    ): Boolean {
        val okA = try {
            mifare.authenticateSectorWithKeyA(sectorIdx, keyA)
        } catch (_: Exception) {
            false
        }
        if (okA) {
            return true
        }

        return try {
            mifare.authenticateSectorWithKeyB(sectorIdx, keyB)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 构建 AEFS 镜像
     */
    suspend fun buildAEFSImage(
        alias: String,
        passphrase: String,
        fileDataHex: String,
        sipLevel: SIPLevel = SIPLevel.ARCHITECT
    ): Result<AEFSImageData> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr(
                    "build_aefs_image",
                    alias,
                    passphrase,
                    fileDataHex,
                    sipLevel.name
                )
                
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val imageData = AEFSImageData(
                        poolId = json.optString("pool_id", ""),
                        volId = json.optString("vol_id", ""),
                        alias = alias,
                        sipLevel = sipLevel,
                        encryptedPayload = json.optString("payload", ""),
                        merkleRoot = json.optString("merkle_root", ""),
                        mac = json.optString("mac", ""),
                        streamSize = json.optInt("stream_size", 0)
                    )
                    
                    Timber.i("AEFS 镜像构建成功: $alias")
                    Result.success(imageData)
                } else {
                    Result.failure(Exception(json.optString("error", "构建失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "AEFS 镜像构建失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 解密 AEFS 镜像
     */
    suspend fun decryptAEFSImage(
        payloadHex: String,
        passphrase: String
    ): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr(
                    "decrypt_aefs_image",
                    payloadHex,
                    passphrase
                )
                
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val plaintext = json.optString("plaintext", "")
                    Timber.i("AEFS 镜像解密成功")
                    Result.success(plaintext)
                } else {
                    Result.failure(Exception(json.optString("error", "解密失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "AEFS 镜像解密失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 导出卡片数据
     */
    suspend fun exportCardData(format: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("export_card_data", format)
                val content = result?.toString() ?: ""
                
                Timber.i("卡片数据已导出 ($format)")
                Result.success(content)
            } catch (e: Exception) {
                Timber.e(e, "导出失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 导入密钥文件
     */
    suspend fun importKeysFile(content: String): Result<Map<Int, Pair<String, String>>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("import_keys_file", content)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val sectorsObj = json.optJSONObject("sectors") ?: JSONObject()
                    val keys = mutableMapOf<Int, Pair<String, String>>()
                    
                    for (key in sectorsObj.keys()) {
                        val sector = key.toIntOrNull() ?: continue
                        val sectorObj = sectorsObj.optJSONObject(key)
                        val keyA = sectorObj?.optString("key_a", "") ?: ""
                        val keyB = sectorObj?.optString("key_b", "") ?: ""
                        keys[sector] = Pair(keyA, keyB)
                    }
                    
                    Timber.i("已导入 ${keys.size} 个扇区的密钥")
                    Result.success(keys)
                } else {
                    Result.failure(Exception(json.optString("error", "导入失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "密钥导入失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 导出密钥文件
     */
    suspend fun exportKeysFile(): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("export_keys_file")
                val json = JSONObject(result?.toString() ?: "{}")
                if (json.optBoolean("success", false)) {
                    val content = json.optString("content", "")
                    Timber.i("密钥文件已导出")
                    Result.success(content)
                } else {
                    Result.failure(Exception(json.optString("error", "密钥导出失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "密钥导出失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 计算 BCC
     */
    suspend fun calculateBCC(uidHex: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("calculate_bcc", uidHex)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                val bcc = json.optString("bcc", "00")
                Timber.i("BCC 已计算: $bcc")
                Result.success(bcc)
            } catch (e: Exception) {
                Timber.e(e, "BCC 计算失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 解析访问条件
     */
    suspend fun parseAccessConditions(trailerHex: String): Result<Map<String, Any>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("parse_access_conditions", trailerHex)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                val map = mutableMapOf<String, Any>()
                map["key_a"] = json.optString("key_a", "")
                map["key_b"] = json.optString("key_b", "")
                map["access_bits"] = json.optString("access_bits_raw", "")
                
                Timber.i("访问条件已解析")
                Result.success(map)
            } catch (e: Exception) {
                Timber.e(e, "访问条件解析失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取操作日志摘要
     */
    suspend fun getOperationLogSummary(): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("get_operation_log_summary")
                val jsonStr = result?.toString() ?: "{}"
                
                Timber.i("操作日志已获取")
                Result.success(jsonStr)
            } catch (e: Exception) {
                Timber.e(e, "操作日志获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 保存快照
     */
    suspend fun saveSnapshot(filename: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("save_snapshot_to_file", filename)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val file = json.optString("file", "")
                    Timber.i("快照已保存: $file")
                    Result.success(file)
                } else {
                    Result.failure(Exception(json.optString("error", "保存失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "快照保存失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取审计摘要（真实实现）
     */
    suspend fun getAuditSummary(): Result<Map<String, Any>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("get_audit_summary")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val summaryMap = mutableMapOf<String, Any>()
                    
                    // 提取审计数据
                    summaryMap["total_operations"] = json.optInt("total_operations", 0)
                    summaryMap["session_id"] = json.optString("session_id", "")
                    summaryMap["success_count"] = json.optInt("success_count", 0)
                    summaryMap["failed_count"] = json.optInt("failed_count", 0)
                    summaryMap["start_time"] = json.optString("start_time", "")
                    summaryMap["end_time"] = json.optString("end_time", "")
                    
                    val operationTypes = json.optJSONObject("operation_types")
                    if (operationTypes != null) {
                        for (key in operationTypes.keys()) {
                            summaryMap["op_$key"] = operationTypes.optInt(key, 0)
                        }
                    }
                    
                    Timber.i("审计摘要已获取: ${summaryMap["total_operations"]} 个操作")
                    Result.success(summaryMap)
                } else {
                    Result.failure(Exception(json.optString("error", "获取失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "审计摘要获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 写入操作验证与预检 - 工业级实现
     * 执行完整的数据完整性验证、风险评估和冲突检测
     */
    suspend fun simulateWriteOperation(
        sectorIdx: Int,
        blockIdx: Int,
        data: String
    ): Result<Map<String, Any>> {
        return withContext(Dispatchers.Default) {
            try {
                // 调用Python后端进行完整的预检验证
                val result = ffiBridge!!.callAttr(
                    "validate_write_operation",
                    sectorIdx,
                    blockIdx,
                    data
                )
                
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val resultMap = mutableMapOf<String, Any>()
                    resultMap["success"] = true
                    resultMap["risk_level"] = json.optString("risk_level", "LOW")
                    resultMap["bytes_changed"] = json.optInt("bytes_changed", 0)
                    resultMap["verification_required"] = json.optBoolean("verification_required", false)
                    resultMap["will_overwrite"] = json.optBoolean("will_overwrite", false)
                    
                    // 添加完整的验证信息
                    resultMap["crc_check"] = json.optBoolean("crc_check", true)
                    resultMap["bcc_valid"] = json.optBoolean("bcc_valid", true)
                    resultMap["access_check"] = json.optBoolean("access_check", true)
                    resultMap["conflict_detected"] = json.optBoolean("conflict_detected", false)
                    
                    // 获取详细的验证说明
                    if (json.has("warning")) {
                        resultMap["warning"] = json.optString("warning", "")
                    }
                    if (json.has("suggestion")) {
                        resultMap["suggestion"] = json.optString("suggestion", "")
                    }
                    
                    Timber.i("写入操作验证完成 S$sectorIdx:B$blockIdx - Risk=${resultMap["risk_level"]}")
                    Result.success(resultMap)
                } else {
                    Result.failure(Exception(json.optString("error", "验证失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "写入操作验证异常")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取缓存统计（真实实现）
     */
    suspend fun getCacheStats(): Result<Map<String, Any>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("get_cache_statistics")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val statsMap = mutableMapOf<String, Any>()
                    
                    statsMap["cache_hits"] = json.optInt("cache_hits", 0)
                    statsMap["cache_misses"] = json.optInt("cache_misses", 0)
                    statsMap["total_requests"] = json.optInt("total_requests", 0)
                    statsMap["hit_rate"] = json.optDouble("hit_rate", 0.0)
                    statsMap["cache_size_bytes"] = json.optInt("cache_size_bytes", 0)
                    statsMap["max_cache_bytes"] = json.optInt("max_cache_bytes", 0)
                    statsMap["eviction_count"] = json.optInt("eviction_count", 0)
                    
                    Timber.i("缓存统计: 命中率=${statsMap["hit_rate"]}")
                    Result.success(statsMap)
                } else {
                    Result.failure(Exception(json.optString("error", "获取失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "缓存统计获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 导出差异报告（真实实现）
     */
    suspend fun exportDifferenceReport(format: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("export_difference_report", format)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val reportContent = json.optString("report", "")
                    
                    // 支持多种格式：JSON、CSV、PDF、HTML
                    when (format.lowercase()) {
                        "json" -> {
                            Timber.i("差异报告已生成 (JSON 格式)")
                        }
                        "csv" -> {
                            Timber.i("差异报告已生成 (CSV 格式)")
                        }
                        "html" -> {
                            Timber.i("差异报告已生成 (HTML 格式)")
                        }
                        else -> {
                            Timber.i("差异报告已生成 ($format 格式)")
                        }
                    }
                    
                    Result.success(reportContent)
                } else {
                    Result.failure(Exception(json.optString("error", "生成失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "差异报告导出失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 执行原始命令（真实 APDU 执行）
     */
    suspend fun executeRawCommand(command: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("execute_raw_apdu_command", command)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val response = json.optString("response", "")
                    val statusWord = json.optString("status_word", "")
                    val executionTime = json.optLong("execution_time_ms", 0)
                    
                    Timber.i("APDU 命令执行成功 (${executionTime}ms): $command → $statusWord")
                    Result.success(response)
                } else {
                    Result.failure(Exception(json.optString("error", "执行失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "APDU 命令执行失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取卡片仪表盘（真实实现）
     */
    suspend fun getCardDashboard(): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("get_card_dashboard")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val dashboard = json.optJSONObject("dashboard")
                    
                    // 提取仪表盘数据
                    val dashboardData = mutableMapOf<String, Any>()
                    if (dashboard != null) {
                        for (key in dashboard.keys()) {
                            dashboardData[key] = dashboard.get(key)
                        }
                    }
                    
                    // 格式化为可读的文本
                    val formattedDashboard = json.optString("formatted", "")
                    
                    Timber.i("卡片仪表盘已获取")
                    Result.success(formattedDashboard)
                } else {
                    Result.failure(Exception(json.optString("error", "获取失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "卡片仪表盘获取失败")
                Result.failure(e)
            }
        }
    }
    
    suspend fun exportFullReport(format: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val mappedFormat = when (format.lowercase()) {
                    "md", "markdown" -> "md"
                    "json" -> "json"
                    "mct" -> "mct"
                    else -> "md"
                }
                val result = ffiBridge!!.callAttr("export_card_data", mappedFormat)
                val json = JSONObject(result?.toString() ?: "{}")
                if (json.optBoolean("success", false)) {
                    Result.success(json.optString("content", json.toString()))
                } else {
                    Result.failure(Exception(json.optString("error", "完整报告导出失败")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    suspend fun exportLogs(format: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge!!.callAttr("export_logs", format)
                val json = JSONObject(result?.toString() ?: "{}")
                if (json.optBoolean("success", false)) {
                    Result.success(json.optString("content", ""))
                } else {
                    Result.failure(Exception(json.optString("error", "日志导出失败")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
