"""简易 cron：默认每天 12:00 push。"""

from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Awaitable, Callable, Optional

from common.logging import get_logger

logger = get_logger("sync-service.scheduler")


def cron_match(expr: str, now: datetime) -> bool:
    """支持标准 5 段 cron：分 时 日 月 周。仅实现数字与 *。"""
    parts = expr.strip().split()
    if len(parts) != 5:
        return False
    minute, hour, day, month, weekday = parts

    def match(field: str, value: int) -> bool:
        if field == "*":
            return True
        if field.isdigit():
            return int(field) == value
        return False

    # cron 周日 0-6
    return (
        match(minute, now.minute)
        and match(hour, now.hour)
        and match(day, now.day)
        and match(month, now.month)
        and match(weekday, now.weekday() % 7)
    )


class SyncScheduler:
    def __init__(self, cron: str, job: Callable[[], Awaitable[None]]):
        self.cron = cron
        self.job = job
        self._task: Optional[asyncio.Task] = None
        self._running = False
        self.last_trigger: Optional[str] = None

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._loop())
        logger.info(f"Sync scheduler started cron={self.cron}")

    async def stop(self) -> None:
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except Exception:
                pass

    async def _loop(self) -> None:
        last_min = None
        while self._running:
            now = datetime.now()
            key = now.strftime("%Y%m%d%H%M")
            if key != last_min and cron_match(self.cron, now):
                last_min = key
                self.last_trigger = now.isoformat()
                try:
                    await self.job()
                except Exception as e:
                    logger.warning(f"scheduled sync failed: {e}")
            await asyncio.sleep(15)
