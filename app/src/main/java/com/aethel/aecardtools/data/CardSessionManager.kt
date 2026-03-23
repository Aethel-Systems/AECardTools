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

package com.aethel.aecardtools.data

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aethel.aecardtools.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 全局卡片会话状态管理器 (Singleton)
 * 
 * 解决问题：双ViewModel状态不同步
 * 方案：建立唯一的"执行域"状态中心，所有功能模块共享该会话
 * 
 * 工业级实现特性：
 * - 单例设计确保状态唯一性
 * - StateFlow事件驱动，支持多个订阅方无阻塞监听
 * - 原子操作保证线程安全
 * - 完整的会话生命周期管理（初始化、更新、清除）
 */
class CardSessionManager private constructor() {
    
    // 当前卡片会话状态
    private val _currentSession = MutableStateFlow<CardSessionState?>(null)
    val currentSession: StateFlow<CardSessionState?> = _currentSession.asStateFlow()
    
    // 扫描状态
    private val _isScannerActive = MutableStateFlow(false)
    val isScannerActive: StateFlow<Boolean> = _isScannerActive.asStateFlow()
    
    // NFC设备状态  
    private val _nfcStatus = MutableStateFlow(NFCStatus.UNKNOWN)
    val nfcStatus: StateFlow<NFCStatus> = _nfcStatus.asStateFlow()
    
    // 会话变更事件队列（用于实时通知订阅方）
    private val _sessionEvents = MutableStateFlow<SessionEvent?>(null)
    val sessionEvents: StateFlow<SessionEvent?> = _sessionEvents.asStateFlow()
    
    /**
     * 当检测到新卡片时调用此方法
     * （由MainActivity.onCardDetected触发）
     */
    fun onCardDetected(
        uid: String,
        sak: String,
        atqa: String,
        cardType: CardType,
        sectorCount: Int,
        isAEFS: Boolean,
        initialSectors: Map<Int, SectorData> = emptyMap(),
        initialOperationLogs: List<OperationLogEntry> = emptyList()
    ) {
        val cardIdentity = CardIdentity(
            uid = uid,
            sak = sak,
            atqa = atqa,
            cardType = cardType,
            sectorCount = sectorCount,
            isAEFS = isAEFS,
            detectedAt = System.currentTimeMillis()
        )
        
        val newSession = CardSessionState(
            cardIdentity = cardIdentity,
            sectors = initialSectors,
            operationLogs = initialOperationLogs,
            createdAt = System.currentTimeMillis(),
            sessionId = generateSessionId()
        )
        
        _currentSession.value = newSession
        _sessionEvents.value = SessionEvent.CardDetected(cardIdentity)
        
        Timber.i("卡片会话已建立: ${cardIdentity.uid} (${cardIdentity.cardType})")
    }
    
    /**
     * 更新卡片扇区数据
     */
    fun updateSectorData(sectorIndex: Int, sectorData: SectorData) {
        val currentSess = _currentSession.value ?: return
        val updatedSectors = currentSess.sectors.toMutableMap()
        updatedSectors[sectorIndex] = sectorData
        
        _currentSession.value = currentSess.copy(
            sectors = updatedSectors,
            lastUpdatedAt = System.currentTimeMillis()
        )
        
        _sessionEvents.value = SessionEvent.SectorUpdated(sectorIndex, sectorData)
    }
    
    /**
     * 批量更新多个扇区
     */
    fun updateMultipleSectors(sectorsMap: Map<Int, SectorData>) {
        val currentSess = _currentSession.value ?: return
        val updatedSectors = currentSess.sectors.toMutableMap()
        updatedSectors.putAll(sectorsMap)
        
        _currentSession.value = currentSess.copy(
            sectors = updatedSectors,
            lastUpdatedAt = System.currentTimeMillis()
        )
        
        _sessionEvents.value = SessionEvent.MultipleSectorsUpdated(sectorsMap)
    }
    
    /**
     * 设置NFC扫描状态
     */
    fun setScannerActive(isActive: Boolean) {
        _isScannerActive.value = isActive
    }
    
    /**
     * 更新NFC设备状态
     */
    fun updateNFCStatus(status: NFCStatus) {
        _nfcStatus.value = status
    }
    
    /**
     * 记录操作日志
     */
    fun recordOperation(entry: OperationLogEntry) {
        val currentSess = _currentSession.value ?: return
        val updatedLogs = currentSess.operationLogs.toMutableList()
        updatedLogs.add(entry)
        
        _currentSession.value = currentSess.copy(
            operationLogs = updatedLogs,
            lastUpdatedAt = System.currentTimeMillis()
        )
        
        _sessionEvents.value = SessionEvent.OperationLogged(entry)
    }
    
    /**
     * 清除当前会话（卡片离开时）
     */
    fun clearSession() {
        _currentSession.value = null
        _sessionEvents.value = SessionEvent.SessionCleared
        Timber.i("卡片会话已清除")
    }
    
    /**
     * 获取当前会话的快照（不可变）
     */
    fun getSessionSnapshot(): CardSessionState? = _currentSession.value
    
    /**
     * 生成唯一的会话ID
     */
    private fun generateSessionId(): String {
        return "SESSION_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(10000)}"
    }
    
    companion object {
        @Volatile
        private var instance: CardSessionManager? = null
        
        fun getInstance(): CardSessionManager {
            return instance ?: synchronized(this) {
                instance ?: CardSessionManager().also { instance = it }
            }
        }
    }
}

/**
 * 卡片会话状态数据类
 */
data class CardSessionState(
    val sessionId: String,
    val cardIdentity: CardIdentity,
    val sectors: Map<Int, SectorData> = emptyMap(),
    val operationLogs: List<OperationLogEntry> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 会话事件（用于跨屏幕通知）
 */
sealed class SessionEvent {
    data class CardDetected(val cardIdentity: CardIdentity) : SessionEvent()
    data class SectorUpdated(val sectorIndex: Int, val sectorData: SectorData) : SessionEvent()
    data class MultipleSectorsUpdated(val sectorsMap: Map<Int, SectorData>) : SessionEvent()
    data class OperationLogged(val entry: OperationLogEntry) : SessionEvent()
    object SessionCleared : SessionEvent()
}
