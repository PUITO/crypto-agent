"""
简单消息发布/订阅封装（基于 Redis Stream）。
早期够用，后期可平滑替换为 NATS。
"""

import json
from typing import Any, Callable, Dict, Optional

from .logging import get_logger

logger = get_logger(__name__)


class MessageBus:
    """轻量消息总线抽象。当前实现为内存 mock，方便单服务独立测试。
    真正接入 Redis 时只需替换此实现。
    """

    def __init__(self, redis_url: str = "redis://localhost:6379/0"):
        self.redis_url = redis_url
        self._subscribers: Dict[str, list] = {}

    async def publish(self, channel: str, message: Dict[str, Any]) -> None:
        """发布消息。"""
        logger.info(f"[MessageBus] publish to {channel}: {message}")
        # TODO: 真实环境用 redis.asyncio 写入 Stream
        for callback in self._subscribers.get(channel, []):
            try:
                await callback(message)
            except Exception as e:
                logger.error(f"Subscriber error on {channel}: {e}")

    async def subscribe(self, channel: str, callback: Callable) -> None:
        """订阅消息（当前为进程内模拟）。"""
        if channel not in self._subscribers:
            self._subscribers[channel] = []
        self._subscribers[channel].append(callback)
        logger.info(f"[MessageBus] subscribed to {channel}")


# 全局单例（简单起见）
_bus: Optional[MessageBus] = None


def get_message_bus(redis_url: str = "redis://localhost:6379/0") -> MessageBus:
    global _bus
    if _bus is None:
        _bus = MessageBus(redis_url)
    return _bus
