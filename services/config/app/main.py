"""
Config Service —— 统一配置中心入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

from fastapi import FastAPI, Body
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler


class Settings(BaseServiceSettings):
    service_name: str = "config-service"
    port: int = 8002


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

# 简单内存配置存储（骨架，后续可换 Redis / DB）
_config_store: Dict[str, Any] = {
    "mode": "event_30m",          # perpetual / event_30m / both
    "symbol": "BTCUSDT",
    "rsi_oversold": 30,
    "rsi_overbought": 85,
    "enabled_plugins": ["kdj_rsi_event"],
}


class ConfigUpdate(BaseModel):
    key: str
    value: Any
    confirm: bool = False  # Chat 修改时需要确认


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Config Service",
        description="统一配置中心，支持手动界面与 Chat 双通道修改",
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

    @app.get("/api/v1/config")
    async def get_all_config():
        return {"config": _config_store}

    @app.get("/api/v1/config/{key}")
    async def get_config(key: str):
        if key not in _config_store:
            raise AppException(f"配置项 {key} 不存在", code="CONFIG_NOT_FOUND", status_code=404)
        return {"key": key, "value": _config_store[key]}

    @app.put("/api/v1/config")
    async def update_config(body: ConfigUpdate):
        """更新配置。Chat 调用时应先 preview 再带 confirm=True。"""
        old_value = _config_store.get(body.key)
        if not body.confirm:
            return {
                "preview": True,
                "key": body.key,
                "old_value": old_value,
                "new_value": body.value,
                "message": "请确认后再次调用并设置 confirm=true",
            }
        _config_store[body.key] = body.value
        logger.info(f"Config updated: {body.key} = {body.value} (was {old_value})")
        # TODO: 发布 ConfigChangedEvent
        return {
            "success": True,
            "key": body.key,
            "old_value": old_value,
            "new_value": body.value,
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
