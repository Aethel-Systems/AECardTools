# AECardTools & AEFS - v6.0 Sovereign

[中文](README_CN.md)

## ⚠️ Industrial-Grade Legal Disclaimer

**IMPORTANT: Please read carefully before downloading, compiling, or running this software.**

1.  **Legality and Authorization Guarantee**: This tool is intended solely for legitimate security research, authorized penetration testing, academic development, and management of personally owned cards. Users must ensure they have full operational rights under applicable national or regional laws. It is strictly prohibited to use this tool for any illegal purposes, including but not limited to tampering with payment credentials, identity impersonation, or gaining unauthorized access to entry systems.
2.  **Hardware Damage Risk (Brick Risk)**: AECardTools provides raw instruction passthrough capabilities deep into the physical and protocol layers. **Incorrect low-level instructions (especially misoperations on Sector 0 or the Trailer Block control bits) will result in irreversible physical damage or permanent locking of the card (i.e., "bricking").**
3.  **Sovereign Data Responsibility**: The AEFS system employs extremely high-strength encryption. **If you lose your Sovereign Passphrase or the locally generated snapshot keys, no one (including the developer) can recover the encrypted data within the card.**
4.  **No Warranty and Liability Waiver**: This software is provided "as is," without warranty of any kind, express or implied. The developer shall not be held liable for any direct or indirect losses (data loss, hardware destruction, legal prosecution, economic compensation) resulting from the use of this tool.
5.  **Action as Confirmation**: **Installing or running this program indicates that you fully understand, accept, and voluntarily assume all the aforementioned risks and legal consequences.**

> **Note on Factory Default Keys**: This software only pre-installs general default keys published by chip manufacturers (such as NXP) in their public technical documentation for the initialization of blank chips. This software does not contain or distribute any private keys, cracking dictionaries, or leaked sensitive industry data obtained through illegal means.

---

## 🏛️ Sovereign Vision: From "Reader" to "Operating System"

Traditional NFC read/write tools merely treat cards as a series of linearly arranged raw bytes, lacking robustness, consistency protection, and semantic management.

**AECardTools redefines the essence of NFC storage.** At its core is **AEFS (AethelOS Encrypted File System)**—which is not just a file system, but a complete set of **Sovereign Storage Protocols**.

Our goal is to evolve cheap, ubiquitous, yet fragile NFC chips (such as MIFARE Classic, Ultralight, NTAG) into **high-strength, tamper-resistant encrypted storage nodes with atomic transaction guarantees**. As the official reference terminal for the AEFS protocol, AECardTools provides users with an experience similar to managing a physical card like a modern operating system, ensuring that data maintains absolute sovereignty and integrity amidst physical-level chaos.

---

## 🛡️ AEFS Three Core Technical Pillars

The revolutionary nature of AEFS lies in its ability to implement enterprise-grade storage protocol features within extremely constrained NFC hardware environments (only 1KB-4KB of space).

### 1. LCOW (Log-structured Copy-on-Write) Transaction Engine
This is the lifeline for AEFS against power-failure corruption.
*   **Atomic Commit**: In traditional NFC writing, if the card is moved away mid-write, the data becomes corrupted (dirty data). In AEFS, data is treated as a transaction. New data is first written to a physical free pool; only after all data frames are confirmed as successfully written will the "Anchor" located at Sector 0/15 be updated atomically. If communication is interrupted before the anchor update, the system automatically rolls back, and the card remains in its last valid state, **completely eliminating the risk of dead cards caused by "partial writes."**
*   **Logical/Physical Separation (VTL)**: Users manipulate data through the `AEFS://` logical path, while the underlying LCOW engine automatically manages the allocation of physical blocks. It includes built-in **Wear Leveling** algorithms to automatically balance the erase/write frequency of physical blocks and performs silent **Garbage Collection (GC)** to optimize storage efficiency.

### 2. Sovereign End-to-End Encryption & Zero-Trust Architecture
AEFS ensures your data is an absolute black box on the physical medium.
*   **Hardcore Algorithm List**:
    *   **Root Key Derivation**: Based on `Argon2id v1.3`. Combined with hardware-specific UID salts and the user’s Sovereign Passphrase, it provides extremely high resistance against GPU/ASIC brute-force attacks.
    *   **Authenticated Encryption (AEAD)**: Employs `XChaCha20-Poly1305` or `AES-128`. Each frame of data carries an independent Nonce and MAC tag, ensuring data is neither readable nor forgeable.
    *   **Full-Disk Integrity Verification**: The system constructs a `Merkle Tree` for all data on the disk. When mounting a card, the system calculates the current root hash and compares it with the Superblock. Even if an external device tampers with a single bit, AEFS will immediately detect it and lock the mount, preventing any form of replay or injection attacks.
*   **Zero-Trust Architecture**: Data encryption and decryption are strictly performed within the high-level Python core engine. The Kotlin UI layer is only responsible for displaying processed logical results; plaintext payloads never pass through the system clipboard or non-secure memory regions.

### 3. Virtual Registry & Path Personality
AEFS abstracts the flat card space into a structured **Virtual Registry**.
*   **Semantic Management**: You can manage the card just like managing a Windows Registry or Unix configuration. For example: `AEFS://Payload/Data_Records/WiFi/Home/Pass`.
*   **Multi-mode Path Personality**: The system supports real-time switching of display conventions based on user habits:
    *   **AETHEL Mode** (Default): `>|aefs->:registry-System-Header` (emphasizing sovereign node relationships).
    *   **UNIX Mode**: `/mnt/aefs/registry/System/Header` (standard file system path).
    *   **Windows Legacy Mode**: `A:\registry\System\Header` (compatible with traditional views).

