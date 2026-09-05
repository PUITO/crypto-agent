"""
Data Service —— 真实 Binance K 线采集 + 本地 Parquet 存储 + 定时任务 + Hugging Face 数据集。
可独立启动与测试。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime
from typing import Optional

from fastapi import FastAPI, Query, BackgroundTasks, Body
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

from core.binance_client import BinanceClient
from core.storage import ParquetStorage
from core.scheduler import KlineScheduler
from core.dataset_builder import DatasetBuilder


class Settings(BaseServiceSettings):
    service_name: str = "data-service"
    port: int = 8001

    # Binance
    binance_base_url: str = "https://data-api.binance.vision"
    default_symbol: str = "BTCUSDT"
    kline_interval: str = "5m"

    # 存储
    data_dir: str = "./data"

    # 定时任务
    enable_scheduler: bool = True
    fetch_every_seconds: int = 300          # 5 分钟
    history_days_on_first_run: int = 14     # 首次启动回填天数
    symbols: str = "BTCUSDT"                # 逗号分隔

    # Hugging Face
    hf_repo_id: Optional[str] = None        # 例如 "PUITO/crypto-btc-5m"
    hf_token: Optional[str] = None



class FetchRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    days: int = Field(7, ge=1, le=365, description="回填最近多少天")
    force_full: bool = False


class DatasetBuildRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    horizon: int = Field(6, description="未来看几根 K 线做标签（5m×6≈30分钟）")
    push_to_hf: bool = False
    hf_repo_id: Optional[str] = None
    private: bool = False


class HFPushRequest(BaseModel):
    symbol: str = "BTCUSDT"
    interval: str = "5m"
    horizon: int = 6
    repo_id: Optional[str] = None
    private: bool = False


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

# 全局实例
storage = ParquetStorage(base_dir=settings.data_dir)
scheduler: Optional[KlineScheduler] = None
dataset_builder = DatasetBuilder(
    storage=storage,
    output_dir=Path(settings.data_dir) / "datasets",
    hf_repo_id=settings.hf_repo_id,
    hf_token=settings.hf_token,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global scheduler
    logger.info(f"{settings.service_name} starting on port {settings.port}")

    Path(settings.data_dir).mkdir(parents=True, exist_ok=True)

    if settings.enable_scheduler:
        symbols = [s.strip().upper() for s in settings.symbols.split(",") if s.strip()]
        scheduler = KlineScheduler(
            storage=storage,
            symbols=symbols,
            interval=settings.kline_interval,
            fetch_every_seconds=settings.fetch_every_seconds,
            history_days_on_first_run=settings.history_days_on_first_run,
        )
        await scheduler.start()
        logger.info("Background kline scheduler started")

    yield

    if scheduler:
        await scheduler.stop()
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Data Service",
        description="加密行情采集（Binance）、本地 Parquet 存储、定时增量更新、训练数据集生成与 Hugging Face 上传",
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
            "status": "running",
            "docs": "/docs",
            "endpoints": {
                "latest_price": "/api/v1/latest_price",
                "klines": "/api/v1/klines",
                "local_data": "/api/v1/local/list",
                "trigger_fetch": "POST /api/v1/fetch",
                "scheduler_status": "/api/v1/scheduler/status",
                "build_dataset": "POST /api/v1/dataset/build",
                "push_hf": "POST /api/v1/dataset/push_hf",
            },
        }

    @app.get("/api/v1/latest_price")
    async def latest_price(symbol: str = Query(settings.default_symbol)):
        """获取最新成交价（直接请求 Binance）"""
        async with BinanceClient(base_url=settings.binance_base_url) as client:
            return await client.get_latest_price(symbol)

    @app.get("/api/v1/klines")
    async def get_klines(
        symbol: str = Query(settings.default_symbol),
        interval: str = Query(settings.kline_interval),
        limit: int = Query(500, ge=1, le=5000),
        source: str = Query("local", description="local | binance"),
    ):
        """获取 K 线。source=local 优先读本地 Parquet；source=binance 直接拉最新。"""
        if source == "local":
            df = storage.load_klines(symbol, interval)
            if df.empty:
                async with BinanceClient(base_url=settings.binance_base_url) as client:
                    df = await client.get_klines(symbol=symbol, interval=interval, limit=min(limit, 1000))
            else:
                df = df.tail(limit)
        else:
            async with BinanceClient(base_url=settings.binance_base_url) as client:
                df = await client.get_klines(symbol=symbol, interval=interval, limit=min(limit, 1000))

        records = []
        for _, row in df.iterrows():
            records.append({
                "open_time": row["open_time"].isoformat() if hasattr(row["open_time"], "isoformat") else str(row["open_time"]),
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
                "volume": float(row["volume"]),
            })

        return {
            "symbol": symbol.upper(),
            "interval": interval,
            "source": source,
            "count": len(records),
            "data": records,
        }

    @app.get("/api/v1/local/list")
    async def list_local_data():
        """列出本地已存储的 symbol / interval 及时间范围"""
        return {"items": storage.list_available()}

    @app.post("/api/v1/fetch")
    async def trigger_fetch(req: FetchRequest = Body(...), background_tasks: BackgroundTasks = None):
        """手动触发一次采集（后台执行）。默认增量；force_full=true 时按 days 回填。"""
        async def _job():
            async with BinanceClient(base_url=settings.binance_base_url) as client:
                if req.force_full or storage.get_latest_open_time(req.symbol, req.interval) is None:
                    from datetime import timedelta, timezone
                    start = datetime.now(timezone.utc) - timedelta(days=req.days)
                    df = await client.fetch_historical(
                        symbol=req.symbol,
                        interval=req.interval,
                        start_time=start,
                    )
                else:
                    from datetime import timedelta
                    latest = storage.get_latest_open_time(req.symbol, req.interval)
                    start = latest - timedelta(minutes=10)
                    df = await client.fetch_historical(
                        symbol=req.symbol,
                        interval=req.interval,
                        start_time=start,
                    )
                added = storage.save_klines(df, req.symbol, req.interval)
                logger.info(f"Manual fetch done: {req.symbol} +{added}")

        background_tasks.add_task(_job)
        return {"message": "fetch job submitted", "symbol": req.symbol, "interval": req.interval}

    @app.get("/api/v1/scheduler/status")
    async def scheduler_status():
        if scheduler is None:
            return {"enabled": False, "message": "scheduler not started"}
        return {"enabled": True, **scheduler.status}

    @app.post("/api/v1/scheduler/run_once")
    async def scheduler_run_once():
        """立即执行一轮定时采集"""
        if scheduler is None:
            return {"ok": False, "message": "scheduler not started"}
        result = await scheduler.run_once()
        return {"ok": True, "result": result}

    @app.post("/api/v1/dataset/build")
    async def build_dataset(req: DatasetBuildRequest = Body(...)):
        """从本地 K 线构建带特征 + 标签的数据集，可选推送到 Hugging Face。"""
        if req.push_to_hf:
            result = dataset_builder.build_and_push(
                symbol=req.symbol,
                interval=req.interval,
                horizon=req.horizon,
                repo_id=req.hf_repo_id or settings.hf_repo_id,
                private=req.private,
            )
        else:
            df = dataset_builder.build(
                symbol=req.symbol,
                interval=req.interval,
                horizon=req.horizon,
            )
            if df.empty:
                return {"ok": False, "message": "no data, please fetch klines first"}
            ts = datetime.utcnow().strftime("%Y%m%d_%H%M")
            name = f"{req.symbol.lower()}_{req.interval}_h{req.horizon}_{ts}"
            path = dataset_builder.save_local(df, name)
            result = {
                "ok": True,
                "local_path": str(path),
                "rows": len(df),
                "columns": list(df.columns),
            }
        return result

    @app.post("/api/v1/dataset/push_hf")
    async def push_dataset_to_hf(req: HFPushRequest = Body(...)):
        """构建数据集并推送到 Hugging Face Hub"""
        result = dataset_builder.build_and_push(
            symbol=req.symbol,
            interval=req.interval,
            horizon=req.horizon,
            repo_id=req.repo_id or settings.hf_repo_id,
            private=req.private,
        )
        return result

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
