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
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class AEFSSignatureDetection(
    val isAefs: Boolean,
    val sovereignUid: String,
    val physicalUid: String,
    val aliasHashHex: String,
    val headerHex: String,
    val versionHex: String,
    val transactionSequence: Long
)

object AEFSSovereignIdentity {
    const val SOVEREIGN_UID = "41454653"
    const val SOVEREIGN_BCC = "11"
    const val VERSION_V6_HEX = "60"

    fun detectFromBlock1(
        block1: ByteArray,
        physicalUid: String
    ): AEFSSignatureDetection? {
        if (block1.size < 16) return null
        if (!block1.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x45, 0x46, 0x53))) return null
        if (block1[4] != 0x11.toByte()) return null
        if (block1[5] != 0x60.toByte()) return null
        val aliasHash = block1.copyOfRange(10, 16).toHex()
        val txSequence = ((block1[6].toLong() and 0xFF) shl 24) or
            ((block1[7].toLong() and 0xFF) shl 16) or
            ((block1[8].toLong() and 0xFF) shl 8) or
            (block1[9].toLong() and 0xFF)
        return AEFSSignatureDetection(
            isAefs = true,
            sovereignUid = SOVEREIGN_UID,
            physicalUid = physicalUid.uppercase(),
            aliasHashHex = aliasHash,
            headerHex = block1.copyOfRange(0, 16).toHex(),
            versionHex = VERSION_V6_HEX,
            transactionSequence = txSequence
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}

