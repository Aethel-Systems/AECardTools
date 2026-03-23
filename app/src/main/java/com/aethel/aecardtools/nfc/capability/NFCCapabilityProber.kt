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

package com.aethel.aecardtools.nfc.capability

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import com.aethel.aecardtools.nfc.protocol.NFCCapabilityLevel
import com.aethel.aecardtools.nfc.protocol.NFCCapabilityProbeResult
import com.aethel.aecardtools.nfc.protocol.NFCControllerVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NFC 硬件能力探测器。
 *
 * 实现策略：
 * 1. 设备侧：按厂商/型号推断控制器族
 * 2. Tag 侧：使用当前 Tag 的 tech 实例读取真实 max transceive / 扩展 APDU 能力
 */
class NFCCapabilityProber(private val context: Context) {

    suspend fun probe(tag: Tag?): NFCCapabilityProbeResult = withContext(Dispatchers.IO) {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val vendorLikelihood = inferVendor(manufacturer, model)

        val measurements = measureByCurrentTag(tag)
        val supportsMifareClassic = measurements.supportsMifareClassic ?: inferMifareClassicByVendor(vendorLikelihood)
        val maxTransceiveLength = measurements.maxTransceiveLength
        val supportsExtendedAPDU = measurements.supportsExtendedApdu

        val nonStandardCardSupportScore = calculateNonStandardCardScore(
            vendor = vendorLikelihood,
            supportsMifare = supportsMifareClassic,
            maxLength = maxTransceiveLength,
            hasNfcA = measurements.hasNfcA
        )

        val capabilityLevel = classifyCapabilityLevel(
            vendor = vendorLikelihood,
            maxLength = maxTransceiveLength,
            supportsExtendedAPDU = supportsExtendedAPDU,
            supportsMifare = supportsMifareClassic
        )

        NFCCapabilityProbeResult(
            vendorLikelihood = vendorLikelihood,
            maxTransceiveLength = maxTransceiveLength,
            supportsExtendedAPDU = supportsExtendedAPDU,
            deviceModel = "$manufacturer $model",
            supportsMifareClassic = supportsMifareClassic,
            nonStandardCardSupportScore = nonStandardCardSupportScore,
            capabilityLevel = capabilityLevel,
            detailedCapabilities = buildDetailedCapabilities(
                vendor = vendorLikelihood,
                maxLength = maxTransceiveLength,
                supportsExtendedApdu = supportsExtendedAPDU,
                supportsMifare = supportsMifareClassic,
                measurements = measurements
            )
        )
    }

    private data class TagMeasurements(
        val maxTransceiveLength: Int,
        val supportsExtendedApdu: Boolean,
        val supportsMifareClassic: Boolean?,
        val hasNfcA: Boolean,
        val perTechMax: Map<String, Int>
    )

    private fun measureByCurrentTag(tag: Tag?): TagMeasurements {
        if (tag == null) {
            return TagMeasurements(
                maxTransceiveLength = 257,
                supportsExtendedApdu = false,
                supportsMifareClassic = null,
                hasNfcA = false,
                perTechMax = emptyMap()
            )
        }

        val perTechMax = mutableMapOf<String, Int>()
        var maxLen = 257

        val isoDep = IsoDep.get(tag)
        val nfcA = NfcA.get(tag)
        val nfcB = NfcB.get(tag)
        val nfcF = NfcF.get(tag)
        val nfcV = NfcV.get(tag)
        val mfc = MifareClassic.get(tag)

        fun update(name: String, value: Int?) {
            if (value != null && value > 0) {
                perTechMax[name] = value
                if (value > maxLen) {
                    maxLen = value
                }
            }
        }

        update("IsoDep", isoDep?.maxTransceiveLength)
        update("NfcA", nfcA?.maxTransceiveLength)
        update("NfcB", nfcB?.maxTransceiveLength)
        update("NfcF", nfcF?.maxTransceiveLength)
        update("NfcV", nfcV?.maxTransceiveLength)

        val extended = try {
            isoDep?.isExtendedLengthApduSupported ?: false
        } catch (e: Exception) {
            Timber.w(e, "isExtendedLengthApduSupported 读取失败")
            false
        }

        return TagMeasurements(
            maxTransceiveLength = maxLen,
            supportsExtendedApdu = extended || maxLen > 261,
            supportsMifareClassic = mfc != null,
            hasNfcA = nfcA != null,
            perTechMax = perTechMax.toMap()
        )
    }

