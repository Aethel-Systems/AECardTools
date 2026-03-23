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
import com.aethel.aecardtools.data.model.CardIdentity
import com.aethel.aecardtools.data.model.CardType
import com.aethel.aecardtools.data.model.SectorData
import com.aethel.aecardtools.data.model.isUltralightLike
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

object CardExportManager {
    data class PreparedExport(
        val suggestedFileName: String,
        val mimeType: String,
        val bytes: ByteArray,
        val isPartial: Boolean,
        val format: String
    )

    data class ExportSummary(
        val file: File,
        val format: String,
        val isPartial: Boolean
    )

    private data class ExportArtifact(
        val baseName: String,
        val extension: String,
        val bytes: ByteArray,
        val isPartial: Boolean,
        val mimeType: String
    )

    fun prepareCurrentCardExport(
        card: CardIdentity?,
        sectors: Map<Int, SectorData>,
        ultralightPages: Map<Int, String>,
        format: String
    ): PreparedExport {
        require(card != null) { "当前没有卡片数据可导出" }
        val artifact = buildArtifact(card, sectors, ultralightPages, format)
        return PreparedExport(
            suggestedFileName = ExportFileManager.buildSuggestedFileName(
                baseName = artifact.baseName,
                extension = artifact.extension
            ),
            mimeType = artifact.mimeType,
            bytes = artifact.bytes,
            isPartial = artifact.isPartial,
            format = format.lowercase()
        )
    }

    fun exportCurrentCard(
        context: Context,
        card: CardIdentity?,
        sectors: Map<Int, SectorData>,
        ultralightPages: Map<Int, String>,
        format: String
    ): ExportSummary {
        val artifact = prepareCurrentCardExport(card, sectors, ultralightPages, format)
        val file = ExportFileManager.exportBytes(
            context = context,
            category = "card_data",
            baseName = artifact.suggestedFileName.substringBeforeLast('.'),
            extension = artifact.suggestedFileName.substringAfterLast('.', "txt"),
            content = artifact.bytes
        )
        return ExportSummary(file = file, format = artifact.format, isPartial = artifact.isPartial)
    }

    private fun buildArtifact(
        card: CardIdentity,
        sectors: Map<Int, SectorData>,
        ultralightPages: Map<Int, String>,
        format: String
    ): ExportArtifact {
        val normalizedFormat = format.trim().lowercase()
        return if (card.cardType.isUltralightLike()) {
            buildUltralightArtifact(card, ultralightPages, normalizedFormat)
        } else {
            buildClassicArtifact(card, sectors, normalizedFormat)
        }
    }

