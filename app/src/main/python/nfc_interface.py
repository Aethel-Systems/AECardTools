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
nfc_interface.py - Kotlin/Python NFC 接口层

实现 Kotlin NFC 层与 Python 逻辑层的真实通信
所有 NFC 操作必须通过这个接口，严禁模拟
"""

import logging
import time
from typing import Optional, Callable, Dict, Any, Tuple, List
from enum import Enum
import threading
import queue

logger = logging.getLogger("AECardTools.NFCInterface")

# ============================================================
# === NFC 操作状态
# ============================================================
class NFCOperationStatus(Enum):
    """NFC 操作状态"""
    IDLE = 0
    AUTHENTICATING = 1
    READING = 2
    WRITING = 3
    TRANSCEIVING = 4
    ERROR = 5
    TIMEOUT = 6

# ============================================================
# === NFC 认证结果
# ============================================================
class AuthenticationResult:
    """认证结果"""
    def __init__(self, sector: int, key_type: str, success: bool, error: Optional[str] = None):
        self.sector = sector
        self.key_type = key_type
        self.success = success
        self.error = error
        self.timestamp = time.time()
        self.duration_ms = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            'sector': self.sector,
            'key_type': self.key_type,
            'success': self.success,
            'error': self.error,
            'timestamp': self.timestamp,
            'duration_ms': self.duration_ms
        }

# ============================================================
# === NFC 读取结果
# ============================================================
class ReadResult:
    """读取结果"""
    def __init__(self, sector: int, block: int, success: bool, data: Optional[bytes] = None, error: Optional[str] = None):
        self.sector = sector
        self.block = block
        self.success = success
        self.data = data
        self.error = error
        self.timestamp = time.time()
        self.duration_ms = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            'sector': self.sector,
            'block': self.block,
            'success': self.success,
            'data': self.data.hex() if self.data else None,
            'error': self.error,
            'timestamp': self.timestamp,
            'duration_ms': self.duration_ms
        }

# ============================================================
# === NFC 写入结果
# ============================================================
class WriteResult:
    """写入结果"""
    def __init__(self, sector: int, block: int, success: bool, bytes_written: int = 0, error: Optional[str] = None):
        self.sector = sector
        self.block = block
        self.success = success
        self.bytes_written = bytes_written
        self.error = error
        self.timestamp = time.time()
        self.duration_ms = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            'sector': self.sector,
            'block': self.block,
            'success': self.success,
            'bytes_written': self.bytes_written,
            'error': self.error,
            'timestamp': self.timestamp,
            'duration_ms': self.duration_ms
        }

class TransceiveResult:
    """Transceive 结果"""
    def __init__(self, success: bool, response: Optional[bytes] = None, error: Optional[str] = None):
        self.success = success
        self.response = response
        self.error = error
        self.timestamp = time.time()
        self.duration_ms = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            'success': self.success,
            'response': self.response.hex() if self.response else None,
            'error': self.error,
            'timestamp': self.timestamp,
            'duration_ms': self.duration_ms
        }

# ============================================================
# === NFC 接口管理器
# ============================================================
class NFCInterface:
    """NFC 接口 - Kotlin 回调"""
    
    def __init__(self):
        self.status = NFCOperationStatus.IDLE
        self.last_error: Optional[str] = None
        self.operation_timeout_ms = 5000
        self.result_queue: queue.Queue = queue.Queue()
        self.callbacks_registered = False
        
        # 回调函数存储
        self._authenticate_callback: Optional[Callable] = None
        self._read_callback: Optional[Callable] = None
        self._write_callback: Optional[Callable] = None
        self._transceive_callback: Optional[Callable] = None
    
    def register_callbacks(self, 
                          authenticate_cb: Optional[Callable] = None,
                          read_cb: Optional[Callable] = None,
                          write_cb: Optional[Callable] = None,
                          transceive_cb: Optional[Callable] = None) -> bool:
        """注册 Kotlin NFC 回调函数"""
        if authenticate_cb:
            self._authenticate_callback = authenticate_cb
        if read_cb:
            self._read_callback = read_cb
        if write_cb:
            self._write_callback = write_cb
        if transceive_cb:
            self._transceive_callback = transceive_cb
        
        self.callbacks_registered = bool(
            self._authenticate_callback or 
            self._read_callback or 
            self._write_callback or 
            self._transceive_callback
        )
        
        logger.info(f"NFC 回调注册完成: {self.callbacks_registered}")
        return self.callbacks_registered
    
    def authenticate_sector(self, sector: int, key: bytes, key_type: str) -> AuthenticationResult:
        """认证扇区（调用 Kotlin NFC 层）"""
        if not self._authenticate_callback:
            logger.error("认证回调未注册")
            return AuthenticationResult(sector, key_type, False, "Callback not registered")
        
        self.status = NFCOperationStatus.AUTHENTICATING
        start_time = time.time()
        
        try:
            # 调用 Kotlin 的真实认证函数
            result = self._authenticate_callback(sector, key, key_type)
            
            # 处理返回值：可能是布尔值或 AuthenticationResult
            if isinstance(result, AuthenticationResult):
                auth_result = result
            elif isinstance(result, bool):
                auth_result = AuthenticationResult(sector, key_type, result)
            else:
                auth_result = AuthenticationResult(sector, key_type, False, "Invalid callback response")
            
            auth_result.duration_ms = (time.time() - start_time) * 1000
            
            if auth_result.success:
                logger.info(f"扇区 {sector} Key {key_type} 认证成功 ({auth_result.duration_ms:.1f}ms)")
            else:
                logger.warning(f"扇区 {sector} Key {key_type} 认证失败: {auth_result.error} ({auth_result.duration_ms:.1f}ms)")
            
            self.status = NFCOperationStatus.IDLE
            return auth_result
            
        except Exception as e:
            logger.error(f"扇区 {sector} 认证异常: {e}")
            self.status = NFCOperationStatus.ERROR
            self.last_error = str(e)
            return AuthenticationResult(sector, key_type, False, str(e))
    
    def read_block(self, sector: int, block: int) -> ReadResult:
        """读取块（调用 Kotlin NFC 层）"""
        if not self._read_callback:
            logger.error("读取回调未注册")
            return ReadResult(sector, block, False, error="Callback not registered")
        
        self.status = NFCOperationStatus.READING
        start_time = time.time()
        
        try:
            # 调用 Kotlin 的真实读取函数
            result = self._read_callback(sector, block)
            
            # 处理返回值
            if isinstance(result, ReadResult):
                read_result = result
            elif isinstance(result, bytes):
                read_result = ReadResult(sector, block, True, data=result)
            elif isinstance(result, dict):
                read_result = ReadResult(
                    sector, block,
                    result.get('success', False),
                    data=bytes.fromhex(result.get('data', '')) if 'data' in result else None,
                    error=result.get('error')
                )
            else:
                read_result = ReadResult(sector, block, False, error="Invalid callback response")
            
            read_result.duration_ms = (time.time() - start_time) * 1000
            
            if read_result.success:
                logger.info(f"块 {sector}:{block} 读取成功 ({read_result.duration_ms:.1f}ms)")
            else:
                logger.warning(f"块 {sector}:{block} 读取失败: {read_result.error}")
            
            self.status = NFCOperationStatus.IDLE
            return read_result
            
        except Exception as e:
            logger.error(f"块 {sector}:{block} 读取异常: {e}")
            self.status = NFCOperationStatus.ERROR
            self.last_error = str(e)
            return ReadResult(sector, block, False, error=str(e))
    
    def write_block(self, sector: int, block: int, data: bytes) -> WriteResult:
        """写入块（调用 Kotlin NFC 层）"""
        if not self._write_callback:
            logger.error("写入回调未注册")
            return WriteResult(sector, block, False, error="Callback not registered")
        
        if len(data) != 16:
            logger.error(f"写入数据长度必须是 16 字节，收到 {len(data)} 字节")
            return WriteResult(sector, block, False, error="Invalid data length")
        
        self.status = NFCOperationStatus.WRITING
        start_time = time.time()
        
        try:
            # 调用 Kotlin 的真实写入函数
            result = self._write_callback(sector, block, data)
            
            # 处理返回值
            if isinstance(result, WriteResult):
                write_result = result
            elif isinstance(result, bool):
                write_result = WriteResult(sector, block, result, bytes_written=16 if result else 0)
            elif isinstance(result, dict):
                write_result = WriteResult(
                    sector, block,
                    result.get('success', False),
                    bytes_written=result.get('bytes_written', 0),
                    error=result.get('error')
                )
            else:
                write_result = WriteResult(sector, block, False, error="Invalid callback response")
            
            write_result.duration_ms = (time.time() - start_time) * 1000
            
            if write_result.success:
                logger.info(f"块 {sector}:{block} 写入成功 ({write_result.duration_ms:.1f}ms, {write_result.bytes_written} 字节)")
            else:
                logger.warning(f"块 {sector}:{block} 写入失败: {write_result.error}")
            
            self.status = NFCOperationStatus.IDLE
            return write_result
            
        except Exception as e:
            logger.error(f"块 {sector}:{block} 写入异常: {e}")
            self.status = NFCOperationStatus.ERROR
            self.last_error = str(e)
            return WriteResult(sector, block, False, error=str(e))
    
    def transceive(self, apdu: bytes) -> TransceiveResult:
        """Transceive APDU 命令（调用 Kotlin NFC 层）"""
        if not self._transceive_callback:
            logger.error("Transceive 回调未注册")
            return TransceiveResult(False, error="Callback not registered")
        
        self.status = NFCOperationStatus.TRANSCEIVING
        start_time = time.time()
        
        try:
            # 调用 Kotlin 的真实 transceive 函数
            response = self._transceive_callback(apdu)
            
            duration_ms = (time.time() - start_time) * 1000
            
            if isinstance(response, bytes) and len(response) >= 2:
                sw = (response[-2] << 8) | response[-1]
                status_str = f"SW={sw:04X}"
                logger.info(f"APDU transceive 成功: {apdu.hex()[:40]}... → {response.hex()[:40]}... ({duration_ms:.1f}ms, {status_str})")
                
                result = TransceiveResult(True, response=response)
                result.duration_ms = duration_ms
                self.status = NFCOperationStatus.IDLE
                return result
            else:
                logger.warning(f"APDU transceive 返回无效数据: {type(response)}")
                
                result = TransceiveResult(False, error=f"Invalid response type: {type(response)}")
                result.duration_ms = duration_ms
                self.status = NFCOperationStatus.IDLE
                return result
            
        except Exception as e:
            duration_ms = (time.time() - start_time) * 1000
            logger.error(f"APDU transceive 异常: {e}")
            self.status = NFCOperationStatus.ERROR
            self.last_error = str(e)
            
            result = TransceiveResult(False, error=str(e))
            result.duration_ms = duration_ms
            return result
    
    def is_ready(self) -> bool:
        """检查接口是否就绪"""
        return self.callbacks_registered and self.status != NFCOperationStatus.ERROR

# ============================================================
# === 全局 NFC 接口实例
# ============================================================
_nfc_interface = NFCInterface()

def get_nfc_interface() -> NFCInterface:
    """获取全局 NFC 接口"""
    return _nfc_interface

def register_nfc_callbacks(
    authenticate_cb: Optional[Callable] = None,
    read_cb: Optional[Callable] = None,
    write_cb: Optional[Callable] = None,
    transceive_cb: Optional[Callable] = None) -> bool:
    """注册 NFC 回调（从 Kotlin 调用）"""
    return _nfc_interface.register_callbacks(authenticate_cb, read_cb, write_cb, transceive_cb)
