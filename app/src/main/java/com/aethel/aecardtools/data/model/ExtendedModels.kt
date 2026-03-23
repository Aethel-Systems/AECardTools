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

package com.aethel.aecardtools.data.model

import java.io.Serializable

// ============================================================
// === LCOW 事务与垃圾回收模型
// ============================================================

/**
 * 物理块信息
 */
data class PhysicalBlock(
    val sector: Int,
    val block: Int,
    val wearCount: Int = 0,
    val isFree: Boolean = true,
    val data: String? = null
) : Serializable

/**
 * 虚拟地址空间映射
 */
data class VirtualAddressMapping(
    val virtualAddress: Int,
    val physicalSector: Int,
    val physicalBlock: Int,
    val isValid: Boolean = true,
    val lastModified: Long = System.currentTimeMillis()
) : Serializable

/**
 * LCOW 事务状态
 */
enum class TransactionState {
    PENDING,
    COMMITTED,
    ABORTED,
    ROLLED_BACK
}

/**
 * LCOW 事务
 */
data class LCOWTransaction(
    val transactionId: String,
    val virtualAddress: Int,
    val oldPhysicalLocation: Pair<Int, Int>,  // (sector, block)
    val newPhysicalLocation: Pair<Int, Int>,  // (sector, block)
    val timestamp: Long = System.currentTimeMillis(),
    val state: TransactionState = TransactionState.PENDING,
    val dataPayload: String = "",
    val merkleHash: String = ""
) : Serializable

/**
 * 位图状态（用于 GC）
 */
data class BitmapStatus(
    val usedBlocks: Int,
    val freeBlocks: Int,
    val totalBlocks: Int,
    val fragmentation: Double,  // 碎片化率 (0.0-1.0)
    val estimatedWearPerBlock: Map<Pair<Int, Int>, Int>,  // (sector, block) -> wear count
    val recommendGCClean: Boolean  // 是否建议进行 GC
) : Serializable

/**
 * 存储管理页签数据
 */
data class StorageManagementData(
    val cardUid: String,
    val bitmap: BitmapStatus,
    val transactions: List<LCOWTransaction>,
    val lastGCTime: Long? = null,
    val totalGCCount: Int = 0,
    val physicalBlocks: List<PhysicalBlock> = emptyList()
) : Serializable

// ============================================================
// === 审计摘要与性能监控模型
// ============================================================

/**
 * 操作统计
 */
data class OperationStatistics(
    val type: String,
    val count: Int,
    val successCount: Int,
    val failureCount: Int,
    val averageDuration: Double,  // 毫秒
    val minDuration: Double,
    val maxDuration: Double
) : Serializable

/**
 * 审计会话摘要
 */
data class AuditSummary(
    val sessionId: String,
    val totalOperations: Int,
    val successfulOperations: Int,
    val failedOperations: Int,
    val successRate: Double,  // 百分比
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val operationBreakdown: Map<String, OperationStatistics> = emptyMap()
) : Serializable

/**
 * 性能指标
 */
data class PerformanceMetrics(
    val readOperations: OperationStatistics? = null,
    val writeOperations: OperationStatistics? = null,
    val authOperations: OperationStatistics? = null,
    val overallAverageDuration: Double = 0.0,
    val systemUptime: Long = 0,
    val sampleCount: Int = 0
) : Serializable

/**
 * 性能仪表盘数据
 */
data class PerformanceDashboardData(
    val averageSeekTime: Double,  // 毫秒
    val authSuccessRate: Double,  // 百分比
    val readAverageLatency: Double,
    val writeAverageLatency: Double,
    val metrics: PerformanceMetrics,
    val lastUpdate: Long = System.currentTimeMillis()
) : Serializable

// ============================================================
// === 卡片厂商深度识别模型
// ============================================================

/**
 * 厂商信息
 */
data class VendorInfo(
    val vendorName: String,
    val vendorCode: String,
    val isClone: Boolean,
    val confidence: Double  // 0.0-1.0
) : Serializable

/**
 * 硬件指纹
 */
