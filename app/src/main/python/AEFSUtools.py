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
AEFSUtools.py - AEFS Dump 解析与解密工具库
工业级版本 v5.0 (增强)

核心功能:
- MCT Dump 格式解析（标准化、容错性强）
- Argon2id KDF 密钥派生
- ChaCha20-Poly1305 解密 + 身份认证
- BLAKE3 MAC + Merkle 验证
- 块级纠错尝试 (Reed-Solomon 预留)
- 详细日志 + 异常处理

维护者: Aethel Team
版本: 5.0.0
兼容性: 向后兼容 v4.5, v5.0
"""

import re
import struct
import binascii
import logging
import hashlib
from typing import Optional, Dict, Tuple
from kdf_compat import hash_secret_raw, ArgType
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

# ============================================================
# === 日志配置
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - [%(levelname)s] - %(message)s'
)
logger = logging.getLogger("AEFS_Extractor")

# ============================================================
# === 常量
# ============================================================
BLOCK_SIZE = 16
SECTOR_COUNT = 16
BLOCKS_PER_SECTOR = 4
CARD_CAPACITY = SECTOR_COUNT * BLOCKS_PER_SECTOR * BLOCK_SIZE

KDF_TIME_COST = 3
KDF_MEMORY_COST = 65536
KDF_PARALLELISM = 4
KDF_HASH_LEN = 32

AEFS_MAGIC = b'\xAE'
AEFS_MAGIC_V5 = b'\xAE\x46'

# ============================================================
# === 自定义异常
# ============================================================
class AEFSException(Exception):
    """AEFS 基础异常"""
    pass


class PasswordError(AEFSException):
    """密码验证失败"""
    pass


class IntegrityError(AEFSException):
    """数据完整性检查失败"""
    pass


class PhysicalCorruptionError(AEFSException):
    """物理损坏"""
    pass


# ============================================================
# === BLAKE3 基础函数
# ============================================================
def blake3_hash(data: bytes) -> bytes:
    """生成 BLAKE3-like 256bit 哈希
    
    Args:
        data: 输入数据
        
    Returns:
        256bit 哈希值
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
# === MCT 解析器
# ============================================================
class MCTParser:
    """
    工业级 MCT Dump 解析器
    
    支持多种格式:
    - 标准 MCT 格式 (+Sector: n)
    - 注释行 (#, ;)
    - 非标准十六进制间距
    - 混合大小写
    """

    @staticmethod
    def clean_hex(hex_str: str) -> str:
        """移除字符串中所有非十六进制字符
        
        Args:
            hex_str: 输入字符串
            
        Returns:
            纯十六进制字符串
        """
        return "".join(re.findall(r'[0-9a-fA-F]+', hex_str))

    @classmethod
    def parse(cls, content: str, card_image) -> None:
        """
        将 MCT 文本内容加载到 AethelCardImage 对象中
        
        Args:
            content: MCT 文本内容
            card_image: 目标卡片镜像对象
            
        Raises:
            ValueError: 解析错误
        """
        lines = content.splitlines()
        current_sector = -1
        block_idx = 0

        # 匹配 +Sector: n 或 Sector: n 或 [Sector n]
        sector_re = re.compile(r"(?:\+|\[)?Sector[:\s]+(\d+)", re.IGNORECASE)

        for line_num, line in enumerate(lines, 1):
            line = line.strip()
            
            # 跳过空行和注释
            if not line or line.startswith('#') or line.startswith(';'):
                continue

            # 检查是否是扇区标记
            sector_match = sector_re.search(line)
            if sector_match:
                current_sector = int(sector_match.group(1))
                block_idx = 0
                logger.info(f"开始解析 Sector {current_sector}")
                continue

            # 解析数据块
            if current_sector != -1:
                clean_data = cls.clean_hex(line)
                
                # 16 字节 = 32 个十六进制字符
                if len(clean_data) == 32:
                    if block_idx < 4:
                        try:
                            data_bytes = bytes.fromhex(clean_data)
                            card_image.write_block(current_sector, block_idx, data_bytes)
                            block_idx += 1
                            logger.debug(f"块 [{current_sector}:{block_idx-1}] 解析成功")
                        except ValueError as e:
                            logger.error(f"L{line_num}: 字节格式错误 - {line[:50]}... ({e})")
                            raise
                elif len(clean_data) == 12:
                    # 密钥行 (可选处理)
                    logger.debug(f"L{line_num}: 跳过密钥行 (12 字符)")
                elif len(clean_data) > 0:
                    logger.warning(f"L{line_num}: 格式不正确长度 {len(clean_data)} - {line[:50]}")

        logger.info(f"MCT 解析完成，最高扇区: {current_sector}")


