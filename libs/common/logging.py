"""
统一日志配置。
- 控制台
- 本地轮转文件 logs/{service}.log
- 可选上报 Log Service（集中查询，其它服务零额外业务代码）
"""

from __future__ import annotations

import atexit
import logging
import queue
import sys
import threading
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Optional

from .config import BaseServiceSettings

_LOG_ROOT: Optional[Path] = None
_remote_thread: Optional[threading.Thread] = None
_remote_queue: Optional[queue.Queue] = None
_remote_stop = threading.Event()


def get_log_root() -> Path:
    global _LOG_ROOT
    if _LOG_ROOT is not None:
        return _LOG_ROOT
    root = Path(__file__).resolve().parents[2]
    _LOG_ROOT = root / "logs"
    _LOG_ROOT.mkdir(parents=True, exist_ok=True)
    return _LOG_ROOT


class RemoteLogHandler(logging.Handler):
    """异步批量上报到 Log Service，失败静默（不影响主流程）。"""

    def __init__(self, service_name: str, log_service_url: str, level=logging.INFO):
        super().__init__(level)
        self.service_name = service_name
        self.log_service_url = log_service_url.rstrip("/")
        global _remote_queue, _remote_thread
        if _remote_queue is None:
            _remote_queue = queue.Queue(maxsize=5000)
            _remote_thread = threading.Thread(
                target=_remote_worker,
                args=(_remote_queue, self.log_service_url),
                name="remote-log-shipper",
                daemon=True,
            )
            _remote_thread.start()
            atexit.register(_flush_remote)

    def emit(self, record: logging.LogRecord) -> None:
        if _remote_queue is None:
            return
        try:
            entry = {
                "service": self.service_name,
                "level": record.levelname,
                "logger": record.name,
                "message": self.format(record) if self.formatter else record.getMessage(),
                "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + "Z",
            }
            # 简化 message：只要 msg 本体
            entry["message"] = record.getMessage()
            try:
                _remote_queue.put_nowait(entry)
            except queue.Full:
                pass
        except Exception:
            pass


def _remote_worker(q: queue.Queue, base_url: str) -> None:
    batch: list = []
    last_flush = time.time()
    while not _remote_stop.is_set() or not q.empty() or batch:
        try:
            item = q.get(timeout=0.5)
            batch.append(item)
        except queue.Empty:
            pass
        now = time.time()
        if batch and (len(batch) >= 20 or now - last_flush >= 1.0 or _remote_stop.is_set()):
            _send_batch(base_url, batch)
            batch = []
            last_flush = now


def _send_batch(base_url: str, batch: list) -> None:
    if not batch:
        return
    try:
        import urllib.request
        import json
        data = json.dumps({"entries": batch}).encode("utf-8")
        req = urllib.request.Request(
            f"{base_url}/api/v1/logs/batch",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=2)
    except Exception:
        pass


def _flush_remote() -> None:
    _remote_stop.set()
    if _remote_queue is not None:
        # 尽力冲刷
        time.sleep(0.3)


def setup_logging(settings: Optional[BaseServiceSettings] = None) -> None:
    if settings is None:
        from .config import get_base_settings
        settings = get_base_settings()

    level = getattr(logging, settings.log_level.upper(), logging.INFO)

    if settings.log_json:
        fmt = '{"time":"%(asctime)s","level":"%(levelname)s","service":"%(name)s","message":"%(message)s"}'
    else:
        fmt = "%(asctime)s | %(levelname)-7s | %(name)s | %(message)s"

    formatter = logging.Formatter(fmt, datefmt="%Y-%m-%d %H:%M:%S")
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]

    try:
        log_dir = get_log_root()
        file_path = log_dir / f"{settings.service_name}.log"
        fh = RotatingFileHandler(file_path, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
        fh.setFormatter(formatter)
        handlers.append(fh)
    except Exception as e:
        sys.stderr.write(f"warning: file logging disabled: {e}\n")

    # 远程集中日志（log-service 自身关闭）
    remote_enabled = getattr(settings, "log_remote_enabled", True)
    log_url = getattr(settings, "log_service_url", "http://localhost:8009")
    if remote_enabled and settings.service_name != "log-service" and log_url:
        try:
            rh = RemoteLogHandler(settings.service_name, log_url, level=level)
            rh.setFormatter(formatter)
            handlers.append(rh)
        except Exception as e:
            sys.stderr.write(f"warning: remote logging disabled: {e}\n")

    for h in handlers:
        if not isinstance(h, RotatingFileHandler):
            h.setFormatter(formatter)

    logging.basicConfig(level=level, handlers=handlers, force=True)
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def read_log_tail(service_name: str, lines: int = 200) -> list[str]:
    """本地文件尾部（兼容旧 Ops）；优先建议查 Log Service。"""
    path = get_log_root() / f"{service_name}.log"
    if not path.exists():
        return []
    try:
        with path.open("rb") as f:
            f.seek(0, 2)
            size = f.tell()
            block = 4096
            data = b""
            while size > 0 and data.count(b"\n") <= lines:
                step = min(block, size)
                size -= step
                f.seek(size)
                data = f.read(step) + data
            text = data.decode("utf-8", errors="replace")
            return text.splitlines()[-lines:]
    except Exception as e:
        return [f"[error reading log: {e}]"]
