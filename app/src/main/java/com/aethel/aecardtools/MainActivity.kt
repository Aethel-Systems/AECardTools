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

package com.aethel.aecardtools

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.aethel.aecardtools.data.model.*
import com.aethel.aecardtools.R
import com.aethel.aecardtools.data.AppSettingsManager
import com.aethel.aecardtools.data.AppLanguageManager
import com.aethel.aecardtools.data.AEFSSignatureDetection
import com.aethel.aecardtools.data.AEFSSovereignIdentity
import com.aethel.aecardtools.data.AEFSSovereignKeyVault
import com.aethel.aecardtools.data.ExportFileManager
import com.aethel.aecardtools.data.FullBackupManager
import com.aethel.aecardtools.data.KeyVaultManager
import com.aethel.aecardtools.data.NfcRuntimeContext
import com.aethel.aecardtools.data.PathTransformationEngine
import com.aethel.aecardtools.nfc.UniversalProtocolManager
import com.aethel.aecardtools.nfc.protocol.NFCProtocolDetector
import com.aethel.aecardtools.nfc.safety.RiskWarningContent
import com.aethel.aecardtools.ui.navigation.ContextualBottomBar
import com.aethel.aecardtools.ui.navigation.DrawerHeader
import com.aethel.aecardtools.ui.screens.*
import com.aethel.aecardtools.ui.screens.protocol.RawProtocolTerminalScreen
import com.aethel.aecardtools.ui.screens.protocol.RawTerminalViewModel
import com.aethel.aecardtools.ui.theme.AECardToolsTheme
import com.aethel.aecardtools.ui.viewmodel.CardToolsViewModel
import com.aethel.aecardtools.ui.viewmodel.UIState
import com.aethel.aecardtools.ui.viewmodel.ExtendedCardToolsViewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

