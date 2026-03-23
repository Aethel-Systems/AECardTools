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
xxhash 兼容层：
- 优先使用 xxhash（工业级高性能）
- 若环境缺失，则降级到 hashlib，保证功能可用
"""

import hashlib

try:
    import xxhash as _xxhash  # type: ignore
except Exception:
    class _FallbackHash:
        def __init__(self, data: bytes):
            self._data = data

        def digest(self) -> bytes:
            # 兼容 xxh64().digest() 的 8 字节输出长度
            return hashlib.sha256(self._data).digest()[:8]

    class _FallbackXXHash:
        @staticmethod
        def xxh3_64_digest(data: bytes) -> bytes:
            return hashlib.sha256(data).digest()[:8]

        @staticmethod
        def xxh64(data: bytes) -> _FallbackHash:
            return _FallbackHash(data)

    _xxhash = _FallbackXXHash()

xxhash = _xxhash

