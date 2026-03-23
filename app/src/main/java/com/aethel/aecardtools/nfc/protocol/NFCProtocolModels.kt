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

package com.aethel.aecardtools.nfc.protocol

import java.io.Serializable

/**
 * NFC 协议类型枚举 (ISO/IEC 标准)
 */
enum class NFCProtocolType {
    /** IsoDep - ISO 14443-4 (CPU卡/金融卡/交通联合) */
    ISO_DEP,
    
    /** NfcA - ISO 14443-3A (MIFARE Classic/Ultralight/NTAG/魔术卡) */
    NFC_A,
    
    /** NfcB - ISO 14443-3B (二代证/老式门禁) */
    NFC_B,
    
    /** NfcV - ISO 15693 (物流标签) */
    NFC_V,
    
    /** NfcF - FeliCa (日系卡片系统) */
    NFC_F,
    
    /** 未知协议 */
    UNKNOWN
}

/**
 * MIFARE 卡片子类型 (NfcA 下的细分)
 */
enum class MifareCardSubType {
    /** MIFARE Classic 1K */
    CLASSIC_1K,
    
    /** MIFARE Classic 4K */
    CLASSIC_4K,
    
    /** MIFARE Classic Gen 1a (魔术卡 - 支持后门) */
    MAGIC_GEN_1A,
    
    /** MIFARE Classic Gen 2 (CUID - Block 0可写) */
    MAGIC_GEN_2_CUID,
    
    /** MIFARE Classic FUID (一次写入) */
    MAGIC_FUID,
    
    /** MIFARE Classic UFUID (超级一次写入) */
    MAGIC_UFUID,
    
    /** MIFARE Ultralight */
    ULTRALIGHT,
    
    /** MIFARE Ultralight C */
    ULTRALIGHT_C,
    
    /** NTAG (NXP标签) */
    NTAG,
    
    /** 非标卡或未知MIFARE变种 */
    UNKNOWN_MIFARE
}

/**
 * NFC 控制器厂商推断结果
 */
enum class NFCControllerVendor {
    /** NXP (SN100/SN200系列, 最佳兼容性) */
    NXP,
    
    /** Broadcom */
    BROADCOM,
    
    /** ST Microelectronics */
    ST_MICRO,
    
    /** 其他/未知 */
    OTHER
}

/**
 * NFC 硬件能力级别
 */
enum class NFCCapabilityLevel {
    /** 最低级：仅支持IsoDep和NfcA基本操作 */
    BASIC,
    
    /** 标准级：支持所有协议但传输长度受限 */
    STANDARD,
    
    /** 高级：支持扩展APDU和非标卡 */
    ADVANCED,
    
    /** 旗舰级：完全支持所有功能 */
    FLAGSHIP
}

/**
 * CPU卡认证状态
 */
enum class IsoDEPAuthStatus {
    NOT_ATTEMPTED,
    ATR_RETRIEVED,
    SELECT_AID_SUCCESS,
    SELECT_AID_FAILED,
    LOCKED_BY_ATTEMPTS
}

/**
 * MIFARE Classic 认证结果
 */
enum class MifareAuthResult {
    /** 使用Key A认证成功 */
    SUCCESS_KEY_A,
    
    /** 使用Key B认证成功 */
    SUCCESS_KEY_B,
    
    /** 认证失败 */
    FAILED,
    
    /** 未尝试 */
    NOT_ATTEMPTED,
    
    /** 通过后门进入 (魔术卡) */
    BACKDOOR_ENTERED
}

/**
 * APDU 命令响应
 */
data class APDUResponse(
    /** 响应数据 (不含状态字) */
    val data: ByteArray = byteArrayOf(),
    
    /** 状态字 SW1 */
    val sw1: Byte = 0x00,
    
    /** 状态字 SW2 */
    val sw2: Byte = 0x00,
    
    /** 是否执行成功 (SW == 9000) */
    val isSuccess: Boolean = false,
    
    /** 错误描述信息 */
    val errorDescription: String = ""
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is APDUResponse) return false
        if (!data.contentEquals(other.data)) return false
        if (sw1 != other.sw1) return false
        if (sw2 != other.sw2) return false
        if (isSuccess != other.isSuccess) return false
        if (errorDescription != other.errorDescription) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sw1
        result = 31 * result + sw2
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + errorDescription.hashCode()
        return result
    }

    fun toHexString(): String = "SW: %02X%02X | Data: ${data.toHexString()}".format(sw1, sw2)
}

/**
 * MIFARE Classic 块操作结果
 */
data class MifareBlockResult(
    /** 块号 */
    val blockNum: Int,
    /** 块中的数据 */
    val data: ByteArray = byteArrayOf(),
    /** 是否成功读取/写入 */
    val isSuccess: Boolean = false,
    /** 错误信息 */
    val errorMessage: String? = null
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MifareBlockResult) return false
        if (blockNum != other.blockNum) return false
        if (!data.contentEquals(other.data)) return false
        if (isSuccess != other.isSuccess) return false
        if (errorMessage != other.errorMessage) return false
        return true
    }

    override fun hashCode(): Int {
        var result = blockNum
        result = 31 * result + data.contentHashCode()
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

/**
 * 原始NFC收发操作结果
 */
data class RawTransceiveResult(
    /** 应答数据 */
    val response: ByteArray = byteArrayOf(),
    /** 操作是否成功 */
    val isSuccess: Boolean = false,
    /** 异常信息 */
    val exception: Exception? = null,
    /** 执行耗时 (毫秒) */
    val elapsedTimeMs: Long = 0L
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTransceiveResult) return false
        if (!response.contentEquals(other.response)) return false
        if (isSuccess != other.isSuccess) return false
        if (exception != other.exception) return false
        if (elapsedTimeMs != other.elapsedTimeMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = response.contentHashCode()
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + (exception?.hashCode() ?: 0)
        result = 31 * result + elapsedTimeMs.hashCode()
        return result
    }
}

