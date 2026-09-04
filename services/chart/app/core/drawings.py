"""
绘图对象存储与计算（斐波那契等）。
前端（Lightweight Charts）根据标准化 JSON 渲染。
"""

from __future__ import annotations

import threading
from copy import deepcopy
from datetime import datetime, timezone
from typing import Any, Optional
from uuid import uuid4

from common.logging import get_logger

logger = get_logger("chart-service.drawings")

# 标准斐波那契回撤比例
DEFAULT_FIB_LEVELS = [0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0]


def compute_fibonacci(
    price_from: float,
    price_to: float,
    levels: list[float] | None = None,
) -> list[dict[str, Any]]:
    """
    计算斐波那契水平价位。
    price_from = 起点价（通常为低点或高点），price_to = 终点价。
    """
    levels = levels or DEFAULT_FIB_LEVELS
    span = price_to - price_from
    result = []
    for lv in levels:
        price = price_from + span * lv
        result.append({"level": lv, "price": round(price, 4)})
    return result


class DrawingStore:
    """按 session / symbol 管理绘图对象"""

    def __init__(self):
        self._lock = threading.RLock()
        # key = drawing_id
        self._items: dict[str, dict[str, Any]] = {}

    def list(
        self,
        symbol: Optional[str] = None,
        session_id: Optional[str] = None,
        type: Optional[str] = None,
    ) -> list[dict[str, Any]]:
        with self._lock:
            items = list(self._items.values())
        if symbol:
            items = [i for i in items if i.get("symbol") == symbol.upper()]
        if session_id:
            items = [i for i in items if i.get("session_id") == session_id]
        if type:
            items = [i for i in items if i.get("type") == type]
        return deepcopy(items)

    def get(self, drawing_id: str) -> Optional[dict[str, Any]]:
        with self._lock:
            item = self._items.get(drawing_id)
            return deepcopy(item) if item else None

    def add(self, drawing: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            did = drawing.get("id") or str(uuid4())
            drawing["id"] = did
            drawing["created_at"] = datetime.now(timezone.utc).isoformat()
            drawing["updated_at"] = drawing["created_at"]
            if "symbol" in drawing and drawing["symbol"]:
                drawing["symbol"] = str(drawing["symbol"]).upper()
            self._items[did] = drawing
            logger.info(f"Drawing added: {drawing.get('type')} id={did}")
            return deepcopy(drawing)

    def delete(self, drawing_id: str) -> bool:
        with self._lock:
            if drawing_id in self._items:
                del self._items[drawing_id]
                return True
            return False

    def clear(
        self,
        symbol: Optional[str] = None,
        session_id: Optional[str] = None,
        type: Optional[str] = None,
    ) -> int:
        with self._lock:
            to_del = []
            for did, item in self._items.items():
                if symbol and item.get("symbol") != symbol.upper():
                    continue
                if session_id and item.get("session_id") != session_id:
                    continue
                if type and item.get("type") != type:
                    continue
                to_del.append(did)
            for did in to_del:
                del self._items[did]
            return len(to_del)

    # ----- 便捷构造 -----

    def create_fibonacci(
        self,
        symbol: str,
        time_from: str,
        price_from: float,
        time_to: str,
        price_to: float,
        levels: list[float] | None = None,
        name: Optional[str] = None,
        session_id: Optional[str] = None,
        color: str = "#2196F3",
    ) -> dict[str, Any]:
        fib_levels = compute_fibonacci(price_from, price_to, levels)
        return self.add({
            "type": "fibonacci",
            "name": name or f"fib_{symbol}",
            "symbol": symbol,
            "session_id": session_id,
            "time_from": time_from,
            "time_to": time_to,
            "price_from": price_from,
            "price_to": price_to,
            "levels": fib_levels,
            "style": {"color": color, "lineWidth": 1},
        })

    def create_horizontal(
        self,
        symbol: str,
        price: float,
        name: Optional[str] = None,
        kind: str = "support",  # support | resistance | horizontal
        session_id: Optional[str] = None,
        color: Optional[str] = None,
    ) -> dict[str, Any]:
        if color is None:
            color = "#4CAF50" if kind == "support" else "#F44336"
        return self.add({
            "type": "horizontal",
            "kind": kind,
            "name": name or f"{kind}_{price}",
            "symbol": symbol,
            "session_id": session_id,
            "price": price,
            "style": {"color": color, "lineWidth": 1, "lineStyle": 2},
        })

    def create_trendline(
        self,
        symbol: str,
        time_from: str,
        price_from: float,
        time_to: str,
        price_to: float,
        name: Optional[str] = None,
        session_id: Optional[str] = None,
        color: str = "#FF9800",
    ) -> dict[str, Any]:
        return self.add({
            "type": "trendline",
            "name": name or "trendline",
            "symbol": symbol,
            "session_id": session_id,
            "time_from": time_from,
            "price_from": price_from,
            "time_to": time_to,
            "price_to": price_to,
            "style": {"color": color, "lineWidth": 2},
        })

    def create_annotation(
        self,
        symbol: str,
        time: str,
        price: float,
        text: str,
        session_id: Optional[str] = None,
        color: str = "#9C27B0",
    ) -> dict[str, Any]:
        return self.add({
            "type": "annotation",
            "name": text[:32],
            "symbol": symbol,
            "session_id": session_id,
            "time": time,
            "price": price,
            "text": text,
            "style": {"color": color},
        })

    def create_signal_marker(
        self,
        symbol: str,
        time: str,
        price: float,
        direction: str,
        session_id: Optional[str] = None,
    ) -> dict[str, Any]:
        color = "#4CAF50" if direction == "long" else "#F44336"
        shape = "arrowUp" if direction == "long" else "arrowDown"
        return self.add({
            "type": "marker",
            "kind": "signal",
            "name": f"signal_{direction}",
            "symbol": symbol,
            "session_id": session_id,
            "time": time,
            "price": price,
            "direction": direction,
            "style": {"color": color, "shape": shape},
        })