data class HardwareFingerprint(
    val uid: String,
    val sak: String,
    val atqa: String,
    val bcc: String,
    val bccValid: Boolean,
    val vendor: VendorInfo,
    val totalSectors: Int,
    val totalSizeBytes: Int,
    val userAreaBytes: Int,
    val atqaInterpretation: String,
    val sakInterpretation: String,
    val detectionTimestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * 卡片深度识别结果
 */
data class CardForensicsResult(
    val cardUid: String,
    val fingerprint: HardwareFingerprint,
    val isAuthentic: Boolean,
    val forensicsReport: String,
    val riskLevel: RiskLevel = RiskLevel.UNKNOWN
) : Serializable

enum class RiskLevel {
    UNKNOWN,
    SAFE,
    WARNING,
    CRITICAL
}

// ============================================================
// === AEFS 镜像构建器模型
// ============================================================

/**
 * 文件元数据
 */
data class FileMetadata(
    val fileName: String,
    val fileSize: Long,
    val fileMimeType: String,
    val fileHash: String,  // SHA-256
    val uploadTime: Long = System.currentTimeMillis()
) : Serializable

/**
 * AEFS 镜像详情
 */
data class AEFSImageDetails(
    val poolId: String,
    val volId: String,
    val alias: String,
    val sipLevel: SIPLevel,
    val encryptedPayload: String,
    val payloadSize: Int,
    val merkleRoot: String,
    val merkleProof: List<String> = emptyList(),
    val mac: String,
    val streamSize: Int,
    val fileMetadata: List<FileMetadata> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
) : Serializable

/**
 * 镜像构建结果
 */
data class ImageBuildResult(
    val success: Boolean,
    val imageData: AEFSImageDetails? = null,
    val error: String? = null,
    val buildDuration: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

// ============================================================
// === 数据处理向导模型
// ============================================================

/**
 * 批量操作类型
 */
enum class BatchOperationType {
    CLEAR_SECTORS,
    FILL_SECTORS,
    BACKUP_KEYS,
    RESTORE_KEYS,
    CLONE_SECTOR_DATA,
    VERIFY_CHECKSUM,
    UPDATE_ACL
}

/**
 * 批量操作
 */
data class BatchOperation(
    val operationId: String,
    val operationType: BatchOperationType,
    val targetSectors: List<Int>,
    val parameters: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null
) : Serializable

/**
 * 批量操作结果
 */
data class BatchOperationResult(
    val operationId: String,
    val operationType: BatchOperationType,
    val success: Boolean,
    val processedCount: Int,
    val failureCount: Int,
    val details: Map<Int, String>,  // sector -> status message
    val totalDuration: Long,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * 数据向导会话
 */
data class DataWizardSession(
    val sessionId: String,
    val cardUid: String,
    val operations: List<BatchOperation> = emptyList(),
    val results: List<BatchOperationResult> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis()
) : Serializable

// ============================================================
// === AEFS 格式化要素模型
// ============================================================

/**
 * AEFS 初始化参数
 */
data class AEFSInitializationParams(
    val cardAlias: String,
    val sipLevel: SIPLevel = SIPLevel.ARCHITECT,
    val encryptionPassword: String = "",
    val createBackup: Boolean = true,
    val plaintextMode: Boolean = false,
    val allowTruncation: Boolean = false,
    val recordTypeLabel: String = "INIT"
) : Serializable

/**
 * AEFS 格式化步骤
 */
enum class FormatStep {
    AUTH,
    PREPARE_PAYLOAD,
    WIPE_SECTORS,
    WRITE_TILED_PAYLOAD,
    WRITE_ANCHOR,
    SAVE_KEYVAULT,
    COMMIT_SEQUENCE,
    FINALIZE
}

/**
 * AEFS 扇区 0 定义
 */
data class AEFSSector0(
    val magicUid: String = "41454653",  // "AEFS"
    val bcc: String = "11",
    val version: String = "60",
    val transactionSequence: Long = 0L,
    val recordTypeIndex: String = "",
    val aliasHash: String = "",
    val saltHex: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * AEFS 格式化结果
 */
data class AEFSFormattingResult(
    val success: Boolean,
    val cardUid: String,
    val alias: String,
    val sector0Data: AEFSSector0? = null,
    val error: String? = null,
    val completedSteps: List<FormatStep> = emptyList(),
    val failedStep: FormatStep? = null,
    val duration: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

data class AEFSMountedPayload(
    val cardUid: String,
    val alias: String,
    val recordType: String,
    val transactionSequence: Long,
    val payloadDocumentJson: String,
    val originalSize: Int = 0,
    val compressedSize: Int = 0,
    val integrityMessage: String = "",
    val rawFileName: String? = null,
    val rawFileMimeType: String? = null,
    val rawFileSize: Int? = null,
    val rawFileHex: String? = null
) : Serializable

data class AEFSRecoveryCandidate(
    val packageState: String,
    val cardUid: String,
    val physicalUid: String,
    val aliasHash: String,
    val transactionSequence: Long,
    val createdAt: Long,
    val matchReason: String,
    val sector15AuthSuccess: Boolean,
    val sector0AuthSuccess: Boolean,
    val observedTransactionSequence: Long? = null,
    val snapshotAvailable: Boolean = false,
    val rootKeySource: String,
    val recommended: Boolean,
    val message: String
) : Serializable

data class AEFSRecoveryDiagnostic(
    val physicalUid: String,
    val presentedCardUid: String,
    val summary: String,
    val recoverable: Boolean,
    val recommendedAliasHash: String? = null,
    val candidates: List<AEFSRecoveryCandidate> = emptyList()
) : Serializable

/**
 * BCC 计算结果
 */
data class BCCCalculationResult(
    val byte0: String,
    val byte1: String,
    val byte2: String,
    val byte3: String,
    val calculatedBcc: String,
    val isValid: Boolean,
    val xorExpression: String  // 用于显示计算过程
) : Serializable
