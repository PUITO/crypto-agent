"""
各微服务从 Config Service 拉取统一配置。
Config 不可达时回退到本地默认 / 环境变量。
"""

from __future__ import annotations

from typing import Any, Optional

import httpx

from .logging import get_logger

logger = get_logger("common.remote_config")


class RemoteConfigClient:
    def __init__(self, config_url: str = "http://localhost:8002", timeout: float = 5.0):
        self.config_url = config_url.rstrip("/")
        self.timeout = timeout
        self._cache: Optional[dict[str, Any]] = None

    def fetch(self, raw: bool = True, force: bool = False) -> dict[str, Any]:
        if self._cache is not None and not force:
            return self._cache
        try:
            with httpx.Client(timeout=self.timeout) as client:
                r = client.get(f"{self.config_url}/api/v1/config", params={"raw": str(raw).lower()})
                r.raise_for_status()
                self._cache = r.json().get("config") or {}
                return self._cache
        except Exception as e:
            logger.warning(f"Remote config unavailable, using empty/fallback: {e}")
            return self._cache or {}

    def get(self, key: str, default: Any = None) -> Any:
        cfg = self.fetch()
        parts = key.split(".")
        cur: Any = cfg
        for p in parts:
            if not isinstance(cur, dict) or p not in cur:
                return default
            cur = cur[p]
        return cur

    def section(self, name: str) -> dict[str, Any]:
        cfg = self.fetch()
        val = cfg.get(name)
        return val if isinstance(val, dict) else {}

    def invalidate(self) -> None:
        self._cache = None
