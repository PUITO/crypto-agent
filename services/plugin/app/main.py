"""
Plugin Service —— 策略/模型热插拔管理。
支持扫描加载、列表、获取信息、卸载、重载、生成信号。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Body
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

from core.loader import PluginLoader


class Settings(BaseServiceSettings):
    service_name: str = "plugin-service"
    port: int = 8003
    # 默认指向仓库内的 plugins 目录
    plugins_dir: str = str(Path(__file__).resolve().parents[1] / "plugins")




class SignalRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    limit: int = Field(200, ge=50, le=5000)
    params: dict[str, Any] = Field(default_factory=dict)
    klines: Optional[list[dict]] = None


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

loader = PluginLoader(settings.plugins_dir)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting, plugins_dir={settings.plugins_dir}")
    # 同时加载内置 plugins 目录（服务内）和仓库级 plugins 目录
    builtin = Path(__file__).resolve().parents[1] / "plugins"
    repo_plugins = ROOT / "services" / "plugin" / "plugins"
    for d in [builtin, repo_plugins, Path(settings.plugins_dir)]:
        if d.exists():
            loader.plugins_dir = d
            n = loader.scan_and_load()
            logger.info(f"Scanned {d}: loaded {n} plugins")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Plugin Service",
        description="策略与训练模型热插拔管理",
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
            "plugins_loaded": len(loader.registry),
            "docs": "/docs",
        }

    @app.get("/api/v1/plugins")
    async def list_plugins():
        return {"plugins": loader.list_plugins()}

    @app.get("/api/v1/plugins/{name}")
    async def get_plugin(name: str):
        plugin = loader.get(name)
        if not plugin:
            raise HTTPException(404, f"Plugin '{name}' not found")
        return plugin.info()

    @app.post("/api/v1/plugins/{name}/reload")
    async def reload_plugin(name: str):
        ok = loader.reload(name)
        if not ok:
            raise HTTPException(404, f"Plugin '{name}' not found or reload failed")
        return {"ok": True, "plugin": loader.get(name).info()}

    @app.delete("/api/v1/plugins/{name}")
    async def unload_plugin(name: str):
        ok = loader.unload(name)
        if not ok:
            raise HTTPException(404, f"Plugin '{name}' not found")
        return {"ok": True, "message": f"unloaded {name}"}

    @app.post("/api/v1/plugins/scan")
    async def rescan_plugins():
        n = loader.scan_and_load()
        return {"loaded": n, "plugins": loader.list_plugins()}

    @app.post("/api/v1/plugins/{name}/signals")
    async def generate_signals(name: str, req: SignalRequest = Body(...)):
        """用指定插件生成信号。可传入 klines，或内部从 Data Service 拉取。"""
        plugin = loader.get(name)
        if not plugin:
            raise HTTPException(404, f"Plugin '{name}' not found")

        import pandas as pd

        if req.klines:
            df = pd.DataFrame(req.klines)
            if "open_time" in df.columns:
                df["open_time"] = pd.to_datetime(df["open_time"], utc=True)
        else:
            # 从 Data Service 拉
            import httpx
            data_url = settings.data_service_url.rstrip("/") + "/api/v1/klines"
            async with httpx.AsyncClient(timeout=30) as client:
                r = await client.get(data_url, params={
                    "symbol": req.symbol,
                    "interval": req.interval,
                    "limit": req.limit,
                    "source": "local",
                })
                r.raise_for_status()
                payload = r.json()
            df = pd.DataFrame(payload.get("data", []))
            if df.empty:
                raise HTTPException(400, "No kline data available. Fetch data first via Data Service.")
            df["open_time"] = pd.to_datetime(df["open_time"], utc=True)

        signals = plugin.generate_signals(df, params=req.params or None)
        return {
            "plugin": name,
            "symbol": req.symbol,
            "interval": req.interval,
            "params": {**plugin.default_params(), **(req.params or {})},
            "count": len(signals),
            "signals": [s.to_dict() for s in signals],
        }

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
