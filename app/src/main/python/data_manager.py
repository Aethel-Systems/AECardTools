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
data_manager.py - 数据管理层

管理卡片数据、差分比较、导出/导入等
"""

import json
import logging
from typing import Dict, List, Optional, Any, Tuple
from dataclasses import dataclass, asdict
import datetime

logger = logging.getLogger("AECardTools.DataManager")

# ============================================================
# === 数据模型
# ============================================================
@dataclass
class BlockData:
    """块数据"""
    sector: int
    block: int
    data: str  # hex 字符串
    is_readable: bool = True
    is_writable: bool = True
    
    def to_dict(self) -> Dict:
        return asdict(self)

@dataclass
class SectorData:
    """扇区数据"""
    sector: int
    blocks: List[BlockData]
    key_a: str  # hex
    key_b: str  # hex
    auth_status: str  # "SUCCESS_A", "SUCCESS_B", "FAILED", "NOT_ATTEMPTED"
    access_bits: str  # hex
    
    def to_dict(self) -> Dict:
        return {
            'sector': self.sector,
            'blocks': [b.to_dict() for b in self.blocks],
            'key_a': self.key_a,
            'key_b': self.key_b,
            'auth_status': self.auth_status,
            'access_bits': self.access_bits
        }

@dataclass
class CardSnapshot:
    """卡片快照"""
    timestamp: str
    uid: str
    card_type: str
    sector_count: int
    sectors: Dict[int, SectorData]
    metadata: Dict[str, Any]
    
    def to_dict(self) -> Dict:
        return {
            'timestamp': self.timestamp,
            'uid': self.uid,
            'card_type': self.card_type,
            'sector_count': self.sector_count,
            'sectors': {str(k): v.to_dict() for k, v in self.sectors.items()},
            'metadata': self.metadata
        }

# ============================================================
# === 差分对比
# ============================================================
class DifferenceCalculator:
    """差分计算"""
    
    @staticmethod
    def compare_blocks(block1: str, block2: str) -> Tuple[bool, List[int]]:
        """对比两个块，返回 (是否相同, 不同字节位置列表)"""
        if len(block1) != len(block2):
            return False, list(range(min(len(block1), len(block2))))
        
        differences = []
        for i in range(0, len(block1), 2):  # 每个字节 = 2 个十六进制字符
            if block1[i:i+2] != block2[i:i+2]:
                differences.append(i // 2)
        
        return len(differences) == 0, differences
    
    @staticmethod
    def compare_snapshots(snap1: CardSnapshot, snap2: CardSnapshot) -> Dict[str, Any]:
        """对比两个快照"""
        differences = {
            'uid_changed': snap1.uid != snap2.uid,
            'sectors_changed': [],
            'total_blocks_changed': 0,
            'total_bytes_changed': 0
        }
        
        for sector_idx in snap1.sectors:
            if sector_idx not in snap2.sectors:
                continue
            
            sec1 = snap1.sectors[sector_idx]
            sec2 = snap2.sectors[sector_idx]
            
            sector_diff = {
                'sector': sector_idx,
                'blocks_changed': [],
                'keys_changed': False
            }
            
            # 对比块
            for block_idx, block1 in enumerate(sec1.blocks):
                if block_idx < len(sec2.blocks):
                    block2 = sec2.blocks[block_idx]
                    same, diff_bytes = DifferenceCalculator.compare_blocks(block1.data, block2.data)
                    
                    if not same:
                        sector_diff['blocks_changed'].append({
                            'block': block_idx,
                            'changed_bytes': diff_bytes,
                            'before': block1.data,
                            'after': block2.data
                        })
                        differences['total_blocks_changed'] += 1
                        differences['total_bytes_changed'] += len(diff_bytes)
            
            # 对比密钥
            if sec1.key_a != sec2.key_a or sec1.key_b != sec2.key_b:
                sector_diff['keys_changed'] = True
                sector_diff['key_a_changed'] = sec1.key_a != sec2.key_a
                sector_diff['key_b_changed'] = sec1.key_b != sec2.key_b
            
            if sector_diff['blocks_changed'] or sector_diff['keys_changed']:
                differences['sectors_changed'].append(sector_diff)
        
        return differences

# ============================================================
# === 导入/导出
# ============================================================
class DataIOManager:
    """数据导入/导出"""
    
    @staticmethod
    def export_snapshot_to_json(snapshot: CardSnapshot) -> str:
        """导出快照为 JSON"""
        return json.dumps(snapshot.to_dict(), indent=2, ensure_ascii=False)
    
    @staticmethod
    def export_snapshot_to_mct(snapshot: CardSnapshot) -> str:
        """导出快照为 MCT 格式"""
        lines = []
        
        for sector_idx in range(snapshot.sector_count):
            if sector_idx not in snapshot.sectors:
                continue
            
            sector = snapshot.sectors[sector_idx]
            lines.append(f"+Sector: {sector_idx}")
            
            # 写入前 3 个块
            for block_idx in range(3):
                if block_idx < len(sector.blocks):
                    lines.append(sector.blocks[block_idx].data)
            
            # 写入尾块（Key A + Access Bits + Key B）
            key_a = sector.key_a
            access_bits = sector.access_bits
            key_b = sector.key_b
            lines.append(f"{key_a}{access_bits}{key_b}")
        
        return "\n".join(lines)
    
    @staticmethod
    def export_keys_to_file(snapshot: CardSnapshot) -> str:
        """导出密钥文件"""
        lines = [
            "# AECardTools Key File",
            f"# Generated: {datetime.datetime.now().isoformat()}",
            f"# UID: {snapshot.uid}",
            f"# Card Type: {snapshot.card_type}",
            ""
        ]
        
        for sector_idx in range(snapshot.sector_count):
            if sector_idx not in snapshot.sectors:
                continue
            
            sector = snapshot.sectors[sector_idx]
            ka = sector.key_a
            kb = sector.key_b
            lines.append(f"Sector {sector_idx:2d}: A={ka} B={kb}")
        
        return "\n".join(lines)
    
    @staticmethod
    def import_mct_dump(content: str) -> Dict[int, Dict]:
        """导入 MCT Dump 格式"""
        sectors = {}
        current_sector = None
        block_count = 0
        
        for line in content.split('\n'):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            
            if line.startswith('+Sector:'):
                # 新扇区开始
                if current_sector is not None:
                    sectors[current_sector] = {'blocks': [], 'trailer': None}
                
                try:
                    current_sector = int(line.split(':')[1].strip())
                    block_count = 0
                except:
                    continue
            
            elif current_sector is not None and len(line) == 32:  # 16 字节 = 32 十六进制字符
                if block_count < 3:
                    # 数据块
                    if current_sector not in sectors:
                        sectors[current_sector] = {'blocks': [], 'trailer': None}
                    sectors[current_sector]['blocks'].append(line)
                    block_count += 1
                
                elif block_count == 3:
                    # 尾块（40 字节 = 12 + 2 + 2 + 12）
                    if current_sector not in sectors:
                        sectors[current_sector] = {'blocks': [], 'trailer': None}
                    sectors[current_sector]['trailer'] = {
                        'key_a': line[:12],
                        'access_bits': line[12:16],
                        'key_b': line[16:32]
                    }
                    block_count += 1
        
        return sectors
    
    @staticmethod
    def import_keys_file(content: str) -> Dict[int, Tuple[str, str]]:
        """导入密钥文件"""
        keys = {}
        
        for line in content.split('\n'):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            
            # 格式: "Sector  0: A=FFFFFFFFFFFF B=FFFFFFFFFFFF"
            if line.startswith('Sector'):
                try:
                    parts = line.split(': ')
                    sector_str = parts[0].split()[1]
                    sector = int(sector_str)
                    
                    key_parts = parts[1].split(' B=')
                    key_a = key_parts[0].replace('A=', '')
                    key_b = key_parts[1]
                    
                    keys[sector] = (key_a, key_b)
                except:
                    continue
        
        return keys

# ============================================================
# === 数据处理向导
# ============================================================
class DataWizard:
    """数据处理向导"""
    
    @staticmethod
    def execute_batch_operation(sectors: Dict[int, SectorData], 
                              operation: str, 
                              target_sectors: List[int]) -> List[Tuple[int, bool, str]]:
        """执行批量操作"""
        results = []
        
        if operation == 'clear':
            # 清零选定扇区的所有块
            for sector_idx in target_sectors:
                if sector_idx in sectors:
                    sector = sectors[sector_idx]
                    for block in sector.blocks:
                        block.data = '0' * 32  # 32 个零
                    results.append((sector_idx, True, "Cleared"))
        
        elif operation == 'fill':
            # 填充选定扇区
            for sector_idx in target_sectors:
                if sector_idx in sectors:
                    sector = sectors[sector_idx]
                    for block in sector.blocks:
                        block.data = 'F' * 32  # 32 个 F
                    results.append((sector_idx, True, "Filled"))
        
        elif operation == 'backup_key':
            # 备份密钥
            backup_data = {}
            for sector_idx in target_sectors:
                if sector_idx in sectors:
                    sector = sectors[sector_idx]
                    backup_data[sector_idx] = {
                        'key_a': sector.key_a,
                        'key_b': sector.key_b
                    }
            results.append((999, True, json.dumps(backup_data)))
        
        return results

# ============================================================
# === 全局数据管理器
# ============================================================
class GlobalDataManager:
    """全局数据管理器"""
    
    def __init__(self):
        self.current_snapshot: Optional[CardSnapshot] = None
        self.snapshot_history: List[CardSnapshot] = []
        self.io_manager = DataIOManager()
        self.diff_calculator = DifferenceCalculator()
        self.wizard = DataWizard()
    
    def save_snapshot(self, snapshot: CardSnapshot):
        """保存快照"""
        self.current_snapshot = snapshot
        self.snapshot_history.append(snapshot)
        logger.info(f"快照已保存: {snapshot.uid} at {snapshot.timestamp}")
    
    def get_difference_report(self, index1: int, index2: int) -> Optional[Dict]:
        """获取差异报告"""
        if index1 < len(self.snapshot_history) and index2 < len(self.snapshot_history):
            return self.diff_calculator.compare_snapshots(
                self.snapshot_history[index1],
                self.snapshot_history[index2]
            )
        return None
    
    def export_current(self, format_type: str) -> Optional[str]:
        """导出当前快照"""
        if not self.current_snapshot:
            return None
        
        if format_type == 'json':
            return self.io_manager.export_snapshot_to_json(self.current_snapshot)
        elif format_type == 'mct':
            return self.io_manager.export_snapshot_to_mct(self.current_snapshot)
        elif format_type == 'keys':
            return self.io_manager.export_keys_to_file(self.current_snapshot)
        
        return None

# ============================================================
# === 可视化报告生成
# ============================================================
class ReportGenerator:
    """报告生成器"""
    
    @staticmethod
    def generate_text_report(snapshot: CardSnapshot) -> str:
        """生成文本报告"""
        lines = [
            "=" * 70,
            "AECardTools 卡片分析报告",
            "=" * 70,
            f"生成时间: {snapshot.timestamp}",
            f"卡片 UID: {snapshot.uid}",
            f"卡片类型: {snapshot.card_type}",
            f"扇区数量: {snapshot.sector_count}",
            "",
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            "扇区统计",
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            ""
        ]
        
        total_blocks = 0
        readable_blocks = 0
        writable_blocks = 0
        
        for sector_idx in range(snapshot.sector_count):
            if sector_idx not in snapshot.sectors:
                lines.append(f"Sector {sector_idx:2d}: [无数据]")
                continue
            
            sector = snapshot.sectors[sector_idx]
            auth_status = sector.auth_status
            
            lines.append(f"Sector {sector_idx:2d}:")
            lines.append(f"  认证状态: {auth_status}")
            lines.append(f"  Key A: {sector.key_a}")
            lines.append(f"  Key B: {sector.key_b}")
            lines.append(f"  访问位: {sector.access_bits}")
            lines.append(f"  块数: {len(sector.blocks)}")
            
            total_blocks += len(sector.blocks)
            readable_blocks += len([b for b in sector.blocks if b.is_readable])
            writable_blocks += len([b for b in sector.blocks if b.is_writable])
            lines.append("")
        
        lines.extend([
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            "统计摘要",
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            f"总块数: {total_blocks}",
            f"可读块数: {readable_blocks}",
            f"可写块数: {writable_blocks}",
            f"读取率: {readable_blocks / total_blocks * 100:.1f}%" if total_blocks > 0 else "N/A",
            f"写入率: {writable_blocks / total_blocks * 100:.1f}%" if total_blocks > 0 else "N/A",
            "=" * 70,
        ])
        
        return "\n".join(lines)
    
    @staticmethod
    def generate_markdown_report(snapshot: CardSnapshot) -> str:
        """生成 Markdown 报告"""
        lines = [
            "# AECardTools 卡片分析报告",
            "",
            f"**生成时间**: {snapshot.timestamp}",
            f"**卡片 UID**: `{snapshot.uid}`",
            f"**卡片类型**: {snapshot.card_type}",
            f"**扇区数量**: {snapshot.sector_count}",
            "",
            "## 扇区详情",
            "",
        ]
        
        for sector_idx in range(snapshot.sector_count):
            if sector_idx not in snapshot.sectors:
                continue
            
            sector = snapshot.sectors[sector_idx]
            
            lines.append(f"### Sector {sector_idx}")
            lines.append("")
            lines.append("| 项目 | 值 |")
            lines.append("|------|-----|")
            lines.append(f"| 认证状态 | {sector.auth_status} |")
            lines.append(f"| Key A | `{sector.key_a}` |")
            lines.append(f"| Key B | `{sector.key_b}` |")
            lines.append(f"| 访问位 | `{sector.access_bits}` |")
            lines.append(f"| 块数 | {len(sector.blocks)} |")
            lines.append("")
            
            # 块数据
            lines.append("#### 块数据")
            lines.append("")
            for block_idx, block in enumerate(sector.blocks):
                if block.is_readable:
                    lines.append(f"**Block {block_idx}**: `{block.data}`")
                else:
                    lines.append(f"**Block {block_idx}**: `[不可读]`")
            lines.append("")
        
        return "\n".join(lines)

# ============================================================
# === 数据恢复与修复
# ============================================================
class DataRecovery:
    """数据恢复与修复"""
    
    @staticmethod
    def recover_from_backup(current: CardSnapshot, backup: CardSnapshot, force_sectors: List[int] = None) -> Dict[str, Any]:
        """从备份恢复数据"""
        recovered_sectors = 0
        recovered_blocks = 0
        
        for sector_idx in range(current.sector_count):
            # 如果指定了扇区列表，仅恢复这些扇区
            if force_sectors and sector_idx not in force_sectors:
                continue
            
            if sector_idx in backup.sectors and sector_idx in current.sectors:
                backup_sector = backup.sectors[sector_idx]
                current_sector = current.sectors[sector_idx]
                
                # 浅层比较，如果备份有数据而当前没有，则恢复
                for block_idx, backup_block in enumerate(backup_sector.blocks):
                    if block_idx < len(current_sector.blocks):
                        current_block = current_sector.blocks[block_idx]
                        
                        if not current_block.is_readable and backup_block.is_readable:
                            current_block.data = backup_block.data
                            recovered_blocks += 1
                
                recovered_sectors += 1
        
        return {
            'recovered_sectors': recovered_sectors,
            'recovered_blocks': recovered_blocks,
            'success': True
        }
    
    @staticmethod
    def repair_corrupted_sectors(snapshot: CardSnapshot) -> Dict[int, Dict]:
        """修复损坏的扇区"""
        repairs = {}
        
        for sector_idx, sector in snapshot.sectors.items():
            issues = []
            
            # 检查密钥
            if sector.key_a == '000000000000':
                issues.append('Key A 为零')
            
            if sector.key_b == '000000000000':
                issues.append('Key B 为零')
            
            # 检查块
            for block_idx, block in enumerate(sector.blocks):
                if not block.is_readable:
                    issues.append(f'Block {block_idx} 不可读')
            
            if issues:
                repairs[sector_idx] = {'issues': issues}
        
        return repairs

# ============================================================
# === 批量数据处理
# ============================================================
class BatchProcessor:
    """批量数据处理"""
    
    @staticmethod
    def batch_export_snapshots(snapshots: List[CardSnapshot], format_type: str) -> Dict[str, str]:
        """批量导出快照"""
        results = {}
        io_mgr = DataIOManager()
        
        for snapshot in snapshots:
            filename = f"{snapshot.uid}_{snapshot.timestamp.replace(':', '-')}"
            
            if format_type == 'json':
                results[f"{filename}.json"] = io_mgr.export_snapshot_to_json(snapshot)
            elif format_type == 'mct':
                results[f"{filename}.mct"] = io_mgr.export_snapshot_to_mct(snapshot)
            elif format_type == 'keys':
                results[f"{filename}.keys"] = io_mgr.export_keys_to_file(snapshot)
        
        return results
    
    @staticmethod
    def merge_snapshots(snapshots: List[CardSnapshot]) -> Dict[str, Any]:
        """合并多个快照"""
        if not snapshots:
            return {'success': False, 'error': 'No snapshots provided'}
        
        merged = {
            'snapshots_count': len(snapshots),
            'uids': [s.uid for s in snapshots],
            'timestamp_range': {
                'earliest': min(s.timestamp for s in snapshots),
                'latest': max(s.timestamp for s in snapshots)
            }
        }
        
        return merged

# ============================================================
# === 增强的全局数据管理器
# ============================================================
class GlobalDataManager:
    """全局数据管理器 - 增强版本"""
    
    def __init__(self):
        self.current_snapshot: Optional[CardSnapshot] = None
        self.snapshot_history: List[CardSnapshot] = []
        self.io_manager = DataIOManager()
        self.diff_calculator = DifferenceCalculator()
        self.wizard = DataWizard()
        self.report_generator = ReportGenerator()
        self.data_recovery = DataRecovery()
        self.batch_processor = BatchProcessor()
    
    def save_snapshot(self, snapshot: CardSnapshot):
        """保存快照"""
        self.current_snapshot = snapshot
        self.snapshot_history.append(snapshot)
        logger.info(f"快照已保存: {snapshot.uid} at {snapshot.timestamp}")
    
    def get_difference_report(self, index1: int, index2: int) -> Optional[Dict]:
        """获取差异报告"""
        if index1 < len(self.snapshot_history) and index2 < len(self.snapshot_history):
            return self.diff_calculator.compare_snapshots(
                self.snapshot_history[index1],
                self.snapshot_history[index2]
            )
        return None
    
    def export_current(self, format_type: str) -> Optional[str]:
        """导出当前快照"""
        if not self.current_snapshot:
            return None
        
        if format_type == 'json':
            return self.io_manager.export_snapshot_to_json(self.current_snapshot)
        elif format_type == 'mct':
            return self.io_manager.export_snapshot_to_mct(self.current_snapshot)
        elif format_type == 'keys':
            return self.io_manager.export_keys_to_file(self.current_snapshot)
        elif format_type == 'text_report':
            return self.report_generator.generate_text_report(self.current_snapshot)
        elif format_type == 'markdown_report':
            return self.report_generator.generate_markdown_report(self.current_snapshot)
        
        return None
    
    def get_snapshot_history_summary(self) -> Dict[str, Any]:
        """获取快照历史摘要"""
        return {
            'total_snapshots': len(self.snapshot_history),
            'uids': list(set(s.uid for s in self.snapshot_history)),
            'timestamps': [s.timestamp for s in self.snapshot_history],
            'card_types': list(set(s.card_type for s in self.snapshot_history))
        }

# ============================================================
# === 全局实例
# ============================================================
_global_data_manager = GlobalDataManager()

def get_global_data_manager() -> GlobalDataManager:
    """获取全局数据管理器"""
    return _global_data_manager
