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

/**
 * 卡片类型枚举
 */
enum class CardType {
    CLASSIC_1K,
    CLASSIC_4K,
    ULTRALIGHT,
    ULTRALIGHT_C,
    NTAG,
    PLUS_2K,
    PLUS_4K,
    UNKNOWN
}

fun CardType.isUltralightLike(): Boolean {
    return this == CardType.ULTRALIGHT ||
        this == CardType.ULTRALIGHT_C ||
        this == CardType.NTAG
}

/**
 * 认证状态枚举
 */
enum class AuthStatus {
    NOT_ATTEMPTED,
    SUCCESS_A,
    SUCCESS_B,
    FAILED
}

/**
 * NFC 状态枚举
 */
enum class NFCStatus {
    UNKNOWN,
    DISABLED,
    ENABLED_NO_CARD,
    CARD_DETECTED,
    NOT_SUPPORTED
}

/**
 * AEFS SIP 级别
 */
enum class SIPLevel {
    SANDBOX,
    ARCHITECT,
    SOVEREIGN,
    CUSTOM
}

/**
 * 块数据
 */
data class BlockData(
    val sector: Int,
    val block: Int,
    val data: String,  // hex 字符串
    val isReadable: Boolean = true,
    val isWritable: Boolean = true
) : Serializable

/**
 * 扇区数据
 */
data class SectorData(
    val sector: Int,
    val blocks: List<BlockData>,
    val keyA: String,  // hex
    val keyB: String,  // hex
    val authStatus: AuthStatus,
    val accessBits: String  // hex
) : Serializable

/**
 * 卡片身份信息
 */
data class CardIdentity(
    val uid: String,
    val sak: String,
    val atqa: String,
    val cardType: CardType,
    val sectorCount: Int,
    val isAEFS: Boolean = false,
    val detectedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * 卡片快照
 */
data class CardSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val cardIdentity: CardIdentity,
    val sectors: Map<Int, SectorData>,
    val metadata: Map<String, String> = emptyMap()
) : Serializable

/**
 * 操作日志条目
 */
data class OperationLogEntry(
    val type: String,  // READ, WRITE, AUTH, etc.
    val sector: Int,
    val block: Int,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * AEFS 镜像数据
 */
data class AEFSImageData(
    val poolId: String,
    val volId: String,
    val alias: String,
    val sipLevel: SIPLevel,
    val encryptedPayload: String,
    val merkleRoot: String,
    val mac: String,
    val streamSize: Int
) : Serializable

/**
 * 最近卡片历史条目
 */
data class RecentCardHistory(
    val cardIdentity: CardIdentity,
    val alias: String = "",  // 用户自定义别名
    val aefsModeTag: String = "",  // AEFS标签，如"AEFS v5.0"或"Classic"
    val firstDetectedAt: Long = System.currentTimeMillis(),
    val lastDetectedAt: Long = System.currentTimeMillis(),
    val detectionCount: Int = 1,
    val metadata: Map<String, String> = emptyMap()
) : Serializable