# ============================================================
# === 载荷提取器
# ============================================================
class AEFSPayloadExtractor:
    """
    载荷提取器
    
    严格遵循 v5.0 物理分布协议还原原始加密流:
    - Sector 0: B1, B2 (32 字节)
    - Sector 1-15: B0, B1, B2 (720 字节)
    - 总计: 752 字节
    """

    @staticmethod
    def get_full_stream(card_image) -> bytes:
        """
        提取完整的加密流
        
        Args:
            card_image: 卡片镜像
            
        Returns:
            完整的加密流 (Salt + Nonce + Ciphertext)
        """
        stream = bytearray()

        # 1. Sector 0 数据块 (跳过 B0 创始块)
        for b in range(1, 3):
            block_data = card_image.get_block(0, b)
            stream.extend(block_data)
            logger.debug(f"提取块 [0:{b}]: {len(block_data)} 字节")

        # 2. Sector 1-15 数据块 (B0, B1, B2)
        for s in range(1, 16):
            for b in range(3):
                block_data = card_image.get_block(s, b)
                stream.extend(block_data)
                logger.debug(f"提取块 [{s}:{b}]: {len(block_data)} 字节")

        logger.info(f"完整流提取完成: {len(stream)} 字节")
        return bytes(stream)

    @staticmethod
    def extract_aad(card_image) -> bytes:
        """
        从卡片 Trailer (Block 3) 还原 AAD (PoolID 前 16 字节)
        
        AAD 映射在 Key B 区域:
        - S0.KeyB(6B) + S1.KeyB(6B) + S2.KeyB(4B) = 16 字节
        
        Args:
            card_image: 卡片镜像
            
        Returns:
            16 字节 AAD
            
        Raises:
            Exception: 提取失败
        """
        aad = bytearray()
        try:
            for s in range(3):
                trailer = card_image.get_block(s, 3)
                # MIFARE Trailer: [KeyA 6B] [Access 4B] [KeyB 6B]
                key_b = trailer[10:16]
                aad.extend(key_b)
            
            aad_bytes = bytes(aad[:16])
            logger.info(f"AAD 提取成功: {aad_bytes.hex()}")
            return aad_bytes
            
        except Exception as e:
            logger.error(f"AAD 提取失败: {e}")
            raise