    private fun buildClassicArtifact(
        card: CardIdentity,
        sectors: Map<Int, SectorData>,
        format: String
    ): ExportArtifact {
        val exportedAt = Instant.now().toString()
        val orderedSectors = (0 until card.sectorCount).map { sectorIdx ->
            val sector = sectors[sectorIdx]
            val expectedBlocks = expectedClassicBlockCount(card.cardType, sectorIdx)
            val blocks = (0 until expectedBlocks).map { blockIdx ->
                sector?.blocks?.getOrNull(blockIdx)?.data.normalizeClassicBlockHex()
            }
            Triple(sectorIdx, sector, blocks)
        }
        val isPartial = orderedSectors.any { (_, sector, blocks) ->
            sector == null || blocks.any { it == null }
        }
        val baseName = buildBaseName(card, format, isPartial)

        return when (format) {
            "json" -> {
                val json = JSONObject().apply {
                    put("uid", card.uid)
                    put("card_type", card.cardType.name)
                    put("sector_count", card.sectorCount)
                    put("exported_at", exportedAt)
                    put("is_partial", isPartial)
                    put("format", "classic")
                    val sectorsJson = JSONObject()
                    orderedSectors.forEach { (sectorIdx, sector, blocks) ->
                        sectorsJson.put(
                            sectorIdx.toString(),
                            JSONObject().apply {
                                put("key_a", sanitizeKeyHex(sector?.keyA))
                                put("key_b", sanitizeKeyHex(sector?.keyB))
                                put("auth_status", sector?.authStatus?.name ?: "NOT_ATTEMPTED")
                                put("access_bits", sector?.accessBits ?: "FF078069")
                                put(
                                    "blocks",
                                    JSONArray(
                                        blocks.mapIndexed { blockIdx, value ->
                                            JSONObject().apply {
                                                put("block", blockIdx)
                                                put("data", value ?: missingClassicBlockHex())
                                                put("readable", value != null)
                                            }
                                        }
                                    )
                                )
                            }
                        )
                    }
                    put("sectors", sectorsJson)
                }
                ExportArtifact(baseName, "json", json.toString(2).toByteArray(Charsets.UTF_8), isPartial, "application/json")
            }
            "bin" -> {
                val bytes = orderedSectors.flatMap { (_, _, blocks) ->
                    blocks.map { (it ?: missingClassicBlockHex()).hexToBytes() }
                }.fold(ByteArray(0)) { acc, next -> acc + next }
                ExportArtifact(baseName, "bin", bytes, isPartial, "application/octet-stream")
            }
            "md" -> {
                val lines = mutableListOf<String>()
                lines += "# AECardTools Classic Dump"
                lines += ""
                lines += "- UID: `${card.uid}`"
                lines += "- Card Type: `${card.cardType.name}`"
                lines += "- Sector Count: `${card.sectorCount}`"
                lines += "- Exported At: `$exportedAt`"
                lines += "- Partial: `${if (isPartial) "YES" else "NO"}`"
                lines += ""
                orderedSectors.forEach { (sectorIdx, sector, blocks) ->
                    lines += "## Sector $sectorIdx"
                    lines += ""
                    lines += "- Auth: `${sector?.authStatus?.name ?: "NOT_ATTEMPTED"}`"
                    lines += "- Key A: `${sanitizeKeyHex(sector?.keyA)}`"
                    lines += "- Key B: `${sanitizeKeyHex(sector?.keyB)}`"
                    lines += "- Access Bits: `${sector?.accessBits ?: "FF078069"}`"
                    lines += ""
                    lines += "```text"
                    blocks.forEachIndexed { blockIdx, value ->
                        lines += "B%02d: %s".format(blockIdx, value ?: "${missingClassicBlockHex()}  [UNREAD]")
                    }
                    lines += "```"
                    lines += ""
                }
                ExportArtifact(baseName, "md", lines.joinToString("\n").toByteArray(Charsets.UTF_8), isPartial, "text/markdown")
            }
            "hex" -> {
                val lines = mutableListOf<String>()
                lines += "# AECardTools Classic HEX Dump"
                lines += "# UID=${card.uid} TYPE=${card.cardType.name} EXPORTED_AT=$exportedAt PARTIAL=${if (isPartial) 1 else 0}"
                orderedSectors.forEach { (sectorIdx, _, blocks) ->
                    lines += ""
                    lines += "[Sector $sectorIdx]"
                    blocks.forEachIndexed { blockIdx, value ->
                        lines += "B%02d: %s".format(blockIdx, value ?: "${missingClassicBlockHex()} [UNREAD]")
                    }
                }
                ExportArtifact(baseName, "hex", lines.joinToString("\n").toByteArray(Charsets.UTF_8), isPartial, "text/plain")
            }
            else -> error("不支持的导出格式: $format")
        }
    }

