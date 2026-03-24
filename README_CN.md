# AECardTools & AEFS - v6.0 Sovereign

[English](README.md)

## ⚠️ 工业级法律免责声明

**重要：在下载、编译或运行本软件前，请务必仔细阅读。**

1. **合法性与授权保证**：本工具仅供合法的安全研究、授权穿透测试、学术开发及个人拥有的卡片管理使用。使用者必须确保在适用的国家或地区法律下拥有完全的操作权利。严禁将本工具用于任何非法目的，包括但不限于篡改支付凭证、冒充身份、非法获取门禁权限等。
2. **硬件损坏风险（Brick Risk）**：AECardTools 提供了深达物理层与协议底层的原始指令透传能力。**错误的底层指令（尤其是对 Sector 0 或尾块控制位的误操作）将导致物理卡片不可逆的损坏或永久锁定（即“变砖”）**。
3. **主权数据自负**：AEFS 系统采用极高强度的加密。**如果您丢失了主权密码（Passphrase）或本地生成的快照快照密钥，任何人都无法（包括开发者）恢复卡片中的加密数据。**
4. **无担保与责任豁免**：本软件按“原样”提供，不附带任何明示或暗示的保证。开发者不对因使用本工具导致的任何直接或间接损失（数据丢失、硬件报废、法律追责、经济赔偿）承担任何法律责任。
5. **行为即确认**：**安装或运行本程序即表示您已完全理解、接受并自愿承担上述所有风险及法律后果。**
> **关于出厂默认密钥的说明**：本软件仅预置了由芯片生产厂商（如 NXP）在其公开技术文档中发布的、用于空白芯片初始化的通用默认密钥。本软件不包含、不分发任何通过非法手段获取的私有密钥、破解字典或被泄露的行业敏感数据。

---

## 🏛️ 主权愿景：从“读写器”到“操作系统” (Sovereign Vision)

传统的 NFC 读写工具仅仅将卡片视为一串线性排列的原始字节，缺乏鲁棒性、一致性保护与语义化管理。

**AECardTools 重新定义了 NFC 存储的本质。** 它的核心是 **AEFS (Aethel File System)** —— 这不仅仅是一个文件系统，更是一整套 **主权存储协议**。

我们的目标是将廉价、普遍但脆弱的 NFC 芯片（如 MIFARE Classic, Ultralight, NTAG）进化为**高强度、抗篡改、具备原子性事务保障的加密存储节点**。AECardTools 作为 AEFS 协议的官方参考终端，为用户提供了像操作现代操作系统一样管理物理卡片的体验，确保数据在物理层面的混乱中依然保持绝对的主权与完整。

---

## 🛡️ AEFS 三大核心技术支柱 (The Three Pillars)

AEFS 的革命性在于它在极其受限的 NFC 硬件环境（仅 1KB-4KB 空间）中，实现了企业级存储协议的功能。

### 1. LCOW (Log-structured Copy-on-Write) 事务引擎
这是 AEFS 防断电损坏的生命线。
*   **原子性提交（Atomic Commit）**：在传统的 NFC 写入中，如果在写入一半时卡片移开，数据就会损坏（脏数据）。在 AEFS 中，数据被视为事务。新数据会先写入物理空闲池，只有在所有数据帧确认写入成功后，才会最后原子性地更新位于 Sector 0/15 的“锚点（Anchor）”。如果在更新锚点前通信中断，系统会自动回滚，卡片将保持在上一个有效状态，**彻底杜绝“半写”导致的死卡风险**。
*   **逻辑/物理分离（VTL）**：用户通过 `AEFS://` 逻辑路径操作数据，而底层的 LCOW 引擎会自动管理物理块的分配。它包含内置的**磨损均衡（Wear Leveling）**算法，自动平衡物理块的擦写频率，并执行静默**垃圾回收（GC）**以优化存储效率。

