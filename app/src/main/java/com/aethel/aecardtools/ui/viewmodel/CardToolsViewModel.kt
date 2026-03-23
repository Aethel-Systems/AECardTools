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

package com.aethel.aecardtools.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aethel.aecardtools.data.model.*
import com.aethel.aecardtools.data.repository.CardRepository
import com.aethel.aecardtools.data.CardSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * UI 状态数据类
 */
data class UIState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val currentCard: CardIdentity? = null,
    val sectors: Map<Int, SectorData> = emptyMap(),
    val operationLogs: List<OperationLogEntry> = emptyList(),
    val viewMode: ViewMode = ViewMode.HEX_CLASSIC,
    val recentCards: List<RecentCardHistory> = emptyList(),
    val nfcStatus: NFCStatus = NFCStatus.UNKNOWN,
    val nfcIsScanning: Boolean = false,
    val currentActionMode: String = "",  // 当前操作模式：write, read, home_scan, read_scan
    val isToolkitMode: Boolean = false,  // 《重要修复》HomeScreen 的双态视图模式（识别模式 vs 工具模式）
    val ultralightPages: Map<Int, String> = emptyMap(),
    val ultralightReadWindows: Map<Int, String> = emptyMap(),
    val pendingAefsUnlockCardUid: String? = null
)

/**
 * 视图模式枚举
 */
enum class ViewMode {
    HEX_CLASSIC,      // 传统 MCT hex 视图
    AEFS_SEMANTIC,    // AEFS 语义视图
    DUAL_PANE,        // 双窗格
    RAW_TERMINAL      // 原始命令终端
}

/**
 * 主 ViewModel
 */
class CardToolsViewModel(private val context: Context) : ViewModel() {
    
    internal val repository = CardRepository(context)
    private val sharedPrefs = context.getSharedPreferences("aecardtools_recent_cards", Context.MODE_PRIVATE)
    
    // 《重要修复》注入全局会话管理器
    private val sessionManager = CardSessionManager.getInstance()
    
    // UI 状态
    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()
    
    // 操作日志
    private val _operationLogs = MutableStateFlow<List<OperationLogEntry>>(emptyList())
    val operationLogs: StateFlow<List<OperationLogEntry>> = _operationLogs.asStateFlow()
    
    // 当前选中的扇区
    private val _selectedSector = MutableStateFlow<Int?>(null)
    val selectedSector: StateFlow<Int?> = _selectedSector.asStateFlow()
    
    init {
        Timber.i("CardToolsViewModel 已初始化")
        loadRecentCards()
        
        // 《重要修复》监听全局会话事件，旁路任何状态不同步的风险
        viewModelScope.launch {
            sessionManager.currentSession.collect { session ->
                val currentState = _uiState.value
                val preserveUltralightState = currentState.currentCard?.uid != null &&
                    currentState.currentCard?.uid == session?.cardIdentity?.uid
                if (session != null) {
                    _uiState.value = _uiState.value.copy(
                        currentCard = session.cardIdentity,
                        sectors = session.sectors,
                        operationLogs = session.operationLogs,
                        ultralightPages = if (preserveUltralightState) currentState.ultralightPages else emptyMap(),
                        ultralightReadWindows = if (preserveUltralightState) currentState.ultralightReadWindows else emptyMap()
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        currentCard = null,
                        sectors = emptyMap(),
                        operationLogs = emptyList(),
                        ultralightPages = emptyMap(),
                        ultralightReadWindows = emptyMap()
                    )
                }
            }
        }
        
        // 监听NFC状态
        viewModelScope.launch {
            sessionManager.nfcStatus.collect { status ->
                _uiState.value = _uiState.value.copy(nfcStatus = status)
            }
        }
    }
    
