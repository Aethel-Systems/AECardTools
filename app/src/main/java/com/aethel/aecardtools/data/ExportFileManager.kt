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
import android.net.Uri
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportFileManager {
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun buildSuggestedFileName(baseName: String, extension: String): String {
        return "${sanitizeSegment(baseName)}_${timestampFormat.format(Date())}.${sanitizeExtension(extension)}"
    }

    fun exportText(
        context: Context,
        category: String,
        baseName: String,
        extension: String,
        content: String
    ): File {
        return exportBytes(
            context = context,
            category = category,
            baseName = baseName,
            extension = extension,
            content = content.toByteArray(Charsets.UTF_8)
        )
    }

    fun exportBytes(
        context: Context,
        category: String,
        baseName: String,
        extension: String,
        content: ByteArray
    ): File {
        val exportDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "exports/${sanitizeSegment(category)}"
        )
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val file = File(
            exportDir,
            "${sanitizeSegment(baseName)}_${timestampFormat.format(Date())}.${sanitizeExtension(extension)}"
        )
        file.writeBytes(content)
        return file
    }

    fun writeTextToUri(
        context: Context,
        uri: Uri,
        content: String
    ) {
        writeBytesToUri(context, uri, content.toByteArray(Charsets.UTF_8))
    }

    fun writeBytesToUri(
        context: Context,
        uri: Uri,
        content: ByteArray
    ) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(content)
            output.flush()
        } ?: throw IllegalStateException("无法打开导出目标")
    }

    fun readTextFromUri(
        context: Context,
        uri: Uri
    ): String {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("无法读取导入文件")
    }

    private fun sanitizeSegment(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return cleaned.ifBlank { "export" }
    }

    private fun sanitizeExtension(raw: String): String {
        val cleaned = raw.trim().trimStart('.').replace(Regex("[^A-Za-z0-9]+"), "")
        return cleaned.ifBlank { "txt" }
    }
}
