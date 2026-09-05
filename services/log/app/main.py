"""
Log Service —— 集中日志采集与查询。
其它微服务通过 common.logging 的 RemoteLogHandler 上报，前端/Ops 统一查询。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, List, Optional

from fastapi import FastAPI, Query
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

from core.store import LogStore


class Settings(BaseServiceSettings):
    service_name: str = "log-service"
    port: int = 8009


settings = Settings()
# Log service 自身默认不上报自己，避免循环
settings.log_remote_enabled = False
setup_logging(settings)
logger = get_logger(settings.service_name)

store = LogStore()


class LogEntryIn(BaseModel):
    service: str = "unknown"
    level: str = "INFO"
    message: str
    logger: str = ""
    ts: Optional[str] = None
    extra: dict[str, Any] = Field(default_factory=dict)


class LogBatchIn(BaseModel):
    entries: List[LogEntryIn]


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Log Service",
        description="集中日志采集、存储与查询",
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
            "endpoints": [
                "POST /api/v1/logs",
                "POST /api/v1/logs/batch",
                "GET  /api/v1/logs",
                "GET  /api/v1/logs/{service}/tail",
                "GET  /api/v1/services",
                "GET  /api/v1/stats",
            ],
        }

    @app.post("/api/v1/logs")
    async def ingest_one(entry: LogEntryIn):
        n = store.ingest(entry.model_dump())
        return {"ok": True, "ingested": n}

    @app.post("/api/v1/logs/batch")
    async def ingest_batch(body: LogBatchIn):
        n = store.ingest([e.model_dump() for e in body.entries])
        return {"ok": True, "ingested": n}

    @app.get("/api/v1/logs")
    async def query_logs(
        service: Optional[str] = None,
        level: Optional[str] = None,
        contains: Optional[str] = None,
        limit: int = Query(200, ge=1, le=2000),
    ):
        return {
            "logs": store.query(service=service, level=level, contains=contains, limit=limit),
            "count": None,
        }

    @app.get("/api/v1/logs/{service}/tail")
    async def tail_service(service: str, lines: int = Query(200, ge=1, le=2000)):
        logs = store.tail(service, lines=lines)
        # 兼容 Ops 旧格式：lines 为字符串列表
        return {
            "service": service,
            "logs": logs,
            "lines": [f"{x.get('ts','')} | {x.get('level','')} | {x.get('message','')}" for x in logs],
        }

    @app.get("/api/v1/services")
    async def list_log_services():
        return {"services": store.services()}

    @app.get("/api/v1/stats")
    async def stats():
        return store.stats()

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
