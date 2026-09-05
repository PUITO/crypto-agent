"""
Ops Service —— 微服务生命周期管理 + 日志查询。
本地开发主入口：一键启动/停止/重启各服务，供前端健康界面调用。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler

from core.supervisor import Supervisor, SERVICE_SPECS


class Settings(BaseServiceSettings):
    service_name: str = "ops-service"
    port: int = 8008


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

supervisor = Supervisor(repo_root=ROOT)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Ops Service",
        description="微服务生命周期管理与日志查询（本地开发运维入口）",
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
        return {
            "service": settings.service_name,
            "version": "0.1.0",
            "docs": "/docs",
            "managed_services": [s.name for s in SERVICE_SPECS],
        }

    @app.get("/api/v1/services")
    async def list_services():
        return {"services": await supervisor.status_all()}

    @app.post("/api/v1/services/{name}/start")
    async def start_service(name: str):
        return supervisor.start(name)

    @app.post("/api/v1/services/{name}/stop")
    async def stop_service(name: str):
        return supervisor.stop(name)

    @app.post("/api/v1/services/{name}/restart")
    async def restart_service(name: str):
        return supervisor.restart(name)

    @app.post("/api/v1/services/start_all")
    async def start_all():
        return {"results": supervisor.start_all()}

    @app.post("/api/v1/services/stop_all")
    async def stop_all():
        return {"results": supervisor.stop_all()}

    @app.get("/api/v1/logs/{name}")
    async def get_logs(name: str, lines: int = Query(200, ge=10, le=2000)):
        return supervisor.logs(name, lines=lines)

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
