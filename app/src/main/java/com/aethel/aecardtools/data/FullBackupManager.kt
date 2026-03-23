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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FullBackupResult(
    val restoredFiles: Int,
    val restoredDirectories: Int,
    val restoredPrefs: Int
)

object FullBackupManager {
    private const val SCHEMA = "AECardTools.FullBackup.v1"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_STATE = "managed_state.json"

    fun createBackup(context: Context): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeJsonEntry(
                zip = zip,
                name = ENTRY_MANIFEST,
                json = JSONObject().apply {
                    put("schema", SCHEMA)
                    put("generated_at", System.currentTimeMillis())
                    put("package_name", context.packageName)
                }
            )
            writeJsonEntry(zip, ENTRY_STATE, buildManagedState(context))

            val roots = listOfNotNull(
                "files" to context.filesDir,
                "cache" to context.cacheDir,
                "code_cache" to context.codeCacheDir,
                "no_backup" to context.noBackupFilesDir,
                "databases" to context.getDatabasePath("placeholder").parentFile,
                "external_files" to context.getExternalFilesDir(null)
            )
            roots.forEach { (label, dir) ->
                addDirectory(zip, dir, "payload/$label")
            }
        }
        return output.toByteArray()
    }

    fun importBackup(context: Context, bytes: ByteArray): FullBackupResult {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val manifest = JSONObject(entries[ENTRY_MANIFEST]?.toString(Charsets.UTF_8) ?: "{}")
        require(manifest.optString("schema") == SCHEMA) { "备份文件格式不受支持" }

        clearAllData(context)

        var restoredFiles = 0
        var restoredDirectories = 0
        entries.forEach { (name, data) ->
            if (!name.startsWith("payload/")) return@forEach
            val relative = name.removePrefix("payload/")
            val rootName = relative.substringBefore('/')
            val childRelative = relative.substringAfter('/', "")
            val rootDir = when (rootName) {
                "files" -> context.filesDir
                "cache" -> context.cacheDir
                "code_cache" -> context.codeCacheDir
                "no_backup" -> context.noBackupFilesDir
                "databases" -> context.getDatabasePath("placeholder").parentFile
                "external_files" -> context.getExternalFilesDir(null)
                else -> null
            } ?: return@forEach
            if (childRelative.isBlank()) return@forEach
            val target = File(rootDir, childRelative)
            target.parentFile?.mkdirs()
            target.writeBytes(data)
            restoredFiles++
        }

        restoredDirectories += listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.getDatabasePath("placeholder").parentFile,
            context.getExternalFilesDir(null)
        ).count { it.exists() }

        val state = JSONObject(entries[ENTRY_STATE]?.toString(Charsets.UTF_8) ?: "{}")
        val restoredPrefs = restoreManagedState(context, state)
        return FullBackupResult(
            restoredFiles = restoredFiles,
            restoredDirectories = restoredDirectories,
            restoredPrefs = restoredPrefs
        )
    }

    fun clearAllData(context: Context) {
        KeyVaultManager.clearAll(context)
        AppSettingsManager.clear(context)
        clearNamedPrefs(context, "aecardtools_recent_cards")
        clearNamedPrefs(context, "aecardtools_prefs")
        clearNamedPrefs(context, "aecardtools_disclaimers")

        listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.getDatabasePath("placeholder").parentFile,
            context.getExternalFilesDir(null)
        ).forEach { dir ->
            dir.listFiles()?.forEach { child -> deleteRecursively(child) }
        }
    }

    private fun buildManagedState(context: Context): JSONObject {
        return JSONObject().apply {
            put("settings", JSONObject().apply {
                val settings = AppSettingsManager.load(context)
                put("theme_mode", settings.themeMode.name)
                put("dynamic_color", settings.dynamicColor)
                put("app_language", settings.appLanguage.name)
                put("nfc_wakelock", settings.nfcWakeLock)
                put("enable_bcc_auto_correct", settings.enableBccAutoCorrect)
                put("enable_safety_interceptor", settings.enableSafetyInterceptor)
                put("nfc_timeout_ms", settings.nfcTimeoutMs)
                put("retry_count", settings.retryCount)
                put("path_mode", settings.pathMode.name)
            })
            put("key_vault_export", JSONObject(KeyVaultManager.exportVaultAsJson(context)))
            put("recent_cards", dumpSharedPreferences(context.getSharedPreferences("aecardtools_recent_cards", Context.MODE_PRIVATE)))
            put("prefs", dumpSharedPreferences(context.getSharedPreferences("aecardtools_prefs", Context.MODE_PRIVATE)))
            put("disclaimers", dumpSharedPreferences(context.getSharedPreferences("aecardtools_disclaimers", Context.MODE_PRIVATE)))
        }
    }

    private fun restoreManagedState(context: Context, state: JSONObject): Int {
        var restored = 0

        state.optJSONObject("settings")?.let { json ->
            AppSettingsManager.save(
                context,
                AppSettings(
                    themeMode = runCatching { com.aethel.aecardtools.ui.theme.ThemeMode.valueOf(json.optString("theme_mode")) }
                        .getOrDefault(com.aethel.aecardtools.ui.theme.ThemeMode.GRASS_GREEN),
                    dynamicColor = json.optBoolean("dynamic_color", false),
                    appLanguage = runCatching { AppLanguage.valueOf(json.optString("app_language")) }.getOrDefault(AppLanguage.SYSTEM),
                    nfcWakeLock = json.optBoolean("nfc_wakelock", true),
                    enableBccAutoCorrect = json.optBoolean("enable_bcc_auto_correct", true),
                    enableSafetyInterceptor = json.optBoolean("enable_safety_interceptor", true),
                    nfcTimeoutMs = json.optInt("nfc_timeout_ms", 1500),
                    retryCount = json.optInt("retry_count", 3),
                    pathMode = runCatching { PathPersonalityMode.valueOf(json.optString("path_mode")) }
                        .getOrDefault(PathPersonalityMode.AETHEL)
                )
            )
            restored++
        }

        state.optJSONObject("key_vault_export")?.let {
            KeyVaultManager.importVaultContent(context, it.toString())
            KeyVaultManager.syncPublicAefsKeys(context)
            restored++
        }

        state.optJSONObject("recent_cards")?.let {
            restoreSharedPreferences(context.getSharedPreferences("aecardtools_recent_cards", Context.MODE_PRIVATE), it)
            restored++
        }
        state.optJSONObject("prefs")?.let {
            restoreSharedPreferences(context.getSharedPreferences("aecardtools_prefs", Context.MODE_PRIVATE), it)
            restored++
        }
        state.optJSONObject("disclaimers")?.let {
            restoreSharedPreferences(context.getSharedPreferences("aecardtools_disclaimers", Context.MODE_PRIVATE), it)
            restored++
        }

        return restored
    }

    private fun dumpSharedPreferences(prefs: android.content.SharedPreferences): JSONObject {
        return JSONObject().apply {
            prefs.all.forEach { (key, value) ->
                put(
                    key,
                    when (value) {
                        null -> JSONObject.NULL
                        is Boolean, is Int, is Long, is Float, is String -> value
                        is Set<*> -> JSONArray(value.toList())
                        else -> value.toString()
                    }
                )
            }
        }
    }

    private fun restoreSharedPreferences(
        prefs: android.content.SharedPreferences,
        json: JSONObject
    ) {
        val editor = prefs.edit().clear()
        json.keys().forEach { key ->
            val value = json.get(key)
            when (value) {
                JSONObject.NULL -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> {
                    val items = buildSet {
                        for (i in 0 until value.length()) {
                            add(value.optString(i))
                        }
                    }
                    editor.putStringSet(key, items)
                }
                else -> editor.putString(key, value.toString())
            }
        }
        editor.commit()
    }

    private fun clearNamedPrefs(context: Context, name: String) {
        runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { context.deleteSharedPreferences(name) }
    }

    private fun addDirectory(zip: ZipOutputStream, dir: File?, entryRoot: String) {
        if (dir == null || !dir.exists()) return
        dir.walkTopDown().forEach { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            if (relative.isBlank()) return@forEach
            if (file.isDirectory) return@forEach
            val entry = ZipEntry("$entryRoot/$relative")
            zip.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun writeJsonEntry(zip: ZipOutputStream, name: String, json: JSONObject) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
