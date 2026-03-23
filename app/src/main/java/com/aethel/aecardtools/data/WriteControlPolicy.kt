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

import java.util.concurrent.atomic.AtomicInteger

/**
 * 控制区写入策略（Sector 0 禁飞区）。
 *
 * 规则：
 * 1. 非格式化流程下，禁止写入 Sector 0（Block 0/1/2/3）。
 * 2. 仅当用户输入精确确认短语时允许越权写入。
 */
object WriteControlPolicy {
    const val OVERRIDE_PHRASE = "我同意承担后果"

    private val formattingDepth = AtomicInteger(0)

    fun beginFormattingSession() {
        formattingDepth.incrementAndGet()
    }

    fun endFormattingSession() {
        while (true) {
            val current = formattingDepth.get()
            if (current <= 0) {
                formattingDepth.set(0)
                return
            }
            if (formattingDepth.compareAndSet(current, current - 1)) {
                return
            }
        }
    }

    fun isFormattingSession(): Boolean = formattingDepth.get() > 0

    fun canWriteSector(
        sectorIdx: Int,
        overridePhrase: String
    ): Pair<Boolean, String> {
        if (sectorIdx != 0) {
            return Pair(true, "")
        }
        if (isFormattingSession()) {
            return Pair(true, "")
        }
        if (overridePhrase.trim() == OVERRIDE_PHRASE) {
            return Pair(true, "")
        }
        return Pair(
            false,
            "控制区写入已锁定：Sector 0 仅允许格式化流程写入；如需强制写入，请输入确认短语“$OVERRIDE_PHRASE”"
        )
    }
}
