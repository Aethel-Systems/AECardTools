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
lcow_engine.py - 日志式写时复制 (LCOW) 引擎 (AEFS v5.5)

实现核心功能：
- 虚拟地址空间管理 (0x0000-0x02EF)
- 日志结构化写入 (写时复制)
- 事务管理与原子性保障
- Superblock与索引管理
- 垃圾回收 (GC) 触发
- Merkle树校验

Architecture:
  物理存储 (1024B) -> 虚拟地址 (752B)
  
  Sector 0:
    Block 0: 制造商块（S0:B0，永远不写）
    Block 1: AEFS Header Block（Magic/Version 等）
    Block 2: 数据块
    Block 3: 尾块 (Key + Access)
  
  Sector 1-14: 物理页池 (42个数据块)
    每个块可作为：
    - 普通数据块
    - 索引/指针块
    
  Sector 15: Superblock/Index
    Block 0-1: Ping-Pong 锚点 (交替使用)
    Block 2: GC/Metadata (位图, 擦写计数, 事务序列号)
    Block 3: 尾块

核心概念：
  1. 虚拟地址 (VA): 0x0000-0x02EF (752B空间)
     - 应用可见的逻辑地址空间
     - 独立于物理分布
     
  2. 物理块 (PA): 物理扇区/块位置
     - 通过索引表映射VA <-> PA
     
  3. 写时复制 (COW):
     - 修改VA时不覆盖原块
     - 在空闲物理池中分配新块
     - 原子更新索引指针
     
  4. 事务序列号 (TSN):
     - 防止断电回滚到脏状态
     - 始终保留前一个有效快照