    /**
     * 从SharedPreferences加载最近卡片
     */
    private fun loadRecentCards() {
        viewModelScope.launch {
            try {
                val allKeys = sharedPrefs.all.keys
                val recentCards = mutableListOf<RecentCardHistory>()
                
                for (uid in allKeys) {
                    val jsonStr = sharedPrefs.getString(uid, null) ?: continue
                    try {
                        val json = org.json.JSONObject(jsonStr)
                        val cardIdentity = CardIdentity(
                            uid = json.getString("uid"),
                            sak = json.getString("sak"),
                            atqa = json.getString("atqa"),
                            cardType = CardType.valueOf(json.getString("cardType")),
                            sectorCount = json.getInt("sectorCount"),
                            isAEFS = json.getBoolean("isAEFS"),
                            detectedAt = json.getLong("detectedAt")
                        )
                        
                        val history = RecentCardHistory(
                            cardIdentity = cardIdentity,
                            alias = json.optString("alias", ""),
                            aefsModeTag = json.optString("aefsModeTag", ""),
                            firstDetectedAt = json.getLong("firstDetectedAt"),
                            lastDetectedAt = json.getLong("lastDetectedAt"),
                            detectionCount = json.getInt("detectionCount")
                        )
                        
                        recentCards.add(history)
                    } catch (e: Exception) {
                        Timber.e(e, "加载最近卡片失败: $uid")
                    }
                }
                
                // 按最近检测时间排序
                recentCards.sortByDescending { it.lastDetectedAt }
                
                _uiState.value = _uiState.value.copy(recentCards = recentCards.take(10))
                Timber.i("已加载 ${recentCards.size} 张最近卡片")
            } catch (e: Exception) {
                Timber.e(e, "加载最近卡片异常")
            }
        }
    }
    
    /**
     * 保存最近卡片
     */
    private fun saveRecentCard(cardIdentity: CardIdentity) {
        try {
            val uid = cardIdentity.uid
            val existingJson = sharedPrefs.getString(uid, null)
            
            val json = if (existingJson != null) {
                val obj = org.json.JSONObject(existingJson)
                obj.put("lastDetectedAt", System.currentTimeMillis())
                obj.put("detectionCount", obj.getInt("detectionCount") + 1)
                obj
            } else {
                org.json.JSONObject().apply {
                    put("uid", cardIdentity.uid)
                    put("sak", cardIdentity.sak)
                    put("atqa", cardIdentity.atqa)
                    put("cardType", cardIdentity.cardType.name)
                    put("sectorCount", cardIdentity.sectorCount)
                    put("isAEFS", cardIdentity.isAEFS)
                    put("detectedAt", cardIdentity.detectedAt)
                    put("firstDetectedAt", System.currentTimeMillis())
                    put("lastDetectedAt", System.currentTimeMillis())
                    put("detectionCount", 1)
                    put("alias", "")
                    put("aefsModeTag", if (cardIdentity.isAEFS) "AEFS v5.0" else "Classic")
                }
            }
            
            sharedPrefs.edit().putString(uid, json.toString()).apply()
            loadRecentCards()
        } catch (e: Exception) {
            Timber.e(e, "保存最近卡片失败")
        }
    }
    
    /**
     * 更新NFC状态
     */
    fun updateNFCStatus(status: NFCStatus) {
        _uiState.value = _uiState.value.copy(nfcStatus = status)
        // 《重要修复》同时更新会话管理器以保证全局一致性
        sessionManager.updateNFCStatus(status)
    }
    
    /**
     * 设置NFC扫描状态
     */
    fun setNFCScanning(isScanning: Boolean) {
        _uiState.value = _uiState.value.copy(nfcIsScanning = isScanning)
        sessionManager.setScannerActive(isScanning)
    }
    
    /**
     * 更新UI消息
     */
    fun updateUIMessage(message: String, isError: Boolean = false) {
        if (isError) {
            _uiState.value = _uiState.value.copy(
                errorMessage = message,
                successMessage = null
            )
        } else {
            _uiState.value = _uiState.value.copy(
                successMessage = message,
                errorMessage = null
            )
        }
    }
    
