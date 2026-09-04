"""
KDJ + RSI 事件合约策略插件。

逻辑概要（可参数化）：
- 计算 KDJ 与 RSI
- 超卖区域（RSI < rsi_oversold 且 K < d）产生 long 信号
- 超买区域（RSI > rsi_overbought 且 K > d）产生 short 信号
- 适合 30 分钟事件合约（horizon ≈ 6 根 5m K 线）
"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

import sys
from pathlib import Path

# 允许直接被 Plugin Service 动态加载
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.strategy import StrategyPlugin, Signal


def _rsi(close: pd.Series, period: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)
    avg_gain = gain.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1 / period, min_periods=period, adjust=False).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))


def _kdj(high: pd.Series, low: pd.Series, close: pd.Series, n: int = 9, m1: int = 3, m2: int = 3):
    low_min = low.rolling(n).min()
    high_max = high.rolling(n).max()
    rsv = (close - low_min) / (high_max - low_min).replace(0, np.nan) * 100
    k = rsv.ewm(com=m1 - 1, adjust=False).mean()
    d = k.ewm(com=m2 - 1, adjust=False).mean()
    j = 3 * k - 2 * d
    return k, d, j


class KdjRsiEventPlugin(StrategyPlugin):
    name = "kdj_rsi_event"
    version = "1.0.0"
    modes = ["event_30m", "perpetual"]
    description = "KDJ + RSI 组合策略，适合事件合约与短线方向判断"

    def default_params(self) -> dict[str, Any]:
        return {
            "rsi_period": 14,
            "rsi_oversold": 30,
            "rsi_overbought": 70,
            "kdj_n": 9,
            "kdj_m1": 3,
            "kdj_m2": 3,
            "min_confidence": 0.55,
        }

    def generate_signals(self, df: pd.DataFrame, params: dict[str, Any] | None = None) -> list[Signal]:
        p = {**self.default_params(), **(params or {})}
        data = df.copy()

        data["rsi"] = _rsi(data["close"], int(p["rsi_period"]))
        data["k"], data["d"], data["j"] = _kdj(
            data["high"], data["low"], data["close"],
            n=int(p["kdj_n"]), m1=int(p["kdj_m1"]), m2=int(p["kdj_m2"]),
        )

        signals: list[Signal] = []
        min_conf = float(p["min_confidence"])

        for i in range(1, len(data)):
            row = data.iloc[i]
            prev = data.iloc[i - 1]

            rsi = row["rsi"]
            k, d = row["k"], row["d"]
            if pd.isna(rsi) or pd.isna(k) or pd.isna(d):
                continue

            direction = "flat"
            confidence = 0.0

            # 超卖转多：RSI 低位 + K 上穿 D 或 K 仍在 D 下方但 RSI 开始回升
            if rsi < p["rsi_oversold"] and k > prev["k"] and k <= d + 5:
                direction = "long"
                # 越超卖置信度略高
                confidence = min(0.95, 0.5 + (p["rsi_oversold"] - rsi) / 100 + max(0, (d - k) / 100))
            # 超买转空
            elif rsi > p["rsi_overbought"] and k < prev["k"] and k >= d - 5:
                direction = "short"
                confidence = min(0.95, 0.5 + (rsi - p["rsi_overbought"]) / 100 + max(0, (k - d) / 100))

            if direction != "flat" and confidence >= min_conf:
                signals.append(Signal(
                    time=row["open_time"],
                    direction=direction,
                    confidence=round(float(confidence), 4),
                    price=float(row["close"]),
                    meta={
                        "rsi": round(float(rsi), 2),
                        "k": round(float(k), 2),
                        "d": round(float(d), 2),
                        "j": round(float(row["j"]), 2) if not pd.isna(row["j"]) else None,
                    },
                ))

        return signals


# 供动态加载使用的工厂
def create_plugin() -> StrategyPlugin:
    return KdjRsiEventPlugin()
