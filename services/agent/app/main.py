"""
Agent Orchestrator —— LLM 大脑 + 对话入口。
对接 Config / Data / Plugin / Backtest，支持 Ollama 与云端 API。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, List, Optional
from uuid import uuid4

from fastapi import FastAPI
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

from core.llm import LLMClient
from core.tools import ToolRegistry
from core.agent import AgentOrchestrator


class Settings(BaseServiceSettings):
    service_name: str = "agent-service"
    port: int = 8006

    # LLM
    llm_provider: str = "ollama"           # ollama | openai
    llm_model: str = "qwen2.5:14b"
    # ollama 默认；openai 兼容时改 base_url + api_key
    # llm_base_url 默认走 settings.ollama_base_url / openai_base_url


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

orchestrator: Optional[AgentOrchestrator] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global orchestrator
    logger.info(f"{settings.service_name} starting")

    if settings.llm_provider == "ollama":
        base = settings.ollama_base_url
        key = None
    else:
        base = settings.openai_base_url or "https://api.openai.com/v1"
        key = settings.openai_api_key

    llm = LLMClient(
        provider=settings.llm_provider,
        model=settings.llm_model,
        base_url=base,
        api_key=key,
    )
    tools = ToolRegistry(
        data_url=settings.data_service_url,
        config_url=settings.config_service_url,
        plugin_url=settings.plugin_service_url,
        backtest_url=settings.backtest_service_url,
    )
    orchestrator = AgentOrchestrator(llm=llm, tools=tools)
    logger.info(f"Agent ready: provider={settings.llm_provider} model={settings.llm_model}")
    yield
    logger.info(f"{settings.service_name} shutting down")


class ChatMessage(BaseModel):
    role: str = "user"
    content: str


class ChatRequest(BaseModel):
    message: str = Field(..., description="用户本轮输入")
    session_id: Optional[str] = None
    messages: Optional[List[ChatMessage]] = None  # 兼容完整历史传入


def create_app() -> FastAPI:
    app = FastAPI(
        title="Agent Orchestrator",
        description="LLM 大脑：对话、工具调用、配置确认、回测与信号",
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
            "llm_provider": settings.llm_provider,
            "llm_model": settings.llm_model,
            "docs": "/docs",
            "endpoints": ["POST /api/v1/chat", "GET /api/v1/tools"],
        }

    @app.get("/api/v1/tools")
    async def list_tools():
        assert orchestrator is not None
        return {"tools": orchestrator.tools.schemas()}

    @app.post("/api/v1/chat")
    async def chat(req: ChatRequest) -> dict[str, Any]:
        assert orchestrator is not None
        session_id = req.session_id or str(uuid4())
        # 若带完整 messages，只取最后一条 user 作为本轮（历史已在 session 内）
        text = req.message
        if not text and req.messages:
            for m in reversed(req.messages):
                if m.role == "user":
                    text = m.content
                    break
        if not text:
            return {"session_id": session_id, "reply": "请输入内容。", "tool_trace": []}

        result = await orchestrator.chat(session_id, text)
        return result

    @app.delete("/api/v1/session/{session_id}")
    async def clear_session(session_id: str):
        assert orchestrator is not None
        if session_id in orchestrator.sessions:
            del orchestrator.sessions[session_id]
        return {"ok": True}

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
