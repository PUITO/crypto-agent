"""
策略插件统一接口（Strategy Plugin Interface）。

所有可热插拔的策略 / 训练模型都必须实现此接口，
才能被 Plugin Service 加载，并被 Backtest / Agent 调用。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from typing import Any, Optional

import pandas as pd


@dataclass
class Signal:
    """单条交易信号"""
    time: Any                          # pd.Timestamp 或 ISO 字符串
    direction: str                     # "long" | "short" | "flat"
    confidence: float = 0.0            # 0~1
    price: Optional[float] = None
    meta: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = asdict(self)
        if hasattr(self.time, "isoformat"):
            d["time"] = self.time.isoformat()
        return d


@dataclass
class BacktestResult:
    """回测结果标准结构"""
    plugin_name: str
    symbol: str
    mode: str
    params: dict
    total_signals: int = 0
    win_count: int = 0
    loss_count: int = 0
    win_rate: float = 0.0
    total_return: float = 0.0
    avg_return: float = 0.0
    max_drawdown: float = 0.0
    profit_factor: float = 0.0
    signals: list = field(default_factory=list)   # list[dict]
    equity_curve: list = field(default_factory=list)
    extra: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)


class StrategyPlugin(ABC):
    """
    策略插件抽象基类。

    实现要求：
    - name / version / modes / description 为类属性或 property
    - generate_signals: 输入 K 线 DataFrame + 参数 → 输出 Signal 列表
    - default_params: 返回默认参数字典
    - （可选）optimize_params: 参数搜索
    """

    name: str = "base"
    version: str = "0.0.0"
    modes: list[str] = ["perpetual", "event_30m"]  # 支持的交易模式
    description: str = ""

    @abstractmethod
    def default_params(self) -> dict[str, Any]:
        """返回默认参数"""
        ...

    @abstractmethod
    def generate_signals(self, df: pd.DataFrame, params: dict[str, Any] | None = None) -> list[Signal]:
        """
        根据 K 线数据生成信号。

        df 至少包含: open_time, open, high, low, close, volume
        返回按时间排序的 Signal 列表。
        """
        ...

    def optimize_params(
        self,
        df: pd.DataFrame,
        metric: str = "win_rate",
        param_grid: dict[str, list] | None = None,
    ) -> dict[str, Any]:
        """
        可选：参数优化。默认实现为简单网格搜索（子类可覆盖）。
        返回最优参数字典。
        """
        # 默认不实现复杂优化，子类按需覆盖
        return self.default_params()

    def info(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "modes": self.modes,
            "description": self.description,
            "default_params": self.default_params(),
        }
