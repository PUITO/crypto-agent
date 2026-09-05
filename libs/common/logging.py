"""
统一日志配置。
- 控制台输出
- 按服务写入 logs/{service_name}.log（供 Ops / 健康界面读取）
- 支持普通文本与 JSON
"""

from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Optional

from .config import BaseServiceSettings

# 项目根下的 logs 目录
_LOG_ROOT: Optional[Path] = None


def get_log_root() -> Path:
    global _LOG_ROOT
    if _LOG_ROOT is not None:
        return _LOG_ROOT
    # libs/common/logging.py -> parents[2] = repo root
    root = Path(__file__).resolve().parents[2]
    _LOG_ROOT = root / "logs"
    _LOG_ROOT.mkdir(parents=True, exist_ok=True)
    return _LOG_ROOT


def setup_logging(settings: Optional[BaseServiceSettings] = None) -> None:
    """初始化根日志配置，应在服务启动时尽早调用。"""
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

    # 文件日志（轮转）
    try:
        log_dir = get_log_root()
        file_path = log_dir / f"{settings.service_name}.log"
        fh = RotatingFileHandler(
            file_path,
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        fh.setFormatter(formatter)
        handlers.append(fh)
    except Exception as e:
        # 文件不可写时仍保证控制台可用
        sys.stderr.write(f"warning: file logging disabled: {e}\n")

    for h in handlers:
        if not isinstance(h, RotatingFileHandler):
            h.setFormatter(formatter)

    logging.basicConfig(
        level=level,
        handlers=handlers,
        force=True,
    )

    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def read_log_tail(service_name: str, lines: int = 200) -> list[str]:
    """读取某服务日志末尾 N 行（供 Ops 服务调用）。"""
    path = get_log_root() / f"{service_name}.log"
    if not path.exists():
        return []
    try:
        # 简单高效读尾部
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
