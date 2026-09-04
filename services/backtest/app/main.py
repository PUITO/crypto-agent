"""
Backtest & Simulator Service —— 回测与模拟交易。
可独立启动；依赖 Plugin Service 获取策略，依赖 Data Service 获取 K 线。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx
import pandas as pd
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler
from common.strategy import StrategyPlugin

from core.engine import BacktestEngine


class Settings(BaseServiceSettings):
    service_name: str = "backtest-service"
    port: int = 8004
    default_hold_bars: int = 6          # 5m * 6 = 30min 事件合约
    default_fee_rate: float = 0.0004


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


class BacktestRequest(BaseModel):
    plugin_name: str = "kdj_rsi_event"
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    mode: str = "event_30m"
    days: int = Field(14, ge=1, le=90)
    params: dict[str, Any] = Field(default_factory=dict)
    hold_bars: Optional[int] = None
    fee_rate: Optional[float] = None
    # 也可直接传 K 线，跳过 Data Service
    klines: Optional[list[dict]] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


async def _fetch_klines(symbol: str, interval: str, limit: int = 2000) -> pd.DataFrame:
    url = settings.data_service_url.rstrip("/") + "/api/v1/klines"
    async with httpx.AsyncClient(timeout=60) as client:
        r = await client.get(url, params={
            "symbol": symbol,
            "interval": interval,
            "limit": limit,
            "source": "local",
        })
        if r.status_code != 200:
            # 尝试 binance 直拉
            r = await client.get(url, params={
                "symbol": symbol,
                "interval": interval,
                "limit": min(limit, 1000),
                "source": "binance",
            })
        r.raise_for_status()
        data = r.json().get("data", [])
    if not data:
        return pd.DataFrame()
    df = pd.DataFrame(data)
    df["open_time"] = pd.to_datetime(df["open_time"], utc=True)
    for col in ("open", "high", "low", "close", "volume"):
        if col in df.columns:
            df[col] = df[col].astype(float)
    return df


async def _load_plugin(name: str) -> StrategyPlugin:
    """从 Plugin Service 获取插件信息，并在本地实例化（当前内置策略直接 import）"""
    # 优先本地内置
    try:
        plugin_path = ROOT / "services" / "plugin" / "plugins" / f"{name}.py"
        if plugin_path.exists():
            import importlib.util
            spec = importlib.util.spec_from_file_location(f"plugin_{name}", plugin_path)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            if hasattr(mod, "create_plugin"):
                return mod.create_plugin()
    except Exception as e:
        logger.warning(f"Local load failed for {name}: {e}")

    # 回退：请求 Plugin Service 确认存在
    url = settings.plugin_service_url.rstrip("/") + f"/api/v1/plugins/{name}"
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.get(url)
        if r.status_code != 200:
            raise HTTPException(404, f"Plugin '{name}' not found in Plugin Service")
    raise HTTPException(500, f"Plugin '{name}' registered but cannot be instantiated locally")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Backtest & Simulator Service",
        description="历史回测与模拟交易引擎",
        version="0.2.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_exception_handler(AppException, app_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)
    app.include_router(health_router)

    @app.get("/")
    async def root():
        return {
            "service": settings.service_name,
            "version": "0.2.0",
            "docs": "/docs",
            "endpoints": ["POST /api/v1/backtest", "GET /health"],
        }

    @app.post("/api/v1/backtest")
    async def run_backtest(req: BacktestRequest):
        """
        执行回测。
        流程：加载插件 → 获取 K 线 → 生成信号 → 按 hold_bars 结算 → 返回胜率等指标。
        """
        plugin = await _load_plugin(req.plugin_name)

        if req.klines:
            df = pd.DataFrame(req.klines)
            df["open_time"] = pd.to_datetime(df["open_time"], utc=True)
            for col in ("open", "high", "low", "close", "volume"):
                if col in df.columns:
                    df[col] = df[col].astype(float)
        else:
            # 大约 days * 24 * 12 根 5m K 线
            limit = min(req.days * 24 * 12 + 50, 5000)
            df = await _fetch_klines(req.symbol, req.interval, limit=limit)
            if df.empty:
                raise HTTPException(
                    400,
                    "No kline data. Please run Data Service fetch first: POST /api/v1/fetch",
                )

        hold_bars = req.hold_bars or settings.default_hold_bars
        fee_rate = req.fee_rate if req.fee_rate is not None else settings.default_fee_rate
        if req.mode.startswith("event"):
            fee_rate = 0.0  # 事件合约通常无传统手续费模型

        engine = BacktestEngine(hold_bars=hold_bars, fee_rate=fee_rate)
        result = engine.run(
            plugin=plugin,
            df=df,
            params=req.params,
            mode=req.mode,
            symbol=req.symbol,
        )

        out = result.to_dict()
        out["data_points"] = len(df)
        out["data_start"] = df["open_time"].iloc[0].isoformat() if len(df) else None
        out["data_end"] = df["open_time"].iloc[-1].isoformat() if len(df) else None
        return out

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
