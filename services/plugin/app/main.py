"""
Plugin Service —— 策略/模型热插拔管理入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import List, Dict, Any

from fastapi import FastAPI
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
    service_name: str = "plugin-service"
    port: int = 8003
    plugins_dir: str = "./plugins"


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

# 骨架：内存中的插件注册表
_plugins: Dict[str, Dict[str, Any]] = {
    "kdj_rsi_event": {
        "name": "kdj_rsi_event",
        "version": "1.0.0",
        "modes": ["event_30m"],
        "description": "KDJ+RSI 事件合约策略（参考现有技能）",
        "status": "loaded",
    }
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    # TODO: 扫描 plugins_dir 并热加载
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Plugin Service",
        description="策略与训练模型热插拔管理",
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

    @app.get("/api/v1/plugins")
    async def list_plugins():
        return {"plugins": list(_plugins.values())}

    @app.get("/api/v1/plugins/{name}")
    async def get_plugin(name: str):
        if name not in _plugins:
            raise AppException(f"插件 {name} 未找到", code="PLUGIN_NOT_FOUND", status_code=404)
        return _plugins[name]

    @app.post("/api/v1/plugins/{name}/load")
    async def load_plugin(name: str):
        # TODO: 真实热加载逻辑
        if name in _plugins:
            _plugins[name]["status"] = "loaded"
            return {"success": True, "message": f"插件 {name} 已加载"}
        raise AppException(f"插件 {name} 不存在", code="PLUGIN_NOT_FOUND", status_code=404)

    @app.post("/api/v1/plugins/{name}/unload")
    async def unload_plugin(name: str):
        if name in _plugins:
            _plugins[name]["status"] = "unloaded"
            return {"success": True, "message": f"插件 {name} 已卸载"}
        raise AppException(f"插件 {name} 不存在", code="PLUGIN_NOT_FOUND", status_code=404)

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
