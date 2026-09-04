"""
crypto-agent 共享基础库
所有微服务统一依赖此包，避免重复造轮子。
"""

__version__ = "0.1.0"

from .config import BaseServiceSettings
from .logging import setup_logging, get_logger
from .health import router as health_router
from .exceptions import AppException, ErrorResponse

__all__ = [
    "BaseServiceSettings",
    "setup_logging",
    "get_logger",
    "health_router",
    "AppException",
    "ErrorResponse",
]
