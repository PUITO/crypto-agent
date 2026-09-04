"""
Multi-Agent Manager —— 多分身矩阵回测 + 参数优化。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler

from core.matrix import MatrixRunner
from core.optimizer import default_grid_for_plugin, expand_grid


class Settings(BaseServiceSettings):
    service_name: str = "multi-agent-service"
    port: int = 8007
    max_concurrency: int = 4


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

runner = MatrixRunner(
    backtest_url=settings.backtest_service_url,
    config_url=settings.config_service_url,
    max_concurrency=settings.max_concurrency,
)


class MatrixRequest(BaseModel):
    plugins: List[str] = Field(default_factory=lambda: ["kdj_rsi_event"])
    param_sets: Optional[List[Dict[str, Any]]] = None
    param_grid: Optional[Dict[str, List[Any]]] = None
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    mode: str = "event_30m"
    days: int = Field(14, ge=1, le=90)
    hold_bars: int = 6
    metric: str = "win_rate"
    min_signals: int = 5


class OptimizeRequest(BaseModel):
    plugin_name: str = "kdj_rsi_event"
    param_grid: Optional[Dict[str, List[Any]]] = None
    symbol: str = "BTCUSDT"
    mode: str = "event_30m"
    days: int = 14
    metric: str = "win_rate"
    apply_best: bool = False


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Multi-Agent Manager",
        description="多分身并行回测、参数网格优化、可选写回配置",
        version="0.2.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_exception_handler(AppException, app_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)
    app.include_router(health_router)

    @app.get("/")
    async def root():
        return {
            "service": settings.service_name,
            "version": "0.2.0",
            "docs": "/docs",
            "endpoints": [
                "POST /api/v1/matrix",
                "POST /api/v1/optimize",
                "GET  /api/v1/jobs/{job_id}",
                "GET  /api/v1/grids/{plugin_name}",
            ],
        }

    @app.get("/api/v1/grids/{plugin_name}")
    async def get_default_grid(plugin_name: str):
        grid = default_grid_for_plugin(plugin_name)
        combos = expand_grid(grid) if grid else []
        return {
            "plugin_name": plugin_name,
            "grid": grid,
            "combo_count": len(combos),
            "sample": combos[:3],
        }

    @app.post("/api/v1/matrix")
    async def run_matrix(req: MatrixRequest):
        """并行回测多插件/多参数组合，按 metric 排序返回"""
        job = await runner.run_matrix(
            plugins=req.plugins,
            param_sets=req.param_sets,
            param_grid=req.param_grid,
            symbol=req.symbol,
            interval=req.interval,
            mode=req.mode,
            days=req.days,
            hold_bars=req.hold_bars,
            metric=req.metric,
            min_signals=req.min_signals,
        )
        return {
            "job_id": job["id"],
            "status": job["status"],
            "total": job["total"],
            "metric": job["metric"],
            "best": job.get("best"),
            "ranked": (job.get("ranked") or [])[:20],
            "failed": [r for r in job.get("results", []) if not r.get("ok")],
        }

    @app.post("/api/v1/optimize")
    async def optimize(req: OptimizeRequest):
        """对单插件做网格搜索；apply_best=true 时把最优参数写入 Config"""
        result = await runner.optimize_and_optionally_apply(
            plugin_name=req.plugin_name,
            param_grid=req.param_grid,
            symbol=req.symbol,
            mode=req.mode,
            days=req.days,
            metric=req.metric,
            apply_best=req.apply_best,
        )
        return result

    @app.get("/api/v1/jobs/{job_id}")
    async def get_job(job_id: str):
        job = runner.get_job(job_id)
        if not job:
            raise HTTPException(404, "job not found")
        return job

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
