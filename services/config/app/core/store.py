"""
配置存储：内存 + JSON 文件持久化 + 审计日志。
统一管理各微服务运行时配置，前端分栏编辑。
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

DEFAULT_CONFIG: dict[str, Any] = {
    "trading": {
        "mode": "event_30m",
        "symbol": "BTCUSDT",
        "interval": "5m",
        "hold_bars": 6,
        "fee_rate": 0.0,
        "auto_close": True,
    },
    "plugins": {
        "enabled_plugins": ["kdj_rsi_event"],
        "active_plugin": "kdj_rsi_event",
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
    },
    "risk": {
        "max_position_pct": 0.1,
        "max_drawdown_stop": 0.15,
    },
    "data": {
        "binance_base_url": "https://data-api.binance.vision",
        "symbols": "BTCUSDT",
        "kline_interval": "5m",
        "enable_scheduler": True,
        "fetch_every_seconds": 300,
        "history_days_on_first_run": 14,
        "hf_repo_id": "",
        "hf_token": "",
        "push_hf_on_build": False,
    },
    "agent": {
        "llm_provider": "ollama",
        "llm_model": "qwen2.5:14b",
        "ollama_base_url": "http://localhost:11434",
        "openai_base_url": "https://api.openai.com/v1",
        "openai_api_key": "",
        "temperature": 0.2,
        "require_confirm_on_config_change": True,
        "max_tool_rounds": 6,
    },
    "backtest": {
        "default_days": 14,
        "default_mode": "event_30m",
        "min_signals": 5,
    },
    "optimize": {
        "metric": "win_rate",
        "auto_optimize_on_start": False,
        "apply_best_by_default": False,
        "max_concurrency": 4,
    },
    "services": {
        "data_service_url": "http://localhost:8001",
        "config_service_url": "http://localhost:8002",
        "plugin_service_url": "http://localhost:8003",
        "backtest_service_url": "http://localhost:8004",
        "chart_service_url": "http://localhost:8005",
        "agent_service_url": "http://localhost:8006",
        "multi_agent_service_url": "http://localhost:8007",
        "ops_service_url": "http://localhost:8008",
        "log_service_url": "http://localhost:8009",
        "sync_service_url": "http://localhost:8010",
        "notify_service_url": "http://localhost:8011",
        "gateway_url": "http://localhost:8000",
    },
    # legacy flat
    "mode": "event_30m",
    "symbol": "BTCUSDT",
    "interval": "5m",
    "enabled_plugins": ["kdj_rsi_event"],
    "active_plugin": "kdj_rsi_event",
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
    "hold_bars": 6,
    "fee_rate": 0.0,
    "max_position_pct": 0.1,
    "max_drawdown_stop": 0.15,
    "auto_close": True,
    "require_confirm_on_config_change": True,
    "auto_optimize_on_start": False,
    "optimize_metric": "win_rate",
}

CONFIG_SECTIONS: list[dict[str, Any]] = [
    {
        "id": "trading",
        "title": "交易",
        "description": "交易模式、标的、持仓周期",
        "fields": [
            {"key": "trading.mode", "label": "交易模式", "type": "select", "options": ["event_30m", "perpetual", "both"]},
            {"key": "trading.symbol", "label": "交易对", "type": "string"},
            {"key": "trading.interval", "label": "K线周期", "type": "select", "options": ["1m", "5m", "15m", "1h", "4h"]},
            {"key": "trading.hold_bars", "label": "事件持有K线数", "type": "number", "min": 1, "max": 48},
            {"key": "trading.fee_rate", "label": "手续费率", "type": "number"},
            {"key": "trading.auto_close", "label": "自动平仓", "type": "boolean"},
        ],
    },
    {
        "id": "plugins",
        "title": "策略插件",
        "description": "启用插件与策略参数",
        "fields": [
            {"key": "plugins.active_plugin", "label": "当前插件", "type": "string"},
            {"key": "plugins.enabled_plugins", "label": "启用列表(逗号分隔)", "type": "string_list"},
            {"key": "plugins.strategy_params.kdj_rsi_event.rsi_period", "label": "RSI周期", "type": "number"},
            {"key": "plugins.strategy_params.kdj_rsi_event.rsi_oversold", "label": "RSI超卖", "type": "number"},
            {"key": "plugins.strategy_params.kdj_rsi_event.rsi_overbought", "label": "RSI超买", "type": "number"},
            {"key": "plugins.strategy_params.kdj_rsi_event.min_confidence", "label": "最小置信度", "type": "number"},
        ],
    },
    {
        "id": "risk",
        "title": "风险",
        "description": "仓位与回撤控制",
        "fields": [
            {"key": "risk.max_position_pct", "label": "最大仓位比例", "type": "number"},
            {"key": "risk.max_drawdown_stop", "label": "最大回撤熔断", "type": "number"},
        ],
    },
    {
        "id": "data",
        "title": "数据服务",
        "description": "Binance 采集与 Hugging Face",
        "fields": [
            {"key": "data.binance_base_url", "label": "Binance Base URL", "type": "string"},
            {"key": "data.symbols", "label": "采集交易对", "type": "string"},
            {"key": "data.kline_interval", "label": "采集周期", "type": "string"},
            {"key": "data.enable_scheduler", "label": "启用定时采集", "type": "boolean"},
            {"key": "data.fetch_every_seconds", "label": "采集间隔(秒)", "type": "number"},
            {"key": "data.history_days_on_first_run", "label": "首次回填天数", "type": "number"},
            {"key": "data.hf_repo_id", "label": "HF 仓库 ID", "type": "string"},
            {"key": "data.hf_token", "label": "HF Token", "type": "password"},
            {"key": "data.push_hf_on_build", "label": "构建时推送 HF", "type": "boolean"},
        ],
    },
    {
        "id": "agent",
        "title": "Agent / LLM",
        "description": "大模型与对话行为",
        "fields": [
            {"key": "agent.llm_provider", "label": "LLM 提供商", "type": "select", "options": ["ollama", "openai"]},
            {"key": "agent.llm_model", "label": "模型名", "type": "string"},
            {"key": "agent.ollama_base_url", "label": "Ollama URL", "type": "string"},
            {"key": "agent.openai_base_url", "label": "OpenAI 兼容 URL", "type": "string"},
            {"key": "agent.openai_api_key", "label": "API Key", "type": "password"},
            {"key": "agent.temperature", "label": "Temperature", "type": "number"},
            {"key": "agent.require_confirm_on_config_change", "label": "配置变更需确认", "type": "boolean"},
            {"key": "agent.max_tool_rounds", "label": "最大工具轮次", "type": "number"},
        ],
    },
    {
        "id": "backtest",
        "title": "回测与优化",
        "description": "默认回测与矩阵优化参数",
        "fields": [
            {"key": "backtest.default_days", "label": "默认回测天数", "type": "number"},
            {"key": "backtest.default_mode", "label": "默认回测模式", "type": "select", "options": ["event_30m", "perpetual", "both"]},
            {"key": "backtest.min_signals", "label": "最少信号数过滤", "type": "number"},
            {"key": "optimize.metric", "label": "优化指标", "type": "select", "options": ["win_rate", "total_return", "profit_factor"]},
            {"key": "optimize.auto_optimize_on_start", "label": "启动时自动优化", "type": "boolean"},
            {"key": "optimize.apply_best_by_default", "label": "默认应用最优参数", "type": "boolean"},
            {"key": "optimize.max_concurrency", "label": "优化并发数", "type": "number"},
        ],
    },
    {
        "id": "services",
        "title": "服务发现",
        "description": "各微服务 URL",
        "fields": [
            {"key": "services.gateway_url", "label": "Gateway", "type": "string"},
            {"key": "services.data_service_url", "label": "Data", "type": "string"},
            {"key": "services.config_service_url", "label": "Config", "type": "string"},
            {"key": "services.plugin_service_url", "label": "Plugin", "type": "string"},
            {"key": "services.backtest_service_url", "label": "Backtest", "type": "string"},
            {"key": "services.chart_service_url", "label": "Chart", "type": "string"},
            {"key": "services.agent_service_url", "label": "Agent", "type": "string"},
            {"key": "services.multi_agent_service_url", "label": "Multi-Agent", "type": "string"},
            {"key": "services.ops_service_url", "label": "Ops", "type": "string"},
            {"key": "services.log_service_url", "label": "Log", "type": "string"},
            {"key": "services.sync_service_url", "label": "Sync", "type": "string"},
            {"key": "services.notify_service_url", "label": "Notify", "type": "string"},
        ],
    },
]


def sync_legacy_flat(cfg: dict[str, Any]) -> dict[str, Any]:
    t = cfg.setdefault("trading", {})
    p = cfg.setdefault("plugins", {})
    r = cfg.setdefault("risk", {})
    a = cfg.setdefault("agent", {})
    o = cfg.setdefault("optimize", {})
    if "mode" in t: cfg["mode"] = t["mode"]
    if "symbol" in t: cfg["symbol"] = t["symbol"]
    if "interval" in t: cfg["interval"] = t["interval"]
    if "hold_bars" in t: cfg["hold_bars"] = t["hold_bars"]
    if "fee_rate" in t: cfg["fee_rate"] = t["fee_rate"]
    if "auto_close" in t: cfg["auto_close"] = t["auto_close"]
    if "enabled_plugins" in p: cfg["enabled_plugins"] = p["enabled_plugins"]
    if "active_plugin" in p: cfg["active_plugin"] = p["active_plugin"]
    if "strategy_params" in p: cfg["strategy_params"] = p["strategy_params"]
    if "max_position_pct" in r: cfg["max_position_pct"] = r["max_position_pct"]
    if "max_drawdown_stop" in r: cfg["max_drawdown_stop"] = r["max_drawdown_stop"]
    if "require_confirm_on_config_change" in a:
        cfg["require_confirm_on_config_change"] = a["require_confirm_on_config_change"]
    if "auto_optimize_on_start" in o: cfg["auto_optimize_on_start"] = o["auto_optimize_on_start"]
    if "metric" in o: cfg["optimize_metric"] = o["metric"]
    if "mode" in cfg: t["mode"] = cfg["mode"]
    if "symbol" in cfg: t["symbol"] = cfg["symbol"]
    if "interval" in cfg: t["interval"] = cfg["interval"]
    if "hold_bars" in cfg: t["hold_bars"] = cfg["hold_bars"]
    if "enabled_plugins" in cfg: p["enabled_plugins"] = cfg["enabled_plugins"]
    if "active_plugin" in cfg: p["active_plugin"] = cfg["active_plugin"]
    if "strategy_params" in cfg: p["strategy_params"] = cfg["strategy_params"]
    if "require_confirm_on_config_change" in cfg:
        a["require_confirm_on_config_change"] = cfg["require_confirm_on_config_change"]
    if "optimize_metric" in cfg: o["metric"] = cfg["optimize_metric"]
    return cfg



# 变更路径 → 需要重启的服务（前缀匹配）
RESTART_PREFIX_RULES: list[tuple[str, list[str]]] = [
    ("data.", ["data-service"]),
    ("agent.llm_", ["agent-service"]),
    ("agent.ollama_", ["agent-service"]),
    ("agent.openai_", ["agent-service"]),
    ("agent.temperature", ["agent-service"]),
    ("agent.max_tool_rounds", ["agent-service"]),
    ("services.", ["gateway", "agent-service", "data-service", "plugin-service", "backtest-service", "multi-agent-service", "chart-service"]),
    ("optimize.max_concurrency", ["multi-agent-service"]),
]

# 可热更新、无需重启的路径前缀
HOT_RELOAD_PREFIXES: list[str] = [
    "trading.",
    "plugins.",
    "risk.",
    "backtest.",
    "optimize.metric",
    "optimize.auto_optimize_on_start",
    "optimize.apply_best_by_default",
    "agent.require_confirm_on_config_change",
    "mode",
    "symbol",
    "interval",
    "hold_bars",
    "enabled_plugins",
    "active_plugin",
    "strategy_params",
    "require_confirm_on_config_change",
    "optimize_metric",
    "fee_rate",
    "auto_close",
    "max_position_pct",
    "max_drawdown_stop",
]


def classify_changes(changes: list[dict]) -> dict:
    """根据变更路径判断是否需要重启哪些服务。"""
    restart: dict[str, list[str]] = {}  # service -> reasons
    hot: list[str] = []
    for ch in changes:
        path = str(ch.get("path") or "")
        is_hot = any(path == p or path.startswith(p) for p in HOT_RELOAD_PREFIXES)
        matched_restart = False
        for prefix, services in RESTART_PREFIX_RULES:
            if path == prefix.rstrip(".") or path.startswith(prefix):
                matched_restart = True
                for svc in services:
                    restart.setdefault(svc, []).append(path)
        if is_hot and not matched_restart:
            hot.append(path)
        elif not matched_restart and path:
            # 未知路径：保守要求相关服务注意，默认算热更新（交易类）
            hot.append(path)
    return {
        "restart_required": [
            {"service": svc, "paths": sorted(set(paths)), "message": f"修改了 {', '.join(sorted(set(paths))[:5])}，需重启后生效"}
            for svc, paths in sorted(restart.items())
        ],
        "hot_reload_paths": sorted(set(hot)),
        "needs_restart": bool(restart),
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
                merged = deepcopy(DEFAULT_CONFIG)
                self._deep_update(merged, cfg)
                self._config = sync_legacy_flat(merged)
                self._presets = data.get("presets", {})
                self._audit = data.get("audit", [])[-200:]
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
            self.persist_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
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

    def get_section(self, section: str) -> Any:
        with self._lock:
            return deepcopy(self._config.get(section))

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            parts = key.split(".")
            cur: Any = self._config
            for p in parts:
                if not isinstance(cur, dict) or p not in cur:
                    return default
                cur = cur[p]
            return deepcopy(cur)

    def preview(self, patch: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            old = deepcopy(self._config)
            new = deepcopy(self._config)
            self._deep_update(new, patch)
            new = sync_legacy_flat(new)
            changes = []
            self._diff(old, new, "", changes)
            return {"preview": True, "changes": changes, "old_config": old, "new_config": new}

    def apply(self, patch: dict[str, Any], source: str = "api", note: str = "") -> dict[str, Any]:
        with self._lock:
            old = deepcopy(self._config)
            self._deep_update(self._config, patch)
            self._config = sync_legacy_flat(self._config)
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
            impact = classify_changes(changes)
            return {
                "applied": True,
                "changes": changes,
                "config": deepcopy(self._config),
                "audit_id": entry["id"],
                "impact": impact,
                "needs_restart": impact["needs_restart"],
                "restart_required": impact["restart_required"],
            }

    def set_key(self, key: str, value: Any, source: str = "api", note: str = "") -> dict[str, Any]:
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

    def public_view(self) -> dict[str, Any]:
        cfg = self.get_all()
        data = dict(cfg.get("data") or {})
        agent = dict(cfg.get("agent") or {})
        if data.get("hf_token"):
            data["hf_token"] = "***"
            cfg["data"] = data
        if agent.get("openai_api_key"):
            agent["openai_api_key"] = "***"
            cfg["agent"] = agent
        return cfg

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
