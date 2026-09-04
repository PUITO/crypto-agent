"""
统一日志配置。
支持普通文本和 JSON 两种模式，方便本地开发和后期接入日志系统。
"""

import logging
import sys
from typing import Optional

from .config import BaseServiceSettings


def setup_logging(settings: Optional[BaseServiceSettings] = None) -> None:
    """初始化根日志配置，应在服务启动时尽早调用。"""
    if settings is None:
        from .config import get_base_settings
        settings = get_base_settings()

    level = getattr(logging, settings.log_level.upper(), logging.INFO)

    # 简单格式，后期可换成 structlog
    if settings.log_json:
        fmt = '{"time":"%(asctime)s","level":"%(levelname)s","service":"%(name)s","message":"%(message)s"}'
    else:
        fmt = "%(asctime)s | %(levelname)-7s | %(name)s | %(message)s"

    logging.basicConfig(
        level=level,
        format=fmt,
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[logging.StreamHandler(sys.stdout)],
        force=True,
    )

    # 降低第三方库噪音
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """获取带服务名的 logger。"""
    return logging.getLogger(name)
