"""
多分身并行回测：对多插件 × 多参数组合调用 Backtest Service。
"""

from __future__ import annotations

import asyncio
from typing import Any, Optional
from uuid import uuid4

import httpx

from common.logging import get_logger

from .optimizer import expand_grid, default_grid_for_plugin, rank_results

logger = get_logger("multi-agent.matrix")


class MatrixRunner:
    def __init__(
        self,
        backtest_url: str = "http://localhost:8004",
        config_url: str = "http://localhost:8002",
        max_concurrency: int = 4,
    ):
        self.backtest_url = backtest_url.rstrip("/")
        self.config_url = config_url.rstrip("/")
        self.max_concurrency = max_concurrency
        self._jobs: dict[str, dict[str, Any]] = {}

    def get_job(self, job_id: str) -> Optional[dict[str, Any]]:
        return self._jobs.get(job_id)

    async def run_matrix(
        self,
        plugins: list[str],
        param_sets: list[dict[str, Any]] | None = None,
        param_grid: dict[str, list] | None = None,
        symbol: str = "BTCUSDT",
        interval: str = "5m",
        mode: str = "event_30m",
        days: int = 14,
        hold_bars: int = 6,
        metric: str = "win_rate",
        min_signals: int = 5,
    ) -> dict[str, Any]:
        """
        同步等待全部回测完成并返回排序结果。
        若 param_sets 为空且 param_grid 为空，则用插件默认网格。
        """
        job_id = str(uuid4())
        tasks_spec: list[dict[str, Any]] = []

        for plugin in plugins:
            sets = param_sets
            if not sets:
                grid = param_grid or default_grid_for_plugin(plugin)
                sets = expand_grid(grid) if grid else [{}]
            for params in sets:
                tasks_spec.append({
                    "plugin_name": plugin,
                    "params": params,
                    "symbol": symbol,
                    "interval": interval,
                    "mode": mode,
                    "days": days,
                    "hold_bars": hold_bars,
                })

        self._jobs[job_id] = {
            "id": job_id,
            "status": "running",
            "total": len(tasks_spec),
            "done": 0,
            "results": [],
        }

        sem = asyncio.Semaphore(self.max_concurrency)
        results: list[dict[str, Any]] = []

        async def one(spec: dict) -> dict:
            async with sem:
                try:
                    async with httpx.AsyncClient(timeout=180) as client:
                        r = await client.post(
                            f"{self.backtest_url}/api/v1/backtest",
                            json=spec,
                        )
                        r.raise_for_status()
                        data = r.json()
                        out = {
                            "ok": True,
                            "plugin_name": data.get("plugin_name", spec["plugin_name"]),
                            "params": data.get("params", spec["params"]),
                            "total_signals": data.get("total_signals", 0),
                            "win_rate": data.get("win_rate", 0),
                            "total_return": data.get("total_return", 0),
                            "max_drawdown": data.get("max_drawdown", 0),
                            "profit_factor": data.get("profit_factor", 0),
                            "avg_return": data.get("avg_return", 0),
                        }
                except Exception as e:
                    logger.warning(f"Backtest failed for {spec}: {e}")
                    out = {
                        "ok": False,
                        "plugin_name": spec["plugin_name"],
                        "params": spec["params"],
                        "error": str(e),
                    }
                self._jobs[job_id]["done"] += 1
                return out

        results = await asyncio.gather(*[one(s) for s in tasks_spec])
        ranked = rank_results(list(results), metric=metric, min_signals=min_signals)

        self._jobs[job_id].update({
            "status": "done",
            "results": list(results),
            "ranked": ranked,
            "best": ranked[0] if ranked else None,
            "metric": metric,
        })
        return self._jobs[job_id]

    async def optimize_and_optionally_apply(
        self,
        plugin_name: str = "kdj_rsi_event",
        param_grid: dict[str, list] | None = None,
        symbol: str = "BTCUSDT",
        mode: str = "event_30m",
        days: int = 14,
        metric: str = "win_rate",
        apply_best: bool = False,
    ) -> dict[str, Any]:
        """优化单一插件参数，可选写回 Config Service"""
        job = await self.run_matrix(
            plugins=[plugin_name],
            param_grid=param_grid,
            symbol=symbol,
            mode=mode,
            days=days,
            metric=metric,
        )
        best = job.get("best")
        applied = None
        if apply_best and best and best.get("ok"):
            patch = {
                "active_plugin": plugin_name,
                "strategy_params": {plugin_name: best["params"]},
            }
            try:
                async with httpx.AsyncClient(timeout=15) as client:
                    r = await client.post(
                        f"{self.config_url}/api/v1/config/apply",
                        json={
                            "patch": patch,
                            "confirm": True,
                            "source": "multi_agent_optimize",
                            "note": f"best {metric}={best.get(metric)}",
                        },
                    )
                    r.raise_for_status()
                    applied = r.json()
            except Exception as e:
                applied = {"ok": False, "error": str(e)}

        return {
            "job_id": job["id"],
            "total_combos": job["total"],
            "ranked_top5": (job.get("ranked") or [])[:5],
            "best": best,
            "applied": applied,
            "metric": metric,
        }