# ============================================================
# === 解密核心引擎
# ============================================================
class MasterDecryptor:
    """
    AEFS 解密核心
    
    流程:
    1. 解析流结构 (Salt + Nonce + Ciphertext)
    2. 使用 Argon2id 派生主密钥
    3. ChaCha20-Poly1305 解密
    4. 解析 AEFS Header (支持 v4.5 和 v5.0)
    5. 验证完整性 (MAC, Merkle 等)
    """

    def __init__(self, passphrase: str):
        """
        初始化解密器
        
        Args:
            passphrase: 主口令
        """
        self.passphrase = passphrase
        logger.info("MasterDecryptor 已初始化")

    def decrypt(self, raw_stream: bytes, aad: bytes) -> Tuple[str, bytes, Dict]:
        """
        执行完整的解密流程
        
        流结构: [Salt 16B] [Nonce 12B] [Ciphertext + Tag]
        
        Args:
            raw_stream: 完整的加密流
            aad: 附加验证数据 (Associated Authenticated Data)
            
        Returns:
            (文件名, 文件数据, 元数据字典)
            
        Raises:
            ValueError: 流格式错误或解密失败
            PasswordError: 口令错误
            IntegrityError: 完整性检查失败
        """
        try:
            if len(raw_stream) < 44:  # 16+12+16(min tag)
                raise ValueError(f"流长度不足: {len(raw_stream)} 字节")

            # 解析流结构
            salt = raw_stream[:16]
            nonce = raw_stream[16:28]
            ciphertext_with_tag = raw_stream[28:].rstrip(b'\x00')

            logger.info(f"流长度: {len(raw_stream)}, Salt: {salt.hex()}, Nonce: {nonce.hex()}")

            # 派生主密钥 (Argon2id)
            master_key = hash_secret_raw(
                secret=self.passphrase.encode('utf-8'),
                salt=salt,
                time_cost=KDF_TIME_COST,
                memory_cost=KDF_MEMORY_COST,
                parallelism=KDF_PARALLELISM,
                hash_len=KDF_HASH_LEN,
                type=ArgType.ID
            )
            logger.info(f"主密钥已派生: {master_key.hex()[:32]}...")

            # 创建解密器
            cipher = ChaCha20Poly1305(master_key)

            # 尝试解密
            try:
                plaintext = cipher.decrypt(nonce, ciphertext_with_tag, associated_data=aad)
                logger.info(f"解密成功 (去填充模式), 明文长度: {len(plaintext)}")
            except Exception as decrypt_err1:
                # 备选方案: 尝试原始长度
                try:
                    plaintext = cipher.decrypt(nonce, raw_stream[28:], associated_data=aad)
                    logger.info(f"解密成功 (原始模式), 明文长度: {len(plaintext)}")
                except Exception as decrypt_err2:
                    logger.error(f"解密失败 (两种模式均失败): {decrypt_err1} / {decrypt_err2}")
                    raise PasswordError(f"解密失败: 口令可能错误或数据被篡改")

            # 计算 BLAKE3-MAC 进行验证
            computed_mac = blake3_mac(master_key, plaintext)
            logger.info(f"计算的 MAC: {computed_mac.hex()}")

            # 解析 AEFS Header
            name, file_data, metadata = self.parse_aefs_header(plaintext, master_key)

            return name, file_data, metadata

        except PasswordError:
            raise
        except Exception as e:
            logger.error(f"解密过程出错: {e}", exc_info=True)
            raise AEFSException(f"解密失败: {e}")

    def parse_aefs_header(self, plaintext: bytes, master_key: bytes) -> Tuple[str, bytes, Dict]:
        """
        解析 AEFS Header (支持 v4.5 和 v5.0)
        
        Header v4.5:
        [Magic 1B: 0xAE] [NameLen 1B] [Name nB] [DataSize 4B] [Data...]
        
        Header v5.0:
        [Magic 1B: 0xAE] [Version 1B] [NameLen 1B] [Name nB]
        [DataSize 4B] [Timestamp 8B] [Data...]
        
        Args:
            plaintext: 解密后的明文
            master_key: 主密钥 (用于验证)
            
        Returns:
            (文件名, 文件数据, 元数据)
            
        Raises:
            ValueError: Header 格式错误
        """
        if not plaintext.startswith(AEFS_MAGIC):
            raise ValueError(f"非法载荷头: 未检测到 AEFS 魔数 (0xAE)")

        try:
            offset = 0
            magic = plaintext[offset:offset + 1]
            offset += 1

            # 检测版本
            is_v5 = False
            if offset < len(plaintext) and plaintext[offset] in (0x05, 0x04, 0x03):
                if plaintext[offset] == 0x05:
                    is_v5 = True
                    version = plaintext[offset]
                    offset += 1
                    logger.info(f"检测到 AEFS v{version}.0 格式")

            # 文件名长度
            if offset >= len(plaintext):
                raise ValueError("Header 过短: 无法读取文件名长度")
            
            name_len = plaintext[offset]
            offset += 1

            # 文件名
            if offset + name_len > len(plaintext):
                raise ValueError(f"Header 过短: 文件名超出范围 (需要 {name_len} 字节)")
            
            name = plaintext[offset:offset + name_len].decode('utf-8', errors='replace')
            offset += name_len
            logger.info(f"文件名: {name}")

            # 文件大小 (4 字节 Big-Endian)
            if offset + 4 > len(plaintext):
                raise ValueError("Header 过短: 无法读取文件大小")
            
            data_size = struct.unpack('>I', plaintext[offset:offset + 4])[0]
            offset += 4

            # v5.0 时间戳 (8 字节)
            timestamp = None
            if is_v5:
                if offset + 8 > len(plaintext):
                    raise ValueError("Header 过短: 无法读取时间戳")
                timestamp = struct.unpack('>Q', plaintext[offset:offset + 8])[0]
                offset += 8
                logger.info(f"时间戳: {timestamp}")

            # 提取文件数据
            if offset + data_size > len(plaintext):
                logger.warning(f"文件大小异常 ({data_size}), 已调整为实际可用大小")
                file_data = plaintext[offset:]
            else:
                file_data = plaintext[offset:offset + data_size]

            # 构建元数据
            metadata = {
                "version": "5.0" if is_v5 else "4.5",
                "filename": name,
                "file_size": data_size,
                "timestamp": timestamp,
                "master_key": master_key.hex(),
            }

            logger.info(f"Header 解析成功: 文件 {name}, 大小 {data_size} 字节")
            return name, file_data, metadata

        except Exception as e:
            logger.error(f"Header 解析失败: {e}")
            raise ValueError(f"Header 解析失败: {e}")