data class AEFSManagedKeyPackage(
    val cardUidHex: String,
    val physicalUidHex: String = cardUidHex,
    val aliasHashHex: String,
    val poolIdHex: String,
    val sovereignUid: String = AEFSSovereignIdentity.SOVEREIGN_UID,
    val versionHex: String = AEFSSovereignIdentity.VERSION_V6_HEX,
    val transactionSequence: Long = 0L,
    val rootKeyHex: String? = null,
    val saltHex: String? = null,
    val keys: Map<Int, Pair<String, String>>,
    val packageSnapshotJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

object AEFSSovereignKeyVault {
    private const val PREFS = "aefs_sovereign_key_vault"
    private const val PACKAGE_PREFIX = "pkg_"
    private const val UID_PREFIX = "uid_"
    private const val PENDING_PACKAGE_PREFIX = "pending_pkg_"
    private const val PENDING_UID_PREFIX = "pending_uid_"
    private const val HISTORY_PACKAGE_PREFIX = "history_pkg_"
    private const val HISTORY_UID_PREFIX = "history_uid_"

    private fun getPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // 兜底：如果设备/ROM 不支持加密偏好，退回明文存储（仍保持功能可用）
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun savePackage(context: Context, pkg: AEFSManagedKeyPackage) {
        persistPackage(
            context = context,
            pkg = pkg,
            packagePrefix = PACKAGE_PREFIX,
            uidPrefix = UID_PREFIX
        )
    }

    fun savePendingPackage(context: Context, pkg: AEFSManagedKeyPackage) {
        persistPackage(
            context = context,
            pkg = pkg,
            packagePrefix = PENDING_PACKAGE_PREFIX,
            uidPrefix = PENDING_UID_PREFIX
        )
    }

    fun saveHistoryPackage(context: Context, pkg: AEFSManagedKeyPackage) {
        persistPackage(
            context = context,
            pkg = pkg,
            packagePrefix = HISTORY_PACKAGE_PREFIX,
            uidPrefix = HISTORY_UID_PREFIX
        )
    }

    fun activatePendingPackage(context: Context, cardUidHex: String, aliasHashHex: String) {
        val prefs = getPrefs(context)
        val pendingUidKey = PENDING_UID_PREFIX + cardUidHex.uppercase()
        val pendingAliasKey = PENDING_PACKAGE_PREFIX + aliasHashHex.uppercase()
        val raw = prefs.getString(pendingUidKey, null)
            ?: prefs.getString(pendingAliasKey, null)
            ?: return
        val pkg = parsePackage(raw, fallbackAliasHash = aliasHashHex, fallbackCardUid = cardUidHex) ?: return
        archiveCurrentActivePackage(context, pkg)
        savePackage(context, pkg)
        prefs.edit()
            .remove(pendingUidKey)
            .remove(pendingAliasKey)
            .remove(PENDING_UID_PREFIX + pkg.cardUidHex.uppercase())
            .remove(PENDING_UID_PREFIX + pkg.physicalUidHex.uppercase())
            .remove(PENDING_PACKAGE_PREFIX + pkg.aliasHashHex.uppercase())
            .commit()
    }

    private fun persistPackage(
        context: Context,
        pkg: AEFSManagedKeyPackage,
        packagePrefix: String,
        uidPrefix: String
    ) {
        val json = JSONObject().apply {
            put("card_uid", pkg.cardUidHex.uppercase())
            put("physical_uid", pkg.physicalUidHex.uppercase())
            put("alias_hash", pkg.aliasHashHex)
            put("pool_id", pkg.poolIdHex)
            put("sovereign_uid", pkg.sovereignUid)
            put("version", pkg.versionHex)
            put("transaction_sequence", pkg.transactionSequence)
            put("root_key", pkg.rootKeyHex)
            put("salt", pkg.saltHex)
            put("package_snapshot", pkg.packageSnapshotJson)
            put("created_at", pkg.createdAt)
            val keyJson = JSONObject()
            pkg.keys.toSortedMap().forEach { (sector, pair) ->
                keyJson.put(
                    sector.toString(),
                    JSONObject()
                        .put("key_a", pair.first.uppercase())
                        .put("key_b", pair.second.uppercase())
                )
            }
            put("keys", keyJson)
        }

        getPrefs(context)
            .edit()
            .putString(packagePrefix + pkg.aliasHashHex.uppercase(), json.toString())
            .putString(uidPrefix + pkg.cardUidHex.uppercase(), json.toString())
            .putString(uidPrefix + pkg.physicalUidHex.uppercase(), json.toString())
            .commit()
    }

    fun getPackageByAliasHash(context: Context, aliasHashHex: String): AEFSManagedKeyPackage? {
        val key = PACKAGE_PREFIX + aliasHashHex.uppercase()
        val encrypted = getPrefs(context).getString(key, null)
        val raw = encrypted ?: run {
            // 兼容旧版本明文存储：读取后自动迁移到加密偏好
            val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            if (legacy != null && encrypted == null) {
                runCatching { getPrefs(context).edit().putString(key, legacy).commit() }
            }
            legacy
        } ?: return null
        return parsePackage(raw, aliasHashHex)
    }

    fun getPackageByCardUid(context: Context, cardUidHex: String): AEFSManagedKeyPackage? {
        val key = UID_PREFIX + cardUidHex.uppercase()
        val raw = getPrefs(context).getString(key, null)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        if (raw != null) {
            return parsePackage(raw, fallbackCardUid = cardUidHex)
        }
        return listPackages(context).firstOrNull {
            it.cardUidHex.equals(cardUidHex, ignoreCase = true) ||
                it.physicalUidHex.equals(cardUidHex, ignoreCase = true)
        }
    }

    fun listPackages(context: Context): List<AEFSManagedKeyPackage> {
        val seen = linkedMapOf<String, AEFSManagedKeyPackage>()
        val allEntries = getPrefs(context).all + context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        allEntries.forEach { (key, value) ->
            if (!key.startsWith(UID_PREFIX) && !key.startsWith(PACKAGE_PREFIX)) return@forEach
            val raw = value as? String ?: return@forEach
            val pkg = parsePackage(raw) ?: return@forEach
            val dedupeKey = "${pkg.cardUidHex}|${pkg.aliasHashHex}|${pkg.transactionSequence}"
            seen.putIfAbsent(dedupeKey, pkg)
        }
        return seen.values.sortedWith(compareByDescending<AEFSManagedKeyPackage> { it.createdAt }.thenBy { it.cardUidHex })
    }

    fun listPendingPackages(context: Context): List<AEFSManagedKeyPackage> {
        val seen = linkedMapOf<String, AEFSManagedKeyPackage>()
        val allEntries = getPrefs(context).all + context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        allEntries.forEach { (key, value) ->
            if (!key.startsWith(PENDING_UID_PREFIX) && !key.startsWith(PENDING_PACKAGE_PREFIX)) return@forEach
            val raw = value as? String ?: return@forEach
            val pkg = parsePackage(raw) ?: return@forEach
            val dedupeKey = "${pkg.cardUidHex}|${pkg.aliasHashHex}|${pkg.transactionSequence}"
            seen.putIfAbsent(dedupeKey, pkg)
        }
        return seen.values.sortedWith(compareByDescending<AEFSManagedKeyPackage> { it.createdAt }.thenBy { it.cardUidHex })
    }

    fun listHistoryPackages(context: Context): List<AEFSManagedKeyPackage> {
        val seen = linkedMapOf<String, AEFSManagedKeyPackage>()
        val allEntries = getPrefs(context).all + context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        allEntries.forEach { (key, value) ->
            if (!key.startsWith(HISTORY_UID_PREFIX) && !key.startsWith(HISTORY_PACKAGE_PREFIX)) return@forEach
            val raw = value as? String ?: return@forEach
            val pkg = parsePackage(raw) ?: return@forEach
            val dedupeKey = "${pkg.cardUidHex}|${pkg.aliasHashHex}|${pkg.transactionSequence}"
            seen.putIfAbsent(dedupeKey, pkg)
        }
        return seen.values.sortedWith(compareByDescending<AEFSManagedKeyPackage> { it.createdAt }.thenBy { it.cardUidHex })
    }

    fun getAuthKeyCandidates(context: Context, aliasHashHex: String): List<ByteArray> {
        val pkg = getPackageByAliasHash(context, aliasHashHex) ?: return emptyList()
        return pkg.keys.toSortedMap().flatMap { (_, pair) ->
            listOfNotNull(pair.first.hexToBytes(), pair.second.hexToBytes())
        }
    }

    fun clearAll(context: Context) {
        runCatching {
            getPrefs(context).edit().clear().commit()
        }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
        runCatching {
            context.deleteSharedPreferences(PREFS)
        }
    }

    private fun parsePackage(
        raw: String,
        fallbackAliasHash: String = "",
        fallbackCardUid: String = ""
    ): AEFSManagedKeyPackage? {
        return runCatching {
            val json = JSONObject(raw)
            val keysObj = json.optJSONObject("keys") ?: JSONObject()
            val keys = mutableMapOf<Int, Pair<String, String>>()
            keysObj.keys().forEach { sectorStr ->
                val sector = sectorStr.toIntOrNull() ?: return@forEach
                val keyPair = keysObj.optJSONObject(sectorStr) ?: return@forEach
                val keyA = keyPair.optString("key_a", "").uppercase()
                val keyB = keyPair.optString("key_b", "").uppercase()
                if (keyA.length == 12 && keyB.length == 12) {
                    keys[sector] = Pair(keyA, keyB)
                }
            }
            AEFSManagedKeyPackage(
                cardUidHex = json.optString("card_uid", fallbackCardUid).uppercase(),
                physicalUidHex = json.optString("physical_uid", fallbackCardUid).uppercase(),
                aliasHashHex = json.optString("alias_hash", fallbackAliasHash).uppercase(),
                poolIdHex = json.optString("pool_id", ""),
                sovereignUid = json.optString("sovereign_uid", AEFSSovereignIdentity.SOVEREIGN_UID),
                versionHex = json.optString("version", AEFSSovereignIdentity.VERSION_V6_HEX),
                transactionSequence = json.optLong("transaction_sequence", 0L),
                rootKeyHex = json.optString("root_key").takeIf { it.isNotBlank() }?.uppercase(),
                saltHex = json.optString("salt").takeIf { it.isNotBlank() }?.uppercase(),
                keys = keys,
                packageSnapshotJson = json.optString("package_snapshot").takeIf { it.isNotBlank() },
                createdAt = json.optLong("created_at", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private fun String.hexToBytes(): ByteArray? {
        val clean = uppercase()
        if (clean.length != 12 || clean.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return ByteArray(6) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun archiveCurrentActivePackage(context: Context, nextPkg: AEFSManagedKeyPackage) {
        val current = getPackageByCardUid(context, nextPkg.physicalUidHex)
            ?: getPackageByCardUid(context, nextPkg.cardUidHex)
            ?: getPackageByAliasHash(context, nextPkg.aliasHashHex)
            ?: return
        if (current.transactionSequence == nextPkg.transactionSequence) {
            return
        }
        saveHistoryPackage(context, current)
    }
}
