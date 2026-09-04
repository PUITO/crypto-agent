"""
Agent 可调用的工具：对接 Config / Data / Plugin / Backtest 等微服务。
"""

from __future__ import annotations

from typing import Any, Callable, Awaitable, Optional

import httpx

from common.logging import get_logger

logger = get_logger("agent-service.tools")

ToolFunc = Callable[..., Awaitable[dict[str, Any]]]


class ToolRegistry:
    def __init__(
        self,
        data_url: str = "http://localhost:8001",
        config_url: str = "http://localhost:8002",
        plugin_url: str = "http://localhost:8003",
        backtest_url: str = "http://localhost:8004",
    ):
        self.data_url = data_url.rstrip("/")
        self.config_url = config_url.rstrip("/")
        self.plugin_url = plugin_url.rstrip("/")
        self.backtest_url = backtest_url.rstrip("/")
        self._tools: dict[str, dict[str, Any]] = {}
        self._register_builtins()

    def _register_builtins(self) -> None:
        self.register(
            name="get_config",
            description="获取当前系统配置（交易模式、策略参数、启用插件等）",
            parameters={"type": "object", "properties": {}, "required": []},
            func=self.get_config,
        )
        self.register(
            name="preview_config",
            description="预览配置变更（不生效）。修改模式或策略参数前必须先 preview，把结果展示给用户确认。",
            parameters={
                "type": "object",
                "properties": {
                    "patch": {
                        "type": "object",
                        "description": "要修改的配置片段，例如 {\"mode\": \"event_30m\"} 或 {\"strategy_params\": {\"kdj_rsi_event\": {\"rsi_oversold\": 25}}}",
                    }
                },
                "required": ["patch"],
            },
            func=self.preview_config,
        )
        self.register(
            name="apply_config",
            description="在用户明确确认后，真正应用配置变更。必须先调用 preview_config 并得到用户同意。",
            parameters={
                "type": "object",
                "properties": {
                    "patch": {"type": "object", "description": "与 preview 时相同的配置补丁"},
                    "note": {"type": "string", "description": "变更备注"},
                },
                "required": ["patch"],
            },
            func=self.apply_config,
        )
        self.register(
            name="get_latest_price",
            description="获取交易对最新成交价",
            parameters={
                "type": "object",
                "properties": {
                    "symbol": {"type": "string", "description": "例如 BTCUSDT", "default": "BTCUSDT"},
                },
            },
            func=self.get_latest_price,
        )
        self.register(
            name="list_plugins",
            description="列出当前已加载的策略插件",
            parameters={"type": "object", "properties": {}},
            func=self.list_plugins,
        )
        self.register(
            name="generate_signals",
            description="使用指定策略插件生成最新交易信号",
            parameters={
                "type": "object",
                "properties": {
                    "plugin_name": {"type": "string", "default": "kdj_rsi_event"},
                    "symbol": {"type": "string", "default": "BTCUSDT"},
                    "interval": {"type": "string", "default": "5m"},
                    "limit": {"type": "integer", "default": 200},
                    "params": {"type": "object", "description": "覆盖默认策略参数"},
                },
            },
            func=self.generate_signals,
        )
        self.register(
            name="run_backtest",
            description="对指定策略插件做历史回测，返回胜率、收益、最大回撤等",
            parameters={
                "type": "object",
                "properties": {
                    "plugin_name": {"type": "string", "default": "kdj_rsi_event"},
                    "symbol": {"type": "string", "default": "BTCUSDT"},
                    "interval": {"type": "string", "default": "5m"},
                    "mode": {"type": "string", "default": "event_30m"},
                    "days": {"type": "integer", "default": 14},
                    "params": {"type": "object"},
                    "hold_bars": {"type": "integer", "default": 6},
                },
            },
            func=self.run_backtest,
        )
        self.register(
            name="switch_mode",
            description="切换交易模式：perpetual / event_30m / both。会走预览，需用户确认后再 apply。",
            parameters={
                "type": "object",
                "properties": {
                    "mode": {
                        "type": "string",
                        "enum": ["perpetual", "event_30m", "both"],
                    }
                },
                "required": ["mode"],
            },
            func=self.switch_mode,
        )

    def register(self, name: str, description: str, parameters: dict, func: ToolFunc) -> None:
        self._tools[name] = {
            "name": name,
            "description": description,
            "parameters": parameters,
            "func": func,
        }

    def schemas(self) -> list[dict[str, Any]]:
        return [
            {"name": t["name"], "description": t["description"], "parameters": t["parameters"]}
            for t in self._tools.values()
        ]

    async def execute(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        if name not in self._tools:
            return {"ok": False, "error": f"unknown tool: {name}"}
        args = arguments or {}
        try:
            result = await self._tools[name]["func"](**args)
            return {"ok": True, "tool": name, "result": result}
        except Exception as e:
            logger.exception(f"Tool {name} failed")
            return {"ok": False, "tool": name, "error": str(e)}

    # ----- tool implementations -----

    async def get_config(self) -> dict:
        async with httpx.AsyncClient(timeout=15) as c:
            r = await c.get(f"{self.config_url}/api/v1/config")
            r.raise_for_status()
            return r.json()

    async def preview_config(self, patch: dict) -> dict:
        async with httpx.AsyncClient(timeout=15) as c:
            r = await c.post(f"{self.config_url}/api/v1/config/preview", json={"patch": patch})
            r.raise_for_status()
            return r.json()

    async def apply_config(self, patch: dict, note: str = "agent") -> dict:
        async with httpx.AsyncClient(timeout=15) as c:
            r = await c.post(
                f"{self.config_url}/api/v1/config/apply",
                json={"patch": patch, "confirm": True, "source": "agent", "note": note},
            )
            r.raise_for_status()
            return r.json()

    async def get_latest_price(self, symbol: str = "BTCUSDT") -> dict:
        async with httpx.AsyncClient(timeout=15) as c:
            r = await c.get(f"{self.data_url}/api/v1/latest_price", params={"symbol": symbol})
            r.raise_for_status()
            return r.json()

    async def list_plugins(self) -> dict:
        async with httpx.AsyncClient(timeout=15) as c:
            r = await c.get(f"{self.plugin_url}/api/v1/plugins")
            r.raise_for_status()
            return r.json()

    async def generate_signals(
        self,
        plugin_name: str = "kdj_rsi_event",
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        limit: int = 200,
        params: Optional[dict] = None,
    ) -> dict:
        async with httpx.AsyncClient(timeout=60) as c:
            r = await c.post(
                f"{self.plugin_url}/api/v1/plugins/{plugin_name}/signals",
                json={
                    "symbol": symbol,
                    "interval": interval,
                    "limit": limit,
                    "params": params or {},
                },
            )
            r.raise_for_status()
            return r.json()

    async def run_backtest(
        self,
        plugin_name: str = "kdj_rsi_event",
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        mode: str = "event_30m",
        days: int = 14,
        params: Optional[dict] = None,
        hold_bars: int = 6,
    ) -> dict:
        async with httpx.AsyncClient(timeout=120) as c:
            r = await c.post(
                f"{self.backtest_url}/api/v1/backtest",
                json={
                    "plugin_name": plugin_name,
                    "symbol": symbol,
                    "interval": interval,
                    "mode": mode,
                    "days": days,
                    "params": params or {},
                    "hold_bars": hold_bars,
                },
            )
            r.raise_for_status()
            data = r.json()
            # 精简返回，避免把全部信号塞进对话
            return {
                "plugin_name": data.get("plugin_name"),
                "symbol": data.get("symbol"),
                "mode": data.get("mode"),
                "total_signals": data.get("total_signals"),
                "win_rate": data.get("win_rate"),
                "total_return": data.get("total_return"),
                "max_drawdown": data.get("max_drawdown"),
                "profit_factor": data.get("profit_factor"),
                "avg_return": data.get("avg_return"),
                "data_start": data.get("data_start"),
                "data_end": data.get("data_end"),
                "params": data.get("params"),
            }

    async def switch_mode(self, mode: str) -> dict:
        """只做预览，真正切换需 apply_config"""
        return await self.preview_config({"mode": mode})
