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

import android.nfc.Tag
import timber.log.Timber

/**
 * 原始NFC收发接口 (Raw Transceive Interface)
 * 提供底层协议无关的字节级收发能力
 * 
 * 实现原理：
 * - 不依赖高级API (如MifareClassic)的解析逻辑
 * - 直接调用android.nfc.tech中的原始transceive()
 * - 支持魔术卡、非标卡等所有协议层支持的卡片
 */
interface RawTransceiveProvider {
    
    /**
     * 建立卡片连接
     * @return 连接是否成功
     */
    suspend fun connect(): Boolean
    
    /**
     * 断开卡片连接
     */
    fun disconnect()
    
    /**
     * 发送原始指令并接收响应
     * @param command 原始指令字节数组
     * @param timeout 操作超时时间(ms)，0表示无超时
     * @return 收发结果
     */
    suspend fun transceive(command: ByteArray, timeout: Long = 5000): RawTransceiveResult
    
    /**
     * 批量发送指令
     * @param commands 指令序列
     * @param delayBetweenMs 指令间延迟(ms)
     * @return 所有响应结果
     */
    suspend fun transceiveBatch(
        commands: List<ByteArray>,
        delayBetweenMs: Long = 100
    ): List<RawTransceiveResult>
    
    /**
     * 获取该协议的最大传输单元 (MTU)
     */
    fun getMaxTransceiveLength(): Int
    
    /**
     * 判断连接状态
     */
    fun isConnected(): Boolean
}

/**
 * NFC 协议处理器基类
 */
abstract class NFCProtocolHandler(
    protected val tag: Tag,
    protected val protocolType: NFCProtocolType
) {
    protected val TAG = "NFCProtocolHandler"
    
    /**
     * 初始化协议处理器
     */
    abstract suspend fun initialize(): Boolean
    
    /**
     * 清理资源
     */
    abstract fun cleanup()
    
    /**
     * 获取原始收发提供者
     */
    abstract fun getRawTransceiveProvider(): RawTransceiveProvider
    
    /**
     * 获取操作日志
     */
    protected val instructionHistory = mutableListOf<InstructionHistoryEntry>()
    
    /**
     * 记录指令执行
     */
    protected fun logInstruction(
        command: ByteArray,
        response: ByteArray,
        success: Boolean,
        elapsed: Long
    ) {
        instructionHistory.add(
            InstructionHistoryEntry(
                protocol = protocolType,
                sentCommand = command.toHexString(),
                receivedResponse = response.toHexString(),
                success = success,
                elapsedTimeMs = elapsed
            )
        )
    }
    
    fun getOperationLog(): List<InstructionHistoryEntry> = instructionHistory.toList()
    
    fun clearOperationLog() {
        instructionHistory.clear()
    }
}

/**
 * CPU卡 (IsoDep) 协议处理器
 * 
 * 支持：
 * - EMV (银行卡)
 * - ePassport (护照)
 * - JCOP Java卡
 * - SIM 卡
 * - 交通部互联互通卡
 * 
 * 核心功能：APDU隧道 (无内置逻辑)
 */
interface IsoDEPHandler : RawTransceiveProvider {
    
    /**
     * 获取卡片ATR (Answer To Reset)
     * @return ATR字节数组
     */
    fun getATR(): ByteArray?
    
    /**
     * 是否支持扩展长度APDU
     * 对于长指令(>261字节)或长响应的操作至关重要
     */
    fun isExtendedLengthApduSupported(): Boolean
    
    /**
     * 发送APDU命令 (高级封装)
     * @param apdu APDU字节数组
     * @return APDU响应对象
     */
    suspend fun sendAPDU(apdu: ByteArray): APDUResponse
    
    /**
     * 发送Select AID命令
     * @param aid 应用标识符 (Hex字符串)
     * @return 命令响应
     */
    suspend fun selectAID(aid: String): APDUResponse
    
    /**
     * 发送Get Response命令 (用于处理61xx状态字)
     * @param length 要获取的长度
     */
    suspend fun getResponse(length: Int): APDUResponse
}

/**
 * NFC Type A (NfcA) 协议处理器
 * 
 * 支持：
 * - MIFARE Classic (1K/4K/非标卡)
 * - MIFARE Ultralight/Ultralight C
 * - NTAG
 * - 魔术卡 (Gen 1a/Gen 2/FUID/UFUID)
 * 
 * 核心特性：
 * - 底层后门指令透传
 * - 非标参数读取 (ATQA/SAK)
 * - Block级别的直接读写
 */
interface NfcAHandler : RawTransceiveProvider {
    
    /**
     * 获取卡片 ATQA (Answer To Request, Sense Response)
     */
    fun getATQA(): ByteArray
    
    /**
     * 获取卡片 SAK (Select Acknowledge, Sense Response)
     */
    fun getSAK(): Byte
    
    /**
     * 直接读取块数据 (无认证)
     * 注意：对于标准M1卡可能会失败，但对魔术卡有效
     */
    suspend fun readBlock(blockNum: Int): MifareBlockResult
    