private const val TAG = "AECardTools"
private const val DISCLAIMER_PREF_KEY = "aecardtools_disclaimer_accepted"

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var viewModel: CardToolsViewModel
    private lateinit var extendedViewModel: ExtendedCardToolsViewModel
    private var universalProtocolManager: UniversalProtocolManager? = null
    private lateinit var rawTerminalViewModel: RawTerminalViewModel
    private var currentTag: Tag? = null
    private val autoRouteCommand = MutableStateFlow<String?>(null)
    private val isTagProcessing = AtomicBoolean(false)
    private var lastProcessedUid: String? = null
    private var lastProcessedAtMs: Long = 0L
    private var lastAefsSignature: AEFSSignatureDetection? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = AppSettingsManager.load(this)
        AppLanguageManager.apply(settings.appLanguage)
        super.onCreate(savedInstanceState)
        
        // 初始化 Timber 日志
        Timber.plant(Timber.DebugTree())
        Timber.i("=== AECardTools 启动 ===")
        
        // 初始化 Python (ChaquoPy)
        if (!Python.isStarted()) {
            Timber.i("初始化 Python 环境...")
            Python.start(AndroidPlatform(this))
        }
        Timber.i("Python 已初始化")
        
        // 初始化 ViewModel
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return CardToolsViewModel(this@MainActivity) as T
                }
            }
        ).get(CardToolsViewModel::class.java)

        extendedViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return ExtendedCardToolsViewModel(this@MainActivity, viewModel.repository) as T
                }
            }
        ).get(ExtendedCardToolsViewModel::class.java)
        
        // 初始化RawTerminalViewModel
        rawTerminalViewModel = ViewModelProvider(this).get(RawTerminalViewModel::class.java)

        // 应用全局设置（路径人格模式）
        PathTransformationEngine.getInstance().switchMode(settings.pathMode)
        
        // 初始化 NFC
        initializeNFC()
        
        // 设置 UI
        setContent {
            AECardToolsTheme {
                MainAppContentWithDisclaimer(
                    viewModel = viewModel,
                    extendedViewModel = extendedViewModel,
                    context = this,
                    universalProtocolManager = { universalProtocolManager },
                    rawTerminalViewModel = { rawTerminalViewModel },
                    autoRouteFlow = autoRouteCommand,
                    onAutoRouteConsumed = { autoRouteCommand.value = null }
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateNFCStatus()
        enableReaderMode()
    }
    
    override fun onPause() {
        super.onPause()
        disableReaderMode()
        universalProtocolManager?.cleanup()
        universalProtocolManager = null
        NfcRuntimeContext.setCurrentTag(null)
        lastAefsSignature = null
    }
    
    private fun initializeNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Timber.w("此设备不支持 NFC")
            viewModel.updateNFCStatus(NFCStatus.NOT_SUPPORTED)
        } else {
            Timber.i("NFC 已初始化")
            updateNFCStatus()
        }
    }
    
    private fun updateNFCStatus() {
        val nfc = nfcAdapter ?: return
        val nfcEnabled = try {
            nfc.isEnabled
        } catch (e: Exception) {
            false
        }
        
        if (!nfcEnabled) {
            Timber.w("NFC 未启用")
            viewModel.updateNFCStatus(NFCStatus.DISABLED)
        } else {
            Timber.i("NFC 已启用，等待卡片")
            viewModel.updateNFCStatus(NFCStatus.ENABLED_NO_CARD)
        }
    }
    
    private fun enableReaderMode() {
        val nfc = nfcAdapter ?: return
        try {
            // 《重要说明》使用 Reader Mode 而非 Foreground Dispatch
            // 原因：
            // 1. Reader Mode 更适合持续的 NFC 监听，性能更好
            // 2. 不会弹出系统的 NFC 选择器，用户体验更流畅
            // 3. 能够自动处理 NFC Type A 卡片（MIFARE Classic）
            // 4. 可靠的 ReaderCallback 机制
            // 〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜〜
            // 禁止：不要使用 enableForegroundDispatch() 
            // 混用两种模式会导致系统和 App 的 NFC 读写冲突，导致卡片无法正确读写
            
            val options = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100)
            }
            nfc.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options
            )
            Timber.i("读卡模式已启用")
        } catch (e: Exception) {
            Timber.e(e, "启用读卡模式失败")
        }
    }
    
    private fun disableReaderMode() {
        val nfc = nfcAdapter ?: return
        try {
            nfc.disableReaderMode(this)
            Timber.i("读卡模式已禁用")
        } catch (e: Exception) {
            Timber.e(e, "禁用读卡模式失败")
        }
    }
    
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) {
            Timber.w("检测到 Tag 但为空")
            return
        }

        if (!isTagProcessing.compareAndSet(false, true)) {
            Timber.w("忽略重复触发：上一轮读取仍在进行")
            return
        }

        val uidHex = tag.id?.joinToString("") { "%02X".format(it) } ?: "UNKNOWN"
        val now = System.currentTimeMillis()
        if (lastProcessedUid == uidHex && (now - lastProcessedAtMs) < 2000L) {
            isTagProcessing.set(false)
            return
        }
        lastProcessedUid = uidHex
        lastProcessedAtMs = now

        // 判定是否执行全量读取：首次启动（无卡）或用户点击扫描
        val currentActionMode = viewModel.uiState.value.currentActionMode
        val hasNoCard = viewModel.uiState.value.currentCard == null
        val isExplicitScan = hasNoCard || currentActionMode == "home_scan" || currentActionMode == "read_scan"

        Timber.i("=== 发现 MIFARE 卡片: $uidHex (意图: $isExplicitScan) ===")
        viewModel.setNFCScanning(true)
        viewModel.updateNFCStatus(NFCStatus.CARD_DETECTED)
        NfcRuntimeContext.setCurrentTag(tag)

        lifecycleScope.launch(Dispatchers.IO) {
            val mifare = MifareClassic.get(tag)
            if (mifare == null) {
                Timber.i("检测到非 MIFARE Classic，尝试走 NfcA / Ultralight 识别链路")
                val nfcA = NfcA.get(tag)
                val sak = "%02X".format(nfcA?.sak?.toInt()?.and(0xFF) ?: 0)
                val atqa = nfcA?.atqa?.joinToString("") { "%02X".format(it) } ?: "0000"
                val cardIdentity = inferCardIdentityFromTag(
                    tag = tag,
                    uid = uidHex,
                    sak = sak,
                    atqa = atqa,
                    nfcA = nfcA
                )
                initUpmSafely(tag)
                withContext(Dispatchers.Main) {
                    viewModel.setCurrentCard(cardIdentity)
                    viewModel.primeFFICardContext(cardIdentity.uid, cardIdentity.sak, cardIdentity.atqa)
                    extendedViewModel.setCurrentCard(cardIdentity)
                    if (isExplicitScan) {
                        autoRouteCommand.value = if (cardIdentity.cardType.isUltralightLike()) {
                            "read"
                        } else {
                            "raw_protocol_terminal"
                        }
                        viewModel.setCurrentActionMode("")
                        viewModel.updateUIMessage(getString(R.string.message_read_complete, cardIdentity.uid))
                    } else {
                        viewModel.updateUIMessage(getString(R.string.message_link_ready, cardIdentity.uid))
                    }
                    viewModel.setNFCScanning(false)
                    isTagProcessing.set(false)
                }
                return@launch
            }

            var resolvedUid = uidHex
            var isAefsFound = false
            var cardIdentity: CardIdentity? = null
            var hasReadableSector = false

            try {
                if (!mifare.isConnected) mifare.connect()
                mifare.timeout = 2000 // 增加超时容错
                KeyVaultManager.syncPublicAefsKeys(this@MainActivity)
                val fastAefsSector0Keys = KeyVaultManager.getAefsSector0AuthKeys(this@MainActivity)
                val fallbackKeys = KeyVaultManager.getSystemAndImportedAuthKeys(this@MainActivity)

                var detection = detectAefsSignatureWithCandidates(
                    mifare = mifare,
                    physicalUid = uidHex,
                    keyCandidates = fastAefsSector0Keys
                )

                val keyCandidates = if (detection != null && detection.isAefs) {
                    isAefsFound = true
                    lastAefsSignature = detection
                    resolvedUid = AEFSSovereignIdentity.SOVEREIGN_UID
                    KeyVaultManager.getExactAefsAuthKeys(
                        context = this@MainActivity,
                        physicalUid = uidHex,
                        aliasHashHex = detection.aliasHashHex,
                        logicalUid = resolvedUid
                    ).ifEmpty {
                        fastAefsSector0Keys + fallbackKeys
                    }
                } else {
                    detection = detectAefsSignatureWithCandidates(
                        mifare = mifare,
                        physicalUid = uidHex,
                        keyCandidates = fallbackKeys
                    )
                    if (detection != null && detection.isAefs) {
                        isAefsFound = true
                        lastAefsSignature = detection
                        resolvedUid = AEFSSovereignIdentity.SOVEREIGN_UID
                        KeyVaultManager.getExactAefsAuthKeys(
                            context = this@MainActivity,
                            physicalUid = uidHex,
                            aliasHashHex = detection.aliasHashHex,
                            logicalUid = resolvedUid
                        ).ifEmpty {
                            fallbackKeys
                        }
                    } else {
                        fallbackKeys
                    }
                }

                val sectorCount = mifare.sectorCount
                cardIdentity = CardIdentity(
                    uid = resolvedUid,
                    sak = "08",
                    atqa = "0400",
                    cardType = if (sectorCount == 40) CardType.CLASSIC_4K else CardType.CLASSIC_1K,
                    sectorCount = sectorCount,
                    isAEFS = isAefsFound,
                    detectedAt = System.currentTimeMillis()
                )

                // 2. 遍历读取（核心修复点）
                if (isExplicitScan) {
                    for (s in 0 until sectorCount) {
                        // 稳定性检查：如果连接意外断开，尝试重连
                        if (!mifare.isConnected) {
                            runCatching { mifare.connect() }
                        }

                        var usedKeyA: String? = null
                        var usedKeyB: String? = null
                        var isAuthA = false
                        var isAuthB = false

                        // 独立探测 Key A / Key B，不能在先命中 KeyB 后就提前停止，
                        // 否则会出现“数据能读但 KeyA 仍显示 ???”的假阴性。
                        for (key in keyCandidates) {
                            val keyHex = key.joinToString("") { "%02X".format(it) }.uppercase()
                            if (!isAuthA) {
                                val successA = runCatching {
                                    if (!mifare.isConnected) mifare.connect()
                                    mifare.authenticateSectorWithKeyA(s, key)
                                }.getOrDefault(false)
                                if (successA) {
                                    usedKeyA = keyHex
                                    isAuthA = true
                                } else {
                                    runCatching { if (mifare.isConnected) mifare.close() }
                                }
                            }
                            if (!isAuthB) {
                                val successB = runCatching {
                                    if (!mifare.isConnected) mifare.connect()
                                    mifare.authenticateSectorWithKeyB(s, key)
                                }.getOrDefault(false)
                                if (successB) {
                                    usedKeyB = keyHex
                                    isAuthB = true
                                } else {
                                    runCatching { if (mifare.isConnected) mifare.close() }
                                }
                            }
                            if (isAuthA && isAuthB) break
                        }

                        val authStatus = when {
                            isAuthA -> AuthStatus.SUCCESS_A
                            isAuthB -> AuthStatus.SUCCESS_B
                            else -> AuthStatus.FAILED
                        }
                        if (authStatus != AuthStatus.FAILED) {
                            hasReadableSector = true
                        }

                        val blocks = mutableListOf<String>()
                        val blockCount = mifare.getBlockCountInSector(s)

                        if (authStatus != AuthStatus.FAILED) {
                            for (b in 0 until blockCount) {
                                try {
                                    val blockIndex = mifare.sectorToBlock(s) + b
                                    val raw = mifare.readBlock(blockIndex)
                                    var hex = raw.joinToString("") { "%02X".format(it) }.uppercase()

                                    // 【核心修复】：如果是尾块，强行合并我们认证成功的真实 Key A，防止硬件返回 000000000000
                                    if (b == blockCount - 1) {
                                        val ka = usedKeyA ?: "????????????"
                                        val ac = hex.substring(12, 20)
                                        val kb = usedKeyB ?: hex.substring(20, 32)
                                        hex = ka + ac + kb
                                    }
                                    blocks.add(hex)
                                } catch (e: Exception) {
                                    blocks.add("F".repeat(32)) // 读取失败填充 F 而非 0，方便辨认
                                }
                            }
                        } else {
                            // 认证失败：填充占位符
                            repeat(blockCount) { blocks.add("?".repeat(32)) }
                        }

                        // 实时更新当前扇区数据
                        withContext(Dispatchers.Main) {
                            viewModel.updateSectorData(s, blocks, usedKeyA ?: "????????????", usedKeyB ?: "????????????", authStatus)
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "读取链路崩溃")
            } finally {
                // 只有全部读完才关闭
                runCatching { mifare.close() }
            }

            // 3. 初始化 UPM 
            initUpmSafely(tag)

            // 4. UI 跳转与收尾
            if (isExplicitScan && cardIdentity != null) {
                withContext(Dispatchers.Main) {
                    viewModel.setCurrentCard(cardIdentity)
                    viewModel.primeFFICardContext(resolvedUid, "08", "0400")
                    extendedViewModel.setCurrentCard(cardIdentity)
                    autoRouteCommand.value = "read"
                    if (!hasReadableSector) {
                        viewModel.requestAefsUnlockPrompt(resolvedUid)
                    }
                    viewModel.setCurrentActionMode("")
                    viewModel.updateUIMessage(getString(R.string.message_read_complete, resolvedUid))
                }
            } else if (cardIdentity != null) {
                withContext(Dispatchers.Main) {
                    viewModel.updateUIMessage(getString(R.string.message_link_ready, resolvedUid))
                }
            }

            withContext(Dispatchers.Main) {
                viewModel.setNFCScanning(false)
                isTagProcessing.set(false)
            }
        }
    }

    private suspend fun initUpmSafely(tag: Tag) {
        try {
            universalProtocolManager?.cleanup()
            val upm = UniversalProtocolManager(this@MainActivity, tag)
            upm.initialize()
            universalProtocolManager = upm
            withContext(Dispatchers.Main) {
                rawTerminalViewModel.setManager(upm)
            }
            val bridge = com.aethel.aecardtools.nfc.NfcBridgeService(upm)
            bridge.initializePythonBridge()
        } catch (e: Exception) {
            Timber.w("UPT 延迟初始化失败: ${e.message}")
        }
    }

    private fun inferCardIdentityFromTag(
        tag: Tag,
        uid: String,
        sak: String,
        atqa: String,
        nfcA: NfcA?
    ): CardIdentity {
        val mifareClassic = MifareClassic.get(tag)
        if (mifareClassic != null) {
            val sectors = mifareClassic.sectorCount
            val type = when (sectors) {
                40 -> CardType.CLASSIC_4K
                16 -> CardType.CLASSIC_1K
                else -> CardType.UNKNOWN
            }
            return CardIdentity(
                uid = uid,
                sak = sak,
                atqa = atqa,
                cardType = type,
                sectorCount = sectors,
                detectedAt = System.currentTimeMillis()
            )
        }
        // 兼容：部分机型 ROM 不暴露 MifareClassic 技术栈，但仍可通过 SAK 推断是否为 M1
        val sakInt = nfcA?.sak?.toInt()?.and(0xFF) ?: 0
        val inferredClassic = when (sakInt) {
            0x08 -> CardType.CLASSIC_1K
            0x18, 0x28 -> CardType.CLASSIC_4K
            else -> null
        }
        if (inferredClassic != null) {
            return CardIdentity(
                uid = uid,
                sak = sak,
                atqa = atqa,
                cardType = inferredClassic,
                sectorCount = if (inferredClassic == CardType.CLASSIC_4K) 40 else 16,
                detectedAt = System.currentTimeMillis()
            )
        }

        val ultralight = MifareUltralight.get(tag)
        if (ultralight != null) {
            val type = when (ultralight.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> CardType.ULTRALIGHT
                MifareUltralight.TYPE_ULTRALIGHT_C -> CardType.ULTRALIGHT_C
                else -> {
                    val sakInt = nfcA?.sak ?: 0
                    if ((sakInt.toInt() and 0xFF) == 0x00) CardType.NTAG else CardType.ULTRALIGHT
                }
            }
            val pages = when (type) {
                CardType.ULTRALIGHT -> 16
                CardType.ULTRALIGHT_C -> 48
                CardType.NTAG -> 45
                else -> 16
            }
            return CardIdentity(
                uid = uid,
                sak = sak,
                atqa = atqa,
                cardType = type,
                sectorCount = pages,
                detectedAt = System.currentTimeMillis()
            )
        }

        return CardIdentity(
            uid = uid,
            sak = sak,
            atqa = atqa,
            cardType = CardType.UNKNOWN,
            sectorCount = 0,
            detectedAt = System.currentTimeMillis()
        )
    }

    private fun resolveAutoRoute(
        detection: NFCProtocolDetector.DetectionResult,
        cardType: CardType
    ): String {
        val protocols = detection.supportedProtocols
        return when {
            cardType.isUltralightLike() -> "read"
            detection.isStandardMifare -> "read"
            protocols.size == 1 &&
                protocols.contains(com.aethel.aecardtools.nfc.protocol.NFCProtocolType.ISO_DEP) -> {
                "raw_protocol_terminal"
            }
            // 未知卡/非标准 NfcA -> Hex 流透传界面
            protocols.size == 1 &&
                protocols.contains(com.aethel.aecardtools.nfc.protocol.NFCProtocolType.NFC_A) &&
                !detection.isStandardMifare -> {
                "raw_protocol_terminal"
            }
            protocols.any {
                it == com.aethel.aecardtools.nfc.protocol.NFCProtocolType.NFC_B ||
                    it == com.aethel.aecardtools.nfc.protocol.NFCProtocolType.NFC_F ||
                    it == com.aethel.aecardtools.nfc.protocol.NFCProtocolType.NFC_V
            } -> "raw_protocol_terminal"
            else -> "hex_canvas"
        }
    }
    
    private fun readBlock(mifare: MifareClassic, sector: Int, block: Int): ByteArray? {
        return try {
            // 【核心修复 2】使用 系统默认 + 用户导入 的全量密钥库，代替硬编码
            val keyCandidates = com.aethel.aecardtools.data.KeyVaultManager.getAllAuthKeys(this@MainActivity)
            
            for (key in keyCandidates) {
                // 尝试 Key A
                if (runCatching { mifare.authenticateSectorWithKeyA(sector, key) }.getOrDefault(false)) {
                    val blockIndex = mifare.sectorToBlock(sector) + block
                    return mifare.readBlock(blockIndex)
                }
                // 尝试 Key B
                if (runCatching { mifare.authenticateSectorWithKeyB(sector, key) }.getOrDefault(false)) {
                    val blockIndex = mifare.sectorToBlock(sector) + block
                    return mifare.readBlock(blockIndex)
                }
            }
            
            Timber.w("无法读取块 $sector:$block，所有密钥均拒绝")
            null
        } catch (e: Exception) {
            Timber.e(e, "读块错误")
            null
        }
    }
    
    private fun readCardData(mifare: MifareClassic) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 加载全量密钥库
                val keyCandidates = com.aethel.aecardtools.data.KeyVaultManager.getAllAuthKeys(this@MainActivity)

                for (s in 0 until mifare.sectorCount) {
                    try {
                        var foundKeyA: String? = null
                        var foundKeyB: String? = null
                        var isAuthA = false
                        var isAuthB = false

                        // 2. 独立探测 Key A 和 Key B
                        for (key in keyCandidates) {
                            val keyHex = key.joinToString("") { "%02X".format(it) }
                            if (!isAuthA) {
                                var successA = false
                                try {
                                    if (!mifare.isConnected) mifare.connect()
                                    successA = mifare.authenticateSectorWithKeyA(s, key)
                                } catch (e: Exception) {
                                    successA = false
                                }
                                if (successA) {
                                    foundKeyA = keyHex
                                    isAuthA = true
                                } else {
                                    runCatching { mifare.close() }
                                }
                            }
                            if (!isAuthB) {
                                var successB = false
                                try {
                                    if (!mifare.isConnected) mifare.connect()
                                    successB = mifare.authenticateSectorWithKeyB(s, key)
                                } catch (e: Exception) {
                                    successB = false
                                }
                                if (successB) {
                                    foundKeyB = keyHex
                                    isAuthB = true
                                } else {
                                    runCatching { mifare.close() }
                                }
                            }
                            if (isAuthA && isAuthB) break
                        }

                        val authStatus = when {
                            isAuthA -> AuthStatus.SUCCESS_A
                            isAuthB -> AuthStatus.SUCCESS_B
                            else -> AuthStatus.FAILED
                        }

                        val blocks = mutableListOf<String>()
                        val blockCount = mifare.getBlockCountInSector(s)

                        if (authStatus != AuthStatus.FAILED) {
                            for (b in 0 until blockCount) {
                                try {
                                    val blockIndex = mifare.sectorToBlock(s) + b
                                    val rawData = mifare.readBlock(blockIndex)
                                    var hexData = rawData.joinToString("") { "%02X".format(it) }.uppercase()

                                    if (b == blockCount - 1) {
                                        val realKeyA = foundKeyA ?: "000000000000"
                                        val accessBits = hexData.substring(12, 20)
                                        val realKeyB = foundKeyB ?: "000000000000"
                                        hexData = realKeyA + accessBits + realKeyB
                                    }
                                    blocks.add(hexData)
                                } catch (e: Exception) {
                                    blocks.add("00".repeat(16))
                                }
                            }
                        } else {
                            repeat(blockCount) { blocks.add("00".repeat(16)) }
                        }

                        val usedKeyA = foundKeyA ?: "000000000000"
                        val usedKeyB = foundKeyB ?: "000000000000"

                        withContext(Dispatchers.Main) {
                            viewModel.updateSectorData(s, blocks, usedKeyA, usedKeyB, authStatus)
                        }

                    } catch (e: Exception) {
                        Timber.e(e, "读扇区 $s 失败")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "读卡数据异常")
            } finally {
                runCatching { mifare.close() }
                withContext(Dispatchers.Main) {
                    viewModel.setNFCScanning(false)
                }
            }
        }
    }

    /**
     * 【核心修复】使用合法密钥库探测 AEFS 签名
     * 逻辑：遍历 KeyVault 中的所有密钥，成功认证 Sector 0 后再读取 Block 1
     */
    private fun detectAefsSignatureWithCandidates(
        mifare: MifareClassic,
        physicalUid: String,
        keyCandidates: List<ByteArray>
    ): AEFSSignatureDetection? {
        if (keyCandidates.isEmpty()) return null
        val originalTimeout = mifare.timeout
        return try {
            mifare.timeout = 500
            var isAuthenticated = false
            for (key in keyCandidates) {
                val successA = runCatching {
                    if (!mifare.isConnected) mifare.connect()
                    mifare.authenticateSectorWithKeyA(0, key)
                }.getOrDefault(false)
                if (successA) {
                    isAuthenticated = true
                    break
                }
                val successB = runCatching {
                    if (!mifare.isConnected) mifare.connect()
                    mifare.authenticateSectorWithKeyB(0, key)
                }.getOrDefault(false)
                if (successB) {
                    isAuthenticated = true
                    break
                }
            }

            if (!isAuthenticated) {
                return null
            }

            val block1Data = mifare.readBlock(1)
            AEFSSovereignIdentity.detectFromBlock1(
                block1 = block1Data,
                physicalUid = physicalUid
            )
        } catch (e: Exception) {
            Timber.w(e, "按候选密钥探测 AEFS 签名失败")
            null
        } finally {
            runCatching { mifare.timeout = originalTimeout }
        }
    }

    private fun detectAefsSignature(tag: Tag, physicalUid: String): AEFSSignatureDetection? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            if (!mifare.isConnected) {
                mifare.connect()
            }
            KeyVaultManager.syncPublicAefsKeys(this@MainActivity)
            detectAefsSignatureWithCandidates(
                mifare = mifare,
                physicalUid = physicalUid,
                keyCandidates = KeyVaultManager.getAefsSector0AuthKeys(this@MainActivity)
            ) ?: detectAefsSignatureWithCandidates(
                mifare = mifare,
                physicalUid = physicalUid,
                keyCandidates = KeyVaultManager.getSystemAndImportedAuthKeys(this@MainActivity)
            )
        } catch (e: Exception) {
            Timber.e(e, "AEFS 签名探测异常")
            null
        } finally {
            // 注意：这里不 close，由外层的 handle 统一管理连接生命周期
        }
    }
}