维护者: Aethel Engineering
版本: 5.5.0-industrial
"""

import struct
import logging
import secrets
import hashlib
import time
from typing import Dict, List, Optional, Tuple, Any
from enum import Enum
from dataclasses import dataclass
from hash_compat import xxhash

logger = logging.getLogger("AECardTools.LCOW")

# ============================================================
# === 常量定义
# ============================================================

# 物理固定块位置
GENESIS_BLOCK = (0, 1)              # Sector 0, Block 1 (AEFS Header)
SECTOR_0_PAYLOAD_BLOCKS = [(0, 1), (0, 2)]  # 32B

# 日志池
LOG_POOL_SECTORS = list(range(1, 15))  # Sector 1-14
LOG_POOL_BLOCKS = [(s, b) for s in LOG_POOL_SECTORS for b in range(3)]  # 42个块
LOG_POOL_SIZE = len(LOG_POOL_BLOCKS)

# Superblock
SUPERBLOCK_SECTORS = [15]
SUPERBLOCK_ANCHOR_PING = (15, 0)     # Ping锚点
SUPERBLOCK_ANCHOR_PONG = (15, 1)     # Pong锚点
SUPERBLOCK_METADATA = (15, 2)        # GC/元数据块

# 虚拟地址空间
VIRTUAL_SPACE_SIZE = 752             # 752B
GENESIS_VA_START = 0x00              # Genesis数据从虚拟地址0开始
GENESIS_VA_SIZE = 32                 # 32B

# 块大小
BLOCK_SIZE = 16
SECTOR_TRAILER_BLOCK = 3

# 魔数
AEFS_MAGIC = b'\xAE\xF5'             # v5.5
PING_MAGIC = b'\xF1\xF1'
PONG_MAGIC = b'\xF0\xF0'

# Superblock版本
SUPERBLOCK_VERSION = 0x55            # v5.5

# 事务状态
class TransactionState(Enum):
    """事务状态"""
    IDLE = 0
    WRITING = 1
    COMMITTED = 2
    FAILED = 3
    ROLLED_BACK = 4

# ============================================================
# === 数据模型
# ============================================================

@dataclass
class SuperblockAnchor:
    """Superblock锚点"""
    magic: bytes                       # 2B: 0xF1F1 (Ping) 或 0xF0F0 (Pong)
    version: int                       # 1B
    sequence_number: int               # 1B (0-255, 回绕)
    root_index_block: Tuple[int, int]  # 2B: 指向根索引块的物理位置
    root_index_va: int                 # 2B: 根索引块的虚拟地址
    total_used_blocks: int             # 1B
    gc_triggered: bool                 # 1B
    merkle_root: bytes                 # 32B: Merkle根哈希
    checksum: bytes                    # 8B: XXH64校验
    reserved: bytes                    # 16B: 预留
    
    @staticmethod
    def calc_size() -> int:
        """返回固定大小 (64B)"""
        return 64
    
    def to_bytes(self) -> bytes:
        """序列化为字节"""
        data = bytearray(64)
        data[0:2] = self.magic
        data[2] = self.version
        data[3] = self.sequence_number & 0xFF
        data[4:6] = struct.pack('>H', self.root_index_block[0] * 16 + self.root_index_block[1])
        data[6:8] = struct.pack('>H', self.root_index_va)
        data[8] = min(self.total_used_blocks, 255)
        data[9] = 1 if self.gc_triggered else 0
        data[10:42] = self.merkle_root
        data[42:50] = self.checksum
        data[50:64] = self.reserved
        return bytes(data)
    
    @staticmethod
    def from_bytes(data: bytes) -> 'SuperblockAnchor':
        """从字节反序列化"""
        if len(data) < 64:
            raise ValueError(f"Superblock数据太短: {len(data)} < 64")
        
        magic = data[0:2]
        version = data[2]
        sequence_number = data[3]
        block_num = struct.unpack('>H', data[4:6])[0]
        root_block = (block_num // 16, block_num % 16)
        root_va = struct.unpack('>H', data[6:8])[0] if len(data) >= 8 else 0
        total_used = data[8]
        gc_triggered = bool(data[9])
        merkle_root = data[10:42]
        checksum = data[42:50]
        reserved = data[50:64]
        
        return SuperblockAnchor(
            magic=magic,
            version=version,
            sequence_number=sequence_number,
            root_index_block=root_block,
            root_index_va=root_va,
            total_used_blocks=total_used,
            gc_triggered=gc_triggered,
            merkle_root=merkle_root,
            checksum=checksum,
            reserved=reserved
        )

@dataclass
class BlockMapping:
    """虚拟地址块映射"""
    va_start: int                      # 虚拟地址起点
    va_size: int                       # 虚拟大小
    physical_block: Tuple[int, int]    # (sector, block)
    status: str                        # "valid", "reserved", "invalid"
    version: int                       # 版本号
    
    def to_bytes(self) -> bytes:
        """序列化"""
        return struct.pack('>HHHBBH',
            self.va_start, self.va_size,
            self.physical_block[0] * 16 + self.physical_block[1],
            ord(self.status[0]),
            self.version, 0)  # 预留
    
    @staticmethod
    def from_bytes(data: bytes) -> 'BlockMapping':
        """反序列化"""
        if len(data) < 8:
            raise ValueError(f"BlockMapping数据太短: {len(data)} < 8")
        
        va_start, va_size, block_num, status_c, version = struct.unpack('>HHBBH', data[:8])
        status_char = chr(status_c)
        block = (block_num // 16, block_num % 16)
        
        return BlockMapping(
            va_start=va_start,
            va_size=va_size,
            physical_block=block,
            status=f"{status_char}_status",
            version=version
        )

@dataclass
class TransactionLog:
    """事务日志"""
    transaction_id: int
    timestamp: float
    operation: str                     # "write", "erase", "gc"
    affected_addresses: List[Tuple[int, int]]  # [(va_start, va_end), ...]
    state: TransactionState
    previous_state: Optional['TransactionLog'] = None
    
    def is_committed(self) -> bool:
        return self.state == TransactionState.COMMITTED
    
    def can_rollback(self) -> bool:
        return self.previous_state is not None and self.previous_state.is_committed()

# ============================================================
# === 虚拟地址映射表
# ============================================================

class VirtualAddressSpace:
    """虚拟地址空间管理"""
    
    def __init__(self, capacity: int = VIRTUAL_SPACE_SIZE):
        self.capacity = capacity
        self.mappings: Dict[int, BlockMapping] = {}  # va_start -> BlockMapping
        self.reverse_map: Dict[Tuple[int, int], int] = {}  # (sector, block) -> va_start
        
        logger.info(f"初始化VA空间: {capacity}B")
    
    def register_mapping(self, mapping: BlockMapping) -> None:
        """注册虚拟-物理映射"""
        if mapping.va_start + mapping.va_size > self.capacity:
            raise ValueError(f"VA超出容量: {mapping.va_start} + {mapping.va_size} > {self.capacity}")
        
        self.mappings[mapping.va_start] = mapping
        self.reverse_map[mapping.physical_block] = mapping.va_start
        logger.debug(f"VA映射: 0x{mapping.va_start:03X}-0x{mapping.va_start + mapping.va_size:03X} <-> "
                    f"Sector {mapping.physical_block[0]}, Block {mapping.physical_block[1]}")
    
    def lookup_physical(self, va: int) -> Optional[Tuple[int, int]]:
        """虚拟地址 -> 物理块"""
        for va_start, mapping in self.mappings.items():
            if va_start <= va < va_start + mapping.va_size:
                return mapping.physical_block
        return None
    
    def lookup_virtual(self, physical_block: Tuple[int, int]) -> Optional[int]:
        """物理块 -> 虚拟地址"""
        return self.reverse_map.get(physical_block)
    
    def get_free_blocks(self, block_list: List[Tuple[int, int]]) -> List[Tuple[int, int]]:
        """获取未被映射的空闲块"""
        used_blocks = set(self.reverse_map.keys())
        return [b for b in block_list if b not in used_blocks]

# ============================================================
# === 索引树管理
# ============================================================

class IndexTree:
    """索引树 (支持多级间接寻址)"""
    
    def __init__(self, block_size: int = 16):
        self.block_size = block_size
        self.root_va: Optional[int] = None
        self.root_physical: Optional[Tuple[int, int]] = None
        self.nodes: Dict[Tuple[int, int], bytes] = {}  # 每个物理块包含的索引数据
        self.depth = 0
    
    def set_root(self, va: int, physical: Tuple[int, int]) -> None:
        """设置根索引块"""
        self.root_va = va
        self.root_physical = physical
        logger.info(f"设置根索引: VA 0x{va:03X} -> Sector {physical[0]}, Block {physical[1]}")
    
    def add_entry(self, va: int, physical: Tuple[int, int]) -> None:
        """添加索引项"""
        if self.root_physical is None:
            raise RuntimeError("未设置索引树根")
        
        if self.root_physical not in self.nodes:
            self.nodes[self.root_physical] = bytearray(self.block_size)
        
        # 简化的索引: 每2字节为一个入口 (物理块号)
        offset = (va & 0xFF) * 2
        if offset + 2 <= self.block_size:
            block_num = physical[0] * 16 + physical[1]
            self.nodes[self.root_physical][offset:offset+2] = struct.pack('>H', block_num)
            logger.debug(f"索引项: VA 0x{va:03X} -> Block #{block_num}")

# ============================================================
# === 垃圾回收控制器
# ============================================================

class GarbageCollectionController:
    """垃圾回收 (GC) 控制器"""
    
    FREE_BLOCKS_THRESHOLD = 0.15  # 15%时触发GC
    
    def __init__(self, total_log_blocks: int = LOG_POOL_SIZE):
        self.total_log_blocks = total_log_blocks
        self.free_blocks = total_log_blocks
        self.is_collecting = False
        self.collection_count = 0
    
    def check_should_trigger_gc(self) -> bool:
        """检查是否应触发GC"""
        usage_ratio = 1.0 - (self.free_blocks / self.total_log_blocks)
        threshold = 1.0 - self.FREE_BLOCKS_THRESHOLD
        
        if usage_ratio > threshold and not self.is_collecting:
            logger.warning(f"GC阈值触发: {usage_ratio:.1%} > {threshold:.1%}")
            return True
        return False
    
    def trigger_collection(self) -> None:
        """触发垃圾回收"""
        self.is_collecting = True
        self.collection_count += 1
        logger.info(f"GC #{self.collection_count} 开始: "
                   f"释放块数 {self.total_log_blocks - self.free_blocks}/{self.total_log_blocks}")
    
    def finish_collection(self, blocks_freed: int) -> None:
        """完成垃圾回收"""
        self.free_blocks += blocks_freed
        self.is_collecting = False
        logger.info(f"GC #{self.collection_count} 完成: 释放 {blocks_freed} 个块")

# ============================================================
# === Merkle树哈希
# ============================================================

class MerkleTree:
    """Merkle树 (完整性验证)"""
    
    def __init__(self):
        self.leaves: List[bytes] = []
        self.root_hash: Optional[bytes] = None
    
    def add_block_hash(self, block_data: bytes) -> None:
        """添加块哈希作为叶子"""
        leaf_hash = hashlib.sha256(block_data).digest()
        self.leaves.append(leaf_hash)
    
    def build_tree(self) -> bytes:
        """构建树并返回根哈希"""
        if not self.leaves:
            self.root_hash = hashlib.sha256(b'').digest()
            return self.root_hash
        
        current_level = self.leaves[:]
        
        while len(current_level) > 1:
            next_level = []
            for i in range(0, len(current_level), 2):
                if i + 1 < len(current_level):
                    combined = current_level[i] + current_level[i+1]
                else:
                    combined = current_level[i] + current_level[i]
                
                parent_hash = hashlib.sha256(combined).digest()
                next_level.append(parent_hash)
            
            current_level = next_level
        
        self.root_hash = current_level[0] if current_level else hashlib.sha256(b'').digest()
        return self.root_hash
    
    def verify(self, blocks_data: List[bytes]) -> bool:
        """验证完整性"""
        new_tree = MerkleTree()
        for block in blocks_data:
            new_tree.add_block_hash(block)
        new_root = new_tree.build_tree()
        
        is_valid = self.root_hash == new_root
        logger.info(f"Merkle验证: {'✓' if is_valid else '✗'}")
        return is_valid

# ============================================================
# === LCOW引擎主类
# ============================================================

class LCOWEngine:
    """日志式写时复制 (LCOW) 引擎"""
    
    def __init__(self):
        self.va_space = VirtualAddressSpace()
        self.index_tree = IndexTree()
        self.gc_controller = GarbageCollectionController()
        self.merkle_tree = MerkleTree()
        self.virtual_image = bytearray(VIRTUAL_SPACE_SIZE)  # 当前虚拟地址空间镜像（用于读取/编辑器视图）
        
        self.current_anchor = None
        self.previous_anchor = None
        self.transaction_log: Optional[TransactionLog] = None
        self.transaction_metadata: Dict[str, Any] = {}
        self.pending_v6_package: Optional[Dict[str, Any]] = None
        self.active_v6_package: Optional[Dict[str, Any]] = None
        self.transaction_counter = 0
        self.use_ping_anchor = True  # 交替使用ping/pong
        
        self.bitmap = bytearray(6)  # 位图: 42位 (6字节) 表示42个块的使用状态
        self.erase_count = [0] * LOG_POOL_SIZE  # 磨损计数（wear leveling）
        
        self.dirty_blocks: Dict[Tuple[int, int], bytes] = {}  # 内存中修改的块
        self.is_closed = False
        
        logger.info("LCOW引擎初始化完成")
    
    def initialize_from_card(self, card_data: bytes) -> None:
        """从卡片数据初始化引擎"""
        if len(card_data) < 1024:
            raise ValueError(f"卡片数据太小: {len(card_data)} < 1024")
        
        try:
            # 构建基础虚拟镜像（去除 Sector Trailer + S0:B0）
            self.virtual_image = bytearray(self._build_virtual_image(card_data))

            # 读取Superblock锚点
            ping_data = card_data[240:304]  # Sector 15, Block 0
            pong_data = card_data[304:368]  # Sector 15, Block 1
            
            ping = SuperblockAnchor.from_bytes(ping_data)
            pong = SuperblockAnchor.from_bytes(pong_data)
            
            # 选择有效的锚点 (基于序列号)
            if ping.sequence_number > pong.sequence_number:
                self.current_anchor = ping
                self.use_ping_anchor = True
            else:
                self.current_anchor = pong
                self.use_ping_anchor = False
            
            logger.info(f"加载Superblock: 序列号={self.current_anchor.sequence_number}")
            
            # 加载元数据
            metadata = card_data[368:384]  # Sector 15, Block 2
            self._load_metadata(metadata)
            
        except Exception as e:
            logger.error(f"初始化失败: {e}")
            raise

    def _build_virtual_image(self, card_data: bytes) -> bytes:
        """
        将 1024B 物理卡片数据压缩为 752B 虚拟空间：
        - 跳过 Sector Trailer（每扇区 Block 3）
        - 跳过 S0:B0（制造商块）
        """
        out = bytearray()
        for sector in range(16):
            for block in range(3):
                if sector == 0 and block == 0:
                    continue
                absolute = sector * 4 + block
                start = absolute * 16
                out.extend(card_data[start:start + 16].ljust(16, b'\x00'))
        return bytes(out[:VIRTUAL_SPACE_SIZE].ljust(VIRTUAL_SPACE_SIZE, b'\x00'))

    def read_virtual_block(self, va_start: int, size: int) -> Optional[bytes]:
        """从当前虚拟镜像读取数据（支持任意范围）。"""
        if va_start < 0 or size < 0 or va_start + size > VIRTUAL_SPACE_SIZE:
            return None
        return bytes(self.virtual_image[va_start:va_start + size])
    
    def _load_metadata(self, data: bytes) -> None:
        """加载GC/元数据块"""
        if len(data) < 16:
            return
        
        # 位图 (6字节)
        self.bitmap = bytearray(data[0:6])
        
        # 擦写计数 (前6个块的计数)
        for i in range(min(6, len(data) - 6)):
            self.erase_count[i] = data[6 + i]
        
        logger.debug(f"元数据加载: bitmap={self.bitmap.hex()}, "
                    f"erase_count={self.erase_count[:6]}")
    
    def allocate_block(self, prefer_sector: Optional[int] = None) -> Tuple[int, int]:
        """分配空闲块 (COW时使用)"""
        for i, block in enumerate(LOG_POOL_BLOCKS):
            # 检查位图
            if not self._is_block_free(i):
                continue
            
            if prefer_sector is not None and block[0] != prefer_sector:
                continue
            
            self.bitmap[i // 8] |= (1 << (i % 8))
            self.erase_count[i] += 1
            
            logger.debug(f"分配块: Sector {block[0]}, Block {block[1]} "
                        f"(wear={self.erase_count[i]})")
            return block
        
        # 触发GC
        if self.gc_controller.check_should_trigger_gc():
            logger.warning("可用空闲块不足，需要垃圾回收")
            return None
        
        raise RuntimeError("没有可用的空闲块，请执行垃圾回收")
    
    def _is_block_free(self, bitmap_index: int) -> bool:
        """检查块是否空闲"""
        byte_idx = bitmap_index // 8
        bit_idx = bitmap_index % 8
        return not bool(self.bitmap[byte_idx] & (1 << bit_idx))
    
    def begin_transaction(self, operation: str) -> None:
        """开始事务"""
        self.transaction_counter += 1
        self.transaction_log = TransactionLog(
            transaction_id=self.transaction_counter,
            timestamp=time.time(),
            operation=operation,
            affected_addresses=[],
            state=TransactionState.WRITING,
            previous_state=None
        )
        self.transaction_metadata = {}
        logger.info(f"事务 {self.transaction_counter} 开始 ({operation})")

    def begin_v6_rebuild(self, package_snapshot: Dict[str, Any]) -> None:
        """开始 AEFS v6 全量重构事务。"""
        self.begin_transaction("aefs_v6_rebuild")
        self.pending_v6_package = package_snapshot
        self.transaction_metadata = {
            'package_digest': package_snapshot.get('payload_digest', ''),
            'record_type': package_snapshot.get('anchor', {}).get('record_type_index', ''),
            'sector_count': len(package_snapshot.get('sector_payloads', [])),
        }
        logger.info(
            "AEFS v6 rebuild 事务已登记: digest=%s sectors=%s",
            self.transaction_metadata.get('package_digest', '')[:16],
            self.transaction_metadata.get('sector_count', 0)
        )
    
    def commit_block_write(self, va_start: int, va_size: int, data: bytes) -> None:
        """提交块写入 (写时复制)"""
        if self.transaction_log is None:
            raise RuntimeError("没有活跃的事务")

        # 更新虚拟镜像（供注册表编辑器/画布读取）
        if va_start < 0 or va_start + len(data) > VIRTUAL_SPACE_SIZE:
            self.transaction_log.state = TransactionState.FAILED
            raise RuntimeError(f"VA写入越界: 0x{va_start:03X} + {len(data)} > {VIRTUAL_SPACE_SIZE}")
        self.virtual_image[va_start:va_start + len(data)] = data
        
        # 分配新的物理块
        new_block = self.allocate_block()
        if new_block is None:
            self.transaction_log.state = TransactionState.FAILED
            raise RuntimeError("块分配失败")
        
        # 存储在内存中
        self.dirty_blocks[new_block] = data
        
        # 更新映射
        mapping = BlockMapping(
            va_start=va_start,
            va_size=va_size,
            physical_block=new_block,
            status="valid",
            version=self.transaction_counter
        )
        self.va_space.register_mapping(mapping)
        self.index_tree.add_entry(va_start, new_block)
        
        self.transaction_log.affected_addresses.append((va_start, va_start + va_size))
        logger.debug(f"COW提交: VA 0x{va_start:03X} -> Sector {new_block[0]}, Block {new_block[1]}")
    
    def finalize_transaction(self) -> bool:
        """确定事务 (更新Superblock)"""
        if self.transaction_log is None:
            return False
        
        try:
            # 计算Merkle根
            all_blocks = list(self.dirty_blocks.values())
            for data in all_blocks:
                self.merkle_tree.add_block_hash(data)
            merkle_root = self.merkle_tree.build_tree()
            
            # 创建新的Superblock锚点
            new_sequence = (self.current_anchor.sequence_number + 1) % 256
            new_anchor = SuperblockAnchor(
                magic=PING_MAGIC if self.use_ping_anchor else PONG_MAGIC,
                version=SUPERBLOCK_VERSION,
                sequence_number=new_sequence,
                root_index_block=self.index_tree.root_physical or (15, 0),
                root_index_va=self.index_tree.root_va or 0,
                total_used_blocks=len(self.va_space.mappings),
                gc_triggered=self.gc_controller.is_collecting,
                merkle_root=merkle_root,
                checksum=xxhash.xxh64(merkle_root).digest()[:8],
                reserved=b'\x00' * 16
            )
            
            self.previous_anchor = self.current_anchor
            self.current_anchor = new_anchor
            self.transaction_log.state = TransactionState.COMMITTED
            self.use_ping_anchor = not self.use_ping_anchor
            if self.pending_v6_package is not None:
                self.active_v6_package = self.pending_v6_package
                self.pending_v6_package = None
            
            logger.info(f"事务 {self.transaction_log.transaction_id} 已提交 "
                       f"(seq={new_sequence}, merkle={merkle_root.hex()[:16]}...)")
            return True
            
        except Exception as e:
            self.transaction_log.state = TransactionState.FAILED
            logger.error(f"事务确定失败: {e}")
            return False
    
    def can_rollback(self) -> bool:
        """检查是否可以回滚"""
        return self.previous_anchor is not None and self.transaction_log.can_rollback()
    
    def rollback_transaction(self) -> bool:
        """回滚事务"""
        if not self.can_rollback():
            logger.warning("无法回滚: 没有有效的前一体")
            return False
        
        self.current_anchor = self.previous_anchor
        self.dirty_blocks.clear()
        self.pending_v6_package = None
        self.transaction_log.state = TransactionState.ROLLED_BACK
        logger.info("事务已回滚到前一体")
        return True
    
    def trigger_garbage_collection(self) -> int:
        """触发垃圾回收 (空闲块压缩)"""
        self.gc_controller.trigger_collection()
        
        # 简化的GC: 标记无效块并重置位图
        valid_blocks = set(self.va_space.reverse_map.keys())
        
        freed_count = 0
        for i, block in enumerate(LOG_POOL_BLOCKS):
            if block not in valid_blocks:
                self.bitmap[i // 8] &= ~(1 << (i % 8))
                freed_count += 1
        
        self.gc_controller.finish_collection(freed_count)
        return freed_count
    
    def close(self):
        """关闭引擎"""
        if not self.is_closed:
            if self.transaction_log and self.transaction_log.state != TransactionState.COMMITTED:
                self.rollback_transaction()
            
            self.is_closed = True
            logger.info("LCOW引擎已关闭")

# ============================================================
# === 导出符号
# ============================================================
__all__ = [
    'LCOWEngine',
    'VirtualAddressSpace',
    'IndexTree',
    'GarbageCollectionController',
    'MerkleTree',
    'SuperblockAnchor',
    'BlockMapping',
    'TransactionLog',
    'TransactionState',
    'LOG_POOL_BLOCKS',
    'LOG_POOL_SIZE'
]
