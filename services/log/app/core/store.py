"""
集中日志存储：内存环形缓冲 + 按服务落盘，供查询与运维界面使用。
"""

from __future__ import annotations

import json
import threading
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from uuid import uuid4

from common.logging import get_log_root

MAX_MEMORY = 20000  # 全局内存条数上限


class LogStore:
    def __init__(self, persist_dir: Optional[Path] = None):
        self.persist_dir = Path(persist_dir or (get_log_root() / "central"))
        self.persist_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._buf: deque[dict[str, Any]] = deque(maxlen=MAX_MEMORY)

    def ingest(self, entries: list[dict[str, Any]] | dict[str, Any]) -> int:
        if isinstance(entries, dict):
            entries = [entries]
        n = 0
        with self._lock:
            for e in entries:
                item = self._normalize(e)
                self._buf.append(item)
                self._append_file(item)
                n += 1
        return n

    def _normalize(self, e: dict[str, Any]) -> dict[str, Any]:
        ts = e.get("ts") or e.get("time") or datetime.now(timezone.utc).isoformat()
        return {
            "id": e.get("id") or str(uuid4()),
            "ts": ts,
            "service": e.get("service") or e.get("name") or "unknown",
            "level": str(e.get("level") or "INFO").upper(),
            "logger": e.get("logger") or e.get("name") or "",
            "message": e.get("message") or e.get("msg") or "",
            "extra": e.get("extra") or {},
        }

    def _append_file(self, item: dict[str, Any]) -> None:
        try:
            path = self.persist_dir / f"{item['service']}.jsonl"
            with path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
        except Exception:
            pass

    def query(
        self,
        service: Optional[str] = None,
        level: Optional[str] = None,
        contains: Optional[str] = None,
        limit: int = 200,
    ) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 2000))
        with self._lock:
            items = list(self._buf)
        if service:
            items = [x for x in items if x["service"] == service]
        if level:
            lv = level.upper()
            order = {"DEBUG": 10, "INFO": 20, "WARNING": 30, "ERROR": 40, "CRITICAL": 50}
            min_lv = order.get(lv, 0)
            items = [x for x in items if order.get(x["level"], 0) >= min_lv]
        if contains:
            q = contains.lower()
            items = [x for x in items if q in (x.get("message") or "").lower()]
        return items[-limit:]

    def tail(self, service: str, lines: int = 200) -> list[dict[str, Any]]:
        return self.query(service=service, limit=lines)

    def services(self) -> list[str]:
        with self._lock:
            names = sorted({x["service"] for x in self._buf})
        # also from disk
        for p in self.persist_dir.glob("*.jsonl"):
            if p.stem not in names:
                names.append(p.stem)
        return sorted(set(names))

    def stats(self) -> dict[str, Any]:
        with self._lock:
            total = len(self._buf)
            by_level: dict[str, int] = {}
            by_svc: dict[str, int] = {}
            for x in self._buf:
                by_level[x["level"]] = by_level.get(x["level"], 0) + 1
                by_svc[x["service"]] = by_svc.get(x["service"], 0) + 1
        return {"memory_entries": total, "by_level": by_level, "by_service": by_svc}