/**
 * 主应用内容（含一次性免责声明）
 */
@Composable
fun MainAppContentWithDisclaimer(
    viewModel: CardToolsViewModel,
    extendedViewModel: ExtendedCardToolsViewModel,
    context: Context,
    universalProtocolManager: () -> UniversalProtocolManager?,
    rawTerminalViewModel: () -> RawTerminalViewModel,
    autoRouteFlow: StateFlow<String?>,
    onAutoRouteConsumed: () -> Unit
) {
    var disclaimerAccepted by remember { 
        mutableStateOf(
            context.getSharedPreferences("aecardtools_prefs", Context.MODE_PRIVATE)
                .getBoolean(DISCLAIMER_PREF_KEY, false)
        )
    }
    
    if (!disclaimerAccepted) {
        DisclaimerDialog(
            onAccept = {
                context.getSharedPreferences("aecardtools_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(DISCLAIMER_PREF_KEY, true)
                    .apply()
                disclaimerAccepted = true
                Timber.i("用户已确认免责声明")
            },
            onCancel = {
                Timber.i("用户拒绝了免责声明，应用将退出")
                // 用户拒绝，退出应用
                (context as? android.app.Activity)?.finishAffinity()
            }
        )
    } else {
        MainAppContent(
            viewModel = viewModel,
            extendedViewModel = extendedViewModel,
            context = context,
            universalProtocolManager = universalProtocolManager,
            rawTerminalViewModel = rawTerminalViewModel,
            autoRouteFlow = autoRouteFlow,
            onAutoRouteConsumed = onAutoRouteConsumed
        )
    }
}

/**
 * 免责声明对话框
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(R.string.legal_disclaimer_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFD32F2F)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.legal_disclaimer_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                    fontFamily = FontFamily.Default
                )
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    stringResource(R.string.legal_disclaimer_user_must),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                BulletPoint(stringResource(R.string.legal_disclaimer_rule_1))
                BulletPoint(stringResource(R.string.legal_disclaimer_rule_2))
                BulletPoint(stringResource(R.string.legal_disclaimer_rule_3))
                BulletPoint(stringResource(R.string.legal_disclaimer_rule_4))
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    stringResource(R.string.legal_disclaimer_consequences),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color(0xFFD32F2F)
                )
                
                BulletPoint(stringResource(R.string.legal_disclaimer_risk_1))
                BulletPoint(stringResource(R.string.legal_disclaimer_risk_2))
                BulletPoint(stringResource(R.string.legal_disclaimer_risk_3))
                BulletPoint(stringResource(R.string.legal_disclaimer_risk_4))
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    stringResource(R.string.legal_disclaimer_capability_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Green
                )
            ) {
                Text(stringResource(R.string.legal_disclaimer_accept))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.legal_disclaimer_reject))
            }
        },
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .heightIn(max = 500.dp)
    )
}

/**
 * 项目符号点
 */
@Composable
fun BulletPoint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            "• ",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(12.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 主应用内容 - 工业级架构
 * 使用 Material 3 模态侧滑抽屉 + 动态上下文感知底部栏
 * 符合 c.md v6.0 Sovereign 规范
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    viewModel: CardToolsViewModel,
    extendedViewModel: ExtendedCardToolsViewModel,
    context: Context,
    universalProtocolManager: () -> UniversalProtocolManager?,
    rawTerminalViewModel: () -> RawTerminalViewModel,
    autoRouteFlow: StateFlow<String?>,
    onAutoRouteConsumed: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val extendedState by extendedViewModel.extendedState.collectAsState()
    val operationLogs by viewModel.operationLogs.collectAsState()
    val autoRoute by autoRouteFlow.collectAsState()
    
    var currentRoute by remember { mutableStateOf("home") }
    var aefsUnlockPassphrase by remember { mutableStateOf("") }
    var aefsUnlockSubmitting by remember { mutableStateOf(false) }
    var drawerState by remember { mutableStateOf(DrawerState(DrawerValue.Closed)) }
    var keyVaultRefreshNonce by remember { mutableLongStateOf(0L) }
    var pendingKeyVaultExportContent by remember { mutableStateOf<String?>(null) }
    var pendingFullBackupBytes by remember { mutableStateOf<ByteArray?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val keyVaultImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val content = ExportFileManager.readTextFromUri(context, uri)
            val result = KeyVaultManager.importVaultContent(context, content)
            KeyVaultManager.syncPublicAefsKeys(context)
            keyVaultRefreshNonce++
            viewModel.updateUIMessage(
                context.getString(
                    R.string.message_key_vault_import_summary,
                    result.importedKeys,
                    result.activePackages,
                    result.pendingPackages,
                    result.historyPackages
                )
            )
        }.onFailure {
            viewModel.updateUIMessage(
                context.getString(R.string.message_key_vault_import_failed, it.message ?: ""),
                isError = true
            )
            Timber.e(it, "密钥库导入失败")
        }
    }

    val keyVaultExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingKeyVaultExportContent
        pendingKeyVaultExportContent = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        runCatching {
            ExportFileManager.writeTextToUri(context, uri, content)
            viewModel.updateUIMessage(context.getString(R.string.message_key_vault_exported))
        }.onFailure {
            viewModel.updateUIMessage(
                context.getString(R.string.message_key_vault_export_failed, it.message ?: ""),
                isError = true
            )
            Timber.e(it, "密钥库导出失败")
        }
    }

    val fullBackupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingFullBackupBytes
        pendingFullBackupBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching {
            ExportFileManager.writeBytesToUri(context, uri, bytes)
            viewModel.updateUIMessage(context.getString(R.string.message_full_backup_exported))
        }.onFailure {
            viewModel.updateUIMessage(
                context.getString(R.string.message_full_backup_export_failed, it.message ?: ""),
                isError = true
            )
            Timber.e(it, "完整备份导出失败")
        }
    }

    val fullBackupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(context.getString(R.string.message_backup_read_failed))
            val result = FullBackupManager.importBackup(context, bytes)
            keyVaultRefreshNonce++
            AppLanguageManager.apply(AppSettingsManager.load(context).appLanguage)
            viewModel.updateUIMessage(
                context.getString(
                    R.string.message_full_import_summary,
                    result.restoredFiles,
                    result.restoredDirectories,
                    result.restoredPrefs
                )
            )
            (context as? ComponentActivity)?.recreate()
        }.onFailure {
            viewModel.updateUIMessage(
                context.getString(R.string.message_full_import_failed, it.message ?: ""),
                isError = true
            )
            Timber.e(it, "完整导入失败")
        }
    }
    
    // 内部导航回调
    val onNavigateToRoute: (String) -> Unit = { route ->
        currentRoute = route
        coroutineScope.launch {
            drawerState.close()
        }
        Timber.i("导航到: $route")
    }

    LaunchedEffect(autoRoute) {
        val route = autoRoute ?: return@LaunchedEffect
        currentRoute = route
        onAutoRouteConsumed()
    }

    LaunchedEffect(uiState.pendingAefsUnlockCardUid, extendedState.mountedAEFSPayload, extendedState.errorMessage, extendedState.isLoading) {
        val pendingCardUid = uiState.pendingAefsUnlockCardUid ?: return@LaunchedEffect
        if (!aefsUnlockSubmitting || extendedState.isLoading) return@LaunchedEffect
        if (extendedState.mountedAEFSPayload != null) {
            aefsUnlockSubmitting = false
            aefsUnlockPassphrase = ""
            viewModel.clearAefsUnlockPrompt()
            currentRoute = "aefs_store_data"
        } else if (!extendedState.errorMessage.isNullOrBlank()) {
            aefsUnlockSubmitting = false
            aefsUnlockPassphrase = ""
            viewModel.clearAefsUnlockPrompt()
            viewModel.updateUIMessage(
                context.getString(R.string.aefs_unlock_failed_not_aefs),
                isError = true
            )
        }
    }
    
    // 底部栏回调处理 《重要修复》实现底部栏按钮的真实功能而不仅仅打印日志
    val onBottomBarButtonClick: (String) -> Unit = { action ->
        when {
            currentRoute == "home" && action == "identify" -> {
                // 👇【修复】：仅将 UI 切换回识别模式，不要强制触发扫描
                viewModel.setToolkitMode(false) 
                Timber.i("切换到识别模式")
            }
            currentRoute == "home" && action == "toolkit" -> {
                // 👇【修复】：明确设置为工具模式（防止按多次 toggle 导致反转）
                viewModel.setToolkitMode(true) 
                Timber.i("通过底部栏切换工具模式: ON")
            }
            currentRoute == "key_vault" && action == "import" -> {
                keyVaultImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            currentRoute == "key_vault" && action == "export" -> {
                runCatching {
                    pendingKeyVaultExportContent = KeyVaultManager.exportVaultAsJson(context)
                    keyVaultExportLauncher.launch(
                        ExportFileManager.buildSuggestedFileName(
                            baseName = "aecardtools_key_vault",
                            extension = "json"
                        )
                    )
                }.onFailure {
                    viewModel.updateUIMessage(
                        context.getString(R.string.message_key_vault_export_failed, it.message ?: ""),
                        isError = true
                    )
                    Timber.e(it, "导出密钥库失败")
                }
            }
            currentRoute == "key_vault" && action == "scan" -> {
                Timber.i("自动扫描密钥")
            }
        }
    }
    
    // Material 3 ModalNavigationDrawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                // 抽屉头部
                DrawerHeader()
                
                Divider()
                
                // 导航菜单项
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val menuItems = listOf(
                        Triple("home", stringResource(R.string.title_home), Icons.Default.Home),
                        Triple("key_vault", stringResource(R.string.title_key_vault), Icons.Default.VpnKey),
                        Triple("nfc_capability", stringResource(R.string.title_nfc_capability), Icons.Default.Info),
                        Triple("settings", stringResource(R.string.settings_title), Icons.Default.Settings)
                    )
                    
                    menuItems.forEach { (route, label, icon) ->
                        NavigationDrawerItem(
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            selected = currentRoute == route,
                            onClick = {
                                onNavigateToRoute(route)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.secondary,
                                selectedTextColor = MaterialTheme.colorScheme.secondary
                            )
                        )
                    }
                }
            }
        },
        content = {
            if (currentRoute == "read" && uiState.pendingAefsUnlockCardUid != null) {
                AlertDialog(
                    onDismissRequest = {
                        if (!aefsUnlockSubmitting) {
                            aefsUnlockPassphrase = ""
                            viewModel.clearAefsUnlockPrompt()
                        }
                    },
                    title = { Text(stringResource(R.string.aefs_unlock_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.aefs_unlock_message))
                            OutlinedTextField(
                                value = aefsUnlockPassphrase,
                                onValueChange = { aefsUnlockPassphrase = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.aefs_sovereign_passphrase)) },
                                singleLine = true,
                                enabled = !aefsUnlockSubmitting
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val cardUid = uiState.pendingAefsUnlockCardUid ?: return@Button
                                aefsUnlockSubmitting = true
                                extendedViewModel.readMountedAEFSPayload(cardUid, aefsUnlockPassphrase)
                            },
                            enabled = !aefsUnlockSubmitting && aefsUnlockPassphrase.isNotBlank()
                        ) {
                            Text(stringResource(R.string.aefs_unlock_confirm))
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = {
                                aefsUnlockPassphrase = ""
                                viewModel.clearAefsUnlockPrompt()
                            },
                            enabled = !aefsUnlockSubmitting
                        ) {
                            Text(stringResource(R.string.aefs_unlock_cancel))
                        }
                    }
                )
            }
            Scaffold(
                topBar = {
                    // 顶部应用栏
                    TopAppBar(
                        title = {
                            val titleText = when (currentRoute) {
                                "home" -> stringResource(R.string.title_home)
                                "key_vault" -> stringResource(R.string.title_key_vault)
                                "nfc_capability" -> stringResource(R.string.title_nfc_capability)
                                "settings" -> stringResource(R.string.settings_title)
                                else -> stringResource(R.string.app_name)
                            }
                            Text(titleText, color = MaterialTheme.colorScheme.onPrimary)
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    drawerState.open()
                                }
                            }) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.menu),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                },
                bottomBar = {
                    ContextualBottomBar(
                        currentRoute = currentRoute,
                        onButtonClick = onBottomBarButtonClick
                    )
                },
                modifier = Modifier.fillMaxSize()
            ) { contentPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 内容区域 - 根据当前路由显示不同的屏幕
                    when (currentRoute) {
                        "home" -> HomeScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onScanClick = {
                                viewModel.setCurrentActionMode("home_scan")
                                viewModel.setNFCScanning(true)
                                Timber.i("开始NFC扫描")
                            },
                            onNavigateToCard = { card ->
                                viewModel.selectRecentCard(
                                    RecentCardHistory(
                                        cardIdentity = card,
                                        detectionCount = 1
                                    )
                                )
                                onNavigateToRoute("card_detail")
                            },
                            onNavigateToKeys = { onNavigateToRoute("keys") },
                            onNavigateToLogs = { onNavigateToRoute("logs") },
                            onNavigateToSettings = { onNavigateToRoute("settings") },
                            onNavigateToWrite = { onNavigateToRoute("write") },
                            onNavigateToRead = { onNavigateToRoute("read") },
                            onNavigateToACCalculator = { onNavigateToRoute("ac_calculator") },
                            onNavigateToRegistryEditor = { onNavigateToRoute("registry_editor") },
                            onNavigateToHexCanvas = { onNavigateToRoute("hex_canvas") },
                            onNavigateToKeyVault = { onNavigateToRoute("key_vault") },
                            onNavigateToAefsWizard = { onNavigateToRoute("aefs_wizard") },
                            onNavigateToAefsStoreData = { onNavigateToRoute("aefs_store_data") },
                            onNavigateToAefsStoreFile = { onNavigateToRoute("aefs_store_file") },
                            onNavigateToAefsUpgrade = { onNavigateToRoute("aefs_upgrade") },
                            onNavigateToCloneCard = { onNavigateToRoute("clone_card") },
                            onNavigateToRawTerminal = { onNavigateToRoute("raw_protocol_terminal") }
                        )

                        "key_vault" -> KeyVaultScreen(
                            onBackClick = { onNavigateToRoute("home") },
                            refreshNonce = keyVaultRefreshNonce,
                            statusMessage = uiState.errorMessage ?: uiState.successMessage
                        )
                        
                        "settings" -> SettingsScreen(
                            onBackClick = { onNavigateToRoute("home") },
                            onLanguageChange = {
                                AppLanguageManager.apply(it)
                                (context as? ComponentActivity)?.recreate()
                            },
                            onCreateFullBackup = {
                                runCatching {
                                    pendingFullBackupBytes = FullBackupManager.createBackup(context)
                                    fullBackupExportLauncher.launch(
                                        ExportFileManager.buildSuggestedFileName(
                                            baseName = "aecardtools_full_backup",
                                            extension = "bin"
                                        )
                                    )
                                }.onFailure {
                                    viewModel.updateUIMessage(
                                        context.getString(R.string.message_full_backup_generation_failed, it.message ?: ""),
                                        isError = true
                                    )
                                    Timber.e(it, "完整备份生成失败")
                                }
                            },
                            onImportFullBackup = {
                                fullBackupImportLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            },
                            onClearAllData = {
                                runCatching {
                                    FullBackupManager.clearAllData(context)
                                    keyVaultRefreshNonce++
                                    AppLanguageManager.apply(AppSettingsManager.load(context).appLanguage)
                                    viewModel.updateUIMessage(context.getString(R.string.message_all_data_cleared))
                                    (context as? ComponentActivity)?.recreate()
                                }.onFailure {
                                    viewModel.updateUIMessage(
                                        context.getString(R.string.message_clear_data_failed, it.message ?: ""),
                                        isError = true
                                    )
                                    Timber.e(it, "清除全部数据失败")
                                }
                            },
                            dataSafetyStatus = uiState.errorMessage ?: uiState.successMessage
                        )

                        "nfc_capability" -> NFCCapabilityScreen(
                            manager = universalProtocolManager(),
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "card_detail" -> CardDetailScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "write" -> if (uiState.currentCard?.cardType?.isUltralightLike() == true) {
                            UltralightWriteScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                onBackClick = { onNavigateToRoute("home") }
                            )
                        } else {
                            WriteScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                onBackClick = { onNavigateToRoute("home") }
                            )
                        }

                        "read" -> if (uiState.currentCard?.cardType?.isUltralightLike() == true) {
                            UltralightReadScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                onBackClick = { onNavigateToRoute("home") }
                            )
                        } else {
                            ReadScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                onBackClick = { onNavigateToRoute("home") }
                            )
                        }
                        
                        "keys" -> KeyManagerScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            operationLogs = operationLogs,
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "logs" -> LogsScreen(
                            logs = operationLogs,
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "ac_calculator" -> AccessControlCalculatorScreen(
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "registry_editor" -> VirtualRegistryEditorScreen(
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "hex_canvas" -> HexCanvasScreen(
                            onBackClick = { onNavigateToRoute("home") },
                            fallbackSectors = uiState.sectors,
                            cardIdentity = uiState.currentCard
                        )
                        
                        "aefs_wizard" -> AEFSFormattingWizardScreen(
                            viewModel = extendedViewModel,
                            cardUid = uiState.currentCard?.uid ?: "",
                            extendedState = extendedState,
                            onDismiss = { onNavigateToRoute("home") },
                            onSuccess = { onNavigateToRoute("home") }
                        )

                        "aefs_store_data" -> AEFSStoreDataScreen(
                            viewModel = extendedViewModel,
                            extendedState = extendedState,
                            cardUid = uiState.currentCard?.uid ?: "",
                            onBackClick = { onNavigateToRoute("home") }
                        )

                        "aefs_store_file" -> AEFSStoreFileScreen(
                            viewModel = extendedViewModel,
                            extendedState = extendedState,
                            cardUid = uiState.currentCard?.uid ?: "",
                            onBackClick = { onNavigateToRoute("home") }
                        )

                        "aefs_upgrade" -> AEFSUpgradeScreen(
                            viewModel = extendedViewModel,
                            extendedState = extendedState,
                            cardUid = uiState.currentCard?.uid ?: "",
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "clone_card" -> CloneCardScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onBackClick = { onNavigateToRoute("home") }
                        )
                        
                        "raw_protocol_terminal" -> {
                            // 👇【修改】不再拦截 null，无论如何都进入直通终端
                            RawProtocolTerminalScreen(
                                manager = universalProtocolManager(),
                                viewModel = rawTerminalViewModel()
                            )
                        }
                        
                        else -> HomeScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onScanClick = {
                                viewModel.setCurrentActionMode("home_scan")
                                viewModel.setNFCScanning(true)
                            },
                            onNavigateToCard = { onNavigateToRoute("card_detail") },
                            onNavigateToKeys = { onNavigateToRoute("keys") },
                            onNavigateToLogs = { onNavigateToRoute("logs") },
                            onNavigateToSettings = { onNavigateToRoute("settings") },
                            onNavigateToWrite = { onNavigateToRoute("write") },
                            onNavigateToRead = { onNavigateToRoute("read") },
                            onNavigateToACCalculator = { onNavigateToRoute("ac_calculator") },
                            onNavigateToRegistryEditor = { onNavigateToRoute("registry_editor") },
                            onNavigateToHexCanvas = { onNavigateToRoute("hex_canvas") },
                            onNavigateToKeyVault = { onNavigateToRoute("key_vault") },
                            onNavigateToAefsWizard = { onNavigateToRoute("aefs_wizard") },
                            onNavigateToAefsStoreData = { onNavigateToRoute("aefs_store_data") },
                            onNavigateToAefsStoreFile = { onNavigateToRoute("aefs_store_file") },
                            onNavigateToAefsUpgrade = { onNavigateToRoute("aefs_upgrade") },
                            onNavigateToCloneCard = { onNavigateToRoute("clone_card") },
                            onNavigateToRawTerminal = { onNavigateToRoute("raw_protocol_terminal") }
                        )
                    }
                }
            }
        }
    )
}

