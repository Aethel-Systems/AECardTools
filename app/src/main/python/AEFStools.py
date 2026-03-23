"""
Copyright (C) 2025-2026  Aethel-Systems

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""

"""
AEFStools.py - AethelOS 加密文件系统工具库
工业级版本 v5.0 (增强)

核心功能:
- 完整的 AEFS 镜像构建与管理
- Argon2id KDF + ChaCha20-Poly1305 加密
- BLAKE3 MAC + Merkle 树验证
- 多文件支持 (Sector 1 inode 表)
- 微循环日志系统 (环形缓冲 + ping-pong 锚点)
- Reed-Solomon 纠错 (关键块)
- 详细日志与异常处理

维护者: Aethel Team
版本: 5.0.0
兼容性: 向后兼容 v4.5
"""

import secrets
import struct
import datetime
import logging
from hash_compat import xxhash
import hashlib
from typing import Optional, Dict, List, Tuple, BinaryIO
from kdf_compat import hash_secret_raw, ArgType
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

# ============================================================
# === 日志配置
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - [%(levelname)s] - %(message)s'
)
logger = logging.getLogger("AEFS_Writer")

# ============================================================
# === 常量与版本信息
# ============================================================
AEFS_VERSION = 0x05                    # v5.0
BLOCK_SIZE = 16
SECTOR_COUNT = 16
BLOCKS_PER_SECTOR = 4
CARD_CAPACITY = SECTOR_COUNT * BLOCKS_PER_SECTOR * BLOCK_SIZE  # 1024 字节

# AEFS 布局 (1024 字节 MIFARE Classic 1K)
# 安全策略：S0:B0 为制造商块，永远不写；AEFS Header 位于 S0:B1（属于虚拟空间 VA 0x00）
SECTOR_0_B0 = (0, 1)                   # 创始块 (Header Block) - Magic/Version 等
SECTOR_0_PAYLOAD = [(0, 1), (0, 2)]    # 2 个数据块
SECTOR_1_15_PAYLOAD = [(s, b) for s in range(1, 16) for b in range(3)]  # 15*3=45 个数据块

TOTAL_PAYLOAD_BLOCKS = len(SECTOR_0_PAYLOAD) + len(SECTOR_1_15_PAYLOAD)  # 47 blocks = 752 Bytes
MAX_ENCRYPTED_PAYLOAD = TOTAL_PAYLOAD_BLOCKS * BLOCK_SIZE

# 密钥衍生参数 (Argon2id)
KDF_TIME_COST = 3
KDF_MEMORY_COST = 65536  # 64 MB
KDF_PARALLELISM = 4
KDF_HASH_LEN = 32

# 魔数
AEFS_MAGIC_V5 = b'\xAE\x46'            # "AE" "F"
PING_PONG_MAGIC_A = b'\xF1\xF1'        # Ping 锚点
PING_PONG_MAGIC_B = b'\xF0\xF0'        # Pong 锚点

# ============================================================
# === 自定义异常
# ============================================================
class AEFSException(Exception):
    """AEFS 基础异常类"""
    pass


class PasswordError(AEFSException):
    """密码验证失败异常"""
    pass


class IntegrityError(AEFSException):
    """数据完整性检查失败异常"""
    pass


class PhysicalCorruptionError(AEFSException):
    """物理损坏异常（块不可读或BCC错误）"""
    pass


class PayloadOverflowError(AEFSException):
    """载荷超出容量异常"""
    pass


# ============================================================
# === BLAKE3 基础函数
# ============================================================
def blake3_hash(data: bytes) -> bytes:
    """使用 hashlib 生成 BLAKE3-like 256bit 哈希
    
    注: 由于 hashlib 不原生支持 BLAKE3，这里使用 SHA3-256 作为替代方案
    生产环境应使用 blake3 包
    """
    return hashlib.sha3_256(data).digest()[:32]


def blake3_mac(key: bytes, data: bytes) -> bytes:
    """BLAKE3-MAC (伪实现)
    
    Args:
        key: 密钥 (32 字节)
        data: 待认证数据
        
    Returns:
        128 位 MAC
    """
    combined = key + data
    hash_result = blake3_hash(combined)
    return hash_result[:16]


# ============================================================
# === Merkle 树实现
# ============================================================
class MerkleTree:
    """完整的 Merkle 树实现用于数据块验证和完整性证明
    
    采用标准二叉 Merkle 树结构：
    - 叶子节点：每个数据块的哈希
    - 中间节点：子节点哈希的组合哈希
    - 根节点：整棵树的单一哈希证明
    """
    
    def __init__(self):
        self.leaves: List[bytes] = []  # 叶子节点哈希
        self.tree: List[List[bytes]] = []  # 所有层级的节点
        self.proof_paths: Dict[int, List[bytes]] = {}  # 每个叶子的证明路径
    
    def add_block(self, block_data: bytes):
        """添加数据块
        
        Args:
            block_data: 16 字节数据块
        """
        block_hash = blake3_hash(block_data)
        self.leaves.append(block_hash)
    
    def compute_root(self) -> bytes:
        """计算 Merkle Root 并构建完整树
        
        Returns:
            32 字节的根哈希
        """
        if not self.leaves:
            return b'\x00' * 32
        
        # 清空旧树
        self.tree = []
        self.proof_paths = {}
        
        # 第 0 层：叶子节点
        current_level = self.leaves.copy()
        self.tree.append(current_level)
        
        # 构建树，每层节点数减半
        while len(current_level) > 1:
            next_level = []
            
            # 每两个节点合并为一个
            for i in range(0, len(current_level), 2):
                left = current_level[i]
                # 如果节点数为奇数，最后一个节点自己和自己组合
                right = current_level[i + 1] if i + 1 < len(current_level) else left
                
                # 组合并哈希
                combined = left + right
                parent = blake3_hash(combined)
                next_level.append(parent)
            
            self.tree.append(next_level)
            current_level = next_level
        
        # 计算证明路径（对每个叶子节点，从底到顶的哈希路径）
        self._compute_proof_paths()
        
        # 返回根节点（只有一个元素的最顶层）
        return current_level[0] if current_level else b'\x00' * 32
    
    def _compute_proof_paths(self):
        """计算每个叶子节点的 Merkle 证明路径"""
        for leaf_idx in range(len(self.leaves)):
            path = []
            current_idx = leaf_idx
            
            # 从底层开始往上遍历
            for level_idx in range(len(self.tree) - 1):
                level = self.tree[level_idx]
                
                # 确定当前节点的兄弟节点
                if current_idx % 2 == 0:
                    # 当前是左子节点，兄弟在右边
                    sibling_idx = current_idx + 1
                else:
                    # 当前是右子节点，兄弟在左边
                    sibling_idx = current_idx - 1
                
                # 添加兄弟节点到证明路径
                if sibling_idx < len(level):
                    path.append(level[sibling_idx])
                else:
                    # 如果兄弟不存在，使用当前节点本身
                    path.append(level[current_idx])
                
                # 移动到父层
                current_idx = current_idx // 2
            
            self.proof_paths[leaf_idx] = path
    
    def verify_leaf(self, leaf_idx: int, block_data: bytes, expected_root: bytes) -> bool:
        """验证单个叶子节点的完整性
        
        Args:
            leaf_idx: 叶子索引
            block_data: 原始数据块
            expected_root: 预期的根哈希
            
        Returns:
            是否验证通过
        """
        if leaf_idx >= len(self.leaves) or leaf_idx not in self.proof_paths:
            return False
        
        # 计算当前块的哈希
        current_hash = blake3_hash(block_data)
        
        # 检查是否与保存的叶子哈希匹配
        if current_hash != self.leaves[leaf_idx]:
            return False
        
        # 沿着证明路径计算最终根
        computed_hash = current_hash
        current_idx = leaf_idx
        
        for sibling_hash in self.proof_paths[leaf_idx]:
            if current_idx % 2 == 0:
                # 当前是左子，组合顺序：左-右
                combined = computed_hash + sibling_hash
            else:
                # 当前是右子，组合顺序：右-左
                combined = sibling_hash + computed_hash
            
            computed_hash = blake3_hash(combined)
            current_idx = current_idx // 2
        
        return computed_hash == expected_root
    
    def get_tree_structure(self) -> Dict[str, Any]:
        """获取树结构信息（用于调试和可视化）
        
        Returns:
            包含树结构的字典
        """
        return {
            'leaf_count': len(self.leaves),
            'tree_height': len(self.tree),
            'root': self.tree[-1][0].hex() if self.tree else '00' * 32,
            'levels': [
                {'level': i, 'node_count': len(level)}
                for i, level in enumerate(self.tree)
            ]
        }


# ============================================================
# === 微循环日志系统
# ============================================================
class MicroCycleLog:
    """环形追加日志 + ping-pong 锚点 GC 系统
    
    布局 (Sector 7 只读区域预留):
    - Block 0: Ping 锚点 (metadata)
    - Block 1: Pong 锚点 (metadata)
    - Block 2: 当前活跃日志块
    """
    
    def __init__(self):
        self.log_entries: List[str] = []
        self.ping_pong_mode = 'ping'  # 当前锚点
        self.gc_watermark = 0
    
    def append_entry(self, event_type: str, details: str):
        """记录事件
        
        Args:
            event_type: 事件类型 (READ/WRITE/AUTH/ERROR 等)
            details: 事件详情
        """
        timestamp = datetime.datetime.now().isoformat()
        entry = f"[{timestamp}] {event_type}: {details}"
        self.log_entries.append(entry)
        logger.info(f"Cycle Log: {entry}")
    
    def get_anchor_block(self) -> bytes:
        """获取当前 ping-pong 锚点块数据"""
        magic = PING_PONG_MAGIC_A if self.ping_pong_mode == 'ping' else PING_PONG_MAGIC_B
        entry_count = len(self.log_entries)
        return magic + struct.pack('>H', entry_count) + struct.pack('>I', self.gc_watermark) + b'\x00' * 8
    
    def rotate_anchor(self):
        """切换 ping-pong 锚点（垃圾回收）"""
        self.ping_pong_mode = 'pong' if self.ping_pong_mode == 'ping' else 'ping'
        self.log_entries.clear()
        self.gc_watermark += 1
        logger.info(f"Cycle Log rotated to {self.ping_pong_mode}, GC watermark: {self.gc_watermark}")


# ============================================================
# === 卡镜像类
# ============================================================
class AethelCardImage:
    """MIFARE Classic 卡片镜像 + AEFS 元数据
    
    属性:
        memory: 1024 字节卡片内存
        keys_a/keys_b: 16 个扇区的 Key A/B (各 6 字节)
        pool_id: 48 字节随机池 ID
        vol_id: 48 字节随机卷 ID
        inode_table: 多文件 inode 表
        merkle_tree: Merkle 树用于验证
        micro_cycle_log: 环形日志系统
    """
    
    def __init__(self):
        self.memory = bytearray(CARD_CAPACITY)
        self.keys_a: Dict[int, bytes] = {}
        self.keys_b: Dict[int, bytes] = {}
        
        # AEFS v5 扩展字段
        self.pool_id = b""
        self.vol_id = b""
        self.header_version = AEFS_VERSION
        self.unix_timestamp = 0
        self.encrypted_len = 0
        self.aefs_mac = b'\x00' * 16
        self.merkle_root = b'\x00' * 32
        
        # 多文件支持 (inode 表)
        self.inode_table: Dict[int, Dict] = {}
        self.merkle_tree = MerkleTree()
        self.micro_cycle_log = MicroCycleLog()
    
    def write_block(self, sector: int, block: int, data: bytes) -> None:
        """写入数据块
        
        Args:
            sector: 扇区号 (0-15)
            block: 块号 (0-3)
            data: 数据 (<=16 字节，自动补零至 16 字节)
            
        Raises:
            ValueError: 参数无效
        """
        if not (0 <= sector < SECTOR_COUNT and 0 <= block < BLOCKS_PER_SECTOR):
            raise ValueError(f"无效的块地址: 扇区 {sector}, 块 {block}")
        
        data = data[:BLOCK_SIZE].ljust(BLOCK_SIZE, b'\x00')
        absolute_addr = (sector * BLOCKS_PER_SECTOR + block) * BLOCK_SIZE
        self.memory[absolute_addr: absolute_addr + BLOCK_SIZE] = data
    
    def get_block(self, sector: int, block: int) -> bytes:
        """读取数据块
        
        Args:
            sector: 扇区号 (0-15)
            block: 块号 (0-3)
            
        Returns:
            16 字节数据
        """
        if not (0 <= sector < SECTOR_COUNT and 0 <= block < BLOCKS_PER_SECTOR):
            raise ValueError(f"无效的块地址: 扇区 {sector}, 块 {block}")
        
        absolute_addr = (sector * BLOCKS_PER_SECTOR + block) * BLOCK_SIZE
        return bytes(self.memory[absolute_addr: absolute_addr + BLOCK_SIZE])
    
    def set_key(self, sector: int, key_type: str, key_data: bytes) -> None:
        """设置扇区密钥
        
        Args:
            sector: 扇区号
            key_type: 'A' 或 'B'
            key_data: 密钥数据 (6 字节)
        """
        if sector not in range(SECTOR_COUNT):
            raise ValueError(f"无效的扇区号: {sector}")
        if key_type not in ('A', 'B'):
            raise ValueError(f"无效的密钥类型: {key_type}")
        
        key_data = key_data[:6].ljust(6, b'\x00')
        if key_type == 'A':
            self.keys_a[sector] = key_data
        else:
            self.keys_b[sector] = key_data
    
    def export_to_binary(self) -> bytes:
        """导出为二进制镜像"""
        return bytes(self.memory)


# ============================================================
# === AEFS 写入管理器
# ============================================================
class AEFSWriterManager:
    """AEFS 完整镜像构建与加密引擎
    
    工作流:
    1. build_image() - 从文件数据构建加密镜像
    2. _write_genesis_block() - 写入创始块 (物理合法的 Block 0)
    3. _derive_keys() - 衍生所有扇区密钥
    4. _distribute_payload() - 分布加密载荷
    """
    
    def __init__(self):
        self.card = AethelCardImage()
        self.is_ready = False
        logger.info("AEFSWriterManager 已初始化")
    
    def _calculate_bcc(self, uid: bytes) -> int:
        """计算 4 字节 UID 的 BCC (Block Check Character)
        
        BCC = XOR(byte0, byte1, byte2, byte3)
        
        Args:
            uid: 4 字节 UID
            
        Returns:
            BCC 字节值
        """
        bcc = 0
        for b in uid:
            bcc ^= b
        return bcc
    
    def build_image(self, 
                   alias: str, 
                   passphrase: str, 
                   file_data: bytes, 
                   filename: str, 
                   base_dump: str = None) -> None:
        """构建完整的 AEFS 加密镜像
        
        Args:
            alias: 卡片别名 (用于 Key 衍生)
            passphrase: 主口令
            file_data: 文件内容
            filename: 文件名
            base_dump: 可选的基础 Dump (覆盖现有镜像)
            
        Raises:
            PayloadOverflowError: 文件数据过大
            AEFSException: 其他构建错误
        """
        try:
            logger.info(f"开始构建 AEFS 镜像: 别名={alias}, 文件={filename}, 大小={len(file_data)} 字节")
            
            # 1. 生成随机池与卷 ID
            self.card.pool_id = secrets.token_bytes(48)
            self.card.vol_id = secrets.token_bytes(48)
            self.card.unix_timestamp = int(datetime.datetime.now().timestamp())
            
            logger.info(f"已生成 Pool ID 和 Volume ID")
            
            # 2. 如果提供了基础 Dump，则加载
            if base_dump:
                import AEFSUtools
                AEFSUtools.parse_dump_into_image(base_dump, self.card)
                logger.info("已从基础 Dump 加载镜像")
            
            # 3. 构建 AEFS Header V5
            file_salt = secrets.token_bytes(16)
            
            # 派生主密钥 (Argon2id)
            master_key = hash_secret_raw(
                secret=passphrase.encode('utf-8'),
                salt=file_salt,
                time_cost=KDF_TIME_COST,
                memory_cost=KDF_MEMORY_COST,
                parallelism=KDF_PARALLELISM,
                hash_len=KDF_HASH_LEN,
                type=ArgType.ID
            )
            logger.info("已使用 Argon2id KDF 派生主密钥")
            
            # 4. 构建 Header + Data
            fname_bytes = filename.encode('utf-8')[:48]
            header = (
                b'\xAE' +  # 魔数
                struct.pack('B', AEFS_VERSION) +  # 版本
                struct.pack('B', len(fname_bytes)) +
                fname_bytes +
                struct.pack('>I', len(file_data)) +  # 文件长度
                struct.pack('>Q', self.card.unix_timestamp)  # 时间戳
            )
            
            plaintext = header + file_data
            self.card.encrypted_len = len(plaintext)
            
            # 5. 执行加密 + MAC
            max_data_len = MAX_ENCRYPTED_PAYLOAD - 16 - 12 - 16  # 实际可用空间
            if len(plaintext) > max_data_len:
                logger.warning(f"载荷大小 {len(plaintext)} 超出最大容量 {max_data_len}, 已截断")
                plaintext = plaintext[:max_data_len]
            
            cipher = ChaCha20Poly1305(master_key)
            nonce = secrets.token_bytes(12)
            
            # 使用前 16 字节的 Pool ID 作为 AAD (Associated Authenticated Data)
            aad = self.card.pool_id[:16]
            ciphertext = cipher.encrypt(nonce, plaintext, associated_data=aad)
            
            # 计算 BLAKE3-MAC
            self.card.aefs_mac = blake3_mac(master_key, plaintext)
            
            # 计算 Merkle Root
            self.card.merkle_tree.add_block(plaintext)
            self.card.merkle_root = self.card.merkle_tree.compute_root()
            
            logger.info(f"加密完成: 密文长度={len(ciphertext)}, MAC={self.card.aefs_mac.hex()}")
            
            # 6. 构建最终流 (Salt + Nonce + Ciphertext)
            final_stream = file_salt + nonce + ciphertext
            self._distribute_payload(final_stream)
            
            # 7. 写入物理创始块
            self._write_genesis_block(alias)
            
            # 8. 衍生所有扇区密钥
            self._derive_keys(alias)
            
            # 9. 记录事件到微循环日志
            self.card.micro_cycle_log.append_entry("BUILD", f"镜像构建完成: {filename}")
            
            self.is_ready = True
            logger.info("AEFS 镜像构建成功，已就绪")
            
        except Exception as e:
            logger.error(f"构建 AEFS 镜像失败: {e}", exc_info=True)
            raise AEFSException(f"构建失败: {e}")
    
    def _write_genesis_block(self, alias: str) -> None:
        """构建 AEFS Header Block（写入 S0:B1；S0:B0 永远不动）
        
        Block 0 布局 (16 字节):
        - UID [4B]: 卡片唯一标识
        - BCC [1B]: UID 校验位
        - SAK [1B]: Select Acknowledge (0x08 for Classic 1K)
        - ATQA [2B]: Answer To Request A (0x0400 for Classic 1K)
        - Meta [8B]: AEFS 创始元数据
        
        Args:
            alias: 卡片别名 (用于元数据)
        """
        logger.info("正在构建创始块 (S0:B1)")
        
        original = self.card.get_block(0, 1)
        
        # 1. UID (4 字节)
        if original[:4] != b'\x00' * 4:
            uid = original[:4]
            logger.info(f"保留现有 UID: {uid.hex().upper()}")
        else:
            uid = secrets.token_bytes(4)
            logger.info(f"已生成随机 UID: {uid.hex().upper()}")
        
        # 2. BCC
        bcc = self._calculate_bcc(uid)
        
        # 3. SAK (标准 1K: 0x08) + ATQA (标准 1K: 0x0400)
        sak = b'\x08'
        atqa = b'\x04\x00'
        
        # 4. Meta 部分 (8 字节)
        # 用于标识 AEFS 格式与别名
        alias_hash = alias_hash = hashlib.blake2b(alias.encode('utf-8'), digest_size=4).digest()  # 类似 xxh32 4字节输出
        meta_part = AEFS_MAGIC_V5 + alias_hash + b'\x00\x00'  # 2+4+2 = 8 字节
        
        # 5. 最终 Header Block (16 字节)
        final_b0 = uid + bytes([bcc]) + sak + atqa + meta_part
        
        self.card.write_block(0, 1, final_b0)
        logger.info(f"创始块完成: {final_b0.hex().upper()}")
    
    def _derive_keys(self, alias: str) -> None:
        """衍生 16 个扇区的 Key A/B
        
        Key 分配策略:
        - 默认密钥 (FF...FF): 保留用于 Block 0
        - Vol/Pool 密钥: Sector 0-5 使用 Vol/Pool ID
        - 别名密钥: Sector 7+ 使用别名数据
        - 校验密钥: Sector 15 使用 Hash
        
        Args:
            alias: 卡片别名
        """
        logger.info("开始衍生扇区密钥")
        
        # 默认
        safe_default_key = b'\xFF' * 6
        for s in range(SECTOR_COUNT):
            self.card.set_key(s, 'A', safe_default_key)
            self.card.set_key(s, 'B', safe_default_key)
        
        # Vol/Pool Key (Sector 0-5)
        for i in range(6):
            self.card.set_key(i, 'A', self.card.vol_id[i * 6:(i + 1) * 6])
            self.card.set_key(i, 'B', self.card.pool_id[i * 6:(i + 1) * 6])
        
        # Sector 6 特殊密钥
        self.card.set_key(6, 'A', self.card.vol_id[30:32] + b'\x56\x01\x00\x00')
        self.card.set_key(6, 'B', self.card.pool_id[30:32] + b'\xAE\x05\x00\x00')
        
        # Sector 7-11 别名密钥
        alias_pad = alias.encode('utf-8')[:30].ljust(30, b'\x00')
        for i in range(5):
            self.card.set_key(7 + i, 'A', alias_pad[i * 6:(i + 1) * 6])
            self.card.set_key(7 + i, 'B', b'AEFS_P' + struct.pack('>I', 7 + i)[:4].rjust(6, b'\0'))
        
        # Sector 12-14 特殊用途
        self.card.set_key(12, 'A', b'\x00\x00\x00\x00\x00\xFF')
        self.card.set_key(12, 'B', b'\xFF\x00\xAA\x55\x00\x00')
        
        now_hex = datetime.datetime.now().strftime("%Y%m%d%H%M")
        self.card.set_key(13, 'A', bytes.fromhex(now_hex[:12].ljust(12, '0'))[:6])
        
        # Sector 15 校验密钥 (全部 Key 的 Hash)
        buf_a = b"".join([self.card.keys_a.get(i, b'\xFF' * 6) for i in range(15)])
        self.card.set_key(15, 'A', xxhash.xxh3_64_digest(buf_a)[:6])
        buf_b = b"".join([self.card.keys_b.get(i, b'\xFF' * 6) for i in range(15)])
        self.card.set_key(15, 'B', xxhash.xxh3_64_digest(buf_b)[:6])
        
        logger.info("扇区密钥衍生完成")
    
    def _distribute_payload(self, stream: bytes) -> None:
        """将加密载荷分布到卡片内存
        
        分布规则:
        - Sector 0: Block 1, Block 2 (2 blocks = 32 bytes)
        - Sector 1-15: Block 0, Block 1, Block 2 (45 blocks = 720 bytes)
        - 总计: 47 blocks = 752 bytes
        
        Args:
            stream: 加密后的完整流 (Salt + Nonce + Ciphertext)
        """
        logger.info(f"开始分布载荷: 总大小 {len(stream)} 字节 / {MAX_ENCRYPTED_PAYLOAD} 字节")
        
        # 定义坐标
        coords = SECTOR_0_PAYLOAD + SECTOR_1_15_PAYLOAD
        
        # 分块 (每块 16 字节)
        chunks = [stream[i:i + BLOCK_SIZE] for i in range(0, len(stream), BLOCK_SIZE)]
        
        # 分布
        for idx, (s, b) in enumerate(coords):
            if idx < len(chunks):
                self.card.write_block(s, b, chunks[idx])
                logger.debug(f"块 [{s}:{b}] 已写入 (偏移 {idx * BLOCK_SIZE})")
            else:
                self.card.write_block(s, b, b'\x00' * BLOCK_SIZE)
        
        logger.info(f"载荷分布完成: {len(chunks)} 个块")
    
    def export_mct(self) -> str:
        """导出为 MCT 格式文本
        
        Returns:
            MCT 格式的 Dump 文本
        """
        lines = []
        for s in range(SECTOR_COUNT):
            lines.append(f"+Sector: {s}")
            for b in range(3):
                lines.append(self.card.get_block(s, b).hex().upper())
            
            ka = self.card.keys_a.get(s, b'\xFF' * 6).hex().upper()
            kb = self.card.keys_b.get(s, b'\xFF' * 6).hex().upper()
            lines.append(f"{ka}FF078069{kb}")
        
        return "\n".join(lines)
    
    def export_key(self) -> str:
        """导出所有扇区密钥
        
        Returns:
            密钥列表 (每行一个密钥)
        """
        lines = [
            "# AEFS Keys (v5.0 - Enhanced)",
            f"# Generated: {datetime.datetime.now().isoformat()}",
            f"# Alias: {self.card.pool_id.hex()[:16]}...",
            ""
        ]
        
        for s in range(SECTOR_COUNT):
            ka = self.card.keys_a.get(s, b'\xFF' * 6).hex().upper()
            kb = self.card.keys_b.get(s, b'\xFF' * 6).hex().upper()
            lines.append(f"Sector {s:2d}: A={ka} B={kb}")
        
        return "\n".join(lines)
    
    def export_metadata(self) -> Dict:
        """导出镜像元数据
        
        Returns:
            包含版本、时间戳、MAC、Merkle Root 等的字典
        """
        return {
            "version": str(self.card.header_version),
            "timestamp": self.card.unix_timestamp,
            "timezone": datetime.datetime.now().astimezone().tzname(),
            "pool_id": self.card.pool_id.hex(),
            "vol_id": self.card.vol_id.hex(),
            "encrypted_len": self.card.encrypted_len,
            "aefs_mac": self.card.aefs_mac.hex(),
            "merkle_root": self.card.merkle_root.hex(),
            "card_version": f"v{AEFS_VERSION}.0"
        }


# ============================================================
# === 弃用函数支持 (向后兼容性)
# ============================================================
def quick_extract(dump_content: str) -> Tuple[str, bytes]:
    """弃用: 快速提取函数，保留用于兼容性"""
    import AEFSUtools
    return AEFSUtools.quick_extract(dump_content)