    private fun inferVendor(manufacturer: String, model: String): NFCControllerVendor {
        val manufacturerLower = manufacturer.lowercase()
        val modelLower = model.lowercase()

        return when {
            "oppo" in manufacturerLower || "oneplus" in manufacturerLower -> NFCControllerVendor.NXP
            "google" in manufacturerLower || "pixel" in modelLower -> NFCControllerVendor.NXP
            "samsung" in manufacturerLower && ("s24" in modelLower || "s23" in modelLower || "s22" in modelLower || "s21" in modelLower) -> NFCControllerVendor.NXP
            "samsung" in manufacturerLower -> NFCControllerVendor.BROADCOM
            "xiaomi" in manufacturerLower && ("14" in modelLower || "13" in modelLower) -> NFCControllerVendor.NXP
            "xiaomi" in manufacturerLower -> NFCControllerVendor.BROADCOM
            "huawei" in manufacturerLower -> NFCControllerVendor.BROADCOM
            else -> NFCControllerVendor.OTHER
        }
    }

    private fun inferMifareClassicByVendor(vendor: NFCControllerVendor): Boolean {
        return when (vendor) {
            NFCControllerVendor.NXP -> true
            NFCControllerVendor.BROADCOM -> false
            NFCControllerVendor.ST_MICRO -> false
            NFCControllerVendor.OTHER -> true
        }
    }

    private fun calculateNonStandardCardScore(
        vendor: NFCControllerVendor,
        supportsMifare: Boolean,
        maxLength: Int,
        hasNfcA: Boolean
    ): Int {
        var score = 40
        score += when (vendor) {
            NFCControllerVendor.NXP -> 30
            NFCControllerVendor.BROADCOM -> 8
            NFCControllerVendor.ST_MICRO -> 10
            NFCControllerVendor.OTHER -> 5
        }
        if (supportsMifare) score += 15
        if (hasNfcA) score += 5
        if (maxLength >= 1024) score += 5
        if (maxLength >= 4096) score += 5
        return score.coerceIn(0, 100)
    }

    private fun classifyCapabilityLevel(
        vendor: NFCControllerVendor,
        maxLength: Int,
        supportsExtendedAPDU: Boolean,
        supportsMifare: Boolean
    ): NFCCapabilityLevel {
        return when {
            vendor == NFCControllerVendor.NXP && supportsExtendedAPDU && supportsMifare && maxLength >= 4096 -> NFCCapabilityLevel.FLAGSHIP
            vendor == NFCControllerVendor.NXP && supportsMifare -> NFCCapabilityLevel.ADVANCED
            supportsMifare && maxLength >= 253 -> NFCCapabilityLevel.STANDARD
            else -> NFCCapabilityLevel.BASIC
        }
    }

    private fun buildDetailedCapabilities(
        vendor: NFCControllerVendor,
        maxLength: Int,
        supportsExtendedApdu: Boolean,
        supportsMifare: Boolean,
        measurements: TagMeasurements
    ): Map<String, String> {
        val capabilities = mutableMapOf<String, String>()

        capabilities["控制器厂商(推断)"] = vendor.name
        capabilities["最大传输长度"] = "$maxLength 字节"
        capabilities["扩展APDU"] = if (supportsExtendedApdu) "✅ 支持" else "❌ 不支持"
        capabilities["MIFARE Classic"] = if (supportsMifare) "✅ 支持" else "❌ 不支持"
        capabilities["NfcA"] = if (measurements.hasNfcA) "✅ 支持" else "❌ 未检测"

        if (measurements.perTechMax.isNotEmpty()) {
            measurements.perTechMax.forEach { (tech, len) ->
                capabilities["$tech MaxTransceive"] = "$len"
            }
        }

        capabilities["非标卡支持评估"] = when (vendor) {
            NFCControllerVendor.NXP -> "高"
            NFCControllerVendor.BROADCOM -> "中-低"
            NFCControllerVendor.ST_MICRO -> "中"
            NFCControllerVendor.OTHER -> "未知"
        }

        return capabilities
    }
}
