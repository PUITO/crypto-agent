"""
配置存储：内存 + JSON 文件持久化 + 审计日志。
"""

from __future__ import annotations

import json
import threading
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from uuid import uuid4

from common.logging import get_logger

logger = get_logger("config-service.store")

# 默认完整配置结构
DEFAULT_CONFIG: dict[str, Any] = {
    # 交易模式
    "mode": "event_30m",                    # perpetual | event_30m | both
    "symbol": "BTCUSDT",
    "interval": "5m",
    # 插件
    "enabled_plugins": ["kdj_rsi_event"],
    "active_plugin": "kdj_rsi_event",
    # 策略参数（可被 Chat / 优化覆盖）
    "strategy_params": {
        "kdj_rsi_event": {
            "rsi_period": 14,
            "rsi_oversold": 30,
            "rsi_overbought": 70,
            "kdj_n": 9,
            "kdj_m1": 3,
            "kdj_m2": 3,
            "min_confidence": 0.55,
        }
    },
    # 回测 / 模拟
    "hold_bars": 6,
    "fee_rate": 0.0,
    # 风险
    "max_position_pct": 0.1,
    "max_drawdown_stop": 0.15,
    "auto_close": True,
    # Agent 行为
    "require_confirm_on_config_change": True,
    "auto_optimize_on_start": False,
    "optimize_metric": "win_rate",
}


class ConfigStore:
    def __init__(self, persist_path: str | Path = "./data/config/runtime_config.json"):
        self.persist_path = Path(persist_path)
        self.persist_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._config: dict[str, Any] = deepcopy(DEFAULT_CONFIG)
        self._audit: list[dict[str, Any]] = []
        self._presets: dict[str, dict[str, Any]] = {}
        self._load()

    def _load(self) -> None:
        if self.persist_path.exists():
            try:
                data = json.loads(self.persist_path.read_text(encoding="utf-8"))
                cfg = data.get("config", {})
                # 合并默认，避免缺字段
                merged = deepcopy(DEFAULT_CONFIG)
                self._deep_update(merged, cfg)
                self._config = merged
                self._presets = data.get("presets", {})
                self._audit = data.get("audit", [])[-200:]  # 只保留最近 200 条
                logger.info(f"Config loaded from {self.persist_path}")
            except Exception as e:
                logger.warning(f"Failed to load config, using defaults: {e}")

    def _save(self) -> None:
        try:
            payload = {
                "config": self._config,
                "presets": self._presets,
                "audit": self._audit[-200:],
                "updated_at": datetime.now(timezone.utc).isoformat(),
            }
            self.persist_path.write_text(
                json.dumps(payload, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
        except Exception as e:
            logger.error(f"Failed to persist config: {e}")

    @staticmethod
    def _deep_update(base: dict, patch: dict) -> dict:
        for k, v in patch.items():
            if isinstance(v, dict) and isinstance(base.get(k), dict):
                ConfigStore._deep_update(base[k], v)
            else:
                base[k] = v
        return base

    def get_all(self) -> dict[str, Any]:
        with self._lock:
            return deepcopy(self._config)

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            # 支持点号路径，如 strategy_params.kdj_rsi_event.rsi_oversold
            parts = key.split(".")
            cur: Any = self._config
            for p in parts:
                if not isinstance(cur, dict) or p not in cur:
                    return default
                cur = cur[p]
            return deepcopy(cur)

    def preview(self, patch: dict[str, Any]) -> dict[str, Any]:
        """返回变更预览（不落库）"""
        with self._lock:
            old = deepcopy(self._config)
            new = deepcopy(self._config)
            self._deep_update(new, patch)
            changes = []
            self._diff(old, new, "", changes)
            return {
                "preview": True,
                "changes": changes,
                "old_config": old,
                "new_config": new,
            }

    def apply(
        self,
        patch: dict[str, Any],
        source: str = "api",
        note: str = "",
    ) -> dict[str, Any]:
        """真正应用变更并写审计"""
        with self._lock:
            old = deepcopy(self._config)
            self._deep_update(self._config, patch)
            changes = []
            self._diff(old, self._config, "", changes)
            entry = {
                "id": str(uuid4()),
                "ts": datetime.now(timezone.utc).isoformat(),
                "source": source,
                "note": note,
                "changes": changes,
            }
            self._audit.append(entry)
            self._save()
            logger.info(f"Config applied from {source}: {len(changes)} changes")
            return {
                "applied": True,
                "changes": changes,
                "config": deepcopy(self._config),
                "audit_id": entry["id"],
            }

    def set_key(self, key: str, value: Any, source: str = "api", note: str = "") -> dict[str, Any]:
        """按点号路径设置单个键"""
        parts = key.split(".")
        patch: dict[str, Any] = {}
        cur = patch
        for p in parts[:-1]:
            cur[p] = {}
            cur = cur[p]
        cur[parts[-1]] = value
        return self.apply(patch, source=source, note=note)

    def audit_log(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            return deepcopy(self._audit[-limit:])

    def save_preset(self, name: str, config: Optional[dict] = None) -> dict:
        with self._lock:
            self._presets[name] = deepcopy(config or self._config)
            self._save()
            return {"ok": True, "name": name}

    def list_presets(self) -> list[str]:
        with self._lock:
            return list(self._presets.keys())

    def load_preset(self, name: str, source: str = "preset") -> dict[str, Any]:
        with self._lock:
            if name not in self._presets:
                raise KeyError(f"preset '{name}' not found")
            return self.apply(self._presets[name], source=source, note=f"load preset {name}")

    @staticmethod
    def _diff(old: Any, new: Any, path: str, out: list) -> None:
        if type(old) != type(new) or not isinstance(old, dict):
            if old != new:
                out.append({"path": path or ".", "old": old, "new": new})
            return
        keys = set(old.keys()) | set(new.keys())
        for k in sorted(keys):
            p = f"{path}.{k}" if path else k
            if k not in old:
                out.append({"path": p, "old": None, "new": new[k]})
            elif k not in new:
                out.append({"path": p, "old": old[k], "new": None})
            else:
                ConfigStore._diff(old[k], new[k], p, out)
