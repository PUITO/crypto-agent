"""
Backtest & Simulator Service —— 回测与模拟交易入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import Optional, Dict, Any

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
    service_name: str = "backtest-service"
    port: int = 8004


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


class BacktestRequest(BaseModel):
    plugin_name: str = "kdj_rsi_event"
    symbol: str = "BTCUSDT"
    mode: str = "event_30m"          # perpetual / event_30m
    start: Optional[str] = None
    end: Optional[str] = None
    days: int = Field(14, ge=1, le=90)
    params: Dict[str, Any] = Field(default_factory=dict)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Backtest & Simulator Service",
        description="历史回测、模拟交易、自动平仓",
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

    @app.post("/api/v1/backtest")
    async def run_backtest(req: BacktestRequest):
        """执行回测（骨架）。"""
        logger.info(f"Backtest request: {req.model_dump()}")
        # TODO: 调用 Data Service 取数 + 加载插件 + 运行 vectorbt / 自研引擎
        return {
            "success": True,
            "plugin": req.plugin_name,
            "mode": req.mode,
            "days": req.days,
            "result": {
                "total_signals": 0,
                "win_rate": None,
                "message": "骨架接口，待实现真实回测逻辑",
            },
        }

    @app.post("/api/v1/simulate")
    async def run_simulate(req: BacktestRequest):
        """启动模拟交易（骨架）。"""
        return {
            "success": True,
            "message": "模拟交易骨架，待实现账户状态机与自动平仓",
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
