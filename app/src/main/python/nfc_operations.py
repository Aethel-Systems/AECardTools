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
nfc_operations.py - NFC 操作层

处理所有 NFC 通信、MIFARE Classic 读写、认证等底层操作
"""

import logging
from typing import Optional, List, Tuple, Dict, Any
from enum import Enum
import time

logger = logging.getLogger("AECardTools.NFC")

# ============================================================
# === 常量
# ============================================================
MIFARE_CLASSIC_1K_SECTORS = 16
MIFARE_CLASSIC_4K_SECTORS = 40
BLOCK_SIZE = 16
BLOCKS_PER_SECTOR = 4
SECTOR_TRAILER_BLOCK = 3

# MIFARE 命令
CMD_AUTH_A = 0x60
CMD_AUTH_B = 0x61
CMD_READ = 0x30
CMD_WRITE = 0xA0
CMD_INCREMENT = 0xC1
CMD_DECREMENT = 0xC0
CMD_RESTORE = 0xC2
CMD_TRANSFER = 0xB0

# ============================================================
# === 内存分布
# ============================================================
class MemoryLayout:
    """内存分布计算"""
    
    @staticmethod
    def get_block_absolute_address(sector: int, block: int) -> int:
        """获取块的绝对地址（字节）"""
        return (sector * BLOCKS_PER_SECTOR + block) * BLOCK_SIZE
    
    @staticmethod
    def get_sector_address(block_address: int) -> Tuple[int, int]:
        """从块地址获取扇区和块"""
        absolute_block = block_address // BLOCK_SIZE
        sector = absolute_block // BLOCKS_PER_SECTOR
        block = absolute_block % BLOCKS_PER_SECTOR
        return sector, block
    
    @staticmethod
    def is_trailer_block(sector: int, block: int) -> bool:
        """检查是否为尾块"""
        return block == 3
    
    @staticmethod
    def get_blocks_in_sector(total_sectors: int, sector: int) -> int:
        """获取扇区中的块数"""
        if sector < 32:
            return 4
        else:
            return 16

# ============================================================
# === 访问条件解析
# ============================================================
class AccessConditionsParser:
    """访问条件解析"""
    
    # 访问条件编码 (C1, C2, C3)
    ACCESS_MATRIX = {
        (0, 0, 0): ('Read: KeyA|KeyB', 'Write: KeyA', 'Increment: KeyA', 'Decrement: KeyA|KeyB'),
        (0, 0, 1): ('Read: KeyA|KeyB', 'Write: Never', 'Increment: Never', 'Decrement: KeyA|KeyB'),
        (0, 1, 0): ('Read: KeyA|KeyB', 'Write: KeyB', 'Increment: Never', 'Decrement: KeyA|KeyB'),
        (0, 1, 1): ('Read: KeyA|KeyB', 'Write: KeyB', 'Increment: KeyB', 'Decrement: Never'),
        (1, 0, 0): ('Read: KeyA|KeyB', 'Write: Never', 'Increment: Never', 'Decrement: Never'),
        (1, 0, 1): ('Read: KeyB', 'Write: KeyB', 'Increment: Never', 'Decrement: Never'),
        (1, 1, 0): ('Read: KeyB', 'Write: Never', 'Increment: Never', 'Decrement: Never'),
        (1, 1, 1): ('Read: Never', 'Write: Never', 'Increment: Never', 'Decrement: Never'),
    }
    
    @staticmethod
    def parse_trailer_block(trailer_data: bytes) -> Dict[str, Any]:
        """解析尾块（16 字节）"""
        if len(trailer_data) != BLOCK_SIZE:
            raise ValueError("尾块数据必须是 16 字节")
        
        key_a = trailer_data[0:6].hex().upper()
        access_bits = trailer_data[6:9]
        key_b = trailer_data[10:16].hex().upper()
        
        # 解析访问条件
        c1 = (access_bits[0] >> 4) & 1
        c2 = (access_bits[1] >> 4) & 1
        c3 = (access_bits[2] >> 4) & 1
        
        access_tuple = (c1, c2, c3)
        access_conditions = AccessConditionsParser.ACCESS_MATRIX.get(access_tuple, ('未知', '未知', '未知', '未知'))
        
        return {
            'key_a': key_a,
            'key_b': key_b,
            'access_bits_raw': access_bits.hex().upper(),
            'access_conditions': {
                'read': access_conditions[0],
                'write': access_conditions[1],
                'increment': access_conditions[2],
                'decrement': access_conditions[3]
            },
            'gpb': trailer_data[9]
        }
    
    @staticmethod
    def build_trailer_block(key_a: bytes, key_b: bytes, access_bits: bytes, gpb: int = 0) -> bytes:
        """构建尾块"""
        if len(key_a) != 6 or len(key_b) != 6 or len(access_bits) != 3:
            raise ValueError("密钥必须是 6 字节，访问位必须是 3 字节")
        
        return key_a + access_bits + bytes([gpb]) + key_b

# ============================================================
# === 数据操作
# ============================================================
class DataOperations:
    """数据操作"""
    
    # CRC-16/MODBUS 多项式表（预计算）
    _CRC_TABLE = None
    
    @staticmethod
    def _init_crc_table():
        """初始化 CRC-16/MODBUS 查找表"""
        if DataOperations._CRC_TABLE is not None:
            return
        
        table = []
        for i in range(256):
            crc = i
            for _ in range(8):
                if crc & 1:
                    crc = (crc >> 1) ^ 0xA001  # CRC-16/MODBUS 多项式
                else:
                    crc >>= 1
            table.append(crc)
        
        DataOperations._CRC_TABLE = table
    
    @staticmethod
    def calculate_crc16_modbus(data: bytes) -> int:
        """计算 CRC-16/MODBUS 校验和"""
        DataOperations._init_crc_table()
        
        crc = 0xFFFF
        for byte in data:
            tbl_idx = (crc ^ byte) & 0xFF
            crc = ((crc >> 8) ^ DataOperations._CRC_TABLE[tbl_idx]) & 0xFFFF
        
        return crc
    
    @staticmethod
    def calculate_mfoc_trailer(sector_data: bytes) -> Dict[str, Any]:
        """计算块数据的 CRC-16/MODBUS 校验"""
        # MIFARE Classic 标准的 CRC 检查
        crc = DataOperations.calculate_crc16_modbus(sector_data[:-2])
        
        # CRC-16/MODBUS 低字节在前
        expected_crc_low = sector_data[-2]
        expected_crc_high = sector_data[-1]
        
        calculated_crc = crc & 0xFFFF
        calculated_low = calculated_crc & 0xFF
        calculated_high = (calculated_crc >> 8) & 0xFF
        
        is_valid = (expected_crc_low == calculated_low and 
                   expected_crc_high == calculated_high)
        
        return {
            'checksum': calculated_crc,
            'valid': is_valid,
            'expected': (expected_crc_high << 8) | expected_crc_low,
            'algorithm': 'CRC-16/MODBUS'
        }
    
    @staticmethod
    def validate_block_integrity(block_data: bytes) -> bool:
        """验证块完整性"""
        return len(block_data) == BLOCK_SIZE
    
    @staticmethod
    def increment_value_block(current_value: int, increment: int) -> bytes:
        """增值操作 - 返回 4 字节小端整数"""
        new_value = current_value + increment
        return new_value.to_bytes(4, byteorder='little')
    
    @staticmethod
    def decrement_value_block(current_value: int, decrement: int) -> bytes:
        """减值操作"""
        new_value = max(0, current_value - decrement)  # 不能为负数
        return new_value.to_bytes(4, byteorder='little')
    
    @staticmethod
    def read_value_from_block(block_data: bytes) -> int:
        """从块读取整数值（4 字节小端）"""
        if len(block_data) < 4:
            return 0
        return int.from_bytes(block_data[:4], byteorder='little')

# ============================================================
# === 操作日志
# ============================================================
class OperationLog:
    """操作日志记录"""
    
    def __init__(self):
        self.entries: List[Dict] = []
    
    def log_read(self, sector: int, block: int, success: bool, data: Optional[bytes] = None, error: Optional[str] = None):
        """记录读操作"""
        self.entries.append({
            'type': 'READ',
            'sector': sector,
            'block': block,
            'success': success,
            'data_hex': data.hex() if data else None,
            'error': error,
            'timestamp': time.time()
        })
    
    def log_write(self, sector: int, block: int, success: bool, data: Optional[bytes] = None, error: Optional[str] = None):
        """记录写操作"""
        self.entries.append({
            'type': 'WRITE',
            'sector': sector,
            'block': block,
            'success': success,
            'data_hex': data.hex() if data else None,
            'error': error,
            'timestamp': time.time()
        })
    
    def log_auth(self, sector: int, key_type: str, success: bool, error: Optional[str] = None):
        """记录认证操作"""
        self.entries.append({
            'type': 'AUTH',
            'sector': sector,
            'key_type': key_type,
            'success': success,
            'error': error,
            'timestamp': time.time()
        })
    
    def get_summary(self) -> Dict[str, Any]:
        """获取日志摘要"""
        reads = [e for e in self.entries if e['type'] == 'READ']
        writes = [e for e in self.entries if e['type'] == 'WRITE']
        auths = [e for e in self.entries if e['type'] == 'AUTH']
        
        return {
            'total_operations': len(self.entries),
            'read_count': len(reads),
            'read_success': len([e for e in reads if e['success']]),
            'write_count': len(writes),
            'write_success': len([e for e in writes if e['success']]),
            'auth_count': len(auths),
            'auth_success': len([e for e in auths if e['success']]),
            'entries': self.entries[-50:]  # 最后 50 条
        }

# ============================================================
# === Raw 命令终端 (APDU Transceive)
# ============================================================
class RawCommandTerminal:
    """原始命令终端"""
    
    def __init__(self):
        self.command_history: List[Dict[str, Any]] = []
        self.presets = {
            'SELECT_PPSE': '00A404000E325041592E5359532E4444463031',
            'SELECT_AID': '00A404007D32220000000000000000',
            'READ_BINARY': '00B000',
            'UPDATE_BINARY': '00D6',
            'GET_RESPONSE': '00C00000',
        }
    
    def execute_command(self, apdu_hex: str) -> Dict[str, Any]:
        """执行 APDU 命令（使用真实 NFC 接口）"""
        from nfc_interface import get_nfc_interface
        nfc = get_nfc_interface()
        
        if not nfc.is_ready():
            logger.error("NFC 接口未准备就绪，无法执行 APDU 命令")
            entry = {
                'command': apdu_hex,
                'response': None,
                'timestamp': time.time(),
                'success': False,
                'error': 'NFC interface not ready'
            }
            self.command_history.append(entry)
            return entry
        
        try:
            apdu_hex_clean = apdu_hex.replace(' ', '').replace('\n', '').upper()
            
            # 验证格式
            if len(apdu_hex_clean) % 2 != 0:
                raise ValueError("APDU 十六进制字符串长度必须为偶数")
            
            try:
                apdu_bytes = bytes.fromhex(apdu_hex_clean)
            except ValueError as e:
                raise ValueError(f"无效的十六进制格式: {e}")
            
            if len(apdu_bytes) < 4:
                raise ValueError("APDU 命令必须至少 4 字节 (CLA INS P1 P2)")
            
            # 调用真实的 NFC transceive
            response = nfc.transceive(apdu_bytes)
            
            if response is not None:
                entry = {
                    'command': apdu_hex_clean,
                    'response': response.hex(),
                    'timestamp': time.time(),
                    'success': True,
                    'command_bytes_count': len(apdu_bytes),
                    'response_bytes_count': len(response)
                }
                logger.info(f"APDU 命令执行成功: {apdu_hex_clean[:40]}... → {response.hex()[:40]}...")
            else:
                entry = {
                    'command': apdu_hex_clean,
                    'response': None,
                    'timestamp': time.time(),
                    'success': False,
                    'error': 'Transceive returned None'
                }
                logger.error(f"APDU 命令执行失败: {apdu_hex_clean[:40]}...")
            
            self.command_history.append(entry)
            return entry
        
        except Exception as e:
            logger.error(f"APDU 命令执行异常: {e}")
            entry = {
                'command': apdu_hex,
                'response': None,
                'timestamp': time.time(),
                'success': False,
                'error': str(e)
            }
            self.command_history.append(entry)
            return entry

    
    def get_command_presets(self) -> Dict[str, str]:
        """获取预设命令"""
        return self.presets
    
    def get_history(self, max_entries: int = 20) -> List[Dict]:
        """获取命令历史"""
        return self.command_history[-max_entries:]

# ============================================================
# === 卡片信息仪表盘
# ============================================================
class CardInformationDashboard:
    """卡片信息仪表盘"""
    
    @staticmethod
    def build_dashboard(uid: str, sak: str, atqa: str, sectors_data: Dict) -> Dict[str, Any]:
        """构建信息仪表盘"""
        # ATQA/SAK 解析
        atqa_bytes = bytes.fromhex(atqa.replace(' ', ''))
        sak_byte = int(sak, 16)
        
        # 推测厂商
        vendor = CardInformationDashboard._detect_vendor(uid, sak_byte)
        
        # 计算用户区大小
        if sak_byte == 0x08:
            user_size = 704  # 1K 卡
            total_size = 1024
        elif sak_byte == 0x18:
            user_size = 3584  # 4K 卡
            total_size = 4096
        else:
            user_size = 0
            total_size = 0
        
        # BCC 验证
        uid_bytes = bytes.fromhex(uid)
        bcc = CardInformationDashboard._calculate_bcc(uid_bytes[:-1])
        bcc_valid = (uid_bytes[-1] == bcc)
        
        return {
            'uid': uid,
            'uid_formatted': ' '.join(uid[i:i+2] for i in range(0, len(uid), 2)),
            'sak': sak,
            'atqa': atqa,
            'detected_vendor': vendor,
            'bcc': f"{bcc:02X}",
            'bcc_valid': bcc_valid,
            'total_sectors': 16 if sak_byte == 0x08 else 40,
            'total_size_bytes': total_size,
            'user_area_bytes': user_size,
            'atqa_interpretation': CardInformationDashboard._interpret_atqa(atqa_bytes),
            'sak_interpretation': CardInformationDashboard._interpret_sak(sak_byte)
        }
    
    @staticmethod
    def _detect_vendor(uid: str, sak: int) -> str:
        """从 UID/SAK 推测厂商"""
        uid_bytes = bytes.fromhex(uid)
        nxp_prefix = uid_bytes[0] in [0x04, 0x08, 0x0A, 0x0F]
        
        if nxp_prefix:
            return 'NXP Semiconductors'
        elif sak == 0x88:
            return 'Infineon'
        else:
            return 'Unknown / Clone'
    
    @staticmethod
    def _calculate_bcc(data: bytes) -> int:
        """计算 BCC"""
        return sum(data) & 0xFF
    
    @staticmethod
    def _interpret_atqa(atqa: bytes) -> str:
        """解释 ATQA"""
        if atqa == b'\x04\x00':
            return 'MIFARE Classic 1K'
        elif atqa == b'\x02\x00':
            return 'MIFARE Classic 4K'
        elif atqa == b'\x44\x00':
            return 'MIFARE Plus'
        else:
            return f'未知 ATQA: {atqa.hex()}'
    
    @staticmethod
    def _interpret_sak(sak: int) -> str:
        """解释 SAK"""
        if sak == 0x08:
            return 'MIFARE Classic 1K (16 sectors)'
        elif sak == 0x18:
            return 'MIFARE Classic 4K (40 sectors)'
        elif sak == 0x04:
            return 'MIFARE Plus'
        else:
            return f'未知 SAK: {sak:02X}'

# ============================================================
# === 性能监控与统计
# ============================================================
class PerformanceMonitor:
    """性能监控"""
    
    def __init__(self):
        self.read_times: List[float] = []
        self.write_times: List[float] = []
        self.auth_times: List[float] = []
        self.start_time = time.time()
    
    def record_read(self, duration: float):
        """记录读操作时间"""
        self.read_times.append(duration)
    
    def record_write(self, duration: float):
        """记录写操作时间"""
        self.write_times.append(duration)
    
    def record_auth(self, duration: float):
        """记录认证时间"""
        self.auth_times.append(duration)
    
    def get_statistics(self) -> Dict[str, Any]:
        """获取统计"""
        import statistics
        
        stats = {}
        
        if self.read_times:
            stats['read'] = {
                'count': len(self.read_times),
                'avg': statistics.mean(self.read_times),
                'min': min(self.read_times),
                'max': max(self.read_times),
            }
        
        if self.write_times:
            stats['write'] = {
                'count': len(self.write_times),
                'avg': statistics.mean(self.write_times),
                'min': min(self.write_times),
                'max': max(self.write_times),
            }
        
        if self.auth_times:
            stats['auth'] = {
                'count': len(self.auth_times),
                'avg': statistics.mean(self.auth_times),
                'min': min(self.auth_times),
                'max': max(self.auth_times),
            }
        
        stats['uptime'] = time.time() - self.start_time
        
        return stats

# ============================================================
# === 全局实例
# ============================================================
_operation_log = OperationLog()
_raw_terminal = RawCommandTerminal()
_card_dashboard = CardInformationDashboard()
_performance_monitor = PerformanceMonitor()

def get_operation_log() -> OperationLog:
    """获取操作日志"""
    return _operation_log

def get_raw_terminal() -> RawCommandTerminal:
    """获取原始命令终端"""
    return _raw_terminal

def get_card_dashboard() -> CardInformationDashboard:
    """获取卡片仪表盘"""
    return _card_dashboard

def get_performance_monitor() -> PerformanceMonitor:
    """获取性能监控"""
    return _performance_monitor
