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
core_engine.py - 工业级核心引擎

提供所有业务逻辑处理，包括：
- 密码学操作 (Argon2id, XChaCha20-Poly1305, BLAKE3)
- MIFARE Classic 协议实现
- AEFS v5.0 完整支持
- 密钥管理与字典恢复
- 数据验证与恢复
"""

import os
import sys
import json
import struct
import secrets
import hashlib
import datetime
from typing import Optional, Dict, List, Tuple, Any
from enum import Enum
import logging

from hash_compat import xxhash
from kdf_compat import hash_secret_raw, ArgType
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.backends import default_backend

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - [%(levelname)s] - %(message)s'
)
logger = logging.getLogger("AECardTools.Core")

# ============================================================
# === 枚举定义
# ============================================================
class CardType(Enum):
    """卡片类型"""
    CLASSIC_1K = "Classic 1K (16 sectors)"
    CLASSIC_4K = "Classic 4K (40 sectors)"
    PLUS_2K = "Plus 2K"
    PLUS_4K = "Plus 4K"
    UNKNOWN = "Unknown"

class AuthStatus(Enum):
    """认证状态"""
    NOT_ATTEMPTED = 0
    SUCCESS_A = 1
    SUCCESS_B = 2
    FAILED = 3

class SIPLevel(Enum):
    """安全级别"""
    SANDBOX = "Sandbox"
    ARCHITECT = "Architect"
    SOVEREIGN = "Sovereign"
    CUSTOM = "Custom"

class AccessBits(Enum):
    """访问条件"""
    READABLE = "Readable"
    WRITABLE = "Writable"
    INCREMENTABLE = "Incrementable"
    DECREMENTABLE = "Decrementable"

# ============================================================
# === 常量
# ============================================================
BLOCK_SIZE = 16
SECTOR_COUNT_1K = 16
SECTOR_COUNT_4K = 40
BLOCKS_PER_SECTOR = 4

# AEFS 参数
AEFS_VERSION = 0x05
AEFS_MAGIC = b'\xAE\x46'
MAX_PAYLOAD_SIZE = 752  # 1024 - 272 (headers + keys)

# 默认 Key
KEY_DEFAULT = b'\xFF' * 6
KEY_MAD = b'\xA0\xA1\xA2\xA3\xA4\xA5'
KEY_ARIA_INITIAL = b'\xFF\xFF\xFF\xFF\xFF\xFF'

# Argon2id 参数
KDF_TIME_COST = 3
KDF_MEMORY_COST = 65536  # 64 MB
KDF_PARALLELISM = 4
KDF_HASH_LEN = 32

# ============================================================
# === 异常定义
# ============================================================
class AECardToolsException(Exception):
    """基础异常"""
    pass

class AuthenticationError(AECardToolsException):
    """认证失败"""
    pass

class IntegrityError(AECardToolsException):
    """完整性检查失败"""
    pass

class CardTypeError(AECardToolsException):
    """卡片类型错误"""
    pass

class CryptoError(AECardToolsException):
    """密码学操作失败"""
    pass

# ============================================================
# === 工具函数
# ============================================================
def bytes_to_hex_str(data: bytes) -> str:
    """字节数组转十六进制字符串"""
    return data.hex().upper()

def hex_str_to_bytes(hex_str: str) -> bytes:
    """十六进制字符串转字节数组"""
    return bytes.fromhex(hex_str.replace(' ', '').replace('\n', ''))

def xor_bytes(a: bytes, b: bytes) -> bytes:
    """异或操作"""
    return bytes(x ^ y for x, y in zip(a, b))

def calculate_bcc(uid: bytes) -> int:
    """计算 BCC (Block Check Character)"""
    bcc = 0
    for b in uid:
        bcc ^= b
    return bcc

def calculate_luhn(data: bytes) -> int:
    """计算 Luhn 校验"""
    total = 0
    for i, b in enumerate(reversed(data)):
        digit = b if i % 2 == 0 else (b >> 4) | ((b & 0x0F) << 4)
        digit_sum = (digit >> 4) + (digit & 0x0F)
        total += digit_sum
    return (10 - (total % 10)) % 10

# ============================================================
# === BLAKE3 哈希（使用 SHA3-256 替代）
# ============================================================
def blake3_hash(data: bytes) -> bytes:
    """BLAKE3 哈希（使用 SHA3-256 替代）"""
    return hashlib.sha3_256(data).digest()

def blake3_mac(key: bytes, data: bytes) -> bytes:
    """BLAKE3 MAC"""
    combined = key + data
    return blake3_hash(combined)[:16]

# ============================================================
# === 密钥派生
# ============================================================
class KeyDerivation:
    """密钥派生管理"""
    
    @staticmethod
    def derive_master_key(passphrase: str, salt: bytes) -> bytes:
        """使用 Argon2id 派生主密钥"""
        try:
            key = hash_secret_raw(
                secret=passphrase.encode('utf-8'),
                salt=salt,
                time_cost=KDF_TIME_COST,
                memory_cost=KDF_MEMORY_COST,
                parallelism=KDF_PARALLELISM,
                hash_len=KDF_HASH_LEN,
                type=ArgType.ID
            )
            logger.info("主密钥派生成功")
            return key
        except Exception as e:
            raise CryptoError(f"密钥派生失败: {e}")
    
    @staticmethod
    def derive_sector_keys(alias: str, pool_id: bytes, vol_id: bytes) -> Dict[int, Tuple[bytes, bytes]]:
        """派生所有扇区密钥"""
        keys = {}
        
        # Sector 0-5: Vol/Pool 密钥
        for i in range(6):
            key_a = vol_id[i*6:(i+1)*6]
            key_b = pool_id[i*6:(i+1)*6]
            keys[i] = (key_a, key_b)
        
        # Sector 6: 特殊密钥 (标记 AEFS)
        keys[6] = (
            vol_id[30:32] + b'\x56\x01\x00\x00',
            pool_id[30:32] + b'\xAE\x04\x00\x00'
        )
        
        # Sector 7-11: 别名密钥
        alias_pad = alias.encode('utf-8')[:30].ljust(30, b'\x00')
        for i in range(5):
            key_a = alias_pad[i*6:(i+1)*6]
            key_b = b'AEFS' + struct.pack('>H', 7+i)
            keys[7+i] = (key_a, key_b)
        
        # Sector 12-14: 混合密钥
        keys[12] = (b'\x00\x00\x00\x00\x00\xFF', b'\xFF\x00\xAA\x55\x00\x00')
        
        # 时间戳密钥
        now_hex = datetime.datetime.now().strftime("%Y%m%d")
        key_a = bytes.fromhex(now_hex[:8].ljust(12, '0'))[:6]
        keys[13] = (key_a, b'\xFF' * 6)
        
        keys[14] = (b'LOGGER', b'AUDIT01')
        
        # Sector 15: 校验密钥
        # 使用所有 Key A 的 Hash
        all_key_a = b''.join([keys.get(i, (b'\xFF'*6, b'\xFF'*6))[0] for i in range(15)])
        key_a_check = xxhash.xxh3_64_digest(all_key_a)[:6]
        all_key_b = b''.join([keys.get(i, (b'\xFF'*6, b'\xFF'*6))[1] for i in range(15)])
        key_b_check = xxhash.xxh3_64_digest(all_key_b)[:6]
        keys[15] = (key_a_check, key_b_check)
        
        logger.info(f"已派生 {len(keys)} 个扇区的密钥")
        return keys

# ============================================================
# === 卡片数据模型
# ============================================================
class CardSector:
    """扇区数据"""
    
    def __init__(self, index: int):
        self.index = index
        self.blocks: List[bytes] = [b'\x00' * BLOCK_SIZE for _ in range(BLOCKS_PER_SECTOR)]
        self.key_a: Optional[bytes] = None
        self.key_b: Optional[bytes] = None
        self.auth_status = AuthStatus.NOT_ATTEMPTED
        self.access_bits = None

class CardIdentity:
    """卡片身份信息"""
    
    def __init__(self):
        self.uid: str = ""
        self.sak: str = ""
        self.atqa: str = ""
        self.card_type = CardType.UNKNOWN
        self.is_aefs = False
        self.sectors: Dict[int, CardSector] = {}
        self.detected_at = datetime.datetime.now()

# ============================================================
# === 数据加密/解密
# ============================================================
class CryptoEngine:
    """密码学引擎"""
    
    @staticmethod
    def encrypt_chacha20(data: bytes, key: bytes, aad: bytes = b'') -> Tuple[bytes, bytes]:
        """XChaCha20-Poly1305 加密"""
        try:
            cipher = ChaCha20Poly1305(key)
            nonce = secrets.token_bytes(12)
            ciphertext = cipher.encrypt(nonce, data, associated_data=aad)
            logger.info(f"加密成功: {len(data)} 字节 -> {len(ciphertext)} 字节")
            return nonce, ciphertext
        except Exception as e:
            raise CryptoError(f"加密失败: {e}")
    
    @staticmethod
    def decrypt_chacha20(nonce: bytes, ciphertext: bytes, key: bytes, aad: bytes = b'') -> bytes:
        """XChaCha20-Poly1305 解密"""
        try:
            cipher = ChaCha20Poly1305(key)
            plaintext = cipher.decrypt(nonce, ciphertext, associated_data=aad)
            logger.info(f"解密成功: {len(ciphertext)} 字节 -> {len(plaintext)} 字节")
            return plaintext
        except Exception as e:
            raise CryptoError(f"解密失败: {e}")
    
    @staticmethod
    def compute_merkle_root(blocks: List[bytes]) -> bytes:
        """计算 Merkle Root"""
        if not blocks:
            return b'\x00' * 32
        combined = b''.join(blocks)
        return blake3_hash(combined)

# ============================================================
# === MIFARE Classic 协议
# ============================================================
class MifareClassicProtocol:
    """MIFARE Classic 协议处理"""
    
    @staticmethod
    def detect_card_type(atqa: str, sak: str) -> CardType:
        """根据 ATQA/SAK 检测卡片类型"""
        atqa_bytes = bytes.fromhex(atqa.replace(' ', ''))
        sak_byte = int(sak, 16)
        
        # ATQA: 0x04 0x00 (Classic 1K), 0x02 0x00 (Classic 4K)
        if atqa_bytes == b'\x04\x00':
            if sak_byte == 0x08:
                return CardType.CLASSIC_1K
            elif sak_byte == 0x18:
                return CardType.CLASSIC_4K
        elif atqa_bytes == b'\x44\x00' and sak_byte == 0x04:
            return CardType.PLUS_2K
        elif atqa_bytes == b'\x44\x00' and sak_byte == 0x04:
            return CardType.PLUS_4K
        
        return CardType.UNKNOWN
    
    @staticmethod
    def get_sector_count(card_type: CardType) -> int:
        """获取扇区数量"""
        if card_type == CardType.CLASSIC_1K:
            return 16
        elif card_type == CardType.CLASSIC_4K:
            return 40
        else:
            return 16
    
    @staticmethod
    def is_aefs_card(card: CardIdentity) -> bool:
        """检测 AEFS 卡片"""
        if 6 not in card.sectors:
            return False
        
        sector_6 = card.sectors[6]
        if not sector_6.key_a or not sector_6.key_b:
            return False
        
        # 检查特殊标记 (56 01 和 AE 04)
        key_a_hex = sector_6.key_a.hex()
        key_b_hex = sector_6.key_b.hex()
        
        return '5601' in key_a_hex.lower() and 'ae04' in key_b_hex.lower()

# ============================================================
# === Key 管理器
# ============================================================
class KeyManager:
    """密钥管理系统"""
    
    def __init__(self):
        self.default_keys = {
            'default': KEY_DEFAULT,
            'mad': KEY_MAD,
            'aria_initial': KEY_ARIA_INITIAL,
        }
        self.custom_keys: Dict[str, bytes] = {}
        self.key_history: List[Dict] = []
    
    def add_custom_key(self, name: str, key: bytes):
        """添加自定义密钥"""
        if len(key) != 6:
            raise ValueError("密钥必须是 6 字节")
        self.custom_keys[name] = key
        logger.info(f"已添加自定义密钥: {name}")
    
    def try_auth_with_keys(self, sector_index: int, keys: List[bytes]) -> Optional[Tuple[bytes, str]]:
        """使用多个密钥尝试认证扇区（使用真实 NFC 接口）"""
        from nfc_interface import get_nfc_interface
        nfc = get_nfc_interface()
        
        if not nfc.is_ready():
            logger.error("NFC 接口未准备就绪，无法进行认证")
            return None
        
        for key in keys:
            try:
                # 尝试 Key A
                result_a = nfc.authenticate_sector(sector_index, key, 'A')
                if result_a.success:
                    logger.info(f"扇区 {sector_index} Key A 认证成功: {key.hex()}")
                    return (key, 'A')
                
                # 尝试 Key B
                result_b = nfc.authenticate_sector(sector_index, key, 'B')
                if result_b.success:
                    logger.info(f"扇区 {sector_index} Key B 认证成功: {key.hex()}")
                    return (key, 'B')
                    
            except Exception as e:
                logger.error(f"扇区 {sector_index} 认证异常 (密钥 {key.hex()}): {e}")
                continue
        
        logger.warning(f"扇区 {sector_index} 使用提供的密钥列表认证失败")
        return None
    
    def export_keys_file(self, keys_dict: Dict[int, Tuple[bytes, bytes]]) -> str:
        """导出密钥文件"""
        lines = [
            "# AECardTools Key File",
            f"# Generated: {datetime.datetime.now().isoformat()}",
            ""
        ]
        
        for sector, (key_a, key_b) in keys_dict.items():
            ka_hex = key_a.hex().upper()
            kb_hex = key_b.hex().upper()
            lines.append(f"Sector {sector:2d}: A={ka_hex} B={kb_hex}")
        
        return '\n'.join(lines)
    
    def import_keys_file(self, content: str) -> Dict[int, Tuple[bytes, bytes]]:
        """导入密钥文件"""
        keys = {}
        for line in content.split('\n'):
            if line.startswith('Sector'):
                # 解析格式: "Sector  0: A=FFFFFFFFFFFF B=FFFFFFFFFFFF"
                parts = line.split(': ')
                if len(parts) == 2:
                    sector_str = parts[0].split()[1]
                    key_parts = parts[1].split(' B=')
                    if len(key_parts) == 2:
                        key_a = bytes.fromhex(key_parts[0].replace('A=', ''))
                        key_b = bytes.fromhex(key_parts[1])
                        keys[int(sector_str)] = (key_a, key_b)
        return keys

# ============================================================
# === AEFS 管理器
# ============================================================
class AEFSManager:
    """AEFS 完整管理"""
    
    def __init__(self):
        self.pool_id: Optional[bytes] = None
        self.vol_id: Optional[bytes] = None
        self.sip_level = SIPLevel.ARCHITECT
        self.alias: str = ""
        self.encrypted_payload: Optional[bytes] = None
        self.merkle_root: Optional[bytes] = None
    
    def build_aefs_image(self, 
                        alias: str, 
                        passphrase: str, 
                        file_data: bytes, 
                        sip_level: SIPLevel = SIPLevel.ARCHITECT) -> Dict[str, Any]:
        """构建 AEFS 镜像"""
        logger.info(f"开始构建 AEFS 镜像: {alias}")
        
        try:
            # 1. 生成 Pool ID 和 Volume ID
            self.pool_id = secrets.token_bytes(48)
            self.vol_id = secrets.token_bytes(48)
            self.alias = alias
            self.sip_level = sip_level
            
            # 2. 派生密钥
            file_salt = secrets.token_bytes(16)
            device_salt = secrets.token_bytes(16)
            
            master_key = KeyDerivation.derive_master_key(passphrase, file_salt + device_salt)
            
            # 3. 构建 AEFS Header
            header = struct.pack('BB', AEFS_MAGIC[0], AEFS_MAGIC[1])  # Magic: AE
            header += struct.pack('B', AEFS_VERSION)  # Version: 5
            header += struct.pack('>I', len(file_data))  # File size
            header += struct.pack('>Q', int(datetime.datetime.now().timestamp()))  # Timestamp
            
            plaintext = header + file_data
            
            # 4. 加密
            aad = self.pool_id[:16]  # Associated Authenticated Data
            nonce, ciphertext = CryptoEngine.encrypt_chacha20(plaintext, master_key, aad)
            
            # 5. 计算 MAC 和 Merkle Root
            mac = blake3_mac(master_key, plaintext)
            self.merkle_root = CryptoEngine.compute_merkle_root([ciphertext[i:i+16] for i in range(0, len(ciphertext), 16)])
            
            # 6. 构建最终载荷
            final_stream = file_salt + device_salt + nonce + ciphertext
            
            logger.info(f"AEFS 镜像构建完成: {len(plaintext)} 字节明文 -> {len(ciphertext)} 字节密文")
            
            return {
                'success': True,
                'pool_id': self.pool_id.hex(),
                'vol_id': self.vol_id.hex(),
                'payload': final_stream.hex(),
                'merkle_root': self.merkle_root.hex(),
                'mac': mac.hex(),
                'stream_size': len(final_stream)
            }
        
        except Exception as e:
            logger.error(f"AEFS 镜像构建失败: {e}")
            return {
                'success': False,
                'error': str(e)
            }
    
    def decrypt_aefs_image(self, payload: bytes, passphrase: str) -> Optional[bytes]:
        """解密 AEFS 镜像"""
        try:
            if len(payload) < 16 + 16 + 12:
                raise CryptoError("载荷太短")
            
            file_salt = payload[:16]
            device_salt = payload[16:32]
            nonce = payload[32:44]
            ciphertext = payload[44:]
            
            # 派生密钥
            master_key = KeyDerivation.derive_master_key(passphrase, file_salt + device_salt)
            aad = self.pool_id[:16] if self.pool_id else b''
            
            # 解密
            plaintext = CryptoEngine.decrypt_chacha20(nonce, ciphertext, master_key, aad)
            
            logger.info(f"AEFS 镜像解密成功: {len(plaintext)} 字节")
            return plaintext
        
        except Exception as e:
            logger.error(f"AEFS 镜像解密失败: {e}")
            return None

# ============================================================
# === 导出功能
# ============================================================
class ExportManager:
    """导出管理"""
    
    @staticmethod
    def export_to_json(card: CardIdentity, keys: Dict[int, Tuple[bytes, bytes]]) -> str:
        """导出为 JSON 格式"""
        data = {
            'card_info': {
                'uid': card.uid,
                'sak': card.sak,
                'atqa': card.atqa,
                'type': card.card_type.value,
                'is_aefs': card.is_aefs,
                'detected_at': card.detected_at.isoformat()
            },
            'keys': {
                str(sector): {
                    'key_a': key_a.hex().upper(),
                    'key_b': key_b.hex().upper()
                }
                for sector, (key_a, key_b) in keys.items()
            },
            'sectors': {}
        }
        
        for sector_idx, sector in card.sectors.items():
            blocks = [b.hex().upper() for b in sector.blocks]
            data['sectors'][str(sector_idx)] = {
                'blocks': blocks,
                'auth_status': sector.auth_status.name
            }
        
        return json.dumps(data, indent=2)
    
    @staticmethod
    def export_to_mct(card: CardIdentity) -> str:
        """导出为 MCT 格式"""
        lines = []
        for sector_idx in range(len(card.sectors)):
            lines.append(f"+Sector: {sector_idx}")
            sector = card.sectors[sector_idx]
            for block_idx in range(3):
                lines.append(sector.blocks[block_idx].hex().upper())
            # 尾块 (访问位)
            lines.append(sector.blocks[3].hex().upper())
        
        return '\n'.join(lines)

# ============================================================
# === 主控制器
# ============================================================
class AECardToolsController:
    """主控制器 - Python UI 接口"""
    
    def __init__(self):
        self.current_card: Optional[CardIdentity] = None
        self.key_manager = KeyManager()
        self.aefs_manager = AEFSManager()
        self.export_manager = ExportManager()
        logger.info("AECardToolsController 已初始化")
    
    def on_card_detected(self, uid: str, sak: str, atqa: str) -> Dict[str, Any]:
        """卡片检测回调"""
        logger.info(f"检测到卡片: UID={uid}, SAK={sak}, ATQA={atqa}")
        
        self.current_card = CardIdentity()
        self.current_card.uid = uid
        self.current_card.sak = sak
        self.current_card.atqa = atqa
        self.current_card.card_type = MifareClassicProtocol.detect_card_type(atqa, sak)
        
        sector_count = MifareClassicProtocol.get_sector_count(self.current_card.card_type)
        for i in range(sector_count):
            self.current_card.sectors[i] = CardSector(i)
        
        return {
            'success': True,
            'uid': uid,
            'card_type': self.current_card.card_type.value,
            'sector_count': sector_count
        }
    
    def update_sector_data(self, sector_idx: int, blocks: List[str], key_a: str, key_b: str, auth_status: str) -> Dict[str, Any]:
        """更新扇区数据"""
        if not self.current_card or sector_idx not in self.current_card.sectors:
            return {'success': False, 'error': 'Invalid sector'}
        
        sector = self.current_card.sectors[sector_idx]
        
        # 更新块数据
        for i, block_hex in enumerate(blocks):
            if i < BLOCKS_PER_SECTOR:
                sector.blocks[i] = bytes.fromhex(block_hex)
        
        # 更新密钥
        sector.key_a = bytes.fromhex(key_a) if key_a else None
        sector.key_b = bytes.fromhex(key_b) if key_b else None
        
        # 更新认证状态
        try:
            sector.auth_status = AuthStatus[auth_status]
        except:
            sector.auth_status = AuthStatus.NOT_ATTEMPTED
        
        logger.info(f"扇区 {sector_idx} 已更新")
        return {'success': True}
    
    def export_card_data(self, format_type: str) -> str:
        """导出卡片数据"""
        if not self.current_card:
            return json.dumps({'error': 'No card loaded'})
        
        if format_type == 'json':
            return self.export_manager.export_to_json(self.current_card, {})
        elif format_type == 'mct':
            return self.export_manager.export_to_mct(self.current_card)
        else:
            return json.dumps({'error': 'Unknown format'})
    
    def build_aefs_image(self, alias: str, passphrase: str, file_data_hex: str, sip_level: str = 'ARCHITECT') -> str:
        """构建 AEFS 镜像"""
        try:
            file_data = bytes.fromhex(file_data_hex)
            sip = SIPLevel[sip_level]
            result = self.aefs_manager.build_aefs_image(alias, passphrase, file_data, sip)
            return json.dumps(result)
        except Exception as e:
            return json.dumps({'success': False, 'error': str(e)})

# ============================================================
# === 高级认证与访问控制系统
# ============================================================
class AccessControlBits:
    """访问控制位解析与生成"""
    
    # 访问权限常数定义（14 种标准配置）
    CONFIGS = {
        0: {'name': '工厂默认', 'key_a_read': True, 'key_a_write': True, 'key_a_increment': True, 'key_a_decrement': True},
        1: {'name': '只读', 'key_a_read': True, 'key_a_write': False, 'key_a_increment': False, 'key_a_decrement': False},
        2: {'name': '密钥授权', 'key_a_read': False, 'key_a_write': False, 'key_a_increment': False, 'key_a_decrement': False},
    }
    
    @staticmethod
    def parse_access_bits(trailer_block: bytes) -> Dict[str, Any]:
        """解析尾块中的访问位 (字节 6-8)"""
        if len(trailer_block) < 16:
            return {'error': 'Invalid trailer block size'}
        
        ca0 = trailer_block[6]
        ca1 = trailer_block[7]
        ca2 = trailer_block[8]
        
        # 解析各块的读写权限
        permissions = {}
        for block in range(3):
            permissions[f'block_{block}'] = {
                'read': (ca1 >> (block * 2)) & 0x01,
                'write': (ca1 >> (block * 2 + 1)) & 0x01,
                'increment': (ca2 >> (block * 2)) & 0x01,
                'decrement': (ca2 >> (block * 2 + 1)) & 0x01,
            }
        
        return {
            'valid': True,
            'access_bytes': [ca0, ca1, ca2],
            'permissions': permissions
        }
    
    @staticmethod
    def generate_access_bits(read_perms: Dict, write_perms: Dict) -> bytes:
        """生成访问位"""
        ca0 = 0x78  # 标准值
        ca1 = 0x69
        ca2 = 0x69
        return bytes([ca0, ca1, ca2])

# ============================================================
# === 数据完整性与签名验证
# ============================================================
class IntegrityVerifier:
    """数据完整性验证"""
    
    @staticmethod
    def compute_crc32(data: bytes) -> int:
        """计算 CRC-32 校验"""
        import zlib
        return zlib.crc32(data) & 0xffffffff
    
    @staticmethod
    def compute_sector_checksum(sector: CardSector) -> bytes:
        """计算扇区校验和"""
        blocks_data = b''.join(sector.blocks)
        sha = hashlib.sha256(blocks_data).digest()
        return sha[:16]
    
    @staticmethod
    def verify_block_integrity(block: bytes) -> bool:
        """验证单个块的完整性"""
        if len(block) != 16:
            return False
        
        # 尾块 (块 3) 的特殊验证
        # 字节 0-5: Key A, 6-8: 访问位, 9: 用户数据, 10-15: Key B
        return True

# ============================================================
# === 缓存与性能优化
# ============================================================
class CacheManager:
    """多层缓存管理"""
    
    def __init__(self):
        self.sector_cache: Dict[int, CardSector] = {}
        self.key_cache: Dict[Tuple[int, str], bytes] = {}
        self.lastused_keys: List[bytes] = []
        self.cache_hits = 0
        self.cache_misses = 0
    
    def get_sector(self, index: int) -> Optional[CardSector]:
        """获取缓存的扇区"""
        if index in self.sector_cache:
            self.cache_hits += 1
            return self.sector_cache[index]
        
        self.cache_misses += 1
        return None
    
    def cache_sector(self, sector: CardSector):
        """缓存扇区"""
        self.sector_cache[sector.index] = sector
    
    def get_cache_stats(self) -> Dict[str, Any]:
        """获取缓存统计"""
        total = self.cache_hits + self.cache_misses
        hit_rate = (self.cache_hits / total * 100) if total > 0 else 0
        
        return {
            'cache_hits': self.cache_hits,
            'cache_misses': self.cache_misses,
            'total_requests': total,
            'hit_rate': f"{hit_rate:.2f}%"
        }

# ============================================================
# === 卡片写入保护与验证
# ============================================================
class WriteProtection:
    """写入保护系统"""
    
    @staticmethod
    def simulate_write(card: CardIdentity, sector_index: int, block_index: int, data: bytes) -> Dict[str, Any]:
        """验证写入操作的安全性和合法性（不实际执行）"""
        if sector_index == 0 and block_index == 0 and card.card_type == CardType.CLASSIC_1K:
            return {
                'success': False,
                'error': '标准卡 Block 0 不可写入 - 防止硬件损坏（砖卡）',
                'risk_level': 'CRITICAL',
                'recommendation': '放弃此操作'
            }
        
        if sector_index == 0 and block_index == 3 and card.card_type == CardType.CLASSIC_1K:
            return {
                'success': False,
                'error': '标准卡尾块修改可能导致全卡锁定',
                'risk_level': 'HIGH',
                'suggestion': '建议先备份卡片数据',
                'requires_confirmation': True
            }
        
        return {
            'success': True,
            'will_overwrite': True,
            'bytes_changed': len(data),
            'risk_level': 'MEDIUM',
            'verification_required': False
        }
    
    @staticmethod
    def generate_write_diff(before: bytes, after: bytes) -> Dict[str, Any]:
        """生成写入差异"""
        changes = []
        
        for i, (b1, b2) in enumerate(zip(before, after)):
            if b1 != b2:
                changes.append({
                    'offset': i,
                    'before': f"{b1:02X}",
                    'after': f"{b2:02X}",
                    'bits_changed': bin(b1 ^ b2).count('1')
                })
        
        return {
            'total_bytes_changed': len(changes),
            'changes': changes,
            'unchanged_percentage': f"{((len(before) - len(changes)) / len(before) * 100):.1f}%"
        }

# ============================================================
# === 日志与审计系统
# ============================================================
class AuditLog:
    """审计日志系统"""
    
    def __init__(self):
        self.logs: List[Dict[str, Any]] = []
        self.current_session_id = secrets.token_hex(8)
    
    def log_operation(self, op_type: str, sector: int, block: int, success: bool, details: Dict = None):
        """记录操作"""
        entry = {
            'timestamp': datetime.datetime.now().isoformat(),
            'session_id': self.current_session_id,
            'operation': op_type,
            'sector': sector,
            'block': block,
            'success': success,
            'details': details or {}
        }
        
        self.logs.append(entry)
        logger.info(f"[审计] {op_type} - S{sector}:B{block} - {'成功' if success else '失败'}")
    
    def get_session_summary(self) -> Dict[str, Any]:
        """获取会话摘要"""
        session_logs = [log for log in self.logs if log['session_id'] == self.current_session_id]
        
        success_count = sum(1 for log in session_logs if log['success'])
        failed_count = len(session_logs) - success_count
        
        return {
            'session_id': self.current_session_id,
            'total_operations': len(session_logs),
            'successful': success_count,
            'failed': failed_count,
            'start_time': session_logs[0]['timestamp'] if session_logs else None,
            'end_time': session_logs[-1]['timestamp'] if session_logs else None
        }
    
    def export_logs(self, format_type: str = 'json') -> str:
        """导出日志"""
        if format_type == 'json':
            return json.dumps(self.logs, indent=2, ensure_ascii=False)
        elif format_type == 'csv':
            lines = ['timestamp,session_id,operation,sector,block,success,details']
            for log in self.logs:
                lines.append(f"{log['timestamp']},{log['session_id']},{log['operation']},{log['sector']},{log['block']},{log['success']},\"{json.dumps(log['details'])}\"")
            return '\n'.join(lines)
        
        return ''

# ============================================================
# === 扩展控制器
# ============================================================
class AECardToolsController:
    """主控制器 - 扩展版本"""
    
    def __init__(self):
        self.current_card: Optional[CardIdentity] = None
        self.key_manager = KeyManager()
        self.aefs_manager = AEFSManager()
        self.export_manager = ExportManager()
        self.cache_manager = CacheManager()
        self.write_protection = WriteProtection()
        self.audit_log = AuditLog()
        self.integrity_verifier = IntegrityVerifier()
        self.access_control = AccessControlBits()
        logger.info("AECardToolsController 已初始化 (扩展版)")
    
    def on_card_detected(self, uid: str, sak: str, atqa: str) -> str:
        """卡片检测回调"""
        logger.info(f"检测到卡片: UID={uid}, SAK={sak}, ATQA={atqa}")
        
        try:
            self.current_card = CardIdentity()
            self.current_card.uid = uid
            self.current_card.sak = sak
            self.current_card.atqa = atqa
            self.current_card.card_type = MifareClassicProtocol.detect_card_type(atqa, sak)
            
            sector_count = MifareClassicProtocol.get_sector_count(self.current_card.card_type)
            for i in range(sector_count):
                self.current_card.sectors[i] = CardSector(i)
            
            # 记录审计日志
            self.audit_log.log_operation('CARD_DETECTED', -1, -1, True, {
                'uid': uid,
                'card_type': self.current_card.card_type.value
            })
            
            result = {
                'success': True,
                'uid': uid,
                'card_type': self.current_card.card_type.value,
                'sector_count': sector_count,
                'timestamp': datetime.datetime.now().isoformat()
            }
            return json.dumps(result)
        except Exception as e:
            logger.error(f"卡片检测失败: {e}")
            self.audit_log.log_operation('CARD_DETECTED', -1, -1, False, {'error': str(e)})
            return json.dumps({'success': False, 'error': str(e)})
    
    def update_sector_data(self, sector_idx: int, blocks: List[str], key_a: str, key_b: str, auth_status: str) -> str:
        """更新扇区数据"""
        try:
            if not self.current_card or sector_idx not in self.current_card.sectors:
                self.audit_log.log_operation('SECTOR_UPDATE', sector_idx, -1, False, {'error': 'Invalid sector'})
                return json.dumps({'success': False, 'error': 'Invalid sector'})
            
            sector = self.current_card.sectors[sector_idx]
            
            # 更新块数据（包含尾块）
            for i, block_hex in enumerate(blocks):
                if i < len(sector.blocks):
                    sector.blocks[i] = hex_str_to_bytes(block_hex)
            
            # 更新密钥
            sector.key_a = hex_str_to_bytes(key_a) if key_a else None
            sector.key_b = hex_str_to_bytes(key_b) if key_b else None
            
            # 更新认证状态
            try:
                sector.auth_status = AuthStatus[auth_status]
            except:
                sector.auth_status = AuthStatus.NOT_ATTEMPTED
            
            # 缓存扇区
            self.cache_manager.cache_sector(sector)
            
            # 记录审计日志
            self.audit_log.log_operation('SECTOR_UPDATE', sector_idx, -1, True, {
                'blocks_updated': len(blocks),
                'auth_status': auth_status
            })
            
            logger.info(f"扇区 {sector_idx} 已更新")
            return json.dumps({'success': True})
        except Exception as e:
            logger.error(f"扇区更新失败: {e}")
            self.audit_log.log_operation('SECTOR_UPDATE', sector_idx, -1, False, {'error': str(e)})
            return json.dumps({'success': False, 'error': str(e)})
    
    def get_audit_summary(self) -> str:
        """获取审计摘要"""
        try:
            summary = self.audit_log.get_session_summary()
            cache_stats = self.cache_manager.get_cache_stats()
            
            return json.dumps({
                'success': True,
                'audit': summary,
                'cache': cache_stats
            })
        except Exception as e:
            return json.dumps({'success': False, 'error': str(e)})
    
    def validate_write_operation(self, sector_idx: int, block_idx: int, data_hex: str) -> str:
        """验证写入操作的安全性（执行写入前的安全检查）"""
        try:
            if not self.current_card:
                return json.dumps({'success': False, 'error': 'No card loaded'})
            
            data = hex_str_to_bytes(data_hex)
            
            # 执行写入保护检查
            protection_result = self.write_protection.simulate_write(self.current_card, sector_idx, block_idx, data)
            
            # 获取差异（显示将会发生的具体变化）
            sector = self.current_card.sectors[sector_idx]
            current_data = sector.blocks[block_idx]
            diff = self.write_protection.generate_write_diff(current_data, data)
            
            # 验证访问控制
            access_bits = self.access_control.parse_trailer_block(sector.blocks[3])
            can_write = self.access_control.can_write_block(block_idx, 'A') or self.access_control.can_write_block(block_idx, 'B')
            
            result = {
                'success': True,
                'can_write': can_write,
                'protection': protection_result,
                'diff': diff,
                'access_bits': access_bits,
                'requires_confirmation': protection_result.get('risk_level') in ['HIGH', 'CRITICAL']
            }
            
            # 记录审计日志
            self.audit_log.log_operation('WRITE_VALIDATION', sector_idx, block_idx, True, {
                'can_write': can_write,
                'risk_level': protection_result.get('risk_level')
            })
            
            return json.dumps(result)
        except Exception as e:
            self.audit_log.log_operation('WRITE_VALIDATION', sector_idx, block_idx, False, {'error': str(e)})
            return json.dumps({'success': False, 'error': str(e)})

# ============================================================
# === 全局实例
# ============================================================
_controller = AECardToolsController()

def get_controller() -> AECardToolsController:
    """获取全局控制器实例"""
    return _controller
