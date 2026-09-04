"""
参数网格生成与结果排序。
目标默认：平均胜率最大化，可附带收益、回撤过滤。
"""

from __future__ import annotations

import itertools
from typing import Any, Iterator


# 内置常见策略的默认搜索空间
DEFAULT_GRIDS: dict[str, dict[str, list]] = {
    "kdj_rsi_event": {
        "rsi_period": [7, 14],
        "rsi_oversold": [20, 25, 30],
        "rsi_overbought": [70, 75, 80],
        "kdj_n": [9],
        "min_confidence": [0.5, 0.55, 0.6],
    }
}


def expand_grid(param_grid: dict[str, list]) -> list[dict[str, Any]]:
    """笛卡尔积展开参数网格"""
    if not param_grid:
        return [{}]
    keys = list(param_grid.keys())
    values = [param_grid[k] if isinstance(param_grid[k], list) else [param_grid[k]] for k in keys]
    combos = []
    for prod in itertools.product(*values):
        combos.append(dict(zip(keys, prod)))
    return combos


def default_grid_for_plugin(plugin_name: str) -> dict[str, list]:
    return DEFAULT_GRIDS.get(plugin_name, {}).copy()


def rank_results(
    results: list[dict[str, Any]],
    metric: str = "win_rate",
    min_signals: int = 5,
    max_drawdown: float | None = None,
) -> list[dict[str, Any]]:
    """
    过滤 + 排序。
    metric: win_rate | total_return | profit_factor
    """
    filtered = []
    for r in results:
        if not r.get("ok", True):
            continue
        if r.get("total_signals", 0) < min_signals:
            continue
        if max_drawdown is not None and r.get("max_drawdown", 1) > max_drawdown:
            continue
        filtered.append(r)

    reverse = True  # 越大越好
    filtered.sort(key=lambda x: x.get(metric, 0) or 0, reverse=reverse)
    return filtered
