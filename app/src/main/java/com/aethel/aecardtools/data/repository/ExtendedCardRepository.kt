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

import com.aethel.aecardtools.data.AEFSManagedKeyPackage
import com.aethel.aecardtools.data.AEFSSovereignIdentity
import com.aethel.aecardtools.data.AEFSSovereignKeyVault
import com.aethel.aecardtools.data.KeyVaultManager
import com.aethel.aecardtools.data.NfcRuntimeContext
import com.aethel.aecardtools.data.WriteControlPolicy
import com.aethel.aecardtools.data.model.*
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.nfc.tech.MifareClassic
import org.json.JSONObject
import org.json.JSONArray
import timber.log.Timber
import java.security.MessageDigest

/**
 * 扩展的 CardRepository - 集成 6 大功能
 */
class ExtendedCardRepository(private val baseRepository: CardRepository) {
    
    private val python: Python = Python.getInstance()
    private var ffiBridge: PyObject? = null
    private var lcowEngine: PyObject? = null
    private var cryptoModule: PyObject? = null
    private var nfcOps: PyObject? = null
    private var dataManager: PyObject? = null
    private var moduleInitError: Exception? = null
    
    init {
        try {
            ffiBridge = python.getModule("ffi_bridge")
            lcowEngine = python.getModule("lcow_engine")
            cryptoModule = python.getModule("crypto_module")
            nfcOps = python.getModule("nfc_operations")
            dataManager = python.getModule("data_manager")
            Timber.i("ExtendedCardRepository 已初始化所有模块")
        } catch (e: Exception) {
            moduleInitError = e
            Timber.e(e, "ExtendedCardRepository 初始化失败")
        }
    }

    private fun requireFfiBridge(): PyObject {
        ffiBridge?.let { return it }
        try {
            ffiBridge = python.getModule("ffi_bridge")
            return ffiBridge!!
        } catch (e: Exception) {
            val root = moduleInitError?.message ?: e.message ?: "unknown"
            throw IllegalStateException("Python FFI 模块未初始化: $root", e)
        }
    }
    
    // ============================================================
    // === LCOW 事务与垃圾回收
    // ============================================================
    
    /**
     * 获取 LCOW 事务列表
     */
    suspend fun getLCOWTransactions(cardUid: String): Result<List<LCOWTransaction>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_transactions", cardUid)
                val jsonStr = result?.toString() ?: "[]"
                val jsonArray = JSONArray(jsonStr)
                
