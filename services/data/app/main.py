"""
Data Service —— 行情采集、历史数据、数据集生成入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

import sys
from pathlib import Path

# 让本地开发能找到 libs/common（生产用包安装或 PYTHONPATH）
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler


class Settings(BaseServiceSettings):
    service_name: str = "data-service"
    port: int = 8001
    # Data Service 特有配置
    binance_base_url: str = "https://data-api.binance.vision"
    default_symbol: str = "BTCUSDT"
    kline_interval: str = "5m"
    data_dir: str = "./data"


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    # TODO: 启动定时采集任务、检查数据目录等
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Data Service",
        description="加密行情采集、历史数据查询、训练数据集生成",
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

    # ---------- 业务 API（骨架） ----------
    @app.get("/")
    async def root():
        return {
            "service": settings.service_name,
            "status": "running",
            "docs": "/docs",
            "endpoints": [
                "/api/v1/klines",
                "/api/v1/latest_price",
                "/health",
            ],
        }

    @app.get("/api/v1/latest_price")
    async def latest_price(symbol: str = Query(settings.default_symbol)):
        """获取最新价格（骨架，后续接真实 Binance 接口）"""
        # TODO: 调用 Binance API
        return {
            "symbol": symbol,
            "price": None,
            "message": "骨架接口，待实现真实拉取逻辑",
        }

    @app.get("/api/v1/klines")
    async def get_klines(
        symbol: str = Query(settings.default_symbol),
        interval: str = Query(settings.kline_interval),
        limit: int = Query(100, ge=1, le=1000),
    ):
        """获取 K 线数据（骨架）"""
        return {
            "symbol": symbol,
            "interval": interval,
            "limit": limit,
            "data": [],
            "message": "骨架接口，待实现真实拉取与缓存逻辑",
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