    /**
     * 直接设置当前卡片
     */
    fun setCurrentCard(card: CardIdentity) {
        val previousUid = _uiState.value.currentCard?.uid
        val existingSectors = _uiState.value.sectors
        val existingLogs = _uiState.value.operationLogs

        // 《重要修复》同时更新全局会话管理器
        sessionManager.onCardDetected(
            uid = card.uid,
            sak = card.sak,
            atqa = card.atqa,
            cardType = card.cardType,
            sectorCount = card.sectorCount,
            isAEFS = card.isAEFS,
            initialSectors = existingSectors,
            initialOperationLogs = existingLogs
        )

        _uiState.value = _uiState.value.copy(
            currentCard = card,
            errorMessage = null,
            successMessage = "检测到卡片: ${card.uid}",
            ultralightPages = if (previousUid == card.uid) _uiState.value.ultralightPages else emptyMap(),
            ultralightReadWindows = if (previousUid == card.uid) _uiState.value.ultralightReadWindows else emptyMap(),
            pendingAefsUnlockCardUid = null
        )
        saveRecentCard(card)
        
        // 记录到会话日志
        sessionManager.recordOperation(OperationLogEntry(
            type = "DETECT",
            sector = -1,
            block = -1,
            success = true
        ))
    }
    
    /**
     * 处理卡片检测 - 支持快速路径和慢速路径
     */
    fun onCardDetected(uid: String, sak: String, atqa: String) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.onCardDetected(uid, sak, atqa)
            updateLoading(false)
            
