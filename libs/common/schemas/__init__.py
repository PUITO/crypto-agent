"""
公共 Pydantic Schema 存放处。
跨服务共享的事件、配置、信号等模型放这里，避免循环依赖和重复定义。
"""

from pydantic import BaseModel, Field
from typing import Any, Dict, List, Optional
from datetime import datetime


class BaseEvent(BaseModel):
    """所有事件的基类。"""
    event_type: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    source_service: str
    payload: Dict[str, Any] = Field(default_factory=dict)


class ConfigChangedEvent(BaseEvent):
    event_type: str = "config.changed"
    # payload 示例: {"key": "mode", "old_value": "...", "new_value": "..."}


class SignalGeneratedEvent(BaseEvent):
    event_type: str = "signal.generated"
    # payload 示例: {"symbol": "BTCUSDT", "direction": "long", "confidence": 0.72}