                val transactions = mutableListOf<LCOWTransaction>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    
                    val transaction = LCOWTransaction(
                        transactionId = obj.optString("id", ""),
                        virtualAddress = obj.optInt("va", 0),
                        oldPhysicalLocation = Pair(
                            obj.optInt("old_sector", 0),
                            obj.optInt("old_block", 0)
                        ),
                        newPhysicalLocation = Pair(
                            obj.optInt("new_sector", 0),
                            obj.optInt("new_block", 0)
                        ),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        state = TransactionState.valueOf(obj.optString("state", "PENDING")),
                        dataPayload = obj.optString("payload", ""),
                        merkleHash = obj.optString("merkle", "")
                    )
                    transactions.add(transaction)
                }
                
                Timber.i("已加载 ${transactions.size} 个 LCOW 事务")
                Result.success(transactions)
            } catch (e: Exception) {
                Timber.e(e, "LCOW 事务加载失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取存储管理数据（包括位图和 GC 信息）
     */
    suspend fun getStorageManagementData(cardUid: String): Result<StorageManagementData> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_storage_management_data", cardUid)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                // 解析位图
                val bitmapObj = json.optJSONObject("bitmap") ?: JSONObject()
                val estimatedWearObj = bitmapObj.optJSONObject("wear_distribution") ?: JSONObject()
                val wearMap = mutableMapOf<Pair<Int, Int>, Int>()
                
                for (key in estimatedWearObj.keys()) {
                    val parts = key.split(":")
                    if (parts.size == 2) {
                        wearMap[Pair(parts[0].toInt(), parts[1].toInt())] = estimatedWearObj.optInt(key, 0)
                    }
                }
                
                val bitmap = BitmapStatus(
                    usedBlocks = bitmapObj.optInt("used", 0),
                    freeBlocks = bitmapObj.optInt("free", 0),
                    totalBlocks = bitmapObj.optInt("total", 0),
                    fragmentation = bitmapObj.optDouble("fragmentation", 0.0),
                    estimatedWearPerBlock = wearMap,
                    recommendGCClean = bitmapObj.optBoolean("recommend_gc", false)
                )
                
                // 获取事务列表
                val transactions = getLCOWTransactions(cardUid).getOrNull() ?: emptyList()
                
                // 获取物理块列表
                val blocksArr = json.optJSONArray("physical_blocks") ?: JSONArray()
                val blocks = mutableListOf<PhysicalBlock>()
                for (i in 0 until blocksArr.length()) {
                    val blockObj = blocksArr.optJSONObject(i) ?: continue
                    blocks.add(PhysicalBlock(
                        sector = blockObj.optInt("sector", 0),
                        block = blockObj.optInt("block", 0),
                        wearCount = blockObj.optInt("wear", 0),
                        isFree = blockObj.optBoolean("free", false),
                        data = blockObj.optString("data", null)
                    ))
                }
                
                val data = StorageManagementData(
                    cardUid = cardUid,
                    bitmap = bitmap,
                    transactions = transactions,
                    lastGCTime = if (json.has("last_gc")) json.getLong("last_gc") else null,
                    totalGCCount = json.optInt("gc_count", 0),
                    physicalBlocks = blocks
                )
                
                Timber.i("存储管理数据已加载: 使用 ${bitmap.usedBlocks}/${bitmap.totalBlocks} 块")
                Result.success(data)
            } catch (e: Exception) {
                Timber.e(e, "存储管理数据加载失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 触发垃圾回收
     */
    suspend fun triggerGarbageCollection(cardUid: String): Result<Map<String, Any>> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("trigger_gc", cardUid)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val resultMap = mapOf(
                        "freed_blocks" to json.optInt("freed_blocks", 0),
                        "duration_ms" to json.optLong("duration", 0),
                        "new_fragmentation" to json.optDouble("new_fragmentation", 0.0)
                    )
                    Timber.i("GC 执行成功: 释放了 ${json.optInt("freed_blocks", 0)} 个块")
                    Result.success(resultMap)
                } else {
                    Result.failure(Exception(json.optString("error", "GC 失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "GC 触发失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 回滚错误的事务
     */
    suspend fun rollbackTransaction(transactionId: String): Result<Boolean> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("rollback_transaction", transactionId)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    Timber.i("事务回滚成功: $transactionId")
                    Result.success(true)
                } else {
                    Result.failure(Exception(json.optString("error", "回滚失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "事务回滚失败")
                Result.failure(e)
            }
        }
    }
    
    // ============================================================
    // === 审计摘要与性能监控
    // ============================================================
    
    /**
     * 获取审计摘要
     */
    suspend fun getAuditSummary(sessionId: String? = null): Result<AuditSummary> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_audit_summary", sessionId ?: "")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                // 解析操作分类统计
                val breakdownObj = json.optJSONObject("breakdown") ?: JSONObject()
                val breakdown = mutableMapOf<String, OperationStatistics>()
                
                for (opType in breakdownObj.keys()) {
                    val opObj = breakdownObj.optJSONObject(opType) ?: continue
                    breakdown[opType] = OperationStatistics(
                        type = opType,
                        count = opObj.optInt("count", 0),
                        successCount = opObj.optInt("success", 0),
                        failureCount = opObj.optInt("failed", 0),
                        averageDuration = opObj.optDouble("avg_duration", 0.0),
                        minDuration = opObj.optDouble("min_duration", 0.0),
                        maxDuration = opObj.optDouble("max_duration", 0.0)
                    )
                }
                
                val summary = AuditSummary(
                    sessionId = json.optString("session_id", ""),
                    totalOperations = json.optInt("total_operations", 0),
                    successfulOperations = json.optInt("successful", 0),
                    failedOperations = json.optInt("failed", 0),
                    successRate = json.optDouble("success_rate", 0.0),
                    startTime = json.optLong("start_time", 0),
                    endTime = json.optLong("end_time", 0),
                    durationMs = json.optLong("duration_ms", 0),
                    operationBreakdown = breakdown
                )
                
                Timber.i("审计摘要已获取: ${summary.totalOperations} 个操作, 成功率 ${summary.successRate}%")
                Result.success(summary)
            } catch (e: Exception) {
                Timber.e(e, "审计摘要获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取性能指标
     */
    suspend fun getPerformanceMetrics(): Result<PerformanceMetrics> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_performance_metrics")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                fun parseOpStats(key: String): OperationStatistics? {
                    val opObj = json.optJSONObject(key) ?: return null
                    return OperationStatistics(
                        type = key,
                        count = opObj.optInt("count", 0),
                        successCount = opObj.optInt("success", 0),
                        failureCount = opObj.optInt("failed", 0),
                        averageDuration = opObj.optDouble("avg", 0.0),
                        minDuration = opObj.optDouble("min", 0.0),
                        maxDuration = opObj.optDouble("max", 0.0)
                    )
                }
                
                val metrics = PerformanceMetrics(
                    readOperations = parseOpStats("read"),
                    writeOperations = parseOpStats("write"),
                    authOperations = parseOpStats("auth"),
                    overallAverageDuration = json.optDouble("overall_avg", 0.0),
                    systemUptime = json.optLong("uptime", 0),
                    sampleCount = json.optInt("sample_count", 0)
                )
                
                Timber.i("性能指标已获取: ${metrics.sampleCount} 个样本")
                Result.success(metrics)
            } catch (e: Exception) {
                Timber.e(e, "性能指标获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取性能仪表盘
     */
    suspend fun getPerformanceDashboard(): Result<PerformanceDashboardData> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_performance_dashboard")
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                val metrics = getPerformanceMetrics().getOrNull() ?: PerformanceMetrics()
                
                val dashboard = PerformanceDashboardData(
                    averageSeekTime = json.optDouble("seek_time", 0.0),
                    authSuccessRate = json.optDouble("auth_success_rate", 0.0),
                    readAverageLatency = json.optDouble("read_latency", 0.0),
                    writeAverageLatency = json.optDouble("write_latency", 0.0),
                    metrics = metrics,
                    lastUpdate = System.currentTimeMillis()
                )
                
                Timber.i("性能仪表盘已加载")
                Result.success(dashboard)
            } catch (e: Exception) {
                Timber.e(e, "性能仪表盘加载失败")
                Result.failure(e)
            }
        }
    }
    
    // ============================================================
    // === 卡片厂商深度识别（Forensics）
    // ============================================================
    
    /**
     * 获取卡片硬件指纹
     */
    suspend fun getHardwareFingerprint(uid: String, sak: String, atqa: String): Result<HardwareFingerprint> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_card_fingerprint", uid, sak, atqa)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                val vendorObj = json.optJSONObject("vendor") ?: JSONObject()
                val vendor = VendorInfo(
                    vendorName = vendorObj.optString("name", "Unknown"),
                    vendorCode = vendorObj.optString("code", ""),
                    isClone = vendorObj.optBoolean("is_clone", false),
                    confidence = vendorObj.optDouble("confidence", 0.0)
                )
                
                val fingerprint = HardwareFingerprint(
                    uid = uid,
                    sak = sak,
                    atqa = atqa,
                    bcc = json.optString("bcc", ""),
                    bccValid = json.optBoolean("bcc_valid", false),
                    vendor = vendor,
                    totalSectors = json.optInt("total_sectors", 0),
                    totalSizeBytes = json.optInt("total_size", 0),
                    userAreaBytes = json.optInt("user_area", 0),
                    atqaInterpretation = json.optString("atqa_interpretation", ""),
                    sakInterpretation = json.optString("sak_interpretation", ""),
                    detectionTimestamp = System.currentTimeMillis()
                )
                
                Timber.i("硬件指纹已获取: ${vendor.vendorName} (Clone: ${vendor.isClone})")
                Result.success(fingerprint)
            } catch (e: Exception) {
                Timber.e(e, "硬件指纹获取失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 执行卡片法医分析
     */
    suspend fun getCardForensics(cardUid: String): Result<CardForensicsResult> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("get_card_forensics", cardUid)
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                // 这里需要额外的卡片数据，从 Repository 获取
                val forensicsResult = CardForensicsResult(
                    cardUid = cardUid,
                    fingerprint = HardwareFingerprint(
                        uid = cardUid,
                        sak = json.optString("sak", ""),
                        atqa = json.optString("atqa", ""),
                        bcc = json.optString("bcc", ""),
                        bccValid = json.optBoolean("bcc_valid", false),
                        vendor = VendorInfo(
                            vendorName = json.optString("vendor", "Unknown"),
                            vendorCode = "",
                            isClone = json.optBoolean("is_clone", false),
                            confidence = json.optDouble("confidence", 0.0)
                        ),
                        totalSectors = 16,
                        totalSizeBytes = 1024,
                        userAreaBytes = 704,
                        atqaInterpretation = "",
                        sakInterpretation = ""
                    ),
                    isAuthentic = !json.optBoolean("is_clone", false),
                    forensicsReport = json.optString("report", ""),
                    riskLevel = when (json.optString("risk_level", "UNKNOWN")) {
                        "SAFE" -> RiskLevel.SAFE
                        "WARNING" -> RiskLevel.WARNING
                        "CRITICAL" -> RiskLevel.CRITICAL
                        else -> RiskLevel.UNKNOWN
                    }
                )
                
                Timber.i("法医分析完成: ${forensicsResult.fingerprint.vendor.vendorName}")
                Result.success(forensicsResult)
            } catch (e: Exception) {
                Timber.e(e, "法医分析失败")
                Result.failure(e)
            }
        }
    }
    
    // ============================================================
    // === AEFS 镜像构建器
    // ============================================================
    
    /**
     * 从文件创建 AEFS 镜像
     */
    suspend fun buildAEFSImageFromFile(
        alias: String,
        fileDataHex: String,
        sipLevel: SIPLevel = SIPLevel.ARCHITECT,
        encryptionPassword: String = ""
    ): Result<ImageBuildResult> {
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                val result = ffiBridge?.callAttr(
                    "build_aefs_image_from_file",
                    alias,
                    fileDataHex,
                    sipLevel.name,
                    encryptionPassword
                )
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val imageDetails = AEFSImageDetails(
                        poolId = json.optString("pool_id", ""),
                        volId = json.optString("vol_id", ""),
                        alias = alias,
                        sipLevel = sipLevel,
                        encryptedPayload = json.optString("payload", ""),
                        payloadSize = json.optInt("payload_size", 0),
                        merkleRoot = json.optString("merkle_root", ""),
                        mac = json.optString("mac", ""),
                        streamSize = json.optInt("stream_size", 0),
                        createdAt = System.currentTimeMillis()
                    )
                    
                    val buildResult = ImageBuildResult(
                        success = true,
                        imageData = imageDetails,
                        buildDuration = System.currentTimeMillis() - startTime,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    Timber.i("AEFS 镜像构建成功: $alias (${imageDetails.payloadSize} 字节)")
                    Result.success(buildResult)
                } else {
                    Result.failure(Exception(json.optString("error", "构建失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "AEFS 镜像构建失败")
                Result.failure(Exception("AEFS 镜像构建失败: ${e.message}"))
            }
        }
    }

    suspend fun probeAEFSPayload(
        payloadJson: String,
        recordTypeLabel: String
    ): Result<JSONObject> {
        return withContext(Dispatchers.Default) {
            try {
                val result = requireFfiBridge().callAttr(
                    "probe_aefs_v6_payload",
                    payloadJson,
                    recordTypeLabel
                )
                val json = JSONObject(result?.toString() ?: "{}")
                if (!json.optBoolean("success", false)) {
                    Result.failure(Exception(json.optString("error", "容量探针失败")))
                } else {
                    Result.success(json)
                }
            } catch (e: Exception) {
                Timber.e(e, "AEFS 容量探针失败")
                Result.failure(e)
            }
        }
    }

    suspend fun storeStructuredPayload(
        cardUid: String,
        alias: String,
        passphrase: String,
        payloadJson: String,
        plaintextMode: Boolean,
        allowTruncation: Boolean,
        recordTypeLabel: String = "MIXED_DATA"
    ): Result<AEFSFormattingResult> {
        return withContext(Dispatchers.IO) {
            try {
                ensureMountedPackageContext(cardUid, passphrase)
                val packageExecution = requireFfiBridge().callAttr(
                    "build_aefs_v6_package_from_payload",
                    cardUid,
                    alias,
                    payloadJson,
                    passphrase,
                    recordTypeLabel,
                    plaintextMode,
                    allowTruncation
                )
                val packageJson = JSONObject(packageExecution?.toString() ?: "{}")
                if (!packageJson.optBoolean("success", false)) {
                    throw Exception(packageJson.optString("error", "结构化载荷构建失败"))
                }
                Result.success(writePreparedV6Package(cardUid, alias, packageJson))
            } catch (e: Exception) {
                Timber.e(e, "储存结构化数据失败")
                Result.failure(e)
            }
        }
    }

    suspend fun storeFilePayload(
        cardUid: String,
        alias: String,
        passphrase: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        plaintextMode: Boolean,
        allowTruncation: Boolean
    ): Result<AEFSFormattingResult> {
        return withContext(Dispatchers.IO) {
            try {
                ensureMountedPackageContext(cardUid, passphrase)
                val payloadJson = JSONObject().apply {
                    put("schema", "AEFS.v6")
                    put("alias", alias)
                    put("card_uid", cardUid)
                    put("record_type", "RAW_FILE")
                    put("raw_file", JSONObject().apply {
                        put("name", fileName)
                        put("mime_type", mimeType)
                        put("size", fileBytes.size)
                        put("data_hex", fileBytes.joinToString("") { "%02X".format(it) })
                    })
                }.toString()
                val packageExecution = requireFfiBridge().callAttr(
                    "build_aefs_v6_package_from_payload",
                    cardUid,
                    alias,
                    payloadJson,
                    passphrase,
                    "RAW_FILE",
                    plaintextMode,
                    allowTruncation
                )
                val packageJson = JSONObject(packageExecution?.toString() ?: "{}")
                if (!packageJson.optBoolean("success", false)) {
                    throw Exception(packageJson.optString("error", "文件载荷构建失败"))
                }
                Result.success(writePreparedV6Package(cardUid, alias, packageJson))
            } catch (e: Exception) {
                Timber.e(e, "储存文件失败")
                Result.failure(e)
            }
        }
    }

    private suspend fun ensureMountedPackageContext(cardUid: String, passphrase: String) {
        val currentPhysicalUid = NfcRuntimeContext.getCurrentTag()?.id
            ?.joinToString("") { "%02X".format(it) }
            ?.uppercase()

        val activeJson = runCatching {
            JSONObject(requireFfiBridge().callAttr("lcow_get_active_v6_package")?.toString() ?: "{}")
        }.getOrNull()

        val activePackage = activeJson?.optJSONObject("package")
        val activeCardUid = activePackage?.optString("card_uid", "")?.uppercase().orEmpty()
        if (activeJson?.optBoolean("success", false) == true &&
            (
                activeCardUid == cardUid.uppercase() ||
                    (currentPhysicalUid?.isNotBlank() == true && activeCardUid == currentPhysicalUid)
                )
        ) {
            return
        }

        runCatching {
            readMountedAEFSPayload(cardUid, passphrase)
        }.onSuccess { result ->
            result.onFailure {
                Timber.w(it, "AEFS 写前挂载旧包失败，将按当前输入单独构建增量包")
            }
        }.onFailure {
            Timber.w(it, "AEFS 写前上下文检查失败，将继续尝试直接构建增量包")
        }
    }

    suspend fun readMountedAEFSPayload(cardUid: String, passphrase: String = ""): Result<AEFSMountedPayload> {
        return withContext(Dispatchers.IO) {
            try {
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描 AEFS 卡"))
                val physicalUid = tag.id?.joinToString("") { "%02X".format(it) }?.uppercase()
                    ?: cardUid.uppercase()
                val presentedCardUid = cardUid.uppercase()
                val diagnostic = buildRecoveryDiagnostic(
                    physicalUid = physicalUid,
                    presentedCardUid = presentedCardUid,
                    passphrase = passphrase
                )
                val recommendedCandidate = diagnostic.candidates.firstOrNull { it.recommended }
                val pkg = resolveMountedPackage(
                    physicalUid = physicalUid,
                    presentedCardUid = presentedCardUid,
                    preferredAliasHash = diagnostic.recommendedAliasHash
                )
                    ?: return@withContext Result.failure(
                        Exception(
                            diagnostic.summary.ifBlank {
                                "本机未找到该 AEFS 卡的托管密钥包。跨设备主权挑战仍需补完信任转移链路。"
                            }
                        )
                    )

                if (recommendedCandidate != null &&
                    (!recommendedCandidate.sector15AuthSuccess || !recommendedCandidate.sector0AuthSuccess)
                ) {
                    return@withContext Result.failure(
                        Exception(
                            buildString {
                                append("检测到 AEFS 半写状态：")
                                append(recommendedCandidate.message)
                                if (recommendedCandidate.snapshotAvailable) {
                                    append("。本机已保存完整快照，请先执行 AEFS 恢复。")
                                } else {
                                    append("。但该代托管包没有完整快照，当前只能继续诊断。")
                                }
                            }
                        )
                    )
                }

                KeyVaultManager.rememberSessionKeys(pkg.keys.values.flatMap { listOf(it.first, it.second) })
                val saltHex = pkg.saltHex
                    ?: return@withContext Result.failure(Exception("托管包缺少 salt，无法读取 AEFS 载荷"))
                val rootKeyHex = pkg.rootKeyHex ?: run {
                    if (passphrase.isBlank()) {
                        return@withContext Result.failure(Exception("托管包未保存 root key，请输入主权密码后再读取"))
                    }
                    val rootJson = JSONObject(
                        requireFfiBridge().callAttr(
                            "derive_aefs_v6_root_key",
                            passphrase,
                            saltHex,
                            false
                        )?.toString() ?: "{}"
                    )
                    if (!rootJson.optBoolean("success", false)) {
                        throw Exception(rootJson.optString("error", "无法通过主权密码恢复 root key"))
                    }
                    rootJson.optString("root_key_hex", "").uppercase()
                }

                val sector0KeysJson = JSONObject(
                    requireFfiBridge().callAttr(
                        "derive_aefs_v6_sector0_keys",
                        rootKeyHex,
                        saltHex
                    )?.toString() ?: "{}"
                )
                if (!sector0KeysJson.optBoolean("success", false)) {
                    throw Exception(sector0KeysJson.optString("error", "无法恢复 S0 密钥"))
                }

                val sector0KeyA = sector0KeysJson.optString("key_a_hex", "").uppercase()
                val sector0KeyB = sector0KeysJson.optString("key_b_hex", "").uppercase()
                val mifare = MifareClassic.get(tag)
                    ?: return@withContext Result.failure(Exception("当前卡片不支持 MIFARE Classic"))

                try {
                    if (!mifare.isConnected) {
                        mifare.connect()
                    }

                    if (!authenticateSector(mifare, 0, sector0KeyA, sector0KeyB)) {
                        return@withContext Result.failure(Exception("无法认证 Sector 0，AEFS 锚点读取失败"))
                    }

                    val block1Hex = mifare.readBlock(mifare.sectorToBlock(0) + 1).toHex()
                    val block2Hex = mifare.readBlock(mifare.sectorToBlock(0) + 2).toHex()
                    val detection = AEFSSovereignIdentity.detectFromBlock1(
                        block1 = mifare.readBlock(mifare.sectorToBlock(0) + 1),
                        physicalUid = physicalUid
                    ) ?: return@withContext Result.failure(Exception("当前卡片不是有效的 AEFS v6 卡"))

                    val sectorPayloads = JSONArray()
                    pkg.keys.toSortedMap().forEach { (sector, pair) ->
                        if (!authenticateSector(mifare, sector, pair.first, pair.second)) {
                            throw Exception("无法认证 AEFS 载荷扇区 S$sector")
                        }
                        val sectorBase = mifare.sectorToBlock(sector)
                        val block0Hex = mifare.readBlock(sectorBase).toHex()
                        val block1PayloadHex = mifare.readBlock(sectorBase + 1).toHex()
                        val block2PayloadHex = mifare.readBlock(sectorBase + 2).toHex()
                        val trailerControlHex = mifare.readBlock(sectorBase + 3)
                            .copyOfRange(6, 10)
                            .toHex()
                        sectorPayloads.put(
                            JSONObject().apply {
                                put("sector", sector)
                                put("block0_hex", block0Hex)
                                put("block1_hex", block1PayloadHex)
                                put("block2_hex", block2PayloadHex)
                                put("key_a_hex", pair.first)
                                put("key_b_hex", pair.second)
                                put("trailer_hex", pair.first + trailerControlHex + pair.second)
                            }
                        )
                    }

                    val packageJson = JSONObject().apply {
                        put("success", true)
                        put("card_uid", physicalUid)
                        put("payload_digest", pkg.poolIdHex)
                        put("sector_payloads", sectorPayloads)
                        put(
                            "anchor",
                            JSONObject().apply {
                                put("version_hex", detection.versionHex)
                                put("transaction_sequence", detection.transactionSequence)
                                put("record_type_index", block1Hex.substring(20, 32))
                                put("alias_hash", block2Hex.substring(0, 16))
                                put("block1_hex", block1Hex)
                                put("block2_hex", block2Hex)
                                put("salt_hex", saltHex)
                                pkg.rootKeyHex?.takeIf { it.isNotBlank() }?.let { put("root_key_hex", it) }
                                put(
                                    "block3",
                                    JSONObject().apply {
                                        put("key_a_hex", sector0KeyA)
                                        put("key_b_hex", sector0KeyB)
                                    }
                                )
                            }
                        )
                        put(
                            "keys",
                            JSONObject().apply {
                                pkg.keys.toSortedMap().forEach { (sector, pair) ->
                                    put(
                                        sector.toString(),
                                        JSONObject()
                                            .put("key_a", pair.first)
                                            .put("key_b", pair.second)
                                    )
                                }
                            }
                        )
                    }

                    val mountJson = JSONObject(
                        requireFfiBridge().callAttr("mount_aefs_v6_package", packageJson.toString())?.toString() ?: "{}"
                    )
                    if (!mountJson.optBoolean("success", false)) {
                        throw Exception(mountJson.optString("error", "AEFS 载荷挂载失败"))
                    }

                    val payloadDocument = mountJson.optJSONObject("payload_document") ?: JSONObject()
                    val rawFile = payloadDocument.optJSONObject("raw_file")
                    Result.success(
                        AEFSMountedPayload(
                            cardUid = physicalUid,
                            alias = payloadDocument.optString("alias", ""),
                            recordType = payloadDocument.optString("record_type", ""),
                            transactionSequence = detection.transactionSequence,
                            payloadDocumentJson = payloadDocument.toString(2),
                            originalSize = mountJson.optInt("original_size", 0),
                            compressedSize = mountJson.optInt("compressed_size", 0),
                            integrityMessage = mountJson.optJSONObject("integrity")?.optString("message", "") ?: "",
                            rawFileName = rawFile?.optString("name"),
                            rawFileMimeType = rawFile?.optString("mime_type"),
                            rawFileSize = if (rawFile?.has("size") == true) rawFile.optInt("size") else null,
                            rawFileHex = rawFile?.optString("data_hex")
                        )
                    )
                } finally {
                    runCatching {
                        if (mifare.isConnected) {
                            mifare.close()
                        }
                    }.onFailure { Timber.w(it, "关闭 MifareClassic 连接失败") }
                }
            } catch (e: Exception) {
                Timber.e(e, "读取 AEFS 载荷失败")
                Result.failure(e)
            }
        }
    }

    suspend fun upgradeAEFSCardToLatest(cardUid: String, passphrase: String = ""): Result<AEFSFormattingResult> {
        return withContext(Dispatchers.IO) {
            try {
                val mounted = readMountedAEFSPayload(cardUid, passphrase).getOrElse { throw it }
                val activePackageJson = JSONObject(
                    requireFfiBridge().callAttr("lcow_get_active_v6_package")?.toString() ?: "{}"
                )
                if (!activePackageJson.optBoolean("success", false)) {
                    throw Exception(activePackageJson.optString("error", "未找到当前 AEFS 包快照"))
                }
                val activePackage = activePackageJson.optJSONObject("package")
                    ?: throw Exception("当前 AEFS 包快照无效")
                val verifyJson = JSONObject(
                    requireFfiBridge().callAttr("verify_aefs_v6_package", activePackage.toString())?.toString() ?: "{}"
                )
                if (!verifyJson.optBoolean("success", false)) {
                    throw Exception(verifyJson.optString("error", "当前 AEFS 包校验失败"))
                }

                val currentLayout = verifyJson.optString("layout", "")
                if (currentLayout == "INCREMENTAL_SECTORS_V2") {
                    return@withContext Result.success(
                        AEFSFormattingResult(
                            success = true,
                            cardUid = mounted.cardUid,
                            alias = mounted.alias.ifBlank { "AEFS Volume" },
                            completedSteps = listOf(FormatStep.PREPARE_PAYLOAD, FormatStep.FINALIZE),
                            duration = 0L,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                val payloadJson = mounted.payloadDocumentJson
                val payloadDocument = JSONObject(payloadJson)
                val alias = mounted.alias.ifBlank {
                    payloadDocument.optString("alias", "AEFS Volume")
                }
                val recordType = mounted.recordType.ifBlank {
                    payloadDocument.optString("record_type", "MIXED_DATA")
                }
                val plaintextMode = payloadDocument.optBoolean("plaintext_mode", false)

                val packageExecution = requireFfiBridge().callAttr(
                    "build_aefs_v6_package_from_payload",
                    cardUid,
                    alias,
                    payloadJson,
                    passphrase,
                    recordType,
                    plaintextMode,
                    false
                )
                val packageJson = JSONObject(packageExecution?.toString() ?: "{}")
                if (!packageJson.optBoolean("success", false)) {
                    throw Exception(packageJson.optString("error", "AEFS 升级包构建失败"))
                }

                Result.success(writePreparedV6Package(cardUid, alias, packageJson))
            } catch (e: Exception) {
                Timber.e(e, "AEFS 一键升级失败")
                Result.failure(e)
            }
        }
    }

    suspend fun recoverAEFSCard(cardUid: String, passphrase: String = ""): Result<AEFSFormattingResult> {
        return withContext(Dispatchers.IO) {
            try {
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描 AEFS 卡"))
                val physicalUid = tag.id?.joinToString("") { "%02X".format(it) }?.uppercase()
                    ?: cardUid.uppercase()
                val diagnostic = buildRecoveryDiagnostic(
                    physicalUid = physicalUid,
                    presentedCardUid = cardUid.uppercase(),
                    passphrase = passphrase
                )
                val target = diagnostic.candidates.firstOrNull { it.recommended && it.snapshotAvailable }
                    ?: diagnostic.candidates.firstOrNull { it.snapshotAvailable && (it.sector15AuthSuccess || it.sector0AuthSuccess) }
                    ?: return@withContext Result.failure(
                        Exception(
                            diagnostic.summary.ifBlank {
                                "本机没有可用于恢复的 AEFS 快照"
                            }
                        )
                    )
                val pkg = resolveMountedPackage(
                    physicalUid = physicalUid,
                    presentedCardUid = cardUid.uppercase(),
                    preferredAliasHash = target.aliasHash
                ) ?: return@withContext Result.failure(Exception("无法定位恢复所需的托管包"))
                val snapshotJson = pkg.packageSnapshotJson
                    ?: return@withContext Result.failure(Exception("托管包缺少完整快照，无法执行 AEFS 恢复"))
                val packageJson = JSONObject(snapshotJson)
                val recoveredAlias = packageJson.optJSONObject("payload_document")
                    ?.optString("alias")
                    ?.takeIf { it.isNotBlank() }
                    ?: "AEFS Recovery"

                Result.success(
                    writePreparedV6Package(
                        cardUid = physicalUid,
                        alias = recoveredAlias,
                        packageJson = packageJson,
                        bootstrapAnchor = true,
                        forceFullRewrite = true
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "AEFS 恢复失败")
                Result.failure(e)
            }
        }
    }

    suspend fun diagnoseAEFSRecovery(cardUid: String, passphrase: String = ""): Result<AEFSRecoveryDiagnostic> {
        return withContext(Dispatchers.IO) {
            try {
                val tag = NfcRuntimeContext.getCurrentTag()
                    ?: return@withContext Result.failure(Exception("没有活跃卡片，请先回首页扫描 AEFS 卡"))
                val physicalUid = tag.id?.joinToString("") { "%02X".format(it) }?.uppercase()
                    ?: cardUid.uppercase()
                val diagnostic = buildRecoveryDiagnostic(
                    physicalUid = physicalUid,
                    presentedCardUid = cardUid.uppercase(),
                    passphrase = passphrase
                )
                diagnostic.recommendedAliasHash?.let { aliasHash ->
                    resolveMountedPackage(
                        physicalUid = physicalUid,
                        presentedCardUid = cardUid.uppercase(),
                        preferredAliasHash = aliasHash
                    )?.let { pkg ->
                        KeyVaultManager.rememberSessionKeys(pkg.keys.values.flatMap { pair -> listOf(pair.first, pair.second) })
                    }
                }
                Result.success(diagnostic)
            } catch (e: Exception) {
                Timber.e(e, "AEFS 救援诊断失败")
                Result.failure(e)
            }
        }
    }

    // ============================================================
    // === AEFS 格式化（工业级实现 - Python 驱动）
    // ============================================================
    
    /**
     * 初始化 AEFS 卡片 - 工业级实现
     * 
     * 遵循 gi.txt 的架构设计：Python 是"指挥官"，Kotlin 是"执行者"
     * 所有 AEFS 格式化逻辑（Genesis Block、BCC 计算、Superblock）由 Python 负责，
     * Kotlin 只负责卡片硬件操作（读、写、认证）
     */
    suspend fun initializeAEFSCard(
        cardUid: String,
        params: AEFSInitializationParams
    ): Result<AEFSFormattingResult> {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val completedSteps = mutableListOf<FormatStep>()
                var vaultPackageSaved = false

                // Step 1: 认证（通过首次真实写入触发底层鉴权）
                Timber.i("AEFS 格式化 - Step 1: 认证卡片")
                completedSteps.add(FormatStep.AUTH)

                // Step 2: 调用 Python 一次性构建 v6 主权平铺镜像
                Timber.i("AEFS 格式化 - Step 2: 构建 AEFS v6 Sovereign Tile Package")
                val packageExecution = requireFfiBridge().callAttr(
                    "build_aefs_v6_package",
                    cardUid,
                    params.cardAlias,
                    params.encryptionPassword,
                    params.recordTypeLabel,
                    params.plaintextMode,
                    params.allowTruncation
                )
                val packageJsonStr = packageExecution?.toString() ?: "{}"
                Timber.d("AEFS v6 package 响应: $packageJsonStr")
                val packageJson = JSONObject(packageJsonStr)

                if (!packageJson.optBoolean("success", false)) {
                    val error = packageJson.optString("error", "AEFS v6 package 生成失败")
                    throw Exception("Python AEFS v6 package 生成失败: $error")
                }
                val result = writePreparedV6Package(
                    cardUid = cardUid,
                    alias = params.cardAlias,
                    packageJson = packageJson,
                    bootstrapAnchor = true
                )
                vaultPackageSaved = true
                Timber.i(
                    "AEFS v6 格式化完成: $cardUid (${result.duration}ms), " +
                        "密钥托管包=${if (vaultPackageSaved) "已入库" else "未入库"}"
                )
                Result.success(result)
            } catch (e: Exception) {
                Timber.e(e, "AEFS 格式化失败")
                Result.failure(e)
            }
        }
    }

    private suspend fun writePreparedV6Package(
        cardUid: String,
        alias: String,
        packageJson: JSONObject,
        bootstrapAnchor: Boolean = false,
        forceFullRewrite: Boolean = false
    ): AEFSFormattingResult {
        val startedAt = System.currentTimeMillis()
        val completedSteps = mutableListOf<FormatStep>()
        completedSteps.add(FormatStep.PREPARE_PAYLOAD)

        val anchorJson = packageJson.optJSONObject("anchor") ?: throw Exception("anchor 数据缺失")
        val block1Hex = anchorJson.optString("block1_hex", "").uppercase()
        val block2Hex = anchorJson.optString("block2_hex", "").uppercase()
        val saltHex = anchorJson.optString("salt_hex", "").uppercase()
        val sequence = anchorJson.optLong("transaction_sequence", 1L)
        val recordTypeIndex = anchorJson.optString("record_type_index", "").uppercase()
        val trailerPayloadJson = anchorJson.optJSONObject("block3") ?: JSONObject()
        val trailerHex = buildTrailerHex(trailerPayloadJson)
        val sectorsJson = packageJson.optJSONArray("sector_payloads")
            ?: throw Exception("平铺载荷数据缺失")
        val gcJson = packageJson.optJSONObject("gc") ?: JSONObject()
        val changeMaskJson = packageJson.optJSONObject("change_mask") ?: JSONObject()
        val changedSectors = mutableSetOf<Int>().apply {
            val arr = changeMaskJson.optJSONArray("changed_sectors")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    add(arr.optInt(i))
                }
            }
            if (forceFullRewrite) {
                addAll(1..15)
            }
        }
        val changedBlocksJson = changeMaskJson.optJSONObject("changed_blocks") ?: JSONObject()

        val verifyJson = JSONObject(
            requireFfiBridge().callAttr("verify_aefs_v6_package", packageJson.toString())?.toString() ?: "{}"
        )
        if (!verifyJson.optBoolean("success", false)) {
            throw Exception(
                verifyJson.optString("error", "AEFS v6 包完整性校验失败")
            )
        }
        Timber.i(
            "AEFS v6 写前校验完成: integrity=%s gc_removed=%d padding=%d changed_sectors=%d",
            verifyJson.optJSONObject("integrity")?.optString("message", "unknown"),
            gcJson.optInt("removed_nodes", 0),
            gcJson.optInt("entropy_padding_bytes", 0),
            changedSectors.size
        )
        val lcowStart = JSONObject(
            requireFfiBridge().callAttr("lcow_begin_aefs_v6_rebuild", packageJson.toString())?.toString() ?: "{}"
        )
        if (!lcowStart.optBoolean("success", false)) {
            throw Exception("LCOW v6 重构事务启动失败: ${lcowStart.optString("error", "unknown")}")
        }

        WriteControlPolicy.beginFormattingSession()
        try {
            val physicalUid = NfcRuntimeContext.getCurrentTag()?.id
                ?.joinToString("") { "%02X".format(it) }
                ?.uppercase()
                ?: cardUid.uppercase()
            val pkg = parseVaultPackage(packageJson, logicalCardUid = cardUid, physicalUid = physicalUid)
            val packageKeys = pkg.keys.values.flatMap { listOf(it.first, it.second) }
            KeyVaultManager.rememberSessionKeys(packageKeys)
            AEFSSovereignKeyVault.savePendingPackage(baseRepository.getContext(), pkg)
            KeyVaultManager.syncPublicAefsKeys(baseRepository.getContext())
            completedSteps.add(FormatStep.SAVE_KEYVAULT)

            val preflightFailedSector = findFirstUnauthenticatedSector(changedSectors.sortedDescending())
            if (preflightFailedSector != null) {
                throw Exception("写前预检失败：S$preflightFailedSector 当前无法认证，已中止初始化以避免进一步锁卡")
            }

            if (bootstrapAnchor && shouldBootstrapInitialAnchor()) {
                val provisionalBlock1Hex = buildProvisionalAnchorBlock1(block1Hex)
                val bootstrapWrites = listOf(
                    BlockWriteRequest(0, 2, block2Hex),
                    BlockWriteRequest(0, 3, trailerHex),
                    BlockWriteRequest(0, 1, provisionalBlock1Hex)
                )
                val writeResult = baseRepository.writeBlocksBatch(bootstrapWrites)
                if (writeResult.isFailure) {
                    throw Exception("初始化锚点预写失败 - ${writeResult.exceptionOrNull()?.message}")
                }
                Timber.i("AEFS 首次初始化已预写临时锚点，后续仍以事务序列号提交为准")
            }

            completedSteps.add(FormatStep.WIPE_SECTORS)

            val payloadWrites = mutableListOf<BlockWriteRequest>()
            for (index in sectorsJson.length() - 1 downTo 0) {
                val sectorJson = sectorsJson.optJSONObject(index) ?: continue
                val sector = sectorJson.optInt("sector", -1)
                if (sector !in 1..15) {
                    throw Exception("非法扇区号: $sector")
                }
                if (!changedSectors.contains(sector)) {
                    Timber.d("AEFS v6 跳过未变化扇区: S$sector")
                    continue
                }
                val changedBlocks = mutableSetOf<Int>().apply {
                    val arr = changedBlocksJson.optJSONArray(sector.toString())
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            add(arr.optInt(i))
                        }
                    } else {
                        addAll(listOf(0, 1, 2, 3))
                    }
                }
                val writes = listOf(
                    0 to sectorJson.optString("block0_hex", ""),
                    1 to sectorJson.optString("block1_hex", ""),
                    2 to sectorJson.optString("block2_hex", ""),
                    3 to sectorJson.optString("trailer_hex", "")
                )
                for ((block, dataHex) in writes) {
                    if (!changedBlocks.contains(block)) {
                        continue
                    }
                    payloadWrites += BlockWriteRequest(sector, block, dataHex)
                }
            }
            val payloadWriteResult = baseRepository.writeBlocksBatch(payloadWrites)
            if (payloadWriteResult.isFailure) {
                throw Exception("写入失败 - ${payloadWriteResult.exceptionOrNull()?.message}")
            }
            completedSteps.add(FormatStep.WRITE_TILED_PAYLOAD)

            val anchorWrites = listOf(
                BlockWriteRequest(0, 2, block2Hex),
                BlockWriteRequest(0, 3, trailerHex)
            )
            val anchorWriteResult = baseRepository.writeBlocksBatch(anchorWrites)
            if (anchorWriteResult.isFailure) {
                throw Exception("写入锚点失败 - ${anchorWriteResult.exceptionOrNull()?.message}")
            }
            completedSteps.add(FormatStep.WRITE_ANCHOR)

            val commitResult = baseRepository.writeBlocksBatch(listOf(BlockWriteRequest(0, 1, block1Hex)))
            if (commitResult.isFailure) {
                throw Exception("写入事务锚点失败 S0:B1 - ${commitResult.exceptionOrNull()?.message}")
            }
            AEFSSovereignKeyVault.activatePendingPackage(
                baseRepository.getContext(),
                cardUidHex = physicalUid,
                aliasHashHex = anchorJson.optString("alias_hash", "")
            )
            KeyVaultManager.syncPublicAefsKeys(baseRepository.getContext())
            completedSteps.add(FormatStep.COMMIT_SEQUENCE)
            completedSteps.add(FormatStep.FINALIZE)

            val lcowCommit = JSONObject(
                requireFfiBridge().callAttr("lcow_commit_transaction")?.toString() ?: "{}"
            )
            if (!lcowCommit.optBoolean("success", false)) {
                throw Exception("LCOW v6 重构事务提交失败: ${lcowCommit.optString("error", "unknown")}")
            }
        } finally {
            if (completedSteps.contains(FormatStep.COMMIT_SEQUENCE).not()) {
                runCatching {
                    requireFfiBridge().callAttr("lcow_rollback_transaction")
                }.onFailure { Timber.w(it, "LCOW v6 重构事务回滚失败") }
            }
            WriteControlPolicy.endFormattingSession()
        }

        return AEFSFormattingResult(
            success = true,
            cardUid = cardUid,
            alias = alias,
            sector0Data = AEFSSector0(
                magicUid = "41454653",
                bcc = "11",
                version = anchorJson.optString("version_hex", "60"),
                transactionSequence = sequence,
                recordTypeIndex = recordTypeIndex,
                aliasHash = anchorJson.optString("alias_hash", ""),
                saltHex = saltHex,
                timestamp = packageJson.optLong("timestamp", System.currentTimeMillis())
            ),
            completedSteps = completedSteps,
            duration = System.currentTimeMillis() - startedAt,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseVaultPackage(
        vaultJson: JSONObject,
        logicalCardUid: String,
        physicalUid: String
    ): AEFSManagedKeyPackage {
        val anchorJson = vaultJson.optJSONObject("anchor") ?: JSONObject()
        val aliasHashHex = anchorJson.optString("alias_hash", "")
        val poolIdHex = vaultJson.optString("payload_digest", "")
        val keysObj = vaultJson.optJSONObject("keys") ?: JSONObject()
        val keyMap = mutableMapOf<Int, Pair<String, String>>()

        keysObj.keys().forEach { sectorStr ->
            val sector = sectorStr.toIntOrNull() ?: return@forEach
            val pair = keysObj.optJSONObject(sectorStr) ?: return@forEach
            val keyA = pair.optString("key_a", "").uppercase()
            val keyB = pair.optString("key_b", "").uppercase()
            if (keyA.length == 12 && keyB.length == 12) {
                keyMap[sector] = Pair(keyA, keyB)
            }
        }

        return AEFSManagedKeyPackage(
            cardUidHex = logicalCardUid.uppercase(),
            physicalUidHex = physicalUid.uppercase(),
            aliasHashHex = aliasHashHex.uppercase(),
            poolIdHex = poolIdHex.uppercase(),
            sovereignUid = vaultJson.optString("sovereign_uid", AEFSSovereignIdentity.SOVEREIGN_UID),
            versionHex = anchorJson.optString("version_hex", AEFSSovereignIdentity.VERSION_V6_HEX),
            transactionSequence = anchorJson.optLong("transaction_sequence", 0L),
            rootKeyHex = anchorJson.optString("root_key_hex").takeIf { it.isNotBlank() }?.uppercase(),
            saltHex = anchorJson.optString("salt_hex").takeIf { it.isNotBlank() }?.uppercase(),
            keys = keyMap,
            packageSnapshotJson = vaultJson.toString()
        )
    }

    private fun buildTrailerHex(block3Json: JSONObject): String {
        val keyA = block3Json.optString("key_a_hex", "").uppercase()
        val keyB = block3Json.optString("key_b_hex", "").uppercase()
        val accessBits = "FF078069"
        require(keyA.length == 12) { "S0:B3 Key A 长度无效" }
        require(keyB.length == 12) { "S0:B3 Key B 长度无效" }
        return keyA + accessBits + keyB
    }

    private fun buildProvisionalAnchorBlock1(block1Hex: String): String {
        val clean = block1Hex.trim().uppercase()
        require(clean.length == 32) { "S0:B1 锚点长度无效" }
        return buildString {
            append(clean.substring(0, 12))
            append("00000000")
            append(clean.substring(20, 32))
        }
    }

    private fun authenticateSector(
        mifare: MifareClassic,
        sector: Int,
        keyAHex: String?,
        keyBHex: String?
    ): Boolean {
        val keyABytes = keyAHex?.hexToBytes()
        if (keyABytes != null && runCatching { mifare.authenticateSectorWithKeyA(sector, keyABytes) }.getOrDefault(false)) {
            return true
        }
        val keyBBytes = keyBHex?.hexToBytes()
        if (keyBBytes != null && runCatching { mifare.authenticateSectorWithKeyB(sector, keyBBytes) }.getOrDefault(false)) {
            return true
        }
        return false
    }

    private fun resolveMountedPackage(
        physicalUid: String,
        presentedCardUid: String,
        preferredAliasHash: String? = null
    ): AEFSManagedKeyPackage? {
        val context = baseRepository.getContext()
        val activePackages = AEFSSovereignKeyVault.listPackages(context)
        val pendingPackages = AEFSSovereignKeyVault.listPendingPackages(context)
        val historyPackages = AEFSSovereignKeyVault.listHistoryPackages(context)

        fun matches(pkg: AEFSManagedKeyPackage, uid: String): Boolean {
            if (uid.isBlank()) return false
            return pkg.cardUidHex.equals(uid, ignoreCase = true) ||
                pkg.physicalUidHex.equals(uid, ignoreCase = true)
        }

        if (!preferredAliasHash.isNullOrBlank()) {
            val preferredActive = activePackages.firstOrNull { it.aliasHashHex.equals(preferredAliasHash, ignoreCase = true) }
            if (preferredActive != null) {
                return preferredActive
            }
            val preferredPending = pendingPackages.firstOrNull { it.aliasHashHex.equals(preferredAliasHash, ignoreCase = true) }
            if (preferredPending != null) {
                return preferredPending
            }
            val preferredHistory = historyPackages.firstOrNull { it.aliasHashHex.equals(preferredAliasHash, ignoreCase = true) }
            if (preferredHistory != null) {
                return preferredHistory
            }
        }

        val exactActive = activePackages.firstOrNull { matches(it, physicalUid) }
            ?: activePackages.firstOrNull { matches(it, presentedCardUid) }
        if (exactActive != null) {
            return exactActive
        }

        val exactPending = pendingPackages.firstOrNull { matches(it, physicalUid) }
            ?: pendingPackages.firstOrNull { matches(it, presentedCardUid) }
        if (exactPending != null) {
            return exactPending
        }

        val exactHistory = historyPackages.firstOrNull { matches(it, physicalUid) }
            ?: historyPackages.firstOrNull { matches(it, presentedCardUid) }
        if (exactHistory != null) {
            return exactHistory
        }

        // 兼容旧实现：曾经把所有已写入 AEFS 卡错误地按主权 UID 41454653 入库。
        if (presentedCardUid.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true)) {
            val legacyCandidates = (activePackages + pendingPackages + historyPackages).filter {
                it.cardUidHex.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true) ||
                    it.physicalUidHex.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true)
            }
            if (legacyCandidates.size == 1) {
                return legacyCandidates.first()
            }
        }

        return null
    }

    private fun buildRecoveryDiagnostic(
        physicalUid: String,
        presentedCardUid: String,
        passphrase: String
    ): AEFSRecoveryDiagnostic {
        val context = baseRepository.getContext()
        val packageStates = buildList {
            AEFSSovereignKeyVault.listPackages(context).forEach { add("ACTIVE" to it) }
            AEFSSovereignKeyVault.listPendingPackages(context).forEach { add("PENDING" to it) }
            AEFSSovereignKeyVault.listHistoryPackages(context).forEach { add("HISTORY" to it) }
        }.distinctBy { "${it.first}|${it.second.aliasHashHex}|${it.second.transactionSequence}|${it.second.createdAt}" }

        if (packageStates.isEmpty()) {
            return AEFSRecoveryDiagnostic(
                physicalUid = physicalUid,
                presentedCardUid = presentedCardUid,
                summary = "本机没有任何 AEFS active/pending 包，当前无法对这张卡做本地救援诊断",
                recoverable = false
            )
        }

        val tag = NfcRuntimeContext.getCurrentTag()
        val mifare = tag?.let { MifareClassic.get(it) }
        if (mifare == null) {
            return AEFSRecoveryDiagnostic(
                physicalUid = physicalUid,
                presentedCardUid = presentedCardUid,
                summary = "当前卡片不支持 MIFARE Classic，无法执行 AEFS 扇区级救援诊断",
                recoverable = false
            )
        }

        val candidates = mutableListOf<AEFSRecoveryCandidate>()
        try {
            reconnectMifare(mifare)
            for ((packageState, pkg) in packageStates) {
                val affinity = describePackageAffinity(pkg, physicalUid, presentedCardUid)
                val sector15Pair = pkg.keys[15]
                val sector15AuthSuccess = if (sector15Pair != null) {
                    reconnectMifare(mifare) && authenticateSector(mifare, 15, sector15Pair.first, sector15Pair.second)
                } else {
                    false
                }

                val resolvedRootKeyHex = resolveRootKeyForPackage(pkg, passphrase)
                val rootKeySource = when {
                    pkg.rootKeyHex?.isNotBlank() == true -> "vault"
                    resolvedRootKeyHex != null -> "passphrase"
                    pkg.saltHex.isNullOrBlank() -> "missing_salt"
                    else -> "missing_root"
                }

                var observedTransactionSequence: Long? = null
                val sector0AuthSuccess = if (resolvedRootKeyHex != null && !pkg.saltHex.isNullOrBlank()) {
                    val sector0KeysJson = JSONObject(
                        requireFfiBridge().callAttr(
                            "derive_aefs_v6_sector0_keys",
                            resolvedRootKeyHex,
                            pkg.saltHex
                        )?.toString() ?: "{}"
                    )
                    if (sector0KeysJson.optBoolean("success", false)) {
                        val authOk = reconnectMifare(mifare) && authenticateSector(
                            mifare,
                            0,
                            sector0KeysJson.optString("key_a_hex", "").uppercase(),
                            sector0KeysJson.optString("key_b_hex", "").uppercase()
                        )
                        if (authOk) {
                            runCatching {
                                val block1 = mifare.readBlock(mifare.sectorToBlock(0) + 1)
                                observedTransactionSequence = AEFSSovereignIdentity.detectFromBlock1(
                                    block1 = block1,
                                    physicalUid = physicalUid
                                )?.transactionSequence
                            }
                        }
                        authOk
                    } else {
                        false
                    }
                } else {
                    false
                }

                val message = buildString {
                    append(affinity.second)
                    append("；S15=")
                    append(if (sector15AuthSuccess) "可认证" else "失败")
                    append("；S0=")
                    append(if (sector0AuthSuccess) "可认证" else "失败")
                    observedTransactionSequence?.let {
                        append("(卡上TX=")
                        append(it)
                        append(if (it == pkg.transactionSequence) "匹配" else "不匹配")
                        append(")")
                    }
                    append("；root=")
                    append(rootKeySource)
                    append("；snapshot=")
                    append(if (pkg.packageSnapshotJson.isNullOrBlank()) "无" else "有")
                }

                candidates += AEFSRecoveryCandidate(
                    packageState = packageState,
                    cardUid = pkg.cardUidHex,
                    physicalUid = pkg.physicalUidHex,
                    aliasHash = pkg.aliasHashHex,
                    transactionSequence = pkg.transactionSequence,
                    createdAt = pkg.createdAt,
                    matchReason = affinity.second,
                    sector15AuthSuccess = sector15AuthSuccess,
                    sector0AuthSuccess = sector0AuthSuccess,
                    observedTransactionSequence = observedTransactionSequence,
                    snapshotAvailable = pkg.packageSnapshotJson.isNullOrBlank().not(),
                    rootKeySource = rootKeySource,
                    recommended = false,
                    message = message
                )
            }
        } finally {
            runCatching {
                if (mifare.isConnected) {
                    mifare.close()
                }
            }
        }

        val ranked = candidates
            .map { candidate -> candidate to recoveryScore(candidate) }
            .sortedByDescending { it.second }
        val recommendedAliasHash = ranked.firstOrNull { it.second > 0 }?.first?.aliasHash
        val recommendedSet = recommendedAliasHash?.let { alias -> candidates.map { if (it.aliasHash == alias) it.copy(recommended = true) else it } }
            ?: candidates
        val recoverable = recommendedSet.any {
            it.recommended && it.snapshotAvailable && (it.sector15AuthSuccess || it.sector0AuthSuccess)
        }
        val summary = when {
            recommendedSet.any { it.recommended && it.sector15AuthSuccess && it.sector0AuthSuccess } ->
                "发现可直接挂载的包：${recommendedAliasHash ?: "unknown"}，当前卡的 S0/S15 都还能认证"
            recoverable ->
                "发现可恢复快照：${recommendedAliasHash ?: "unknown"}，当前卡疑似处于半写状态，请先执行 AEFS 恢复"
            recommendedAliasHash != null ->
                "发现最接近当前卡的包，但它既不能直接挂载，也缺少足够的恢复条件，请先查看候选列表"
            else ->
                "已扫描本机所有 active/pending/history 包，暂无任何一份能重新接管当前卡"
        }

        return AEFSRecoveryDiagnostic(
            physicalUid = physicalUid,
            presentedCardUid = presentedCardUid,
            summary = summary,
            recoverable = recoverable,
            recommendedAliasHash = recommendedAliasHash,
            candidates = recommendedSet.sortedWith(
                compareByDescending<AEFSRecoveryCandidate> { it.recommended }
                    .thenByDescending { it.sector15AuthSuccess }
                    .thenByDescending { it.sector0AuthSuccess }
                    .thenByDescending { it.transactionSequence }
                    .thenByDescending { it.createdAt }
            )
        )
    }

    private fun findFirstUnauthenticatedSector(sectors: List<Int>): Int? {
        val tag = NfcRuntimeContext.getCurrentTag() ?: return sectors.firstOrNull()
        val mifare = MifareClassic.get(tag) ?: return sectors.firstOrNull()
        return try {
            if (!mifare.isConnected) {
                mifare.connect()
            }
            val keyCandidates = KeyVaultManager.getAllAuthKeys(baseRepository.getContext())
            sectors.firstOrNull { sector ->
                !canAuthenticateWithAnyKey(mifare, sector, keyCandidates)
            }
        } catch (e: Exception) {
            Timber.w(e, "AEFS 写前预检失败")
            sectors.firstOrNull()
        } finally {
            runCatching {
                if (mifare.isConnected) {
                    mifare.close()
                }
            }
        }
    }

    private fun canAuthenticateWithAnyKey(
        mifare: MifareClassic,
        sector: Int,
        keyCandidates: List<ByteArray>
    ): Boolean {
        for (key in keyCandidates) {
            val okA = runCatching { mifare.authenticateSectorWithKeyA(sector, key) }.getOrDefault(false)
            if (okA) {
                return true
            }
            val okB = runCatching { mifare.authenticateSectorWithKeyB(sector, key) }.getOrDefault(false)
            if (okB) {
                return true
            }
        }
        return false
    }

    private fun reconnectMifare(mifare: MifareClassic): Boolean {
        return try {
            if (mifare.isConnected) {
                mifare.close()
            }
            mifare.connect()
            true
        } catch (e: Exception) {
            Timber.w(e, "MifareClassic 重连失败")
            false
        }
    }

    private fun shouldBootstrapInitialAnchor(): Boolean {
        val tag = NfcRuntimeContext.getCurrentTag() ?: return false
        val mifare = MifareClassic.get(tag) ?: return false
        return try {
            if (!mifare.isConnected) {
                mifare.connect()
            }
            val keyCandidates = KeyVaultManager.getAllAuthKeys(baseRepository.getContext())
            if (!canAuthenticateWithAnyKey(mifare, 0, keyCandidates)) {
                return false
            }
            val block1 = mifare.readBlock(mifare.sectorToBlock(0) + 1)
            val physicalUid = tag.id?.joinToString("") { "%02X".format(it) }?.uppercase().orEmpty()
            AEFSSovereignIdentity.detectFromBlock1(block1, physicalUid) == null
        } catch (e: Exception) {
            Timber.w(e, "判断是否需要预写初始化锚点失败")
            false
        } finally {
            runCatching {
                if (mifare.isConnected) {
                    mifare.close()
                }
            }
        }
    }

    private fun resolveRootKeyForPackage(pkg: AEFSManagedKeyPackage, passphrase: String): String? {
        pkg.rootKeyHex?.takeIf { it.isNotBlank() }?.let { return it.uppercase() }
        val saltHex = pkg.saltHex?.takeIf { it.isNotBlank() } ?: return null
        if (passphrase.isBlank()) {
            return null
        }
        val rootJson = JSONObject(
            requireFfiBridge().callAttr(
                "derive_aefs_v6_root_key",
                passphrase,
                saltHex,
                false
            )?.toString() ?: "{}"
        )
        if (!rootJson.optBoolean("success", false)) {
            return null
        }
        return rootJson.optString("root_key_hex", "").uppercase().takeIf { it.isNotBlank() }
    }

    private fun describePackageAffinity(
        pkg: AEFSManagedKeyPackage,
        physicalUid: String,
        presentedCardUid: String
    ): Pair<Int, String> {
        return when {
            pkg.physicalUidHex.equals(physicalUid, ignoreCase = true) ->
                100 to "物理 UID 精确匹配"
            pkg.cardUidHex.equals(physicalUid, ignoreCase = true) ->
                95 to "逻辑 UID 曾按物理 UID 入库"
            presentedCardUid.isNotBlank() && pkg.cardUidHex.equals(presentedCardUid, ignoreCase = true) ->
                90 to "当前 UI 卡 UID 与包记录匹配"
            presentedCardUid.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true) &&
                (
                    pkg.cardUidHex.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true) ||
                        pkg.physicalUidHex.equals(AEFSSovereignIdentity.SOVEREIGN_UID, ignoreCase = true)
                    ) ->
                40 to "旧版主权 UID 入库记录"
            else -> 10 to "仅做盲探测"
        }
    }

    private fun recoveryScore(candidate: AEFSRecoveryCandidate): Int {
        var score = when (candidate.matchReason) {
            "物理 UID 精确匹配" -> 100
            "逻辑 UID 曾按物理 UID 入库" -> 95
            "当前 UI 卡 UID 与包记录匹配" -> 90
            "旧版主权 UID 入库记录" -> 40
            else -> 10
        }
        if (candidate.sector15AuthSuccess) score += 120
        if (candidate.sector0AuthSuccess) score += 80
        if (candidate.snapshotAvailable) score += 20
        if (candidate.rootKeySource == "vault") score += 15
        if (candidate.rootKeySource == "passphrase") score += 10
        if (candidate.observedTransactionSequence == candidate.transactionSequence) score += 30
        if (candidate.packageState == "ACTIVE") score += 5
        if (candidate.packageState == "PENDING") score += 10
        return score
    }

    private fun String.hexToBytes(): ByteArray? {
        val clean = trim().replace(" ", "").uppercase()
        if (clean.length % 2 != 0 || clean.any { it !in '0'..'9' && it !in 'A'..'F' }) {
            return null
        }
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    
    // ============================================================
    // === 数据处理向导
    // ============================================================
    
    /**
     * 执行批量操作
     */
    suspend fun executeBatchOperation(operation: BatchOperation): Result<BatchOperationResult> {
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                val result = ffiBridge?.callAttr(
                    "execute_batch_operation",
                    operation.operationType.name,
                    operation.targetSectors.joinToString(","),
                    JSONObject(operation.parameters).toString()
                )
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                val details = mutableMapOf<Int, String>()
                val detailsObj = json.optJSONObject("details") ?: JSONObject()
                for (key in detailsObj.keys()) {
                    details[key.toInt()] = detailsObj.optString(key, "")
                }
                
                val operationResult = BatchOperationResult(
                    operationId = operation.operationId,
                    operationType = operation.operationType,
                    success = json.optBoolean("success", false),
                    processedCount = json.optInt("processed", 0),
                    failureCount = json.optInt("failed", 0),
                    details = details,
                    totalDuration = System.currentTimeMillis() - startTime,
                    timestamp = System.currentTimeMillis()
                )
                
                Timber.i("批量操作完成: ${operation.operationType} - ${operationResult.processedCount} 个成功")
                Result.success(operationResult)
            } catch (e: Exception) {
                Timber.e(e, "批量操作失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 清零扇区
     */
    suspend fun clearSectors(sectorList: List<Int>): Result<BatchOperationResult> {
        return withContext(Dispatchers.Default) {
            try {
                val operation = BatchOperation(
                    operationId = System.currentTimeMillis().toString(),
                    operationType = BatchOperationType.CLEAR_SECTORS,
                    targetSectors = sectorList
                )
                executeBatchOperation(operation)
            } catch (e: Exception) {
                Timber.e(e, "清零扇区失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 填充扇区
     */
    suspend fun fillSectors(sectorList: List<Int>, fillValue: String = "FF"): Result<BatchOperationResult> {
        return withContext(Dispatchers.Default) {
            try {
                val operation = BatchOperation(
                    operationId = System.currentTimeMillis().toString(),
                    operationType = BatchOperationType.FILL_SECTORS,
                    targetSectors = sectorList,
                    parameters = mapOf("fill_value" to fillValue)
                )
                executeBatchOperation(operation)
            } catch (e: Exception) {
                Timber.e(e, "填充扇区失败")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 备份密钥
     */
    suspend fun backupKeys(sectorList: List<Int>): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val result = ffiBridge?.callAttr("backup_keys", sectorList.joinToString(","))
                val jsonStr = result?.toString() ?: "{}"
                val json = JSONObject(jsonStr)
                
                if (json.optBoolean("success", false)) {
                    val backup = json.optString("backup_data", "")
                    Timber.i("密钥备份成功: ${sectorList.size} 个扇区")
                    Result.success(backup)
                } else {
                    Result.failure(Exception(json.optString("error", "备份失败")))
                }
            } catch (e: Exception) {
                Timber.e(e, "密钥备份失败")
                Result.failure(e)
            }
        }
    }
}