    /**
     * 直接写入块数据 (无认证)
     * 用于魔术卡Block 0改写等高风险操作
     */
    suspend fun writeBlock(blockNum: Int, data: ByteArray): MifareBlockResult
    
    /**
     * 进入后门模式 (魔术卡 Gen 1a)
     * 发送 0x40 (Gen1a停止) 或 0x43 (Gen2解锁)
     */
    suspend fun triggerMagicBackdoor(commandByte: Byte = 0x43): Boolean
    
    /**
     * MIFARE认证 (KeyA/KeyB)
     * @param sector 扇区号
     * @param key 认证密钥 (6字节)
     * @param useKeyA true使用KeyA，false使用KeyB
     */
    suspend fun authenticateMifareClassic(
        sector: Int,
        key: ByteArray,
        useKeyA: Boolean = true
    ): Boolean
}

/**
 * NFC Type B (NfcB) 协议处理器
 * 支持 ISO 14443-3B (二代证底层、老式门禁)
 */
interface NfcBHandler : RawTransceiveProvider {
    
    /**
     * 获取卡片 ATQB 响应
     */
    fun getATQB(): ByteArray?
    
    /**
     * 发送Type B格式的命令
     */
    suspend fun sendTypeB(command: ByteArray): RawTransceiveResult
}

/**
 * NFC F (FeliCa) 协议处理器
 * 支持 Sony FeliCa 系统和日系卡片
 */
interface NfcFHandler : RawTransceiveProvider {
    
    /**
     * 获取卡片系统编码 (System Code)
     */
    fun getSystemCode(): ByteArray?
    
    /**
     * 发送FeliCa命令
     */
    suspend fun sendFeliCa(command: ByteArray): RawTransceiveResult
}

/**
 * NFC V (同步卡，ISO 15693) 协议处理器
 * 支持物流标签和工业卡片
 */
interface NfcVHandler : RawTransceiveProvider {
    
    /**
     * 获取卡片DSF (Data Storage Format)
     */
    fun getDSF(): Byte
    
    /**
     * 发送ISO 15693格式命令
     */
    suspend fun sendISO15693Command(command: ByteArray): RawTransceiveResult
}

/**
 * NFC 协议检测器
 * 
 * 作用：
 * 1. 分析Tag.getTechList()返回的所有技术
 * 2. 推断卡片类型和最佳处理策略
 * 3. 构建对应的协议处理器
 * 4. 智能路由到相应的UI界面
 */
class NFCProtocolDetector(private val tag: Tag) {
    
    fun detect(): DetectionResult {
        val techList = tag.techList?.toList() ?: emptyList()
        Timber.i("检测到技术列表: ${techList.joinToString(", ") { it }}") 
        
        val supportedProtocols = mutableListOf<NFCProtocolType>()
        var primaryProtocol = NFCProtocolType.UNKNOWN
        var mifareSubType = MifareCardSubType.UNKNOWN_MIFARE
        
        for (tech in techList.toList()) {
            when (tech) {
                "android.nfc.tech.IsoDep" -> {
                    supportedProtocols.add(NFCProtocolType.ISO_DEP)
                    if (primaryProtocol == NFCProtocolType.UNKNOWN) {
                        primaryProtocol = NFCProtocolType.ISO_DEP
                    }
                }
                "android.nfc.tech.NfcA" -> {
                    supportedProtocols.add(NFCProtocolType.NFC_A)
                    if (primaryProtocol == NFCProtocolType.UNKNOWN) {
                        primaryProtocol = NFCProtocolType.NFC_A
                    }
                    // 尝试推断MIFARE子类型
                    mifareSubType = detectMifareSubType()
                }
                "android.nfc.tech.MifareClassic" -> {
                    // 如果系统识别为MifareClassic，记录但仍使用NfcA处理
                    Timber.i("系统识别为MifareClassic")
                }
                "android.nfc.tech.NfcB" -> {
                    supportedProtocols.add(NFCProtocolType.NFC_B)
                }
                "android.nfc.tech.NfcF" -> {
                    supportedProtocols.add(NFCProtocolType.NFC_F)
                }
                "android.nfc.tech.NfcV" -> {
                    supportedProtocols.add(NFCProtocolType.NFC_V)
                }
            }
        }
        
        return DetectionResult(
            primaryProtocol = primaryProtocol,
            supportedProtocols = supportedProtocols,
            mifareSubType = mifareSubType,
            isCPUCard = supportedProtocols.contains(NFCProtocolType.ISO_DEP),
            isStandardMifare = "android.nfc.tech.MifareClassic" in techList
        )
    }
    
    private fun detectMifareSubType(): MifareCardSubType {
        // 详细的MIFARE子类型检测逻辑
        // 在实际NfcA连接建立后可以进行更精确的判断
        // 这里返回UNKNOWN，由NfcAHandler在运行时精化
        return MifareCardSubType.UNKNOWN_MIFARE
    }
    
    /**
     * 协议检测结果
     */
    data class DetectionResult(
        val primaryProtocol: NFCProtocolType,
        val supportedProtocols: List<NFCProtocolType>,
        val mifareSubType: MifareCardSubType,
        val isCPUCard: Boolean,
        val isStandardMifare: Boolean
    )
}
