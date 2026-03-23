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

package com.aethel.aecardtools.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aethel.aecardtools.R
import com.chaquo.python.Python
import com.aethel.aecardtools.data.model.CardIdentity
import com.aethel.aecardtools.data.model.CardType
import com.aethel.aecardtools.data.model.SectorData
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlin.math.min

private const val TAG = "HexCanvas"
data class HexCanvasRow(
    val address: String,
    val hexBytes: List<String>,
    val asciiChars: String,
    val colors: List<Color>
)

data class HexRegion(
    val start: Int,
    val end: Int,
    val color: String,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexCanvasScreen(
    onBackClick: () -> Unit,
    fallbackSectors: Map<Int, SectorData> = emptyMap(),
    cardIdentity: CardIdentity? = null
) {
    val context = LocalContext.current
    var hexData by remember { mutableStateOf<List<HexCanvasRow>>(emptyList()) }
    var regions by remember { mutableStateOf<List<HexRegion>>(emptyList()) }
    var selectedByteAddress by remember { mutableStateOf(-1) }
    var selectedPhysicalLocation by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val (data, regs) = loadHexCanvasData(fallbackSectors, cardIdentity)
            hexData = data
            regions = regs
            loadError = if (data.isEmpty()) context.getString(R.string.hex_canvas_no_data) else null
        } catch (e: Exception) {
            loadError = e.message
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        TopAppBar(
            title = { Text(stringResource(R.string.hex_canvas_title), fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 图例
            LegendSection(regions)

            loadError?.let { msg ->
                Text(
                    msg,
                    color = Color(0xFFD32F2F),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // 十六进制数据显示
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                items(hexData) { row ->
                    HexCanvasRow(
                        row = row,
                        onByteSelect = { address ->
                            selectedByteAddress = address
                            showDetails = true
                            coroutineScope.launch {
                                selectedPhysicalLocation = getPhysicalLocation(address)
                            }
                        }
                    )
                }
            }
        }

        // 字节详情浮窗
        if (showDetails && selectedByteAddress >= 0) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.hex_canvas_virtual_address, selectedByteAddress.toString(16).padStart(4, '0').uppercase()),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = { showDetails = false },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.home_close), modifier = Modifier.size(16.dp))
                        }
                    }

                    if (selectedPhysicalLocation.isNotEmpty()) {
                        Text(
                            selectedPhysicalLocation,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendSection(regions: List<HexRegion>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            stringResource(R.string.hex_canvas_legend_title),
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            regions.forEach { region ->
                Row(
                    modifier = Modifier
                        .background(colorFromString(region.color), shape = MaterialTheme.shapes.small)
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(colorFromString(region.color))
                    )
                    Text(
                        localizeRegionLabel(region.label, context),
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HexCanvasRow(
    row: HexCanvasRow,
    onByteSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 地址显示
        Text(
            row.address,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(64.dp)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.labelSmall
        )

        // 十六进制字节
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            row.hexBytes.forEachIndexed { index, hexByte ->
                Box(
                    modifier = Modifier
                        .background(row.colors.getOrElse(index) { Color.Gray }, shape = MaterialTheme.shapes.extraSmall)
                        .size(height = 24.dp, width = 30.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val byteAddress = row.address.drop(2).toInt(16) + index
                                    onByteSelect(byteAddress)
                                }
                            )
                        }
                        .padding(horizontal = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        hexByte,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }

        // ASCII显示
        Text(
            row.asciiChars,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private suspend fun loadHexCanvasData(
    fallbackSectors: Map<Int, SectorData>,
    cardIdentity: CardIdentity?
): Pair<List<HexCanvasRow>, List<HexRegion>> {
    return try {
        val py = Python.getInstance()
        val module = py.getModule("ffi_bridge")
            val logicalLength = resolveHexCanvasLength(cardIdentity, fallbackSectors)
            
            // 获取逻辑视图
            val viewResult = module.callAttr(
                "hex_canvas_get_logical_view",
                0x00,
                logicalLength
            ).toString()

            val viewJson = JSONObject(viewResult)
            
            // 获取区域信息
            val regionsResult = module.callAttr(
                "hex_canvas_get_regions"
            ).toString()
            
            val regionsJson = JSONObject(regionsResult)
            val regionsArray = regionsJson.getJSONArray("regions")
            
            val regions = mutableListOf<HexRegion>()
            for (i in 0 until regionsArray.length()) {
                val region = regionsArray.getJSONObject(i)
                regions.add(HexRegion(
                    start = region.getInt("start"),
                    end = region.getInt("end"),
                    color = region.getString("color"),
                    label = region.getString("label")
                ))
            }
            
            // 优先使用当前会话里的真实扇区数据，避免 LCOW 逻辑窗口固定为 752B
            if (fallbackSectors.isNotEmpty()) {
                val rows = buildRowsFromSectors(fallbackSectors, regions.ifEmpty { defaultRegions() })
                Pair(rows, regions.ifEmpty { defaultRegions() })
            } else if (viewJson.optBoolean("success", false)) {
                val hexString = viewJson.getString("hex_data")
                val rows = buildRowsFromHexString(hexString, regions.ifEmpty { defaultRegions() })
                Pair(rows, regions.ifEmpty { defaultRegions() })
            } else {
                Log.e(TAG, "hex_canvas_get_logical_view error: ${viewJson.optString("error")}")
                val fallbackRows = buildRowsFromSectors(fallbackSectors, regions.ifEmpty { defaultRegions() })
                Pair(fallbackRows, regions.ifEmpty { defaultRegions() })
            }
    } catch (e: Exception) {
        Log.e(TAG, "loadHexCanvasData 失败", e)
        val fallbackRegions = defaultRegions()
        val fallbackRows = buildRowsFromSectors(fallbackSectors, fallbackRegions)
        Pair(fallbackRows, fallbackRegions)
    }
}

private suspend fun getPhysicalLocation(va: Int): String {
    return try {
        val py = Python.getInstance()
        val module = py.getModule("ffi_bridge")
        val result = module.callAttr(
            "hex_canvas_get_physical_location",
            va
        ).toString()

        val jsonObj = JSONObject(result)
        if (jsonObj.optBoolean("success", false)) {
            jsonObj.getString("physical_address")
        } else {
            ""
        }
    } catch (e: Exception) {
        Log.e(TAG, "getPhysicalLocation 失败", e)
        ""
    }
}

private fun defaultRegions(): List<HexRegion> = listOf(
    HexRegion(0x00, 0x40, "BLUE", "header & metadata"),
    HexRegion(0x40, 0x200, "GRAY", "encrypted data"),
    HexRegion(0x200, 0x2EF, "GRAY", "file payload")
)

private fun localizeRegionLabel(label: String, context: android.content.Context): String {
    return when (label.trim().lowercase()) {
        "header & metadata" -> context.getString(R.string.hex_canvas_region_header)
        "encrypted data" -> context.getString(R.string.hex_canvas_region_encrypted)
        "file payload", "filepayload" -> context.getString(R.string.hex_canvas_region_payload)
        else -> label
    }
}

private fun colorFromString(colorName: String): Color {
    return when (colorName) {
        "BLUE" -> Color(0xFF1976D2)
        "GRAY" -> Color(0xFF757575)
        "RED" -> Color(0xFFD32F2F)
        else -> Color.Gray
    }
}

private fun buildRowsFromSectors(
    sectors: Map<Int, SectorData>,
    regions: List<HexRegion>
): List<HexCanvasRow> {
    if (sectors.isEmpty()) return emptyList()

    val mergedHex = buildString {
        sectors.toSortedMap().values.forEach { sector ->
            sector.blocks.sortedBy { it.block }.forEach { block ->
                val cleaned = block.data
                    .filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
                    .uppercase()
                when {
                    cleaned.length >= 32 -> append(cleaned.take(32))
                    cleaned.isNotEmpty() -> append(cleaned.padEnd(32, '0'))
                    else -> append("00".repeat(16))
                }
            }
        }
    }
    return buildRowsFromHexString(mergedHex, regions)
}

private fun buildRowsFromHexString(
    hexString: String,
    regions: List<HexRegion>
): List<HexCanvasRow> {
    if (hexString.isBlank()) return emptyList()

    val cleaned = hexString
        .filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        .uppercase()
    if (cleaned.isBlank()) return emptyList()

    val rowCount = (cleaned.length + 31) / 32
    val rows = mutableListOf<HexCanvasRow>()

    for (i in 0 until rowCount) {
        val offset = i * 16
        val rowHexStart = i * 32
        val rowHexEnd = min(rowHexStart + 32, cleaned.length)
        val rowHexString = cleaned.substring(rowHexStart, rowHexEnd)

        val hexBytes = mutableListOf<String>()
        val colors = mutableListOf<Color>()
        val asciiBuf = StringBuilder()

        for (j in 0 until 16) {
            if (j * 2 >= rowHexString.length) break
            val hex = rowHexString.substring(j * 2, min(j * 2 + 2, rowHexString.length))
            hexBytes.add(hex.uppercase())

            val va = offset + j
            val color = regions.find { va >= it.start && va < it.end }?.color ?: "GRAY"
            colors.add(colorFromString(color))

            val byte = hex.toIntOrNull(16) ?: 0
            asciiBuf.append(if (byte in 32..126) byte.toChar() else '.')
        }

        rows.add(
            HexCanvasRow(
                address = "0x${offset.toString(16).padStart(4, '0').uppercase()}",
                hexBytes = hexBytes,
                asciiChars = asciiBuf.toString(),
                colors = colors
            )
        )
    }

    return rows
}

private fun resolveHexCanvasLength(
    cardIdentity: CardIdentity?,
    sectors: Map<Int, SectorData>
): Int {
    val bytesFromSectors = sectors.values.sumOf { it.blocks.size * 16 }
    val bytesFromCard = when (cardIdentity?.cardType) {
        CardType.CLASSIC_1K -> 1024
        CardType.CLASSIC_4K -> 4096
        CardType.ULTRALIGHT -> (cardIdentity.sectorCount.takeIf { it > 0 } ?: 16) * 4
        CardType.ULTRALIGHT_C -> (cardIdentity.sectorCount.takeIf { it > 0 } ?: 48) * 4
        CardType.NTAG -> (cardIdentity.sectorCount.takeIf { it > 0 } ?: 45) * 4
        else -> 0
    }

    val preferred = maxOf(bytesFromCard, bytesFromSectors)
    return when {
        preferred >= 4096 -> 4096
        preferred >= 1024 -> 1024
        preferred > 0 -> preferred
        else -> 1024
    }
}
