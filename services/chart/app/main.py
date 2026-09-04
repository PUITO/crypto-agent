"""
Chart Service —— 图表绘图对象管理。
供前端渲染与 Agent Chat 调用（斐波那契、支撑压力、趋势线、标注、信号标记）。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, List, Optional

from fastapi import FastAPI, HTTPException, Query
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

from core.drawings import DrawingStore, DEFAULT_FIB_LEVELS


class Settings(BaseServiceSettings):
    service_name: str = "chart-service"
    port: int = 8005


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

store = DrawingStore()


# ---------- Request models ----------

class FibRequest(BaseModel):
    symbol: str = "BTCUSDT"
    time_from: str
    price_from: float
    time_to: str
    price_to: float
    levels: Optional[List[float]] = None
    name: Optional[str] = None
    session_id: Optional[str] = None
    color: str = "#2196F3"


class HorizontalRequest(BaseModel):
    symbol: str = "BTCUSDT"
    price: float
    kind: str = Field("support", description="support | resistance | horizontal")
    name: Optional[str] = None
    session_id: Optional[str] = None
    color: Optional[str] = None


class TrendlineRequest(BaseModel):
    symbol: str = "BTCUSDT"
    time_from: str
    price_from: float
    time_to: str
    price_to: float
    name: Optional[str] = None
    session_id: Optional[str] = None
    color: str = "#FF9800"


class AnnotationRequest(BaseModel):
    symbol: str = "BTCUSDT"
    time: str
    price: float
    text: str
    session_id: Optional[str] = None
    color: str = "#9C27B0"


class MarkerRequest(BaseModel):
    symbol: str = "BTCUSDT"
    time: str
    price: float
    direction: str = Field(..., description="long | short")
    session_id: Optional[str] = None


class GenericCommand(BaseModel):
    """通用指令，方便 Agent 一次调用"""
    action: str = Field(
        ...,
        description="draw_fibonacci | draw_horizontal | draw_trendline | draw_annotation | draw_marker | clear",
    )
    symbol: str = "BTCUSDT"
    session_id: Optional[str] = None
    name: Optional[str] = None
    # fib / trendline
    time_from: Optional[str] = None
    price_from: Optional[float] = None
    time_to: Optional[str] = None
    price_to: Optional[float] = None
    levels: Optional[List[float]] = None
    # horizontal / annotation / marker
    price: Optional[float] = None
    time: Optional[str] = None
    text: Optional[str] = None
    kind: Optional[str] = None
    direction: Optional[str] = None
    color: Optional[str] = None
    # clear filters
    type: Optional[str] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Chart Service",
        description="图表绘图对象：斐波那契、支撑压力、趋势线、标注、信号标记",
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
            "docs": "/docs",
            "drawing_count": len(store.list()),
        }

    # ---------- 列表 / 查询 ----------
    @app.get("/api/v1/drawings")
    async def list_drawings(
        symbol: Optional[str] = None,
        session_id: Optional[str] = None,
        type: Optional[str] = None,
    ):
        return {"drawings": store.list(symbol=symbol, session_id=session_id, type=type)}

    @app.get("/api/v1/drawings/{drawing_id}")
    async def get_drawing(drawing_id: str):
        item = store.get(drawing_id)
        if not item:
            raise HTTPException(404, "drawing not found")
        return item

    # ---------- 专用创建接口 ----------
    @app.post("/api/v1/drawings/fibonacci")
    async def draw_fibonacci(req: FibRequest):
        return store.create_fibonacci(
            symbol=req.symbol,
            time_from=req.time_from,
            price_from=req.price_from,
            time_to=req.time_to,
            price_to=req.price_to,
            levels=req.levels,
            name=req.name,
            session_id=req.session_id,
            color=req.color,
        )

    @app.post("/api/v1/drawings/horizontal")
    async def draw_horizontal(req: HorizontalRequest):
        return store.create_horizontal(
            symbol=req.symbol,
            price=req.price,
            kind=req.kind,
            name=req.name,
            session_id=req.session_id,
            color=req.color,
        )

    @app.post("/api/v1/drawings/trendline")
    async def draw_trendline(req: TrendlineRequest):
        return store.create_trendline(
            symbol=req.symbol,
            time_from=req.time_from,
            price_from=req.price_from,
            time_to=req.time_to,
            price_to=req.price_to,
            name=req.name,
            session_id=req.session_id,
            color=req.color,
        )

    @app.post("/api/v1/drawings/annotation")
    async def draw_annotation(req: AnnotationRequest):
        return store.create_annotation(
            symbol=req.symbol,
            time=req.time,
            price=req.price,
            text=req.text,
            session_id=req.session_id,
            color=req.color,
        )

    @app.post("/api/v1/drawings/marker")
    async def draw_marker(req: MarkerRequest):
        return store.create_signal_marker(
            symbol=req.symbol,
            time=req.time,
            price=req.price,
            direction=req.direction,
            session_id=req.session_id,
        )

    # ---------- 通用指令（Agent 友好） ----------
    @app.post("/api/v1/command")
    async def execute_command(cmd: GenericCommand) -> dict[str, Any]:
        action = cmd.action.lower().strip()

        if action in ("draw_fibonacci", "fibonacci", "fib"):
            if None in (cmd.time_from, cmd.price_from, cmd.time_to, cmd.price_to):
                raise HTTPException(400, "fibonacci requires time_from, price_from, time_to, price_to")
            obj = store.create_fibonacci(
                symbol=cmd.symbol,
                time_from=cmd.time_from,
                price_from=cmd.price_from,
                time_to=cmd.time_to,
                price_to=cmd.price_to,
                levels=cmd.levels,
                name=cmd.name,
                session_id=cmd.session_id,
                color=cmd.color or "#2196F3",
            )
            return {"ok": True, "drawing": obj}

        if action in ("draw_horizontal", "horizontal", "support", "resistance"):
            if cmd.price is None:
                raise HTTPException(400, "horizontal requires price")
            kind = cmd.kind or ("support" if action == "support" else "resistance" if action == "resistance" else "horizontal")
            obj = store.create_horizontal(
                symbol=cmd.symbol,
                price=cmd.price,
                kind=kind,
                name=cmd.name,
                session_id=cmd.session_id,
                color=cmd.color,
            )
            return {"ok": True, "drawing": obj}

        if action in ("draw_trendline", "trendline"):
            if None in (cmd.time_from, cmd.price_from, cmd.time_to, cmd.price_to):
                raise HTTPException(400, "trendline requires time_from/price_from/time_to/price_to")
            obj = store.create_trendline(
                symbol=cmd.symbol,
                time_from=cmd.time_from,
                price_from=cmd.price_from,
                time_to=cmd.time_to,
                price_to=cmd.price_to,
                name=cmd.name,
                session_id=cmd.session_id,
                color=cmd.color or "#FF9800",
            )
            return {"ok": True, "drawing": obj}

        if action in ("draw_annotation", "annotation", "text"):
            if None in (cmd.time, cmd.price, cmd.text):
                raise HTTPException(400, "annotation requires time, price, text")
            obj = store.create_annotation(
                symbol=cmd.symbol,
                time=cmd.time,
                price=cmd.price,
                text=cmd.text or "",
                session_id=cmd.session_id,
                color=cmd.color or "#9C27B0",
            )
            return {"ok": True, "drawing": obj}

        if action in ("draw_marker", "marker", "signal"):
            if None in (cmd.time, cmd.price, cmd.direction):
                raise HTTPException(400, "marker requires time, price, direction")
            obj = store.create_signal_marker(
                symbol=cmd.symbol,
                time=cmd.time,
                price=cmd.price,
                direction=cmd.direction or "long",
                session_id=cmd.session_id,
            )
            return {"ok": True, "drawing": obj}

        if action in ("clear", "clear_drawings"):
            n = store.clear(symbol=cmd.symbol if cmd.symbol != "BTCUSDT" or cmd.type else None, session_id=cmd.session_id, type=cmd.type)
            # 若只想清某 symbol
            if cmd.symbol:
                n = store.clear(symbol=cmd.symbol, session_id=cmd.session_id, type=cmd.type)
            return {"ok": True, "cleared": n}

        raise HTTPException(400, f"unknown action: {cmd.action}")

    # ---------- 删除 ----------
    @app.delete("/api/v1/drawings/{drawing_id}")
    async def delete_drawing(drawing_id: str):
        if not store.delete(drawing_id):
            raise HTTPException(404, "drawing not found")
        return {"ok": True}

    @app.delete("/api/v1/drawings")
    async def clear_drawings(
        symbol: Optional[str] = None,
        session_id: Optional[str] = None,
        type: Optional[str] = None,
    ):
        n = store.clear(symbol=symbol, session_id=session_id, type=type)
        return {"ok": True, "cleared": n}

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