### 2. Sovereign 全链路加密与零信任架构
AEFS 确保您的数据在物理介质上是绝对的黑盒。
*   **硬核算法清单**：
    *   **根密钥派生**：基于 `Argon2id v1.3`。结合硬件特有的 UID 盐值与用户主权密码，提供极高的抗 GPU/ASIC 暴力破解强度。
    *   **认证加密（AEAD）**：采用 `XChaCha20-Poly1305` 或 `AES-128`。每一帧数据都带有独立的 Nonce 和 MAC 标签，确保数据既不可读也不可被伪造。
    *   **全盘完整性校验**：系统为全盘数据构建 `Merkle Tree`。在挂载卡片时，系统会计算当前的根哈希并与 Superblock 对比。即使外部设备只篡改了一个比特位，AEFS 也会立即检测到并锁定挂载，防止任何形式的重放或注入攻击。
*   **零信任架构**：数据加解密严格在 Python 高级核心引擎中完成。Kotlin UI 层仅负责显示处理后的逻辑结果，明文载荷从不经过系统剪贴板或非安全内存区域。

### 3. 虚拟注册表与路径人格 (Virtual Registry)
AEFS 将扁平的卡片空间抽象为结构化的**虚拟注册表**。
*   **语义化管理**：您可以像管理 Windows 注册表或 Unix 配置一样管理卡片。例如：`AEFS://Payload/Data_Records/WiFi/Home/Pass`。
*   **多态路径人格 (Path Personality)**：系统支持根据用户的操作习惯实时切换显示规范：
    *   **AETHEL 模式** (默认)：`>|aefs->:registry-System-Header`（强调主权节点关系）。
    *   **UNIX 模式**：`/mnt/aefs/registry/System/Header`（标准文件系统路径）。
    *   **Windows Legacy 模式**：`A:\registry\System\Header`（兼容传统视图）。

---

## ⚡ 主权恢复能力 (Resilience & Recovery)

AEFS 专为极端不稳定的无线通信环境设计，具备强大的自愈能力。

*   **半写状态救援 (Recovery Diagnostic)**：
    由于 AEFS 采用了 LCOW 事务机制，如果写入被意外中断，卡片上会留下“残余锚点”和旧有的快照。AECardTools 能够利用本地 KeyVault 中存储的快照映射表，对处于不一致状态的卡片进行深度救援诊断，帮助用户恢复未提交的事务或安全回滚，将“变砖”的卡片救回。
*   **顺序平铺一键升级 (Sequential Write Upgrade)**：
    随着 AEFS 协议从 v5.0 演进至 v6.0，存储效率得到了极大提升。本终端支持“原地无损升级”，可自动识别卡片上的旧版碎片化布局，并通过一次优化的顺序写入流，将其迁移至最新的 v6.0 顺序平铺布局，提升读写寿命并释放更多可用容量。

---

## 🛠️ 工业级全场景功能矩阵

### 1. 通用协议终端 (Raw Protocol Terminal)
突破系统 API 限制，构建了一条直达 NFC 硬件底层的全协议隧道：
*   **支持协议**：ISO-DEP (CPU卡/EMV), NFC-A (MIFARE系列), NFC-B, NFC-F (FeliCa), NFC-V (ISO 15693)。
*   **APDU 透传**：开发者可以直接输入 Hex 指令（如 `00A4040007...`）执行，自带智能状态字 (SW) 解析。
*   **后门指令支持**：支持向特定变种 MIFARE 卡（Gen1a 魔术卡等）发送厂商特殊指令（如 `0x40`, `0x43`），无视标准的 Sector 认证即可强写 Block 0。

### 2. 深度硬件探查与 Hex 审查 (Forensics & Hex Canvas)
*   **硬件指纹与法医分析**：通过 SAK, ATQA, BCC 和系统厂商特征字典，深度鉴别卡片是否为 NXP 原厂芯片或克隆/模拟卡，出具 Risk Level 报告。
*   **Hex 画布 (Hex Canvas)**：将卡片数据呈现为带有语法高亮、按逻辑和物理区域（Header & Metadata, Encrypted Data, Payload）着色的交互式十六进制画布，支持虚拟地址 (VA) 到物理地址 (PA) 的实时映射寻址。

