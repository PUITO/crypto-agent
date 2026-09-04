"""
Multi-Agent Manager —— 多分身矩阵调度入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import List, Dict, Any, Optional

from fastapi import FastAPI, Body
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


class Settings(BaseServiceSettings):
    service_name: str = "multi-agent-service"
    port: int = 8007


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


class MatrixJob(BaseModel):
    plugins: List[str] = Field(default_factory=lambda: ["kdj_rsi_event"])
    param_sets: List[Dict[str, Any]] = Field(default_factory=list)
    days: int = 14
    mode: str = "event_30m"


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Multi-Agent Manager",
        description="多分身并行回测与结果对比",
        version="0.1.0",
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
        return {"service": settings.service_name, "status": "running", "docs": "/docs"}

    @app.post("/api/v1/matrix")
    async def run_matrix(job: MatrixJob):
        """启动矩阵测试（骨架）。"""
        logger.info(f"Matrix job: plugins={job.plugins}, sets={len(job.param_sets)}")
        return {
            "success": True,
            "job_id": "demo-job-001",
            "message": "矩阵任务已提交（骨架），后续将并行调用 Backtest Service",
            "status": "pending",
        }

    @app.get("/api/v1/matrix/{job_id}")
    async def get_matrix_result(job_id: str):
        return {
            "job_id": job_id,
            "status": "completed",
            "results": [],
            "message": "骨架接口",
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
