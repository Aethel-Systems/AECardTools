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
Argon2 兼容层：
- 优先使用 argon2-cffi 的 low_level API
- 不可用时降级到 PBKDF2-HMAC-SHA256，避免模块初始化失败
"""

import hashlib
import logging

logger = logging.getLogger("AECardTools.KDFCompat")

try:
    from argon2.low_level import hash_secret_raw as _hash_secret_raw, Type as _ArgType  # type: ignore
    hash_secret_raw = _hash_secret_raw
    ArgType = _ArgType
except Exception as exc:
    logger.warning(f"argon2 不可用，降级 PBKDF2: {exc}")

    class _FallbackArgType:
        ID = 2

    ArgType = _FallbackArgType

    def hash_secret_raw(
        secret: bytes,
        salt: bytes,
        time_cost: int,
        memory_cost: int,
        parallelism: int,
        hash_len: int,
        type: int
    ) -> bytes:
        rounds = max(100_000, time_cost * 50_000)
        return hashlib.pbkdf2_hmac("sha256", secret, salt, rounds, dklen=hash_len)

