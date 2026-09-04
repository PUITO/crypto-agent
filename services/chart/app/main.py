"""
Chart Service —— 图表绘图指令管理入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import List, Dict, Any, Optional
from uuid import uuid4

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
    service_name: str = "chart-service"
    port: int = 8005


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

# 内存绘图对象存储（骨架）
_drawings: Dict[str, Dict[str, Any]] = {}


class DrawingCommand(BaseModel):
    action: str = Field(..., description="draw_fibonacci / draw_support_resistance / draw_trendline / clear / ...")
    name: Optional[str] = None
    params: Dict[str, Any] = Field(default_factory=dict)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Chart Service",
        description="接收绘图指令，管理斐波那契、压力位、趋势线等对象",
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

    @app.get("/api/v1/drawings")
    async def list_drawings():
        return {"drawings": list(_drawings.values())}

    @app.post("/api/v1/drawings")
    async def create_drawing(cmd: DrawingCommand):
        drawing_id = str(uuid4())
        obj = {
            "id": drawing_id,
            "action": cmd.action,
            "name": cmd.name or cmd.action,
            "params": cmd.params,
            "status": "active",
        }
        _drawings[drawing_id] = obj
        logger.info(f"Drawing created: {obj}")
        # TODO: 通过 WebSocket / 事件推送给前端
        return {"success": True, "drawing": obj}

    @app.delete("/api/v1/drawings/{drawing_id}")
    async def delete_drawing(drawing_id: str):
        if drawing_id not in _drawings:
            raise AppException("绘图对象不存在", code="DRAWING_NOT_FOUND", status_code=404)
        del _drawings[drawing_id]
        return {"success": True}

    @app.delete("/api/v1/drawings")
    async def clear_all_drawings():
        _drawings.clear()
        return {"success": True, "message": "已清除全部绘图"}

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
