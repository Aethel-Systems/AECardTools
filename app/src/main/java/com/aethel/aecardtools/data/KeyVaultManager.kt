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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KeyVaultManager {
    private const val PREFS = "aecardtools_key_vault"
    private const val KEY_IMPORTED = "imported_keys"
    private const val KEY_PUBLIC_AEFS = "public_aefs_keys"
    private val volatileSessionKeys = linkedSetOf<String>()

    // 与 KeyVaultScreen 的“系统内置”保持一致，并补充常见默认键
    private val builtInKeys = listOf(
        "FFFFFFFFFFFF",
        "000000000000",
        "A0A1A2A3A4A5",
        "B0B1B2B3B4B5",
        "010101010101",
        "D3F7D3F7D3F7"
    )

    fun getBuiltInKeys(): List<String> = builtInKeys

    data class AEFSVaultEntry(
        val state: String,
        val logicalUid: String,
        val physicalUid: String,
        val aliasHash: String,
        val transactionSequence: Long,
        val rootKeyHex: String?,
        val saltHex: String?,
        val createdAt: Long,
        val sectorKeys: Map<String, String>
    )

    data class ImportResult(
        val importedKeys: Int,
        val activePackages: Int,
        val pendingPackages: Int,
        val historyPackages: Int
    )

    fun getImportedKeys(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY_IMPORTED, emptySet())
            ?.map { normalize(it) }
            ?.filter { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
            ?.distinct()
            ?: emptyList()
    }

    fun getPublicAefsKeys(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getStringSet(KEY_PUBLIC_AEFS, emptySet())
            ?.map { normalize(it) }
            ?.filter { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
            ?.distinct()
            ?: emptyList()
    }

    fun saveImportedKeys(context: Context, keys: List<String>) {
        val normalized = keys.map { normalize(it) }
            .filter { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IMPORTED, normalized)
            .apply()
    }

    fun addImportedKey(context: Context, key: String): Boolean {
        val normalized = normalize(key)
        if (normalized.length != 12 || normalized.any { it !in '0'..'9' && it !in 'A'..'F' }) {
            return false
        }
        val keys = getImportedKeys(context).toMutableSet()
        val changed = keys.add(normalized)
        if (changed) {
            saveImportedKeys(context, keys.toList())
        }
        return changed
    }

    fun addImportedKeys(context: Context, keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        val existing = getImportedKeys(context).toMutableSet()
        val before = existing.size
        keys.forEach { raw ->
            val normalized = normalize(raw)
            if (normalized.length == 12 && normalized.all { ch -> ch in '0'..'9' || ch in 'A'..'F' }) {
                existing.add(normalized)
            }
        }
        if (existing.size != before) {
            saveImportedKeys(context, existing.toList())
        }
        return existing.size - before
    }

    fun removeImportedKey(context: Context, key: String) {
        val normalized = normalize(key)
        val keys = getImportedKeys(context).toMutableSet()
        keys.remove(normalized)
        saveImportedKeys(context, keys.toList())
    }

    fun syncPublicAefsKeys(context: Context) {
        val flattened = (
            AEFSSovereignKeyVault.listPackages(context) +
                AEFSSovereignKeyVault.listPendingPackages(context) +
                AEFSSovereignKeyVault.listHistoryPackages(context)
            )
            .flatMap { pkg ->
                pkg.keys.values.flatMap { listOf(it.first, it.second) } + deriveSector0Keys(pkg)
            }
            .map { normalize(it) }
            .filter { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
            .distinct()
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PUBLIC_AEFS, flattened)
            .apply()
    }

    fun getAefsVaultEntries(context: Context): List<AEFSVaultEntry> {
        return buildList {
            AEFSSovereignKeyVault.listPackages(context).forEach { add(pkgToEntry("ACTIVE", it)) }
            AEFSSovereignKeyVault.listPendingPackages(context).forEach { add(pkgToEntry("PENDING", it)) }
            AEFSSovereignKeyVault.listHistoryPackages(context).forEach { add(pkgToEntry("HISTORY", it)) }
        }.sortedWith(
            compareBy<AEFSVaultEntry> { it.physicalUid }
                .thenByDescending { it.transactionSequence }
                .thenByDescending { it.createdAt }
        )
    }

    fun importVaultContent(context: Context, content: String): ImportResult {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            if (json.optString("schema") == "AECardTools.KeyVault.Export.v1") {
                val importedKeyCount = addImportedKeys(
                    context,
                    json.optJSONArray("imported_keys").toStringList()
                )
                var active = 0
                var pending = 0
                var history = 0
                json.optJSONArray("aefs_packages").toPackageList().forEach {
                    AEFSSovereignKeyVault.savePackage(context, it)
                    active++
                }
                json.optJSONArray("pending_aefs_packages").toPackageList().forEach {
                    AEFSSovereignKeyVault.savePendingPackage(context, it)
                    pending++
                }
                json.optJSONArray("history_aefs_packages").toPackageList().forEach {
                    AEFSSovereignKeyVault.saveHistoryPackage(context, it)
                    history++
                }
                syncPublicAefsKeys(context)
                return ImportResult(
                    importedKeys = importedKeyCount,
                    activePackages = active,
                    pendingPackages = pending,
                    historyPackages = history
                )
            }

            val inlineKeys = linkedSetOf<String>()
            json.optJSONArray("imported_keys").toStringList().forEach { inlineKeys += it }
            json.optJSONArray("all_auth_keys").toStringList().forEach { inlineKeys += it }
            json.optJSONObject("sectors")?.let { sectorsObj ->
                sectorsObj.keys().forEach { sector ->
                    val item = sectorsObj.optJSONObject(sector) ?: return@forEach
                    inlineKeys += item.optString("key_a", "")
                    inlineKeys += item.optString("key_b", "")
                }
            }
            if (json.optString("format") == "keys" && json.optString("content").isNotBlank()) {
                return importVaultContent(context, json.optString("content"))
            }
            if (inlineKeys.isNotEmpty()) {
                val imported = addImportedKeys(context, inlineKeys.toList())
                syncPublicAefsKeys(context)
                return ImportResult(importedKeys = imported, activePackages = 0, pendingPackages = 0, historyPackages = 0)
            }
        }

        val sectorKeyPattern = Regex("""^Sector\s+\d+:\s+A=([A-Fa-f0-9]{12})\s+B=([A-Fa-f0-9]{12})$""")
        val plainKeys = trimmed.lineSequence()
            .map { it.trim() }
            .flatMap { line ->
                when {
                    line.matches(Regex("^[A-Fa-f0-9]{12}$")) -> sequenceOf(line)
                    line.matches(Regex("^[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){5}$")) -> sequenceOf(line.replace(":", ""))
                    sectorKeyPattern.matches(line) -> {
                        val match = sectorKeyPattern.matchEntire(line)!!
                        sequenceOf(match.groupValues[1], match.groupValues[2])
                    }
                    else -> emptySequence()
                }
            }
            .toList()
        val importedKeyCount = addImportedKeys(context, plainKeys)
        return ImportResult(importedKeys = importedKeyCount, activePackages = 0, pendingPackages = 0, historyPackages = 0)
    }

    fun getSystemAndImportedAuthKeys(context: Context): List<ByteArray> {
        return authKeysFromHex(getBuiltInKeys() + getImportedKeys(context))
    }

    fun getAefsSector0AuthKeys(context: Context): List<ByteArray> {
        return authKeysFromHex(
            allAefsPackages(context).flatMap { deriveSector0Keys(it) }
        )
    }

    fun getExactAefsAuthKeys(
        context: Context,
        physicalUid: String,
        aliasHashHex: String = "",
        logicalUid: String = ""
    ): List<ByteArray> {
        val pkg = resolveBestAefsPackage(
            context = context,
            physicalUid = physicalUid,
            aliasHashHex = aliasHashHex,
            logicalUid = logicalUid
        ) ?: return emptyList()
        return authKeysFromHex(
            deriveSector0Keys(pkg) + pkg.keys.toSortedMap().flatMap { listOf(it.value.first, it.value.second) }
        )
    }

    fun getAllAuthKeys(context: Context): List<ByteArray> {
        val aefsHex = (
            AEFSSovereignKeyVault.listPackages(context) +
                AEFSSovereignKeyVault.listPendingPackages(context) +
                AEFSSovereignKeyVault.listHistoryPackages(context)
            )
            .flatMap { pkg ->
                pkg.keys.values.flatMap { listOf(it.first, it.second) } + deriveSector0Keys(pkg)
            }
        val publicAefs = getPublicAefsKeys(context).ifEmpty {
            if (aefsHex.isNotEmpty()) {
                syncPublicAefsKeys(context)
            }
            getPublicAefsKeys(context)
        }
        val sessionHex = synchronized(volatileSessionKeys) { volatileSessionKeys.toList() }
        val allHex = (getBuiltInKeys() + getImportedKeys(context) + publicAefs + aefsHex + sessionHex)
            .map { normalize(it) }
            .distinct()
        return allHex.mapNotNull { hex ->
            runCatching {
                ByteArray(6) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }
    }

    fun rememberSessionKeys(keys: List<String>) {
        if (keys.isEmpty()) return
        synchronized(volatileSessionKeys) {
            keys.forEach { raw ->
                val normalized = normalize(raw)
                if (normalized.length == 12 && normalized.all { ch -> ch in '0'..'9' || ch in 'A'..'F' }) {
                    volatileSessionKeys.add(normalized)
                }
            }
        }
    }

    fun exportVaultAsJson(context: Context): String {
        val builtIn = getBuiltInKeys()
        val imported = getImportedKeys(context)
        val aefsPackages = AEFSSovereignKeyVault.listPackages(context)
        val pendingAefsPackages = AEFSSovereignKeyVault.listPendingPackages(context)
        val historyAefsPackages = AEFSSovereignKeyVault.listHistoryPackages(context)
        val generatedAt = isoNow()

        return JSONObject().apply {
            put("schema", "AECardTools.KeyVault.Export.v1")
            put("generated_at", generatedAt)
            put("built_in_keys", JSONArray(builtIn))
            put("imported_keys", JSONArray(imported))
            put(
                "all_auth_keys",
                JSONArray(
                    (
                        builtIn +
                            imported +
                            aefsPackages.flatMap { it.keys.values.flatMap { pair -> listOf(pair.first, pair.second) } } +
                            pendingAefsPackages.flatMap { it.keys.values.flatMap { pair -> listOf(pair.first, pair.second) } } +
                            historyAefsPackages.flatMap { it.keys.values.flatMap { pair -> listOf(pair.first, pair.second) } }
                        )
                        .map { normalize(it) }
                        .distinct()
                )
            )
            put(
                "aefs_packages",
                packageArray(aefsPackages)
            )
            put(
                "pending_aefs_packages",
                packageArray(pendingAefsPackages)
            )
            put(
                "history_aefs_packages",
                packageArray(historyAefsPackages)
            )
        }.toString(2)
    }

    fun exportVaultAsText(context: Context): String {
        val builtIn = getBuiltInKeys()
        val imported = getImportedKeys(context)
        val aefsPackages = AEFSSovereignKeyVault.listPackages(context)
        val pendingAefsPackages = AEFSSovereignKeyVault.listPendingPackages(context)
        val historyAefsPackages = AEFSSovereignKeyVault.listHistoryPackages(context)
        val lines = mutableListOf<String>()

        lines += "# AECardTools Key Vault Export"
        lines += "# Generated: ${isoNow()}"
        lines += ""
        lines += "[BuiltIn]"
        builtIn.forEach { lines += it }
        lines += ""
        lines += "[Imported]"
        if (imported.isEmpty()) {
            lines += "(empty)"
        } else {
            imported.forEach { lines += it }
        }
        lines += ""
        lines += "[AEFS Packages]"
        if (aefsPackages.isEmpty()) {
            lines += "(empty)"
        } else {
            aefsPackages.forEach { pkg ->
                lines += "CARD=${pkg.cardUidHex} PHYSICAL=${pkg.physicalUidHex} ALIAS=${pkg.aliasHashHex} VERSION=${pkg.versionHex} TX=${pkg.transactionSequence}"
                pkg.keys.toSortedMap().forEach { (sector, pair) ->
                    lines += "  S${sector.toString().padStart(2, '0')}: A=${pair.first} B=${pair.second}"
                }
                pkg.rootKeyHex?.let { lines += "  ROOT=$it" }
                pkg.saltHex?.let { lines += "  SALT=$it" }
                lines += "  SNAPSHOT=${if (pkg.packageSnapshotJson.isNullOrBlank()) "NO" else "YES"}"
                lines += ""
            }
        }
        lines += ""
        lines += "[Pending AEFS Packages]"
        if (pendingAefsPackages.isEmpty()) {
            lines += "(empty)"
        } else {
            pendingAefsPackages.forEach { pkg ->
                lines += "CARD=${pkg.cardUidHex} PHYSICAL=${pkg.physicalUidHex} ALIAS=${pkg.aliasHashHex} VERSION=${pkg.versionHex} TX=${pkg.transactionSequence}"
                pkg.keys.toSortedMap().forEach { (sector, pair) ->
                    lines += "  S${sector.toString().padStart(2, '0')}: A=${pair.first} B=${pair.second}"
                }
                pkg.rootKeyHex?.let { lines += "  ROOT=$it" }
                pkg.saltHex?.let { lines += "  SALT=$it" }
                lines += "  SNAPSHOT=${if (pkg.packageSnapshotJson.isNullOrBlank()) "NO" else "YES"}"
                lines += ""
            }
        }
        lines += ""
        lines += "[History AEFS Packages]"
        if (historyAefsPackages.isEmpty()) {
            lines += "(empty)"
        } else {
            historyAefsPackages.forEach { pkg ->
                lines += "CARD=${pkg.cardUidHex} PHYSICAL=${pkg.physicalUidHex} ALIAS=${pkg.aliasHashHex} VERSION=${pkg.versionHex} TX=${pkg.transactionSequence}"
                pkg.keys.toSortedMap().forEach { (sector, pair) ->
                    lines += "  S${sector.toString().padStart(2, '0')}: A=${pair.first} B=${pair.second}"
                }
                pkg.rootKeyHex?.let { lines += "  ROOT=$it" }
                pkg.saltHex?.let { lines += "  SALT=$it" }
                lines += "  SNAPSHOT=${if (pkg.packageSnapshotJson.isNullOrBlank()) "NO" else "YES"}"
                lines += ""
            }
        }
        return lines.joinToString("\n")
    }

    private fun isoNow(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    }

    private fun normalize(raw: String): String {
        return raw.replace(":", "").replace(" ", "").uppercase()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AEFSSovereignKeyVault.clearAll(context)
        synchronized(volatileSessionKeys) {
            volatileSessionKeys.clear()
        }
    }

    private fun packageArray(packages: List<AEFSManagedKeyPackage>): JSONArray {
        return JSONArray().apply {
            packages.forEach { pkg ->
                put(
                    JSONObject().apply {
                        put("card_uid", pkg.cardUidHex)
                        put("physical_uid", pkg.physicalUidHex)
                        put("alias_hash", pkg.aliasHashHex)
                        put("pool_id", pkg.poolIdHex)
                        put("sovereign_uid", pkg.sovereignUid)
                        put("version", pkg.versionHex)
                        put("transaction_sequence", pkg.transactionSequence)
                        put("root_key", pkg.rootKeyHex)
                        put("salt", pkg.saltHex)
                        put("package_snapshot", pkg.packageSnapshotJson)
                        put("has_package_snapshot", pkg.packageSnapshotJson.isNullOrBlank().not())
                        put("created_at", pkg.createdAt)
                        put(
                            "keys",
                            JSONObject().apply {
                                pkg.keys.toSortedMap().forEach { (sector, pair) ->
                                    put(
                                        sector.toString(),
                                        JSONObject()
                                            .put("key_a", pair.first)
                                            .put("key_b", pair.second)
                                    )
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun pkgToEntry(state: String, pkg: AEFSManagedKeyPackage): AEFSVaultEntry {
        val sectorKeys = linkedMapOf<String, String>()
        deriveSector0Keys(pkg).let { sector0 ->
            if (sector0.size == 2) {
                sectorKeys["S00-A"] = sector0[0]
                sectorKeys["S00-B"] = sector0[1]
            }
        }
        pkg.keys.toSortedMap().forEach { (sector, pair) ->
            sectorKeys["S${sector.toString().padStart(2, '0')}-A"] = pair.first
            sectorKeys["S${sector.toString().padStart(2, '0')}-B"] = pair.second
        }
        return AEFSVaultEntry(
            state = state,
            logicalUid = pkg.cardUidHex,
            physicalUid = pkg.physicalUidHex,
            aliasHash = pkg.aliasHashHex,
            transactionSequence = pkg.transactionSequence,
            rootKeyHex = pkg.rootKeyHex,
            saltHex = pkg.saltHex,
            createdAt = pkg.createdAt,
            sectorKeys = sectorKeys
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                add(optString(i))
            }
        }
    }

    private fun JSONArray?.toPackageList(): List<AEFSManagedKeyPackage> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val obj = optJSONObject(i) ?: continue
                val keysObj = obj.optJSONObject("keys") ?: JSONObject()
                val keys = mutableMapOf<Int, Pair<String, String>>()
                keysObj.keys().forEach { sectorStr ->
                    val sector = sectorStr.toIntOrNull() ?: return@forEach
                    val pair = keysObj.optJSONObject(sectorStr) ?: return@forEach
                    val keyA = normalize(pair.optString("key_a", ""))
                    val keyB = normalize(pair.optString("key_b", ""))
                    if (keyA.length == 12 && keyB.length == 12) {
                        keys[sector] = keyA to keyB
                    }
                }
                add(
                    AEFSManagedKeyPackage(
                        cardUidHex = normalize(obj.optString("card_uid", "")),
                        physicalUidHex = normalize(obj.optString("physical_uid", obj.optString("card_uid", ""))),
                        aliasHashHex = normalize(obj.optString("alias_hash", "")),
                        poolIdHex = normalize(obj.optString("pool_id", "")),
                        sovereignUid = obj.optString("sovereign_uid", AEFSSovereignIdentity.SOVEREIGN_UID),
                        versionHex = obj.optString("version", AEFSSovereignIdentity.VERSION_V6_HEX),
                        transactionSequence = obj.optLong("transaction_sequence", 0L),
                        rootKeyHex = obj.optString("root_key").takeIf { it.isNotBlank() }?.let { normalize(it) },
                        saltHex = obj.optString("salt").takeIf { it.isNotBlank() }?.let { normalize(it) },
                        keys = keys,
                        packageSnapshotJson = obj.optString("package_snapshot").takeIf { it.isNotBlank() },
                        createdAt = obj.optLong("created_at", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    private fun deriveSector0Keys(pkg: AEFSManagedKeyPackage): List<String> {
        val root = pkg.rootKeyHex?.uppercase() ?: return emptyList()
        val salt = pkg.saltHex?.uppercase() ?: return emptyList()
        if (root.length != 32 || salt.length != 8) {
            return emptyList()
        }
        val keyA = root.substring(16, 28)
        val keyB = root.substring(28, 32) + salt
        return listOf(keyA, keyB)
    }

    private fun allAefsPackages(context: Context): List<AEFSManagedKeyPackage> {
        return AEFSSovereignKeyVault.listPackages(context) +
            AEFSSovereignKeyVault.listPendingPackages(context) +
            AEFSSovereignKeyVault.listHistoryPackages(context)
    }

    private fun resolveBestAefsPackage(
        context: Context,
        physicalUid: String,
        aliasHashHex: String,
        logicalUid: String
    ): AEFSManagedKeyPackage? {
        val packages = buildList {
            AEFSSovereignKeyVault.listPackages(context).forEach { add("ACTIVE" to it) }
            AEFSSovereignKeyVault.listPendingPackages(context).forEach { add("PENDING" to it) }
            AEFSSovereignKeyVault.listHistoryPackages(context).forEach { add("HISTORY" to it) }
        }

        fun stateScore(state: String): Int = when (state) {
            "ACTIVE" -> 30
            "PENDING" -> 20
            "HISTORY" -> 10
            else -> 0
        }

        return packages
            .map { (state, pkg) ->
                var score = stateScore(state)
                if (physicalUid.isNotBlank() && pkg.physicalUidHex.equals(physicalUid, ignoreCase = true)) score += 100
                if (logicalUid.isNotBlank() && pkg.cardUidHex.equals(logicalUid, ignoreCase = true)) score += 60
                if (aliasHashHex.isNotBlank() && pkg.aliasHashHex.equals(aliasHashHex, ignoreCase = true)) score += 80
                Triple(score, pkg.transactionSequence, pkg)
            }
            .filter { it.first > 0 }
            .sortedWith(
                compareByDescending<Triple<Int, Long, AEFSManagedKeyPackage>> { it.first }
                    .thenByDescending { it.second }
                    .thenByDescending { it.third.createdAt }
            )
            .firstOrNull()
            ?.third
    }

    private fun authKeysFromHex(values: List<String>): List<ByteArray> {
        return values
            .map { normalize(it) }
            .filter { it.length == 12 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
            .distinct()
            .mapNotNull { hex ->
                runCatching {
                    ByteArray(6) { i ->
                        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                }.getOrNull()
            }
    }
}
