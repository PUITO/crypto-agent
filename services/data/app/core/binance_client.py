"""
Binance 公共行情客户端。
使用官方公开接口，无需 API Key。
优先使用 data-api.binance.vision（更稳定，专为历史数据优化）。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any, Optional

import httpx
import pandas as pd

from common.logging import get_logger

logger = get_logger("data-service.binance")


class BinanceClient:
    """Binance K 线拉取客户端（异步）"""

    # 公共接口（无需密钥）
    BASE_URLS = [
        "https://data-api.binance.vision",
        "https://api.binance.com",
    ]

    def __init__(
        self,
        base_url: str = "https://data-api.binance.vision",
        timeout: float = 30.0,
        max_retries: int = 3,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries
        self._client: Optional[httpx.AsyncClient] = None

    async def __aenter__(self):
        self._client = httpx.AsyncClient(timeout=self.timeout)
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self._client:
            await self._client.aclose()
            self._client = None

    async def _request(self, path: str, params: dict[str, Any] | None = None) -> Any:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=self.timeout)

        url = f"{self.base_url}{path}"
        last_err: Exception | None = None

        for attempt in range(1, self.max_retries + 1):
            try:
                resp = await self._client.get(url, params=params)
                resp.raise_for_status()
                return resp.json()
            except Exception as e:
                last_err = e
                logger.warning(f"Binance request failed (attempt {attempt}/{self.max_retries}): {e}")
                if attempt < self.max_retries:
                    await asyncio.sleep(1.0 * attempt)

        raise RuntimeError(f"Binance request failed after {self.max_retries} retries: {last_err}")

    async def get_klines(
        self,
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        limit: int = 1000,
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
    ) -> pd.DataFrame:
        """
        拉取 K 线数据，返回标准化 DataFrame。

        列：
            open_time (datetime UTC), open, high, low, close, volume,
            close_time, quote_volume, trades, taker_buy_base, taker_buy_quote
        """
        params: dict[str, Any] = {
            "symbol": symbol.upper(),
            "interval": interval,
            "limit": min(limit, 1000),
        }
        if start_time is not None:
            params["startTime"] = start_time
        if end_time is not None:
            params["endTime"] = end_time

        raw = await self._request("/api/v3/klines", params=params)

        if not raw:
            return pd.DataFrame()

        columns = [
            "open_time",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "close_time",
            "quote_volume",
            "trades",
            "taker_buy_base",
            "taker_buy_quote",
            "ignore",
        ]
        df = pd.DataFrame(raw, columns=columns)

        # 类型转换
        df["open_time"] = pd.to_datetime(df["open_time"], unit="ms", utc=True)
        df["close_time"] = pd.to_datetime(df["close_time"], unit="ms", utc=True)

        float_cols = ["open", "high", "low", "close", "volume", "quote_volume", "taker_buy_base", "taker_buy_quote"]
        for col in float_cols:
            df[col] = df[col].astype(float)

        df["trades"] = df["trades"].astype(int)
        df = df.drop(columns=["ignore"])

        # 添加元数据列，方便后续存储
        df["symbol"] = symbol.upper()
        df["interval"] = interval

        return df

    async def get_latest_price(self, symbol: str = "BTCUSDT") -> dict[str, Any]:
        """获取最新成交价"""
        data = await self._request("/api/v3/ticker/price", params={"symbol": symbol.upper()})
        return {
            "symbol": data["symbol"],
            "price": float(data["price"]),
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    async def fetch_historical(
        self,
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        max_candles: int = 50000,
    ) -> pd.DataFrame:
        """
        分页拉取历史 K 线（自动处理 limit=1000 限制）。
        返回按时间升序的完整 DataFrame。
        """
        all_dfs: list[pd.DataFrame] = []
        current_start = int(start_time.timestamp() * 1000) if start_time else None
        end_ms = int(end_time.timestamp() * 1000) if end_time else None
        fetched = 0

        while fetched < max_candles:
            df = await self.get_klines(
                symbol=symbol,
                interval=interval,
                limit=1000,
                start_time=current_start,
                end_time=end_ms,
            )
            if df.empty:
                break

            all_dfs.append(df)
            fetched += len(df)

            # 下一页从最后一根的下一毫秒开始
            last_open = df["open_time"].iloc[-1]
            current_start = int(last_open.timestamp() * 1000) + 1

            # 如果返回不足 1000 根，说明已经到头
            if len(df) < 1000:
                break

            # 避免请求过快
            await asyncio.sleep(0.2)

        if not all_dfs:
            return pd.DataFrame()

        result = pd.concat(all_dfs, ignore_index=True)
        result = result.drop_duplicates(subset=["open_time"]).sort_values("open_time").reset_index(drop=True)
        logger.info(f"Fetched {len(result)} candles for {symbol} {interval}")
        return result
