"""
轻量回测引擎。
支持：
- 事件合约模式（固定持有 N 根 K 线后结算）
- 永续简单模式（信号反转或持有到期）
"""

from __future__ import annotations

from dataclasses import asdict
from typing import Any, Optional

import numpy as np
import pandas as pd

from common.logging import get_logger
from common.strategy import StrategyPlugin, Signal, BacktestResult

logger = get_logger("backtest-service.engine")


class BacktestEngine:
    """
    事件合约回测（默认）：
    - 信号出现后，持有 hold_bars 根 K 线，按收盘价结算
    - 只做方向判断，仓位固定为 1（可后续扩展）
    """

    def __init__(self, hold_bars: int = 6, fee_rate: float = 0.0004):
        self.hold_bars = hold_bars
        self.fee_rate = fee_rate  # 单边手续费，事件合约可设 0

    def run(
        self,
        plugin: StrategyPlugin,
        df: pd.DataFrame,
        params: dict[str, Any] | None = None,
        mode: str = "event_30m",
        symbol: str = "BTCUSDT",
    ) -> BacktestResult:
        params = {**plugin.default_params(), **(params or {})}
        signals = plugin.generate_signals(df, params=params)

        if not signals:
            return BacktestResult(
                plugin_name=plugin.name,
                symbol=symbol,
                mode=mode,
                params=params,
            )

        # 建立时间索引方便查找
        df = df.copy()
        df = df.sort_values("open_time").reset_index(drop=True)
        time_to_idx = {t: i for i, t in enumerate(df["open_time"])}

        trades = []
        equity = [1.0]
        returns = []

        for sig in signals:
            # 对齐时间
            t = sig.time
            if not isinstance(t, pd.Timestamp):
                t = pd.Timestamp(t)
            if t.tzinfo is None:
                t = t.tz_localize("UTC")

            if t not in time_to_idx:
                # 找最近的
                diffs = [(abs((df.loc[i, "open_time"] - t).total_seconds()), i) for i in range(len(df))]
                diffs.sort()
                idx = diffs[0][1]
            else:
                idx = time_to_idx[t]

            exit_idx = idx + self.hold_bars
            if exit_idx >= len(df):
                continue

            entry_price = float(df.loc[idx, "close"])
            exit_price = float(df.loc[exit_idx, "close"])

            if sig.direction == "long":
                ret = (exit_price / entry_price) - 1.0
            elif sig.direction == "short":
                ret = (entry_price / exit_price) - 1.0
            else:
                continue

            ret -= 2 * self.fee_rate  # 开平手续费
            returns.append(ret)
            equity.append(equity[-1] * (1 + ret))

            trades.append({
                "entry_time": df.loc[idx, "open_time"].isoformat(),
                "exit_time": df.loc[exit_idx, "open_time"].isoformat(),
                "direction": sig.direction,
                "entry_price": entry_price,
                "exit_price": exit_price,
                "return": round(ret, 6),
                "confidence": sig.confidence,
                "win": ret > 0,
                "meta": sig.meta,
            })

        if not returns:
            return BacktestResult(
                plugin_name=plugin.name,
                symbol=symbol,
                mode=mode,
                params=params,
                signals=[s.to_dict() for s in signals],
            )

        rets = np.array(returns)
        win_count = int((rets > 0).sum())
        loss_count = int((rets <= 0).sum())
        total = len(rets)
        win_rate = win_count / total if total else 0.0

        gross_profit = rets[rets > 0].sum() if (rets > 0).any() else 0.0
        gross_loss = abs(rets[rets <= 0].sum()) if (rets <= 0).any() else 0.0
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else float("inf")

        # 最大回撤
        eq = np.array(equity)
        peak = np.maximum.accumulate(eq)
        dd = (peak - eq) / peak
        max_dd = float(dd.max()) if len(dd) else 0.0

        return BacktestResult(
            plugin_name=plugin.name,
            symbol=symbol,
            mode=mode,
            params=params,
            total_signals=total,
            win_count=win_count,
            loss_count=loss_count,
            win_rate=round(win_rate, 4),
            total_return=round(float(eq[-1] - 1.0), 4),
            avg_return=round(float(rets.mean()), 6),
            max_drawdown=round(max_dd, 4),
            profit_factor=round(float(profit_factor), 4) if profit_factor != float("inf") else 999.0,
            signals=[t for t in trades],
            equity_curve=[round(e, 6) for e in equity],
            extra={"hold_bars": self.hold_bars, "fee_rate": self.fee_rate},
        )