---

## ⚡ Resilience & Recovery

AEFS is specifically designed for extremely unstable wireless communication environments and possesses powerful self-healing capabilities.

*   **Partial-Write State Rescue (Recovery Diagnostic)**:
    Since AEFS employs the LCOW transaction mechanism, if a write is accidentally interrupted, "residual anchors" and old snapshots will remain on the card. AECardTools can utilize the snapshot mapping table stored in the local KeyVault to perform deep rescue diagnostics on cards in an inconsistent state, helping users recover uncommitted transactions or safely roll back, saving "bricked" cards.
*   **Sequential Tiling One-Click Upgrade (Sequential Write Upgrade)**:
    As the AEFS protocol evolves from v5.0 to v6.0, storage efficiency has been greatly improved. This terminal supports "in-place lossless upgrades," which can automatically identify old fragmented layouts on the card and migrate them to the latest v6.0 sequential tiling layout through an optimized sequential write stream, enhancing write life and releasing more usable capacity.

---

## 🛠️ Industrial-Grade Full-Scenario Feature Matrix

### 1. Universal Protocol Terminal (Raw Protocol Terminal)
Breaking through system API limitations, it builds an all-protocol tunnel directly to the NFC hardware layer:
*   **Supported Protocols**: ISO-DEP (CPU Card/EMV), NFC-A (MIFARE series), NFC-B, NFC-F (FeliCa), NFC-V (ISO 15693).
*   **APDU Passthrough**: Developers can directly input Hex instructions (e.g., `00A4040007...`) for execution, complete with intelligent Status Word (SW) parsing.
*   **Backdoor Instruction Support**: Supports sending manufacturer-specific instructions (such as `0x40`, `0x43`) to specific MIFARE card variants (Gen1a Magic Cards, etc.), allowing forced writing to Block 0 regardless of standard Sector authentication.

### 2. Deep Hardware Probing & Hex Canvas (Forensics & Hex Canvas)
*   **Hardware Fingerprinting & Forensics**: Deeply identifies whether a chip is an original NXP chip or a clone/emulation card via SAK, ATQA, BCC, and system manufacturer feature dictionaries, issuing a Risk Level report.
*   **Hex Canvas**: Presents card data as an interactive hexadecimal canvas with syntax highlighting and color-coding by logical and physical regions (Header & Metadata, Encrypted Data, Payload), supporting real-time mapping and addressing from Virtual Address (VA) to Physical Address (PA).

### 3. Key Vault & Control Engineering
*   **Key Vault**: Built-in dictionary of common manufacturer default keys, supporting bulk imports from external `.keys` / `.mct` / `.json` files. The system automatically performs silent background collisions when reading cards.
*   **Key Sharding & Recovery**: Built-in dictionary collision and heuristic brute-force engines. Supports key sharding management based on Shamir Secret Sharing (SSS) or XOR to eliminate single points of failure.
*   **Access Bits Calculator**: Visually parses and compiles the Block 3 Trailer control bits (Bytes 6-8) for MIFARE Classic. It graphicalizes obscure complement check codes to avoid permanent card lockups (deadlocks) caused by manual calculation errors.

### 4. Safety Interceptor
*   **Dynamic Circuit Breaker Mechanism**: Predicts and intercepts high-risk instructions. For example, it prevents users from writing data to Block 0 of a standard 1K card or intercepts write requests directly if the calculated target BCC (Block Check Character) is incorrect, preventing the creation of "brick cards."
*   **CPU Card Protection**: When `VERIFY PIN` (APDU `00 20`) is detected to repeatedly return `63 Cx` (password error warning), it automatically cuts the session to prevent the CPU card from being physically locked.

---

## 🏗️ Architecture Design

The system adopts a **Hybrid Architecture**, utilizing Kotlin to handle high-performance Android native lifecycles and NFC hardware communication, while leveraging Python (via the Chaquopy platform) to drive extremely complex cryptography and AEFS file system logic.

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
│  [ lcow_engine.py ]     => Virtual Memory, Transaction Log, GC │
│  [ crypto_module.py ]   => Argon2id, XChaCha20, Merkle Tree │
│  [ AEFStools.py ]       => Image Building, Compression, Payloads │
│  [ nfc_interface.py ]   => Reverse calls to Kotlin HW callbacks │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Build & Run

### Development Environment Requirements
*   **Android Studio**: 2024.1.1 (Ladybug) or higher.
*   **Android SDK**: API 28 to 36.
*   **Python Runtime**: Automatically configured via Chaquopy.
*   **Hardware Requirement**: A physical Android device with a full NFC controller (NXP chipset recommended for full protocol passthrough capabilities).

### Compilation Process
1.  `git clone https://github.com/Aethel-Systems/AECardTools.git`
2.  Open the project in Android Studio; Gradle will automatically pull native Python extension libraries such as `argon2-cffi` and `cryptography`.
3.  Connect your device and execute `Run`.
4.  **On the first launch, be sure to confirm the disclaimer; otherwise, the core engine will refuse to initialize the underlying NFC stack.**

---

**AECardTools - Empowering every chip with sovereignty.**
*Last Updated: March 22, 2026*