    private fun buildUltralightArtifact(
        card: CardIdentity,
        ultralightPages: Map<Int, String>,
        format: String
    ): ExportArtifact {
        val pageCount = card.sectorCount.takeIf { it > 0 } ?: defaultUltralightPageCount(card.cardType)
        val exportedAt = Instant.now().toString()
        val orderedPages = (0 until pageCount).map { page ->
            page to ultralightPages[page].normalizeUltralightPageHex()
        }
        val isPartial = orderedPages.any { it.second == null }
        val baseName = buildBaseName(card, format, isPartial)

        return when (format) {
            "json" -> {
                val json = JSONObject().apply {
                    put("uid", card.uid)
                    put("card_type", card.cardType.name)
                    put("page_count", pageCount)
                    put("exported_at", exportedAt)
                    put("is_partial", isPartial)
                    val pagesJson = JSONObject()
                    orderedPages.forEach { (page, value) ->
                        pagesJson.put(
                            page.toString(),
                            JSONObject().apply {
                                put("data", value ?: missingUltralightPageHex())
                                put("readable", value != null)
                            }
                        )
                    }
                    put("pages", pagesJson)
                }
                ExportArtifact(baseName, "json", json.toString(2).toByteArray(Charsets.UTF_8), isPartial, "application/json")
            }
            "bin" -> {
                val bytes = orderedPages.fold(ByteArray(0)) { acc, (_, value) ->
                    acc + (value ?: missingUltralightPageHex()).hexToBytes()
                }
                ExportArtifact(baseName, "bin", bytes, isPartial, "application/octet-stream")
            }
            "md" -> {
                val lines = mutableListOf<String>()
                lines += "# AECardTools Ultralight Dump"
                lines += ""
                lines += "- UID: `${card.uid}`"
                lines += "- Card Type: `${card.cardType.name}`"
                lines += "- Page Count: `$pageCount`"
                lines += "- Exported At: `$exportedAt`"
                lines += "- Partial: `${if (isPartial) "YES" else "NO"}`"
                lines += ""
                lines += "```text"
                orderedPages.forEach { (page, value) ->
                    lines += "P%03d: %s".format(page, value ?: "${missingUltralightPageHex()}  [UNREAD]")
                }
                lines += "```"
                ExportArtifact(baseName, "md", lines.joinToString("\n").toByteArray(Charsets.UTF_8), isPartial, "text/markdown")
            }
            "hex" -> {
                val lines = mutableListOf<String>()
                lines += "# AECardTools Ultralight HEX Dump"
                lines += "# UID=${card.uid} TYPE=${card.cardType.name} EXPORTED_AT=$exportedAt PARTIAL=${if (isPartial) 1 else 0}"
                orderedPages.forEach { (page, value) ->
                    lines += "P%03d: %s".format(page, value ?: "${missingUltralightPageHex()} [UNREAD]")
                }
                ExportArtifact(baseName, "hex", lines.joinToString("\n").toByteArray(Charsets.UTF_8), isPartial, "text/plain")
            }
            else -> error("不支持的导出格式: $format")
        }
    }

    private fun buildBaseName(card: CardIdentity, format: String, isPartial: Boolean): String {
        val partialSuffix = if (isPartial) "_partial" else ""
        return "${card.uid}_${card.cardType.name.lowercase()}_${format.lowercase()}$partialSuffix"
    }

    private fun expectedClassicBlockCount(cardType: CardType, sectorIdx: Int): Int {
        return if (cardType == CardType.CLASSIC_4K && sectorIdx >= 32) 16 else 4
    }

    private fun defaultUltralightPageCount(cardType: CardType): Int {
        return when (cardType) {
            CardType.ULTRALIGHT -> 16
            CardType.ULTRALIGHT_C -> 48
            CardType.NTAG -> 45
            else -> 16
        }
    }

    private fun String?.normalizeClassicBlockHex(): String? {
        val cleaned = this?.trim()?.replace(" ", "")?.uppercase() ?: return null
        if (cleaned.length != 32) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) return null
        if (cleaned.all { it == '?' }) return null
        return cleaned
    }

    private fun String?.normalizeUltralightPageHex(): String? {
        val cleaned = this?.trim()?.replace(" ", "")?.uppercase() ?: return null
        if (cleaned.length != 8) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) return null
        if (cleaned.all { it == '?' }) return null
        return cleaned
    }

    private fun sanitizeKeyHex(raw: String?): String {
        val cleaned = raw?.trim()?.replace(" ", "")?.uppercase().orEmpty()
        return if (cleaned.length == 12 && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) cleaned else "????????????"
    }

    private fun missingClassicBlockHex(): String = "00".repeat(16)

    private fun missingUltralightPageHex(): String = "00000000"

    private fun String.hexToBytes(): ByteArray {
        val cleaned = uppercase()
        return ByteArray(cleaned.length / 2) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