/**
 * 密钥管理屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagerScreen(
    viewModel: CardToolsViewModel,
    uiState: UIState,
    operationLogs: List<OperationLogEntry>,
    onBackClick: () -> Unit = {}
) {
    var importedKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importSucceeded by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        importedKeys = KeyVaultManager.getImportedKeys(context)
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // 读取文件内容
                val inputStream = context.contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader().use { it?.readText() ?: "" }
                inputStream?.close()
                
                // 解析密钥文件（支持多种格式）
                val keys = mutableListOf<String>()
                content.split("\n").forEach { line ->
                    val trimmed = line.trim()
                    // 支持十六进制密钥（12 个字符）或带冒号分隔的格式
                    if (trimmed.matches(Regex("^[A-Fa-f0-9]{12}$")) || 
                        trimmed.matches(Regex("^[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){5}$"))) {
                        val hexKey = trimmed.replace(":", "")
                        if (hexKey !in keys) {
                            keys.add(hexKey.uppercase())
                        }
                    }
                }
                
                if (keys.isNotEmpty()) {
                    val merged = (KeyVaultManager.getImportedKeys(context) + keys).distinct()
                    KeyVaultManager.saveImportedKeys(context, merged)
                    importedKeys = merged
                    importStatus = context.getString(R.string.message_keys_imported_count, keys.size)
                    importSucceeded = true
                    Timber.i("导入 ${keys.size} 个密钥：${keys.take(3).joinToString(",")}")
                } else {
                    importStatus = context.getString(R.string.message_key_file_invalid)
                    importSucceeded = false
                    Timber.w("密钥文件格式错误或为空")
                }
            } catch (e: Exception) {
                importStatus = context.getString(R.string.message_key_import_failed, e.message ?: "")
                importSucceeded = false
                Timber.e(e, "密钥文件导入异常")
            }
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(stringResource(R.string.key_manager_title), color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 默认密钥库
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.key_manager_system_defaults), style = MaterialTheme.typography.labelLarge)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.key_manager_default_factory), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.key_manager_default_zero), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.key_manager_mad_default), style = MaterialTheme.typography.bodySmall)
                }
            }
            
            // 自定义密钥导入
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.key_manager_custom_vault), style = MaterialTheme.typography.labelLarge)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Button(
                        onClick = { 
                            filePickerLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.key_manager_import_file))
                    }
                    
                    // 导入状态提示
                    if (importStatus != null) {
                        Text(
                            text = importStatus ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (importSucceeded) Color.Green else Color.Red,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    
                    // 已导入的密钥列表
                    if (importedKeys.isNotEmpty()) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.key_manager_imported_count, importedKeys.size), 
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(importedKeys.take(10)) { key ->
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                            if (importedKeys.size > 10) {
                                item {
                                    Text(
                                        text = stringResource(R.string.key_manager_more_keys, importedKeys.size - 10),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // 操作日志
            if (operationLogs.isNotEmpty()) {
                Text(
                    stringResource(R.string.key_manager_recent_operations),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(operationLogs.takeLast(10)) { log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (log.success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (log.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = if (log.success) Color.Green else Color.Red,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    log.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalContextComposable(): android.content.Context = androidx.compose.ui.platform.LocalContext.current

/**
 * 日志屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(logs: List<OperationLogEntry>, onBackClick: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(stringResource(R.string.operation_log), color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(logs.size) { idx ->
                        val log = logs[idx]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (log.success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    log.type,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "${log.error ?: stringResource(R.string.logs_success)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacySettingsScreen(onBackClick: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(stringResource(R.string.settings_title), color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // NFC 设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_nfc_config), style = MaterialTheme.typography.labelLarge)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_nfc_timeout), modifier = Modifier.weight(1f))
                        Text("1500 ms", style = MaterialTheme.typography.bodySmall)
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_retry_count), modifier = Modifier.weight(1f))
                        Text("3", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            // UI 设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.legacy_settings_ui_config), style = MaterialTheme.typography.labelLarge)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.legacy_settings_dark_mode), modifier = Modifier.weight(1f))
                        Checkbox(checked = true, onCheckedChange = {})
                    }
                }
            }
            
            // 关于
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.labelLarge)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("AECardTools v1.0.0", style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.home_footer_subtitle), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
