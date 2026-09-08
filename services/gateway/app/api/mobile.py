"""Android App 适配 API：统一给 crypto-app / 其它客户端使用的精简接口。"""
from __future__ import annotations

import os
from typing import Any, Optional

import httpx
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/v1/mobile", tags=["mobile-android"])

DATA_URL = os.getenv("DATA_SERVICE_URL", "http://127.0.0.1:8001")
PLUGIN_URL = os.getenv("PLUGIN_SERVICE_URL", "http://127.0.0.1:8003")
BACKTEST_URL = os.getenv("BACKTEST_SERVICE_URL", "http://127.0.0.1:8004")
TIMEOUT = float(os.getenv("MOBILE_PROXY_TIMEOUT", "30"))


class MobileKline(BaseModel):
    open_time: int
    open: float
    high: float
    low: float
    close: float
    volume: float
    close_time: Optional[int] = None


class MobileRule(BaseModel):
    indicator: str = Field(..., description="RSI|MACD|KDJ_J|CLOSE")
    op: str = Field(..., description="GT|GTE|LT|LTE")
    value: float


class MobileStrategyConfig(BaseModel):
    id: Optional[str] = None
    title: str = "mobile-strategy"
    buy_rules: list[MobileRule] = Field(default_factory=list)
    sell_rules: list[MobileRule] = Field(default_factory=list)


class MobileSignalRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "10m"
    limit: int = 1000
    config: MobileStrategyConfig


class MobileBacktestRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "10m"
    limit: int = 1000
    config: MobileStrategyConfig
    hold_bars: int = 1


async def _get(url: str, params: dict | None = None) -> Any:
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        r = await client.get(url, params=params)
        if r.status_code >= 400:
            raise HTTPException(r.status_code, r.text)
        return r.json()


async def _post(url: str, payload: dict) -> Any:
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        r = await client.post(url, json=payload)
        if r.status_code >= 400:
            raise HTTPException(r.status_code, r.text)
        return r.json()


@router.get("/health")
async def mobile_health():
    return {
        "ok": True,
        "client": "android",
        "data": DATA_URL,
        "plugin": PLUGIN_URL,
        "backtest": BACKTEST_URL,
    }


@router.get("/klines")
async def mobile_klines(
    symbol: str = Query("BTCUSDT"),
    interval: str = Query("10m", description="5m|10m|30m|1h"),
    limit: int = Query(1000, ge=1, le=1500),
):
    """返回与 Android Candle 模型对齐的 K 线列表。"""
    # 优先走 data service；失败则明确错误
    try:
        raw = await _get(
            f"{DATA_URL}/api/v1/klines",
            {"symbol": symbol, "interval": interval, "limit": limit},
        )
    except Exception:
        # 兼容不同 data API 命名
        try:
            raw = await _get(
                f"{DATA_URL}/api/v1/candles",
                {"symbol": symbol, "interval": interval, "limit": limit},
            )
        except Exception as e:
            raise HTTPException(502, f"data service unavailable: {e}") from e

    rows = raw if isinstance(raw, list) else raw.get("data") or raw.get("klines") or raw.get("items") or []
    out: list[dict] = []
    for row in rows:
        if isinstance(row, dict):
            out.append(
                {
                    "open_time": int(row.get("open_time") or row.get("openTime") or row.get("t") or 0),
                    "open": float(row.get("open") or row.get("o")),
                    "high": float(row.get("high") or row.get("h")),
                    "low": float(row.get("low") or row.get("l")),
                    "close": float(row.get("close") or row.get("c")),
                    "volume": float(row.get("volume") or row.get("v") or 0),
                    "close_time": int(row.get("close_time") or row.get("closeTime") or 0) or None,
                }
            )
        elif isinstance(row, (list, tuple)) and len(row) >= 6:
            out.append(
                {
                    "open_time": int(row[0]),
                    "open": float(row[1]),
                    "high": float(row[2]),
                    "low": float(row[3]),
                    "close": float(row[4]),
                    "volume": float(row[5]),
                    "close_time": int(row[6]) if len(row) > 6 else None,
                }
            )
    return {"symbol": symbol.upper(), "interval": interval, "count": len(out), "klines": out}


@router.get("/symbols")
async def mobile_symbols():
    return {
        "symbols": ["BTCUSDT", "ETHUSDT"],
        "intervals": ["5m", "10m", "30m", "1h"],
        "default_symbol": "BTCUSDT",
        "default_interval": "10m",
        "default_limit": 1000,
    }


@router.get("/strategy/schema")
async def mobile_strategy_schema():
    """与 crypto-app StrategyConfig 对齐的字段说明，便于 App 与 Agent 热同步。"""
    return {
        "indicators": ["RSI", "MACD", "KDJ_J", "CLOSE"],
        "ops": ["GT", "GTE", "LT", "LTE"],
        "buy_rules_logic": "OR",
        "sell_rules_logic": "OR",
        "example": {
            "title": "RSI 超买超卖",
            "buy_rules": [{"indicator": "RSI", "op": "LT", "value": 30}],
            "sell_rules": [{"indicator": "RSI", "op": "GT", "value": 70}],
        },
    }


@router.post("/signals")
async def mobile_signals(body: MobileSignalRequest):
    """尝试走 plugin；若不可用返回说明（App 可继续本地计算）。"""
    try:
        return await _post(
            f"{PLUGIN_URL}/api/v1/plugins/signals",
            {
                "symbol": body.symbol,
                "interval": body.interval,
                "limit": body.limit,
                "config": body.config.model_dump(),
            },
        )
    except Exception as e:
        return {
            "ok": False,
            "fallback": "local",
            "message": "plugin unavailable; android app should compute locally",
            "error": str(e),
        }


@router.post("/backtest")
async def mobile_backtest(body: MobileBacktestRequest):
    try:
        return await _post(
            f"{BACKTEST_URL}/api/v1/backtest",
            {
                "symbol": body.symbol,
                "interval": body.interval,
                "limit": body.limit,
                "hold_bars": body.hold_bars,
                "config": body.config.model_dump(),
            },
        )
    except Exception as e:
        return {
            "ok": False,
            "fallback": "local",
            "message": "backtest service unavailable; use in-app EventSim",
            "error": str(e),
        }
