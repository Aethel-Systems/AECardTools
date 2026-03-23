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

package com.aethel.aecardtools.util

import android.content.Context
import timber.log.Timber

/**
 * 免责声明管理器 - 工业级实现
 * 负责管理免责声明的显示状态和用户操作历史
 */
class DisclaimerManager(private val context: Context) {
    
    private val sharedPrefs = context.getSharedPreferences("aecardtools_disclaimers", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_WRITE_OPERATION_DISCLAIMER_DISMISSED = "write_operation_disclaimer_dismissed"
        private const val KEY_WRITE_OPERATION_DISCLAIMER_ACKNOWLEDGED_AT = "write_operation_disclaimer_acknowledged_at"
        private const val KEY_WRITE_OPERATION_DISMISSAL_COUNT = "write_operation_dismissal_count"
    }
    
    /**
     * 检查写操作免责声明是否已被用户忽略
     * @return true 表示用户曾选择"不再提示"；false 表示首次或未忽略
     */
    fun isWriteOperationDisclaimerDismissed(): Boolean {
        return sharedPrefs.getBoolean(KEY_WRITE_OPERATION_DISCLAIMER_DISMISSED, false)
    }
    
    /**
     * 获取免责声明被用户认可的时间戳
     * @return 毫秒级时间戳，0 表示未曾认可
     */
    fun getWriteOperationDisclaimerAcknowledgedTime(): Long {
        return sharedPrefs.getLong(KEY_WRITE_OPERATION_DISCLAIMER_ACKNOWLEDGED_AT, 0)
    }
    
    /**
     * 记录用户选择"不再提示"
     * 这是最终决定 - 用户确认已理解风险并选择不再看到此声明
     */
    fun dismissWriteOperationDisclaimer() {
        try {
            sharedPrefs.edit().apply {
                putBoolean(KEY_WRITE_OPERATION_DISCLAIMER_DISMISSED, true)
                putLong(KEY_WRITE_OPERATION_DISCLAIMER_ACKNOWLEDGED_AT, System.currentTimeMillis())
                
                // 记录累计次数用于统计
                val currentCount = sharedPrefs.getInt(KEY_WRITE_OPERATION_DISMISSAL_COUNT, 0)
                putInt(KEY_WRITE_OPERATION_DISMISSAL_COUNT, currentCount + 1)
                
                apply()
            }
            
            Timber.i("写操作免责声明已被用户标记为'不再提示'")
        } catch (e: Exception) {
            Timber.e(e, "保存免责声明状态失败")
        }
    }
    
    /**
     * 记录用户确认（用户点击了确认按钮）
     * 注意：这与"不再提示"不同 - 用户可能下次仍会看到此声明
     */
    fun acknowledgeWriteOperationDisclaimer() {
        try {
            sharedPrefs.edit().apply {
                putLong(KEY_WRITE_OPERATION_DISCLAIMER_ACKNOWLEDGED_AT, System.currentTimeMillis())
                apply()
            }
            
            Timber.i("写操作免责声明已被用户确认")
        } catch (e: Exception) {
            Timber.e(e, "保存免责声明确认时间失败")
        }
    }
    
    /**
     * 重置所有免责声明状态（通常在应用更新或用户请求时调用）
     */
    fun resetAllDisclaimers() {
        try {
            sharedPrefs.edit().clear().apply()
            Timber.i("所有免责声明状态已重置")
        } catch (e: Exception) {
            Timber.e(e, "重置免责声明失败")
        }
    }
    
    /**
     * 获取诊断信息（用于日志和调试）
     */
    fun getDiagnosticInfo(): Map<String, Any> {
        return mapOf(
            "write_operation_dismissed" to isWriteOperationDisclaimerDismissed(),
            "write_operation_acknowledged_at" to getWriteOperationDisclaimerAcknowledgedTime(),
            "write_operation_dismissal_count" to sharedPrefs.getInt(KEY_WRITE_OPERATION_DISMISSAL_COUNT, 0),
            "timestamp" to System.currentTimeMillis()
        )
    }
}
