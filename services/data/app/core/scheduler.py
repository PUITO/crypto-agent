"""
定时采集任务。
默认每 5 分钟增量拉取一次最新 K 线并写入本地 Parquet。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

from common.logging import get_logger

from .binance_client import BinanceClient
from .storage import ParquetStorage

logger = get_logger("data-service.scheduler")


class KlineScheduler:
    """
    简单可靠的异步定时任务。
    不引入 APScheduler 重依赖，使用 asyncio 循环即可满足 5 分钟级采集。
    """

    def __init__(
        self,
        storage: ParquetStorage,
        symbols: list[str] | None = None,
        interval: str = "5m",
        fetch_every_seconds: int = 300,  # 5 分钟
        history_days_on_first_run: int = 7,
    ):
        self.storage = storage
        self.symbols = symbols or ["BTCUSDT"]
        self.interval = interval
        self.fetch_every_seconds = fetch_every_seconds
        self.history_days_on_first_run = history_days_on_first_run

        self._task: Optional[asyncio.Task] = None
        self._running = False
        self._last_run: Optional[datetime] = None
        self._last_status: dict = {}

    @property
    def status(self) -> dict:
        return {
            "running": self._running,
            "symbols": self.symbols,
            "interval": self.interval,
            "fetch_every_seconds": self.fetch_every_seconds,
            "last_run": self._last_run.isoformat() if self._last_run else None,
            "last_status": self._last_status,
        }

    async def start(self):
        if self._running:
            logger.warning("Scheduler already running")
            return
        self._running = True
        self._task = asyncio.create_task(self._loop())
        logger.info(
            f"KlineScheduler started: symbols={self.symbols}, "
            f"interval={self.interval}, every={self.fetch_every_seconds}s"
        )

    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("KlineScheduler stopped")

    async def _loop(self):
        # 启动后立即执行一次
        await self.run_once()
        while self._running:
            try:
                await asyncio.sleep(self.fetch_every_seconds)
                if self._running:
                    await self.run_once()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.exception(f"Scheduler loop error: {e}")
                await asyncio.sleep(10)

    async def run_once(self) -> dict:
        """执行一轮增量采集，返回结果摘要"""
        results = {}
        async with BinanceClient() as client:
            for symbol in self.symbols:
                try:
                    added = await self._fetch_symbol(client, symbol)
                    results[symbol] = {"ok": True, "added": added}
                except Exception as e:
                    logger.exception(f"Failed to fetch {symbol}: {e}")
                    results[symbol] = {"ok": False, "error": str(e)}

        self._last_run = datetime.now(timezone.utc)
        self._last_status = results
        logger.info(f"Scheduler run finished: {results}")
        return results

    async def _fetch_symbol(self, client: BinanceClient, symbol: str) -> int:
        latest_local = self.storage.get_latest_open_time(symbol, self.interval)

        if latest_local is None:
            # 首次运行：拉最近 N 天历史
            start = datetime.now(timezone.utc) - timedelta(days=self.history_days_on_first_run)
            logger.info(f"First run for {symbol}, backfilling {self.history_days_on_first_run} days")
            df = await client.fetch_historical(
                symbol=symbol,
                interval=self.interval,
                start_time=start,
            )
        else:
            # 增量：从本地最新时间之后开始
            # 留一点重叠防止漏数据
            start = latest_local - timedelta(minutes=5)
            df = await client.fetch_historical(
                symbol=symbol,
                interval=self.interval,
                start_time=start,
            )

        if df.empty:
            return 0

        return self.storage.save_klines(df, symbol, self.interval)