            result.onSuccess { card ->
                setCurrentCard(card)
            }.onFailure { error ->
                // 快速路径：如果 FFI 失败，直接创建基本的卡片信息
                Timber.w("FFI 调用失败，使用快速路径: ${error.message}")
                val basicCard = CardIdentity(
                    uid = uid,
                    sak = sak,
                    atqa = atqa,
                    cardType = CardType.UNKNOWN,
                    sectorCount = 0
                )
                setCurrentCard(basicCard)
            }
        }
    }

    /**
     * 同步卡片上下文到 Python FFI，不改变当前 UI 卡片对象。
     * 用于 MainActivity 走硬件直读路径时，确保后续 update_sector_data 可用。
     */
    fun primeFFICardContext(uid: String, sak: String, atqa: String) {
        viewModelScope.launch {
            repository.onCardDetected(uid, sak, atqa)
                .onFailure { Timber.w(it, "同步 FFI 卡片上下文失败") }
        }
    }
    
    /**
     * 更新扇区数据
     */
    fun updateSectorData(
        sectorIdx: Int,
        blocks: List<String>,
        keyA: String,
        keyB: String,
        authStatus: AuthStatus
    ) {
        viewModelScope.launch {
            val sectorData = SectorData(
                sector = sectorIdx,
                blocks = blocks.mapIndexed { idx, data ->
                    BlockData(sectorIdx, idx, data)
                },
                keyA = keyA,
                keyB = keyB,
                authStatus = authStatus,
                accessBits = "FF078069"
            )

            // 先更新本地 UI 与会话，确保硬件已读数据不会因为 FFI 同步失败而丢失显示
            val newSectors = _uiState.value.sectors.toMutableMap()
            newSectors[sectorIdx] = sectorData
            _uiState.value = _uiState.value.copy(
                sectors = newSectors,
                successMessage = "扇区 $sectorIdx 已更新",
                errorMessage = null
            )
            sessionManager.updateSectorData(sectorIdx, sectorData)

            updateLoading(true)
            val result = repository.updateSectorData(sectorIdx, blocks, keyA, keyB, authStatus)
            updateLoading(false)
            
            result.onSuccess {
                addLog(OperationLogEntry(
                    type = "UPDATE",
                    sector = sectorIdx,
                    block = -1,
                    success = true
                ))
            }.onFailure { error ->
                Timber.w(error, "扇区 $sectorIdx 已本地更新，但 FFI 同步失败")
                addLog(OperationLogEntry(
                    type = "UPDATE",
                    sector = sectorIdx,
                    block = -1,
                    success = false,
                    error = error.message
                ))
            }
        }
    }
    
    /**
     * 构建 AEFS 镜像
     */
    fun buildAEFSImage(
        alias: String,
        passphrase: String,
        fileDataHex: String,
        sipLevel: SIPLevel = SIPLevel.ARCHITECT
    ) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.buildAEFSImage(alias, passphrase, fileDataHex, sipLevel)
            updateLoading(false)
            
            result.onSuccess { imageData ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "AEFS 镜像构建成功: ${imageData.alias}",
                    errorMessage = null
                )
                addLog(OperationLogEntry(
                    type = "AEFS_BUILD",
                    sector = -1,
                    block = -1,
                    success = true
                ))
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
                addLog(OperationLogEntry(
                    type = "AEFS_BUILD",
                    sector = -1,
                    block = -1,
                    success = false,
                    error = error.message
                ))
            }
        }
    }
    
    /**
     * 导出卡片数据
     */
    fun exportCardData(format: String) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.exportCardData(format)
            updateLoading(false)
            
            result.onSuccess { content ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "数据已导出 ($format)",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
            }
        }
    }
    
    /**
     * 切换视图模式
     */
    fun switchViewMode(mode: ViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
        Timber.i("视图模式已切换: $mode")
    }
    
    /**
     * 选择扇区
     */
    fun selectSector(sectorIdx: Int?) {
        _selectedSector.value = sectorIdx
    }
    
    /**
     * 添加操作日志
     */
    private fun addLog(entry: OperationLogEntry) {
        val logs = _operationLogs.value.toMutableList()
        logs.add(entry)
        if (logs.size > 100) {
            logs.removeAt(0)
        }
        _operationLogs.value = logs
    }
    
    /**
     * 更新加载状态
     */
    private fun updateLoading(isLoading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = isLoading)
    }
    
    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
    
    /**
     * 清除成功消息
     */
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun requestAefsUnlockPrompt(cardUid: String) {
        _uiState.value = _uiState.value.copy(pendingAefsUnlockCardUid = cardUid)
    }

    fun clearAefsUnlockPrompt() {
        _uiState.value = _uiState.value.copy(pendingAefsUnlockCardUid = null)
    }
    
    /**
     * 获取审计摘要
     */
    fun getAuditSummary() {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.getAuditSummary()
            updateLoading(false)
            
            result.onSuccess { summary ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "审计摘要已获取",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
            }
        }
    }
    
    /**
     * 验证并执行写入操作 - 工业级真实实现
     * 注意：此方法不再仅仅验证，而是直接执行真实的硬件写操作
     */
    fun validateWriteOperation(
        sectorIdx: Int,
        blockIdx: Int,
        dataHex: String,
        overrideAcknowledgement: String = "",
        isAEFSCard: Boolean = false
    ) {
        viewModelScope.launch {
            updateLoading(true)
            
            // 首先验证基本参数
            if (dataHex.isEmpty()) {
                updateLoading(false)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "数据不能为空"
                )
                addLog(OperationLogEntry(
                    type = "WRITE_OPERATION",
                    sector = sectorIdx,
                    block = blockIdx,
                    success = false,
                    error = "数据不能为空"
                ))
                return@launch
            }
            
            if (sectorIdx < 0 || blockIdx < 0) {
                updateLoading(false)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "扇区或块号无效"
                )
                addLog(OperationLogEntry(
                    type = "WRITE_OPERATION",
                    sector = sectorIdx,
                    block = blockIdx,
                    success = false,
                    error = "扇区或块号无效"
                ))
                return@launch
            }
            
            // 执行真实的硬件写操作
            val result = repository.writeBlock(
                sectorIdx = sectorIdx,
                blockIdx = blockIdx,
                dataHex = dataHex,
                overrideAcknowledgement = overrideAcknowledgement,
                isAEFSCard = isAEFSCard
            )
            updateLoading(false)
            
            result.onSuccess { resultData ->
                val bytesWritten = resultData["bytes_written"] as? Int ?: 0
                val verified = resultData["verified"] as? Boolean ?: false
                applyWrittenBlockToUi(sectorIdx, blockIdx, dataHex.uppercase())
                _uiState.value = _uiState.value.copy(
                    successMessage = "写入成功：S$sectorIdx B$blockIdx，$bytesWritten 字节${if (verified) "，已校验" else ""}",
                    errorMessage = null
                )
                addLog(OperationLogEntry(
                    type = "WRITE_OPERATION",
                    sector = sectorIdx,
                    block = blockIdx,
                    success = true,
                    error = null
                ))
                Timber.i("写入操作执行成功: Sector=$sectorIdx Block=$blockIdx 字节数=$bytesWritten")
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "写入操作失败: ${error.message}"
                )
                addLog(OperationLogEntry(
                    type = "WRITE_OPERATION",
                    sector = sectorIdx,
                    block = blockIdx,
                    success = false,
                    error = error.message
                ))
                Timber.e(error, "写入操作执行失败")
            }
        }
    }

    suspend fun readUltralightPage(pageIdx: Int): Result<String> {
        updateLoading(true)
        val result = repository.readUltralightPage(pageIdx)
        updateLoading(false)
        return result.map { bytes ->
            val pageData = bytes.copyOfRange(0, 4).joinToString("") { "%02X".format(it) }
            cacheUltralightWindow(pageIdx, bytes)
            _uiState.value = _uiState.value.copy(
                successMessage = "读取成功：Page $pageIdx = $pageData",
                errorMessage = null
            )
            // 【修复】：必须返回完整的 16 字节，供 UI 的 blockHex 显示完整数据
            bytes.joinToString("") { "%02X".format(it) }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = "读取失败: ${error.message}")
        }
    }

    suspend fun writeUltralightPage(pageIdx: Int, dataHex: String): Result<Map<String, Any>> {
        updateLoading(true)
        val result = repository.writeUltralightPage(pageIdx, dataHex)
        updateLoading(false)
        result.onSuccess {
            cacheUltralightPage(pageIdx, dataHex)
            _uiState.value = _uiState.value.copy(
                successMessage = "写入成功：Page $pageIdx",
                errorMessage = null
            )
            addLog(
                OperationLogEntry(
                    type = "WRITE_ULTRALIGHT",
                    sector = pageIdx,
                    block = 0,
                    success = true
                )
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                errorMessage = "写入失败: ${error.message}"
            )
            addLog(
                OperationLogEntry(
                    type = "WRITE_ULTRALIGHT",
                    sector = pageIdx,
                    block = 0,
                    success = false,
                    error = error.message
                )
            )
        }
        return result
    }

    suspend fun transceiveUltralightCommand(commandHex: String, successLabel: String): Result<String> {
        updateLoading(true)
        val result = repository.transceiveUltralightCommand(commandHex)
        updateLoading(false)
        return result.map { bytes ->
            val responseHex = bytes.joinToString("") { "%02X".format(it) }
            _uiState.value = _uiState.value.copy(
                successMessage = "$successLabel 成功: ${if (responseHex.isBlank()) "<empty>" else responseHex}",
                errorMessage = null
            )
            responseHex
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = "$successLabel 失败: ${error.message}")
        }
    }

    suspend fun fastReadUltralight(startPage: Int, endPage: Int): Result<String> {
        updateLoading(true)
        val result = repository.fastReadUltralight(startPage, endPage)
        updateLoading(false)
        result.onSuccess { bytes ->
            cacheUltralightSequentialPages(startPage, bytes)
        }
        return mapUltralightBytesResult(result, "FAST_READ")
    }

    suspend fun getUltralightVersion(): Result<String> {
        updateLoading(true)
        val result = repository.getUltralightVersion()
        updateLoading(false)
        return mapUltralightBytesResult(result, "GET_VERSION")
    }

    suspend fun readUltralightSignature(): Result<String> {
        updateLoading(true)
        val result = repository.readUltralightSignature()
        updateLoading(false)
        return mapUltralightBytesResult(result, "READ_SIG")
    }

    suspend fun readUltralightCounter(counterIndex: Int): Result<String> {
        updateLoading(true)
        val result = repository.readUltralightCounter(counterIndex)
        updateLoading(false)
        return mapUltralightBytesResult(result, "READ_CNT[$counterIndex]")
    }

    suspend fun incrementUltralightCounter(counterIndex: Int, increment: Int): Result<String> {
        updateLoading(true)
        val result = repository.incrementUltralightCounter(counterIndex, increment)
        updateLoading(false)
        return mapUltralightBytesResult(result, "INCR_CNT[$counterIndex]")
    }

    suspend fun authenticateUltralightPassword(passwordHex: String): Result<String> {
        updateLoading(true)
        val result = repository.authenticateUltralightPassword(passwordHex)
        updateLoading(false)
        return mapUltralightBytesResult(result, "PWD_AUTH")
    }

    suspend fun authenticateUltralightCStep1(): Result<String> {
        updateLoading(true)
        val result = repository.authenticateUltralightCStep1()
        updateLoading(false)
        return mapUltralightBytesResult(result, "UL-C AUTH STEP 1")
    }

    suspend fun authenticateUltralightCStep2(encryptedResponseHex: String): Result<String> {
        updateLoading(true)
        val result = repository.authenticateUltralightCStep2(encryptedResponseHex)
        updateLoading(false)
        return mapUltralightBytesResult(result, "UL-C AUTH STEP 2")
    }

    suspend fun compatibilityWriteUltralight(pageIdx: Int, dataHex: String): Result<Map<String, Any>> {
        updateLoading(true)
        val result = repository.compatibilityWriteUltralight(pageIdx, dataHex)
        updateLoading(false)
        result.onSuccess {
            _uiState.value = _uiState.value.copy(
                successMessage = "COMPATIBILITY_WRITE 成功：Page $pageIdx",
                errorMessage = null
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = "COMPATIBILITY_WRITE 失败: ${error.message}")
        }
        return result
    }

    private fun mapUltralightBytesResult(result: Result<ByteArray>, label: String): Result<String> {
        return result.map { bytes ->
            val responseHex = bytes.joinToString("") { "%02X".format(it) }
            _uiState.value = _uiState.value.copy(
                successMessage = "$label 成功: ${if (responseHex.isBlank()) "<empty>" else responseHex}",
                errorMessage = null
            )
            responseHex
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = "$label 失败: ${error.message}")
        }
    }

    private fun applyWrittenBlockToUi(sectorIdx: Int, blockIdx: Int, dataHex: String) {
        val oldSector = _uiState.value.sectors[sectorIdx] ?: return
        val newBlocks = oldSector.blocks.map {
            if (it.block == blockIdx) it.copy(data = dataHex) else it
        }
        val newSectors = _uiState.value.sectors.toMutableMap()
        val newSectorData = oldSector.copy(blocks = newBlocks)
        newSectors[sectorIdx] = newSectorData
        _uiState.value = _uiState.value.copy(sectors = newSectors)
        sessionManager.updateSectorData(sectorIdx, newSectorData)
    }
    
    /**
     * 获取缓存统计
     */
    fun getCacheStats() {
        viewModelScope.launch {
            try {
                val result = repository.getCacheStats()
                _uiState.value = _uiState.value.copy(
                    successMessage = "缓存统计: $result",
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }
    
    /**
     * 导出差分报告
     */
    fun exportDifferenceReport(format: String) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.exportDifferenceReport(format)
            updateLoading(false)
            
            result.onSuccess { content ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "差分报告已导出 ($format)",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
            }
        }
    }
    
    /**
     * 执行原始 APDU 命令
     */
    fun executeRawCommand(apduHex: String) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.executeRawCommand(apduHex)
            updateLoading(false)
            
            result.onSuccess { response ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "命令执行成功",
                    errorMessage = null
                )
                addLog(OperationLogEntry(
                    type = "RAW_CMD",
                    sector = -1,
                    block = -1,
                    success = true
                ))
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
                addLog(OperationLogEntry(
                    type = "RAW_CMD",
                    sector = -1,
                    block = -1,
                    success = false,
                    error = error.message
                ))
            }
        }
    }
    
    /**
     * 获取卡片信息仪表盘
     */
    fun getCardDashboard() {
        viewModelScope.launch {
            try {
                val result = repository.getCardDashboard()
                _uiState.value = _uiState.value.copy(
                    successMessage = "仪表盘信息: $result",
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }
    
    /**
     * 导出完整报告
     */
    fun exportFullReport(format: String) {
        viewModelScope.launch {
            updateLoading(true)
            val result = repository.exportFullReport(format)
            updateLoading(false)
            
            result.onSuccess { content ->
                _uiState.value = _uiState.value.copy(
                    successMessage = "完整报告已导出 ($format)",
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message
                )
            }
        }
    }
    
    /**
     * 清除所有日志
     */
    fun clearAllLogs() {
        _operationLogs.value = emptyList()
        Timber.i("所有日志已清除")
    }
    
    /**
     * 导出日志
     */
    fun exportLogs(format: String = "json") {
        viewModelScope.launch {
            try {
                val result = repository.exportLogs(format)
                _uiState.value = _uiState.value.copy(
                    successMessage = "日志已导出 ($format)",
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }
    
    /**
     * 选择最近卡片
     */
    fun selectRecentCard(history: RecentCardHistory) {
        _uiState.value = _uiState.value.copy(
            currentCard = history.cardIdentity,
            successMessage = "已加载: ${history.cardIdentity.uid}",
            errorMessage = null
        )
        addLog(OperationLogEntry(
            type = "SELECT_RECENT",
            sector = -1,
            block = -1,
            success = true
        ))
    }
    
    /**
     * 删除最近卡片
     */
    fun deleteRecentCard(uid: String) {
        sharedPrefs.edit().remove(uid).apply()
        loadRecentCards()
    }
    
    /**
     * 清除所有最近卡片
     */
    fun clearAllRecentCards() {
        sharedPrefs.edit().clear().apply()
        _uiState.value = _uiState.value.copy(recentCards = emptyList())
    }
    
    /**
     * 设置当前操作模式
     */
    fun setCurrentActionMode(mode: String) {
        _uiState.value = _uiState.value.copy(currentActionMode = mode)
        Timber.i("操作模式已设置: $mode")
    }
    
    private fun cacheUltralightWindow(startPage: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val responseHex = bytes.joinToString("") { "%02X".format(it) }
        val currentPages = _uiState.value.ultralightPages.toMutableMap()
        val currentWindows = _uiState.value.ultralightReadWindows.toMutableMap()
        bytes.asList()
            .chunked(4)
            .take(4)
            .forEachIndexed { offset, chunk ->
                if (chunk.size == 4) {
                    val pageHex = chunk.joinToString("") { "%02X".format(it) }
                    currentPages[startPage + offset] = pageHex
                    currentWindows[startPage + offset] = responseHex
                }
            }
        _uiState.value = _uiState.value.copy(
            ultralightPages = currentPages,
            ultralightReadWindows = currentWindows
        )
    }

    private fun cacheUltralightSequentialPages(startPage: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val currentPages = _uiState.value.ultralightPages.toMutableMap()
        bytes.asList()
            .chunked(4)
            .forEachIndexed { offset, chunk ->
                if (chunk.size == 4) {
                    currentPages[startPage + offset] = chunk.joinToString("") { "%02X".format(it) }
                }
            }
        _uiState.value = _uiState.value.copy(ultralightPages = currentPages)
    }

    private fun cacheUltralightPage(pageIdx: Int, dataHex: String) {
        val cleaned = dataHex.trim().replace(" ", "").uppercase()
        if (cleaned.length != 8 || cleaned.any { it !in '0'..'9' && it !in 'A'..'F' }) return
        val currentPages = _uiState.value.ultralightPages.toMutableMap()
        currentPages[pageIdx] = cleaned
        _uiState.value = _uiState.value.copy(ultralightPages = currentPages)
    }

    /**
     * 《重要修复》切换工具模式（识别模式 <-> 工具模式）
     * 状态保存在 ViewModel 中，防止 screen recomposition 导致状态丢失
     */
    fun toggleToolkitMode() {
        _uiState.value = _uiState.value.copy(
            isToolkitMode = !_uiState.value.isToolkitMode
        )
        Timber.i("工具模式已切换: ${_uiState.value.isToolkitMode}")
    }
    
    /**
     * 设置工具模式为特定值
     */
    fun setToolkitMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isToolkitMode = enabled)
        Timber.i("工具模式已设置为: $enabled")
    }
}
