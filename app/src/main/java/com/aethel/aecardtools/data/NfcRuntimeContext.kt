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

import android.nfc.Tag

/**
 * 运行时 NFC 上下文：保存最近一次检测到的 Tag，供仓库层执行真实读写。
 */
object NfcRuntimeContext {
    @Volatile
    private var currentTag: Tag? = null

    fun setCurrentTag(tag: Tag?) {
        currentTag = tag
    }

    fun getCurrentTag(): Tag? = currentTag
}