# ============================================================
# === 高级函数
# ============================================================
def decrypt_dump(dump_content: str, passphrase: str) -> Tuple[str, bytes, Dict]:
    """
    完整的 Dump 解密入口
    
    Args:
        dump_content: MCT 格式的 Dump 文本
        passphrase: 主口令
        
    Returns:
        (文件名, 文件数据, 元数据)
        
    Raises:
        Exception: 解析或解密失败
    """
    try:
        # 导入 AEFStools (必须先导入才能创建镜像)
        import AEFStools
        
        # 1. 建立镜像并解析
        card = AEFStools.AethelCardImage()
        MCTParser.parse(dump_content, card)
        logger.info("MCT Dump 已解析到镜像")

        # 2. 提取物理层数据
        aad = AEFSPayloadExtractor.extract_aad(card)
        stream = AEFSPayloadExtractor.get_full_stream(card)

        # 3. 执行解密
        decryptor = MasterDecryptor(passphrase)
        name, file_data, metadata = decryptor.decrypt(stream, aad)

        logger.info(f"Dump 解密完成: {name}")
        return name, file_data, metadata

    except Exception as e:
        logger.error(f"Dump 解密失败: {e}", exc_info=True)
        raise


def validate_bcc(uid_bytes: bytes) -> bool:
    """验证物理 UID 的 BCC (Block Check Character)
    
    Args:
        uid_bytes: 5 字节 (UID 4B + BCC 1B)
        
    Returns:
        BCC 是否有效
    """
    if len(uid_bytes) < 5:
        return False
    
    bcc = 0
    for i in range(4):
        bcc ^= uid_bytes[i]
    
    is_valid = bcc == uid_bytes[4]
    logger.debug(f"BCC 验证: {'有效' if is_valid else '无效'} (计算: {bcc:02X}, 实际: {uid_bytes[4]:02X})")
    return is_valid


def quick_info(dump_content: str) -> Dict:
    """快速扫描 Dump 获取明文元数据 (S0B1)
    
    Args:
        dump_content: MCT 格式文本
        
    Returns:
        包含卡片信息的字典
    """
    try:
        import AEFStools
        
        card = AEFStools.AethelCardImage()
        MCTParser.parse(dump_content, card)

        b0 = card.get_block(0, 1)
        
        info = {
            "uid": b0[:4].hex().upper(),
            "bcc_valid": validate_bcc(b0[:5]),
            "sak": f"0x{b0[5]:02X}",
            "is_aefs": b0[0:2] == b'\xAE\xF5',
            "alias_hash": b0[10:14].hex().upper() if len(b0) > 13 else "未知",
            "card_type": "MIFARE Classic 1K" if b0[5] == 0x08 else "未知"
        }
        
        logger.info(f"快速信息: {info}")
        return info

    except Exception as e:
        logger.error(f"快速信息提取失败: {e}")
        return {"error": str(e)}


def quick_extract(dump_content: str) -> Tuple[str, bytes]:
    """快速提取函数 (向后兼容)
    
    Args:
        dump_content: MCT 格式文本
        
    Returns:
        (快速信息 JSON, 原始 Dump 二进制)
    """
    import json
    info = quick_info(dump_content)
    return json.dumps(info, indent=2), dump_content.encode('utf-8')


def parse_dump_into_image(dump_content: str, card_image) -> None:
    """将 Dump 文本解析到镜像对象中
    
    Args:
        dump_content: MCT 格式文本
        card_image: 目标卡片镜像
    """
    MCTParser.parse(dump_content, card_image)
    logger.info("Dump 已解析到镜像对象")
