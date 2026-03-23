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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 路径人格模式 (Path Personality Mode)
 * 
 * 根据fe.txt第8个功能说明：系统支持路径规范的多种模式
 * 用户可在"系统设置"中选择相应的路径显示规范
 */
enum class PathPersonalityMode {
    AETHEL,     // AETHEL 模式（默认，主权模式）- 使用 >| 根目录, >: 卷, - 分隔符
    UNIX,        // UNIX 模式（兼容模式）- 使用 / 和标准目录结构
    WIN          // WIN 模式（Legacy 模式）- 使用 \ 和盘符
}

/**
 * 路径转换工具类
 * 
 * 实现不同模式下的路径格式转换
 * 工业级实现，支持多个订阅方的状态通知
 */
class PathTransformationEngine private constructor() {
    
    private val _currentMode = MutableStateFlow(PathPersonalityMode.AETHEL)
    val currentMode: StateFlow<PathPersonalityMode> = _currentMode.asStateFlow()
    
    /**
     * 切换路径模式
     */
    fun switchMode(mode: PathPersonalityMode) {
        _currentMode.value = mode
        Timber.i("路径模式已切换: $mode")
    }
    
    /**
     * 将物理池和卷的虚拟路径转换为对应格式
     * 
     * 内部表示: (pool: String, volume?: String, path?: String)
     * 示例调用:
     *   - transformPath("pool_0", "my_card", "system/header")
     */
    fun transformPath(pool: String, volume: String? = null, path: String? = null): String {
        return when (_currentMode.value) {
            PathPersonalityMode.AETHEL -> {
                // AETHEL 模式：>|-pool->:volume-path
                val poolPart = ">|$pool"
                val volPart = volume?.let { ">:$it" }
                val pathPart = path?.replace("/", "-")
                
                listOfNotNull(poolPart, volPart, pathPart)
                    .joinToString("-")
                    .replace(">|-", ">|")
                    .replace(">:-", ">:")
            }
            PathPersonalityMode.UNIX -> {
                // UNIX 模式：/mnt/pool_0/volume/path
                val parts = mutableListOf("/mnt", pool)
                if (volume != null) parts.add(volume)
                if (path != null) parts.addAll(path.split("/"))
                parts.joinToString("/")
            }
            PathPersonalityMode.WIN -> {
                // WIN 模式：A:\volume\path（A代表第一个卷）
                val drive = when {
                    volume != null && volume.startsWith("pool_") -> {
                        val index = volume.substring(5).toIntOrNull() ?: 0
                        ('A' + index).toString()
                    }
                    else -> "A"
                }
                val pathPart = if (path != null) path.replace("/", "\\") else ""
                val fullPath = if (pathPart.isNotEmpty()) {
                    "$drive:\\$pathPart"
                } else {
                    "$drive:\\"
                }
                fullPath
            }
        }
    }
    
    /**
     * 将用户输入的路径解析回内部格式
     * 并验证合法性
     */
    fun parsePath(userPath: String): Triple<String, String?, String?>? {
        return try {
            when (_currentMode.value) {
                PathPersonalityMode.AETHEL -> {
                    // 解析 AETHEL 格式
                    val regex = Regex(">\\|([^->]+)(?:->:([^-]+))?(?:-(.+))?")
                    val match = regex.find(userPath) ?: return null
                    Triple(match.groupValues[1], match.groupValues[2].ifEmpty { null }, match.groupValues[3].ifEmpty { null })
                }
                PathPersonalityMode.UNIX -> {
                    // 解析 UNIX 格式
                    if (!userPath.startsWith("/mnt/")) return null
                    val parts = userPath.substring(5).split("/")
                    if (parts.isEmpty()) return null
                    val pool = parts[0]
                    val volume = parts.getOrNull(1)
                    val path = if (parts.size > 2) parts.drop(2).joinToString("/") else null
                    Triple(pool, volume, path)
                }
                PathPersonalityMode.WIN -> {
                    // 解析 WIN 格式
                    if (userPath.length < 2 || userPath[1] != ':') return null
                    val drive = userPath[0]
                    val index = drive.code - 'A'.code
                    val volume = "pool_$index"
                    val path = if (userPath.length > 3) userPath.substring(3).replace("\\", "/") else null
                    Triple(volume, null, path)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "路径解析失败: $userPath")
            null
        }
    }

    /**
     * 将 AEFS 注册表内部路径转换为当前人格模式的显示路径。
     * 内部 canonical 仅使用 "AEFS://..."
     * }
     */
    fun toDisplayRegistryPath(canonicalPath: String): String {
        if (!canonicalPath.startsWith("AEFS://")) return canonicalPath
        val logical = canonicalPath.removePrefix("AEFS://")
        return transformPath(pool = "aefs", volume = "registry", path = logical.ifEmpty { null })
    }

    /**
     * 将用户可见注册表路径解析回 canonical "AEFS://..."。
     */
    fun toCanonicalRegistryPath(displayPath: String): String? {
        return when (_currentMode.value) {
            PathPersonalityMode.AETHEL -> {
                val parsed = parsePath(displayPath) ?: return null
                val path = parsed.third
                "AEFS://" + (path?.replace("-", "/") ?: "")
            }
            PathPersonalityMode.UNIX -> {
                val parsed = parsePath(displayPath) ?: return null
                val path = parsed.third
                "AEFS://" + (path ?: "")
            }
            PathPersonalityMode.WIN -> {
                val parsed = parsePath(displayPath) ?: return null
                val path = parsed.third
                "AEFS://" + (path ?: "")
            }
        }
    }
    
    companion object {
        @Volatile
        private var instance: PathTransformationEngine? = null
        
        fun getInstance(): PathTransformationEngine {
            return instance ?: synchronized(this) {
                instance ?: PathTransformationEngine().also { instance = it }
            }
        }
    }
}