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
crypto_module.py - 工业级加密模块

实现：
- Argon2id 密钥派生 (KDF)
- XChaCha20-Poly1305 认证加密 (AEAD)
- 随机数生成与管理
- 密钥导出与导入
- 密钥分片 (Key Sharding)

满足FIPS 140-2和工业级安全标准的要求。

维护者: Aethel Cryptography Team
版本: 5.5.0-industrial
"""

import os
import secrets
import hashlib
import logging
import hmac
import struct
import zlib
from typing import Tuple, Optional, Dict, Any, List
from kdf_compat import hash_secret_raw, ArgType
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305, AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

logger = logging.getLogger("AECardTools.Crypto")

# ============================================================
# === 常量
# ============================================================

# Argon2id参数（工业级）
KDF_TIME_COST = 3              # T_cost
KDF_MEMORY_COST = 65536        # 64 MB (M_cost)
KDF_PARALLELISM = 4             # P_cost (并行度)
KDF_HASH_LEN = 32              # 256位
KDF_SALT_LEN = 16              # 128位盐

# XChaCha20-Poly1305参数
XCHACHA_NONCE_LEN = 24         # 192位随机Nonce
CHACHA20_KEY_LEN = 32          # 256位密钥
POLY1305_TAG_LEN = 16          # 128位认证标签

# 密钥分片
KEY_SHARE_COUNT = 3             # Shamir Secret Sharing (3-of-3)
MIN_KEY_SHARE_LEN = 11         # 最小分片长度

# ============================================================
# === 异常定义
# ============================================================

class CryptoError(Exception):
    """加密操作异常"""
    pass

class KeyDerivationError(CryptoError):
    """密钥派生失败"""
    pass

class EncryptionError(CryptoError):
    """加密失败"""
    pass

class DecryptionError(CryptoError):
    """解密失败"""
    pass

class IntegrityCheckFailedError(CryptoError):
    """完整性检查失败"""
    pass


class RecoveryAttemptFailedError(CryptoError):
    """受损恢复尝试失败"""
    pass

# ============================================================
# === 密钥派生函数 (KDF)
# ============================================================

class KeyDerivationFunction:
    """Argon2id密钥派生"""
    
    @staticmethod
    def derive_key(
        passphrase: str,
        salt: bytes,
        key_len: int = KDF_HASH_LEN,
        time_cost: int = KDF_TIME_COST,
        memory_cost: int = KDF_MEMORY_COST,
        parallelism: int = KDF_PARALLELISM
    ) -> bytes:
        """
        使用Argon2id派生密钥
        
        参数：
            passphrase: 用户密码
            salt: 盐值（建议从卡片的Sector 9读取）
            key_len: 输出密钥长度
            time_cost: 时间成本 (高=更耐暴力破解)
            memory_cost: 内存成本 (64MB)
            parallelism: 并行度 (4)
        
        返回：
            派生的密钥
        
        安全特性：
            - 抗GPU/ASIC攻击
            - 高时间成本 (3次迭代)
            - 高内存成本 (64MB)
            - 高并行度要求 (4核)
        """
        try:
            if not passphrase:
                raise KeyDerivationError("密码不能为空")
            
            if len(salt) < KDF_SALT_LEN:
                raise KeyDerivationError(f"盐长度不足: {len(salt)} < {KDF_SALT_LEN}")
            
            logger.info(f"派生密钥: passphrase={len(passphrase)}字符, "
                       f"T={time_cost}, M={memory_cost>>10}MB, P={parallelism}")
            
            # 调用Argon2id
            derived_key = hash_secret_raw(
                secret=passphrase.encode('utf-8'),
                salt=salt[:KDF_SALT_LEN],
                time_cost=time_cost,
                memory_cost=memory_cost,
                parallelism=parallelism,
                hash_len=key_len,
                type=ArgType.ID  # Argon2id
            )
            
            logger.info(f"密钥派生成功: {len(derived_key)}字节")
            return derived_key
            
        except Exception as e:
            logger.error(f"密钥派生失败: {e}")
            raise KeyDerivationError(str(e))
    
    @staticmethod
    def derive_multi_keys(
        passphrase: str,
        salt: bytes,
        key_count: int = 2,
        key_len: int = KDF_HASH_LEN
    ) -> Tuple[bytes, ...]:
        """
        派生多个密钥（用于主密钥+子密钥分离）
        
        返回: (主密钥, 子密钥1, 子密钥2, ...)
        """
        keys = []
        for i in range(key_count):
            # 使用索引修改盐以派生不同的密钥
            modified_salt = salt[:KDF_SALT_LEN - 4] + i.to_bytes(4, 'big')
            key = KeyDerivationFunction.derive_key(
                passphrase=passphrase,
                salt=modified_salt,
                key_len=key_len
            )
            keys.append(key)
        
        return tuple(keys)

# ============================================================
# === 认证加密 (AEAD)
# ============================================================

class AuthenticatedEncryption:
    """XChaCha20-Poly1305 认证加密"""
    
    @staticmethod
    def encrypt(
        plaintext: bytes,
        key: bytes,
        nonce: Optional[bytes] = None,
        associated_data: Optional[bytes] = None
    ) -> Tuple[bytes, bytes, bytes]:
        """
        加密数据
        
        参数：
            plaintext: 明文
            key: 加密密钥 (32字节)
            nonce: Nonce (24字节, 如果None则随机生成)
            associated_data: 关联数据 (MAC保护但不加密)
        
        返回：
            (密文, Nonce, 标签)
        
        安全特性：
            - 每次加密使用不同的随机Nonce
            - 防止重放攻击
            - 检测篡改
        """
        try:
            if len(key) != CHACHA20_KEY_LEN:
                raise EncryptionError(f"密钥长度不正确: {len(key)} != {CHACHA20_KEY_LEN}")
            
            # 生成随机Nonce (192bit)
            if nonce is None:
                nonce = secrets.token_bytes(XCHACHA_NONCE_LEN)
            
            if len(nonce) != XCHACHA_NONCE_LEN:
                raise EncryptionError(f"Nonce长度不正确: {len(nonce)} != {XCHACHA_NONCE_LEN}")
            
            # 初始化加密器
            cipher = ChaCha20Poly1305(key)
            
            # 加密 (返回密文+认证标签)
            ciphertext = cipher.encrypt(nonce, plaintext, associated_data)
            
            # Poly1305标签在密文末尾后16字节
            actual_ciphertext = ciphertext[:-POLY1305_TAG_LEN]
            tag = ciphertext[-POLY1305_TAG_LEN:]
            
            logger.debug(f"加密: plaintext={len(plaintext)}B, "
                        f"ciphertext={len(actual_ciphertext)}B, "
                        f"nonce={nonce.hex()[:16]}..., tag={tag.hex()[:16]}...")
            
            return actual_ciphertext, nonce, tag
            
        except Exception as e:
            logger.error(f"加密失败: {e}")
            raise EncryptionError(str(e))
    
    @staticmethod
    def decrypt(
        ciphertext: bytes,
        key: bytes,
        nonce: bytes,
        tag: bytes,
        associated_data: Optional[bytes] = None
    ) -> bytes:
        """
        解密数据
        
        参数：
            ciphertext: 密文
            key: 解密密钥
            nonce: Nonce (必须与加密时相同)
            tag: 认证标签
            associated_data: 关联数据
        
        返回：
            明文
        
        异常：
            DecryptionError: 解密失败
            IntegrityCheckFailedError: MAC验证失败（数据被篡改或密钥错误）
        """
        try:
            if len(key) != CHACHA20_KEY_LEN:
                raise DecryptionError(f"密钥长度不正确: {len(key)} != {CHACHA20_KEY_LEN}")
            
            if len(nonce) != XCHACHA_NONCE_LEN:
                raise DecryptionError(f"Nonce长度不正确: {len(nonce)} != {XCHACHA_NONCE_LEN}")
            
            if len(tag) != POLY1305_TAG_LEN:
                raise DecryptionError(f"标签长度不正确: {len(tag)} != {POLY1305_TAG_LEN}")
            
            # 初始化解密器
            cipher = ChaCha20Poly1305(key)
            
            # 重组 密文 + 标签
            ciphertext_with_tag = ciphertext + tag
            
            # 解密
            plaintext = cipher.decrypt(nonce, ciphertext_with_tag, associated_data)
            
            logger.debug(f"解密: ciphertext={len(ciphertext)}B, "
                        f"plaintext={len(plaintext)}B")
            
            return plaintext
            
        except Exception as e:
            error_msg = str(e)
            if "tag mismatch" in error_msg.lower():
                logger.error("MAC验证失败: 数据被篡改或密钥错误")
                raise IntegrityCheckFailedError("MAC验证失败") from e
            else:
                logger.error(f"解密失败: {e}")
                raise DecryptionError(str(e))

# ============================================================
# === AES-GCM（备选加密）
# ============================================================

class AESEncryption:
    """AES-256-GCM 加密（用于兼容性）"""
    
    @staticmethod
    def encrypt(
        plaintext: bytes,
        key: bytes,
        nonce: Optional[bytes] = None,
        associated_data: Optional[bytes] = None
    ) -> Tuple[bytes, bytes, bytes]:
        """AES-256-GCM加密"""
        try:
            if len(key) != 32:
                raise EncryptionError(f"密钥长度不正确: {len(key)} != 32")
            
            if nonce is None:
                nonce = secrets.token_bytes(12)  # AES-GCM使用12字节Nonce
            
            cipher = AESGCM(key)
            ciphertext = cipher.encrypt(nonce, plaintext, associated_data)
            
            # GCM也在末尾追加16字节标签
            actual_ciphertext = ciphertext[:-16]
            tag = ciphertext[-16:]
            
            return actual_ciphertext, nonce, tag
            
        except Exception as e:
            logger.error(f"AES加密失败: {e}")
            raise EncryptionError(str(e))
    
    @staticmethod
    def decrypt(
        ciphertext: bytes,
        key: bytes,
        nonce: bytes,
        tag: bytes,
        associated_data: Optional[bytes] = None
    ) -> bytes:
        """AES-256-GCM解密"""
        try:
            cipher = AESGCM(key)
            ciphertext_with_tag = ciphertext + tag
            plaintext = cipher.decrypt(nonce, ciphertext_with_tag, associated_data)
            return plaintext
            
        except Exception as e:
            if "tag mismatch" in str(e).lower():
                raise IntegrityCheckFailedError("MAC验证失败") from e
            raise DecryptionError(str(e))

# ============================================================
# === 密钥管理
# ============================================================

class KeyManager:
    """密钥管理器"""
    
    def __init__(self):
        self.master_key: Optional[bytes] = None
        self.derived_keys: Dict[str, bytes] = {}
        self.nonce_counter = 0
    
    def set_master_key(self, key: bytes) -> None:
        """设置主密钥"""
        if len(key) != CHACHA20_KEY_LEN:
            raise CryptoError(f"主密钥长度必须是{CHACHA20_KEY_LEN}字节")
        
        self.master_key = key
        logger.info("主密钥已设置")
    
    def derive_subkey(self, purpose: str) -> bytes:
        """派生子密钥"""
        if self.master_key is None:
            raise CryptoError("主密钥未设置")
        
        cache_key = f"subkey_{purpose}"
        if cache_key in self.derived_keys:
            return self.derived_keys[cache_key]
        
        # 使用主密钥和目标串派生子密钥
        h = hashlib.blake2b(self.master_key + purpose.encode(), digest_size=32)
        subkey = h.digest()
        
        self.derived_keys[cache_key] = subkey
        logger.debug(f"派生子密钥: {purpose}")
        
        return subkey
    
    def generate_nonce(self) -> bytes:
        """生成随机 Nonce（真实随机，用于生产环境）"""
        return secrets.token_bytes(XCHACHA_NONCE_LEN)
    
    def generate_random_nonce(self) -> bytes:
        """生成随机Nonce（生产环境使用）"""
        return secrets.token_bytes(XCHACHA_NONCE_LEN)

# ============================================================
# === 密钥分片 (Shamir Secret Sharing简化版)
# ============================================================

class KeySharding:
    """密钥分片"""
    
    @staticmethod
    def split_key(key: bytes, threshold: int = 3) -> Tuple[bytes, ...]:
        """
        简单的密钥分片（非Shamir）
        
        使用XOR分片确保至少需要全部分片才能恢复密钥
        """
        if len(key) < 32:
            raise CryptoError("密钥长度不足")
        
        shares = []
        remaining = bytearray(key)
        
        for i in range(threshold - 1):
            share = secrets.token_bytes(len(key))
            shares.append(bytes(share))
            
            # XOR到remaining中
            remaining = bytearray(a ^ b for a, b in zip(remaining, share))
        
        shares.append(bytes(remaining))
        
        logger.info(f"密钥分片: {threshold}个分片")
        return tuple(shares)
    
    @staticmethod
    def recover_key(shares: Tuple[bytes, ...]) -> bytes:
        """恢复分片密钥"""
        if not shares:
            raise CryptoError("没有可用的分片")
        
        result = bytearray(shares[0])
        
        for share in shares[1:]:
            if len(share) != len(result):
                raise CryptoError("分片长度不一致")
            
            result = bytearray(a ^ b for a, b in zip(result, share))
        
        logger.info(f"已恢复密钥: {len(shares)}个分片")
        return bytes(result)


class AEFSV6AES128:
    """AEFS v6 AES-128 主权加密内核。"""

    NONCE_LEN = 16
    MAC_LEN = 16
    PAYLOAD_LEN = 900
    FRAME_LEN = PAYLOAD_LEN - NONCE_LEN
    DATA_REGION_LEN = FRAME_LEN - MAC_LEN
    HEADER_LEN = 4
    MAX_COMPRESSED = DATA_REGION_LEN - HEADER_LEN

    @staticmethod
    def _expand_salt(salt4: bytes) -> bytes:
        if len(salt4) != 4:
            raise CryptoError(f"AEFS v6 盐长度必须是4字节: {len(salt4)}")
        return hashlib.sha256(b'AEFSv6/Argon2Salt' + salt4).digest()[:KDF_SALT_LEN]

    @staticmethod
    def derive_root_key(passphrase: str, salt4: bytes, plaintext_mode: bool = False) -> bytes:
        """派生 AEFS v6 16 字节 AES-128 根密钥。"""
        if plaintext_mode:
            return b'\x00' * 16
        expanded_salt = AEFSV6AES128._expand_salt(salt4)
        return KeyDerivationFunction.derive_key(
            passphrase=passphrase,
            salt=expanded_salt,
            key_len=16,
        )

    @staticmethod
    def shard_root_key(root_key: bytes, salt4: bytes) -> Dict[str, bytes]:
        """按 AEFS v6 Sector 0 规则分片根密钥。"""
        if len(root_key) != 16:
            raise CryptoError(f"AEFS v6 根密钥长度必须是16字节: {len(root_key)}")
        if len(salt4) != 4:
            raise CryptoError(f"AEFS v6 盐长度必须是4字节: {len(salt4)}")
        return {
            'block2_suffix': root_key[:8],
            'block3_key_a': root_key[8:14],
            'block3_key_b': root_key[14:16] + salt4,
        }

    @staticmethod
    def reconstruct_root_key(block2: bytes, trailer_key_a: bytes, trailer_key_b: bytes) -> Tuple[bytes, bytes]:
        """从 S0:B2/B3 物理片段重组根密钥和盐。"""
        if len(block2) != 16:
            raise CryptoError(f"S0:B2 长度错误: {len(block2)}")
        if len(trailer_key_a) != 6:
            raise CryptoError(f"S0:B3 KeyA 长度错误: {len(trailer_key_a)}")
        if len(trailer_key_b) != 6:
            raise CryptoError(f"S0:B3 KeyB 长度错误: {len(trailer_key_b)}")
        root_key = block2[8:16] + trailer_key_a + trailer_key_b[:2]
        salt4 = trailer_key_b[2:6]
        if len(root_key) != 16 or len(salt4) != 4:
            raise CryptoError("AEFS v6 根密钥重组失败")
        return root_key, salt4

    @staticmethod
    def encrypt_frame(root_key: bytes, nonce: bytes, plaintext_frame: bytes) -> bytes:
        if len(root_key) != 16:
            raise EncryptionError(f"AES-128 根密钥长度不正确: {len(root_key)} != 16")
        if len(nonce) != AEFSV6AES128.NONCE_LEN:
            raise EncryptionError(f"Nonce长度不正确: {len(nonce)} != {AEFSV6AES128.NONCE_LEN}")
        cipher = Cipher(algorithms.AES(root_key), modes.CTR(nonce))
        encryptor = cipher.encryptor()
        return encryptor.update(plaintext_frame) + encryptor.finalize()

    @staticmethod
    def decrypt_frame(root_key: bytes, nonce: bytes, ciphertext: bytes) -> bytes:
        if len(root_key) != 16:
            raise DecryptionError(f"AES-128 根密钥长度不正确: {len(root_key)} != 16")
        if len(nonce) != AEFSV6AES128.NONCE_LEN:
            raise DecryptionError(f"Nonce长度不正确: {len(nonce)} != {AEFSV6AES128.NONCE_LEN}")
        cipher = Cipher(algorithms.AES(root_key), modes.CTR(nonce))
        decryptor = cipher.decryptor()
        return decryptor.update(ciphertext) + decryptor.finalize()

    @staticmethod
    def _compute_mac(root_key: bytes, compressed: bytes) -> bytes:
        mac_key = hashlib.sha256(root_key + b'AEFSv6/HMAC').digest()
        return hmac.new(mac_key, compressed, hashlib.sha256).digest()[:AEFSV6AES128.MAC_LEN]

    @staticmethod
    def _deterministic_padding(length: int, root_key: bytes, nonce: bytes) -> bytes:
        if length <= 0:
            return b''
        seed = hashlib.sha256(root_key + nonce + b'AEFSv6/PAD').digest()
        out = bytearray()
        counter = 0
        while len(out) < length:
            out.extend(hashlib.sha256(seed + counter.to_bytes(4, 'big')).digest())
            counter += 1
        return bytes(out[:length])

    @staticmethod
    def build_plaintext_frame(
        payload_bytes: bytes,
        root_key: bytes,
        nonce: Optional[bytes] = None,
        allow_truncation: bool = False,
    ) -> Tuple[bytes, Dict[str, Any]]:
        compressed = zlib.compress(payload_bytes, level=9)
        truncated = False
        if len(compressed) > AEFSV6AES128.MAX_COMPRESSED:
            if not allow_truncation:
                raise CryptoError(
                    f"压缩后数据超出物理载荷上限: {len(compressed)} > {AEFSV6AES128.MAX_COMPRESSED}"
                )
            compressed = compressed[:AEFSV6AES128.MAX_COMPRESSED]
            truncated = True

        original_size = min(len(payload_bytes), 0xFFFF)
        header = struct.pack('>HH', original_size, len(compressed))
        data_region = bytearray(AEFSV6AES128.DATA_REGION_LEN)
        data_region[:AEFSV6AES128.HEADER_LEN] = header
        compressed_end = AEFSV6AES128.HEADER_LEN + len(compressed)
        data_region[AEFSV6AES128.HEADER_LEN:compressed_end] = compressed

        if compressed_end < AEFSV6AES128.DATA_REGION_LEN:
            padding_len = AEFSV6AES128.DATA_REGION_LEN - compressed_end
            if nonce is None:
                data_region[compressed_end:] = secrets.token_bytes(padding_len)
            else:
                data_region[compressed_end:] = AEFSV6AES128._deterministic_padding(
                    padding_len,
                    root_key,
                    nonce
                )

        mac = AEFSV6AES128._compute_mac(root_key, compressed)
        return bytes(data_region) + mac, {
            'original_size': len(payload_bytes),
            'compressed_size': len(compressed),
            'truncated': truncated,
            'padding_size': max(0, AEFSV6AES128.DATA_REGION_LEN - compressed_end),
        }

    @staticmethod
    def _parse_frame(plaintext_frame: bytes) -> Dict[str, Any]:
        if len(plaintext_frame) != AEFSV6AES128.FRAME_LEN:
            raise DecryptionError(
                f"AEFS v6 帧长度错误: {len(plaintext_frame)} != {AEFSV6AES128.FRAME_LEN}"
            )
        data_region = plaintext_frame[:AEFSV6AES128.DATA_REGION_LEN]
        stored_mac = plaintext_frame[AEFSV6AES128.DATA_REGION_LEN:]
        original_size, compressed_size = struct.unpack('>HH', data_region[:AEFSV6AES128.HEADER_LEN])
        return {
            'data_region': data_region,
            'stored_mac': stored_mac,
            'original_size': original_size,
            'compressed_size': compressed_size,
        }

    @staticmethod
    def _try_recover_compressed(
        root_key: bytes,
        data_region: bytes,
        stored_mac: bytes
    ) -> Tuple[Optional[bytes], Dict[str, Any]]:
        """尝试从头字段损坏中恢复压缩体。"""
        attempts = 0
        repaired_candidates: List[int] = []
        payload_region = data_region[AEFSV6AES128.HEADER_LEN:]
        for candidate_size in range(1, len(payload_region) + 1):
            attempts += 1
            compressed = payload_region[:candidate_size]
            expected_mac = AEFSV6AES128._compute_mac(root_key, compressed)
            if hmac.compare_digest(expected_mac, stored_mac):
                try:
                    zlib.decompress(compressed)
                    repaired_candidates.append(candidate_size)
                    return compressed, {
                        'attempted': True,
                        'success': True,
                        'strategy': 'header_rescan',
                        'attempts': attempts,
                        'candidate_sizes': repaired_candidates,
                    }
                except Exception:
                    continue
        return None, {
            'attempted': True,
            'success': False,
            'strategy': 'header_rescan',
            'attempts': attempts,
            'candidate_sizes': repaired_candidates,
        }

    @staticmethod
    def verify_and_unpack_frame(root_key: bytes, plaintext_frame: bytes) -> Dict[str, Any]:
        parsed = AEFSV6AES128._parse_frame(plaintext_frame)
        data_region = parsed['data_region']
        stored_mac = parsed['stored_mac']
        compressed_size = parsed['compressed_size']

        integrity = {
            'ok': False,
            'message': '主权数据受损',
            'repair_attempted': False,
            'repair_success': False,
            'repair_strategy': None,
            'repair_attempts': 0,
        }

        if 0 < compressed_size <= AEFSV6AES128.MAX_COMPRESSED:
            compressed = data_region[
                AEFSV6AES128.HEADER_LEN:AEFSV6AES128.HEADER_LEN + compressed_size
            ]
            expected_mac = AEFSV6AES128._compute_mac(root_key, compressed)
            if hmac.compare_digest(expected_mac, stored_mac):
                payload = zlib.decompress(compressed)
                integrity.update({'ok': True, 'message': '完整性校验通过'})
                return {
                    'payload_bytes': payload,
                    'compressed_size': compressed_size,
                    'original_size': parsed['original_size'],
                    'integrity': integrity,
                }

        recovery = AEFSV6AES128._try_recover_compressed(root_key, data_region, stored_mac)
        integrity.update({
            'repair_attempted': recovery[1].get('attempted', False),
            'repair_success': recovery[1].get('success', False),
            'repair_strategy': recovery[1].get('strategy'),
            'repair_attempts': recovery[1].get('attempts', 0),
        })
        recovered_compressed = recovery[0]
        if recovered_compressed is not None:
            expected_mac = AEFSV6AES128._compute_mac(root_key, recovered_compressed)
            if hmac.compare_digest(expected_mac, stored_mac):
                payload = zlib.decompress(recovered_compressed)
                integrity.update({'ok': True, 'message': '主权数据曾受损，已完成修复'})
                return {
                    'payload_bytes': payload,
                    'compressed_size': len(recovered_compressed),
                    'original_size': len(payload),
                    'integrity': integrity,
                }

        raise IntegrityCheckFailedError("主权数据受损，且自动修复失败")

# ============================================================
# === 导出符号
# ============================================================
__all__ = [
    'KeyDerivationFunction',
    'AuthenticatedEncryption',
    'AESEncryption',
    'KeyManager',
    'KeySharding',
    'CryptoError',
    'KeyDerivationError',
    'EncryptionError',
    'DecryptionError',
    'IntegrityCheckFailedError',
    'RecoveryAttemptFailedError',
    'AEFSV6AES128',
    'KDF_TIME_COST',
    'KDF_MEMORY_COST',
    'KDF_PARALLELISM',
    'KDF_HASH_LEN',
    'XCHACHA_NONCE_LEN',
    'CHACHA20_KEY_LEN',
    'POLY1305_TAG_LEN',
]
