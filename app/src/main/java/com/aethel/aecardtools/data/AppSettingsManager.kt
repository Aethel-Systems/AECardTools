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
import com.aethel.aecardtools.ui.theme.ThemeMode

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.GRASS_GREEN,
    val dynamicColor: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val nfcWakeLock: Boolean = true,
    val enableBccAutoCorrect: Boolean = true,
    val enableSafetyInterceptor: Boolean = true,
    val nfcTimeoutMs: Int = 1500,
    val retryCount: Int = 3,
    val pathMode: PathPersonalityMode = PathPersonalityMode.AETHEL
)

object AppSettingsManager {
    private const val PREFS = "aecardtools_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_APP_LANGUAGE = "app_language"
    private const val KEY_NFC_WAKELOCK = "nfc_wakelock"
    private const val KEY_BCC_AUTOCORRECT = "bcc_autocorrect"
    private const val KEY_SAFETY_INTERCEPTOR = "safety_interceptor"
    private const val KEY_NFC_TIMEOUT = "nfc_timeout_ms"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val KEY_PATH_MODE = "path_mode"

    fun load(context: Context): AppSettings {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val themeMode = runCatching { ThemeMode.valueOf(sp.getString(KEY_THEME_MODE, ThemeMode.GRASS_GREEN.name)!!) }
            .getOrDefault(ThemeMode.GRASS_GREEN)
        val appLanguage = runCatching { AppLanguage.valueOf(sp.getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.name)!!) }
            .getOrDefault(AppLanguage.SYSTEM)
        val pathMode = runCatching { PathPersonalityMode.valueOf(sp.getString(KEY_PATH_MODE, PathPersonalityMode.AETHEL.name)!!) }
            .getOrDefault(PathPersonalityMode.AETHEL)

        return AppSettings(
            themeMode = themeMode,
            dynamicColor = sp.getBoolean(KEY_DYNAMIC_COLOR, false),
            appLanguage = appLanguage,
            nfcWakeLock = sp.getBoolean(KEY_NFC_WAKELOCK, true),
            enableBccAutoCorrect = sp.getBoolean(KEY_BCC_AUTOCORRECT, true),
            enableSafetyInterceptor = sp.getBoolean(KEY_SAFETY_INTERCEPTOR, true),
            nfcTimeoutMs = sp.getInt(KEY_NFC_TIMEOUT, 1500),
            retryCount = sp.getInt(KEY_RETRY_COUNT, 3),
            pathMode = pathMode
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putString(KEY_APP_LANGUAGE, settings.appLanguage.name)
            .putBoolean(KEY_NFC_WAKELOCK, settings.nfcWakeLock)
            .putBoolean(KEY_BCC_AUTOCORRECT, settings.enableBccAutoCorrect)
            .putBoolean(KEY_SAFETY_INTERCEPTOR, settings.enableSafetyInterceptor)
            .putInt(KEY_NFC_TIMEOUT, settings.nfcTimeoutMs)
            .putInt(KEY_RETRY_COUNT, settings.retryCount)
            .putString(KEY_PATH_MODE, settings.pathMode.name)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