/**
 * NFC硬件能力探测结果
 */
data class NFCCapabilityProbeResult(
    /** 推断的控制器厂商 */
    val vendorLikelihood: NFCControllerVendor = NFCControllerVendor.OTHER,
    
    /** 最大传输长度 (字节) */
    val maxTransceiveLength: Int = 257,
    
    /** 是否支持扩展APDU */
    val supportsExtendedAPDU: Boolean = false,
    
    /** 设备型号 */
    val deviceModel: String = "Unknown",
    
    /** MIFARE Classic支持情况 */
    val supportsMifareClassic: Boolean = true,
    
    /** 非标卡支持概率评分 (0-100) */
    val nonStandardCardSupportScore: Int = 50,
    
    /** 能力级别分类 */
    val capabilityLevel: NFCCapabilityLevel = NFCCapabilityLevel.STANDARD,
    
    /** 详细能力描述 */
    val detailedCapabilities: Map<String, String> = emptyMap()
) : Serializable

/**
 * 卡片身份信息 (扩展) - 包含所有支持的协议
 */
data class ExtendedCardIdentity(
    /** 卡片UID */
    val uid: String,
    
    /** SAK (Select Acknowledge) */
    val sak: String,
    
    /** ATQA (Answer to Request) */
    val atqa: String,
    
    /** 检测到的协议列表 */
    val supportedProtocols: List<NFCProtocolType> = emptyList(),
    
    /** MIFARE子类型 (如果是MIFARE) */
    val mifareSubType: MifareCardSubType = MifareCardSubType.UNKNOWN_MIFARE,
    
    /** CPU卡认证状态 */
    val isoDEPAuthStatus: IsoDEPAuthStatus = IsoDEPAuthStatus.NOT_ATTEMPTED,
    
    /** MIFARE认证结果 */
    val mifareAuthResult: MifareAuthResult = MifareAuthResult.NOT_ATTEMPTED,
    
    /** ATR (Answer To Reset, CPU卡) */
    val atr: String? = null,
    
    /** 检测时间戳 */
    val detectedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * 敏感指令执行规则
 */
data class SensitiveInstructionRule(
    /** 指令类别 */
    val category: String,  // "CPU_CARD_PIN", "MIFARE_BLOCK0", etc.
    
    /** 指令模式匹配 */
    val instructionPattern: String,  // APDU命令前缀或特定字节
    
    /** 危险等级: HIGH, MEDIUM, LOW */
    val riskLevel: String = "MEDIUM",
    
    /** 是否默认启用拦截 */
    val enabledByDefault: Boolean = true,
    
    /** 拦截规则描述 */
    val description: String = ""
) : Serializable

/**
 * 指令执行历史记录
 */
data class InstructionHistoryEntry(
    /** 协议类型 */
    val protocol: NFCProtocolType,
    
    /** 发送的指令 (Hex) */
    val sentCommand: String,
    
    /** 接收的响应 (Hex) */
    val receivedResponse: String,
    
    /** 执行是否成功 */
    val success: Boolean,
    
    /** 执行耗时 (ms) */
    val elapsedTimeMs: Long = 0L,
    
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

// ==================== 工具函数 ====================

/**
 * ByteArray 转 Hex 字符串
 */
fun ByteArray.toHexString(separator: String = " "): String {
    return this.joinToString(separator) { byte ->
        "%02X".format(byte)
    }
}

/**
 * Hex 字符串转 ByteArray
 */
fun String.hexToByteArray(): ByteArray {
    val cleanHex = this.replace(" ", "").replace("\n", "")
    return ByteArray(cleanHex.length / 2) { i ->
        cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * 解析APDU状态字
 */
fun parseAPDUStatusWord(sw1: Byte, sw2: Byte): Pair<String, String> {
    val sw = (sw1.toInt() shl 8) or (sw2.toInt() and 0xFF)
    val isSuccess = (sw1.toInt() and 0x60) == 0x60 && sw == 0x9000
    
    val description = when {
        sw == 0x9000 -> "Success"
        (sw1.toInt() and 0xF0) == 0x60 -> "Security-related error"
        (sw1.toInt() and 0xF0) == 0x90 -> {
            when (sw2.toInt()) {
                0x00 -> "Success"
                else -> "Card-specific error"
            }
        }
        (sw1.toInt() and 0x10) != 0 -> "More data available"
        else -> "Unknown error"
    }
    
    return Pair(isSuccess.toString(), description)
}

/**
 * BCC (Block Check Character) 校验位计算
 * 用于MIFARE Classic Block 0校验
 */
fun calculateMifareBlockCheckCharacter(uid0: Byte, uid1: Byte, uid2: Byte, uid3: Byte): Byte {
    return (uid0.toInt() xor uid1.toInt() xor uid2.toInt() xor uid3.toInt()).toByte()
}
