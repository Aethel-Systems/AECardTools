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
__init__.py - Python 模块初始化

确保所有子模块可正常导入
"""

import logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - [%(levelname)s] - %(message)s'
)

logger = logging.getLogger("AECardTools")
logger.info("AECardTools Python Environment Initialized")

# 导入核心模块
try:
    from lcow_engine import LCOWEngine, VirtualAddressSpace, MerkleTree
    logger.info("LCOW引擎已加载")
except ImportError as e:
    logger.warning(f"LCOW引擎导入失败: {e}")

try:
    from crypto_module import (
        KeyDerivationFunction,
        AuthenticatedEncryption,
        KeyManager,
        KeySharding
    )
    logger.info("加密模块已加载")
except ImportError as e:
    logger.warning(f"加密模块导入失败: {e}")

# 导出主要接口供 Kotlin 调用
from ffi_bridge import (
    # NFC 操作
    on_card_detected,
    update_sector_data,
    get_operation_log_summary,
    # AEFS 操作
    build_aefs_image,
    decrypt_aefs_image,
    export_card_data,
    import_keys_file,
    export_keys_file,
    # 工具函数
    calculate_bcc,
    parse_access_conditions,
    hex_string_to_ascii,
    get_controller_info,
    save_snapshot_to_file,
    get_version,
    get_python_module_info,
    # LCOW 引擎接口 (v5.5 新增)
    lcow_initialize,
    lcow_begin_transaction,
    lcow_begin_aefs_v6_rebuild,
    lcow_write_block,
    lcow_commit_transaction,
    lcow_get_active_v6_package,
    lcow_rollback_transaction,
    lcow_trigger_gc,
    verify_aefs_v6_package,
    # 访问控制计算器 (v5.5 新增)
    access_control_parse_bits,
    access_control_calculate_bits,
    # 虚拟注册表编辑器 (v5.5 新增)
    registry_read_value,
    registry_set_value,
    registry_list_children,
    # 十六进制画布 (v5.5 新增)
    hex_canvas_get_logical_view,
    hex_canvas_get_physical_location,
    hex_canvas_get_regions,
    # 密钥派生和加密 (v5.5 新增)
    kdf_derive_key,
    encrypt_aead,
    decrypt_aead,
    verify_merkle_root,
)

__version__ = "1.0.0-Industrial"
__all__ = [
    'on_card_detected',
    'update_sector_data',
    'build_aefs_image',
    'decrypt_aefs_image',
    'export_card_data',
    'import_keys_file',
    'export_keys_file',
    'get_version',
    'get_python_module_info',
    'calculate_bcc',
    'parse_access_conditions',
    'hex_string_to_ascii',
    'get_controller_info',
    'get_operation_log_summary',
    'save_snapshot_to_file',
    'lcow_initialize',
    'lcow_begin_transaction',
    'lcow_begin_aefs_v6_rebuild',
    'lcow_write_block',
    'lcow_commit_transaction',
    'lcow_get_active_v6_package',
    'lcow_rollback_transaction',
    'lcow_trigger_gc',
    'verify_aefs_v6_package',
    'access_control_parse_bits',
    'access_control_calculate_bits',
    'registry_read_value',
    'registry_set_value',
    'registry_list_children',
    'hex_canvas_get_logical_view',
    'hex_canvas_get_physical_location',
    'hex_canvas_get_regions',
    'kdf_derive_key',
    'encrypt_aead',
    'decrypt_aead',
    'verify_merkle_root',
]
