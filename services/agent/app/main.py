"""
Agent Orchestrator —— LLM 大脑 + 对话入口。
可独立启动与测试。
"""

from contextlib import asynccontextmanager
from typing import Optional, List, Dict, Any

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
    service_name: str = "agent-service"
    port: int = 8006


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


class ChatMessage(BaseModel):
    role: str = "user"
    content: str


class ChatRequest(BaseModel):
    messages: List[ChatMessage]
    session_id: Optional[str] = None
    stream: bool = False


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    # TODO: 初始化 LangGraph / LLM 客户端
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Agent Orchestrator",
        description="LLM 大脑，负责对话理解、Tool 调用、配置确认、绘图指令等",
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

    @app.post("/api/v1/chat")
    async def chat(req: ChatRequest):
        """对话入口（骨架）。后续接入 LangGraph + Tools。"""
        last_msg = req.messages[-1].content if req.messages else ""
        logger.info(f"Chat received: {last_msg[:100]}...")
        return {
            "session_id": req.session_id or "demo-session",
            "reply": f"[骨架回复] 收到：{last_msg}。后续将在这里调用 Config / Chart / Backtest 等服务。",
            "tools_called": [],
            "need_confirm": False,
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