### 3. 密钥库与控制工程 (Key Vault & Access Control)
*   **密钥库**：内置常见厂商默认密钥字典，支持外部 `.keys` / `.mct` / `.json` 文件批量导入，系统在读卡时自动在后台执行静默碰撞。
*   **密钥分片与恢复 (Key Sharding & Recovery)**：内置字典碰撞和启发式穷举引擎。支持基于 Shamir Secret Sharing (SSS) 或 XOR 的密钥分片管理，消除单点故障。
*   **访问位计算器 (Access Bits Calculator)**：可视化解析和编译 MIFARE Classic 的 Block 3 尾块控制字 (Byte 6-8)。将晦涩的反码校验图形化，避免手动计算错误导致的卡片永久锁死（死锁）。

### 4. 敏感指令安全拦截器 (Safety Interceptor)
*   **动态熔断机制**：预判并拦截高危指令。例如，阻止用户向标准 1K 卡的 Block 0 写入数据，或在计算出目标 BCC（Block Check Character）错误时，直接拦截写入请求，防止产生“砖卡”。
*   **CPU卡保护**：当检测到 `VERIFY PIN` (APDU `00 20`) 连续返回 `63 Cx`（密码错误警告）时，自动切断会话，防止 CPU 卡被物理锁定。

---

## 🏗️ 架构设计

系统采用**混合架构**，利用 Kotlin 处理高性能的安卓原生生命周期与 NFC 硬件通信，利用 Python (基于 Chaquopy 平台) 驱动极其复杂的密码学和 AEFS 文件系统逻辑。

```text
┌─────────────────────────────────────────────────────────────┐
│                 AECardTools UI (Jetpack Compose)            │
│  - HomeScreen  - AEFSWizard  - RawTerminal  - HexCanvas     │
├─────────────────────────────────────────────────────────────┤
│         Kotlin ViewModels & Universal Protocol Manager      │
│  - Session Manager     - NfcA/IsoDep/NfcV Handlers          │
│  - Safety Interceptor  - Async Coroutine Schedulers         │
├──────────────────────────────┬──────────────────────────────┤
│                              │                              │
│       Android NFC Stack      │    Chaquopy FFI Bridge       │
│ (ReaderMode, MifareClassic,  │    (JNI / CPython 3.10)      │
│  IsoDep, NfcA/B/F/V)         │                              │
│                              │                              │
├──────────────────────────────┴──────────────────────────────┤
│                   Python Core Engine                        │
│                                                             │
│  [ lcow_engine.py ]     => 虚拟内存, 事务日志, 垃圾回收     │
│  [ crypto_module.py ]   => Argon2id, XChaCha20, Merkle Tree │
│  [ AEFStools.py ]       => 镜像构建, 压缩, 动态载荷打包     │
│  [ nfc_interface.py ]   => 反向调用 Kotlin 硬件读写回调     │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ 构建指南 (Build & Run)

### 开发环境要求
*   **Android Studio**：2024.1.1 (Ladybug) 或更高版本。
*   **Android SDK**：API 28 至 36。
*   **Python 运行环境**：通过 Chaquopy 自动配置。
*   **硬件需求**：具有完整 NFC 控制器的 Android 真机（推荐使用 NXP 芯片组以获得完整的协议透传能力）。

### 编译流程
1.  `git clone https://github.com/Aethel-Systems/AECardTools.git`
2.  在 Android Studio 中打开项目，Gradle 会自动拉取 `argon2-cffi`, `cryptography` 等原生 Python 扩展库。
3.  连接设备，执行 `Run`。
4.  **首次启动务必确认免责声明，否则核心引擎将拒绝初始化底层 NFC 栈。**

---

**AECardTools - 赋予每一枚芯片以主权。**
*最后更新：2026年3月22日*
