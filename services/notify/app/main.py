"""
Notify Service —— 通用通知：Webhook / Telegram / EmailJS。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, List, Optional

from fastapi import FastAPI, HTTPException
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

from core.channels import (
    send_webhook,
    send_telegram,
    send_emailjs,
    channels_from_env,
)


class Settings(BaseServiceSettings):
    service_name: str = "notify-service"
    port: int = 8011


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


class NotifyRequest(BaseModel):
    title: str = "Crypto Agent"
    message: str
    channels: List[str] = Field(
        default_factory=lambda: ["webhook"],
        description="webhook | telegram | emailjs",
    )
    # 可选覆盖默认渠道配置
    webhook_url: Optional[str] = None
    telegram_bot_token: Optional[str] = None
    telegram_chat_id: Optional[str] = None
    emailjs_service_id: Optional[str] = None
    emailjs_template_id: Optional[str] = None
    emailjs_public_key: Optional[str] = None
    emailjs_private_key: Optional[str] = None
    email_to: Optional[str] = None
    email_params: Optional[dict[str, Any]] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting")
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="Notify Service",
        description="Webhook / Telegram / EmailJS 通知",
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
        env = channels_from_env()
        return {
            "service": settings.service_name,
            "channels_configured": {
                "webhook": bool(env["webhook_url"]),
                "telegram": bool(env["telegram_bot_token"] and env["telegram_chat_id"]),
                "emailjs": bool(env["emailjs_service_id"] and env["emailjs_public_key"]),
            },
            "docs": "/docs",
        }

    @app.get("/api/v1/channels")
    async def list_channels():
        env = channels_from_env()
        return {
            "webhook": {"configured": bool(env["webhook_url"])},
            "telegram": {
                "configured": bool(env["telegram_bot_token"] and env["telegram_chat_id"]),
            },
            "emailjs": {
                "configured": bool(
                    env["emailjs_service_id"]
                    and env["emailjs_template_id"]
                    and env["emailjs_public_key"]
                ),
            },
        }

    @app.post("/api/v1/notify")
    async def notify(req: NotifyRequest):
        env = channels_from_env()
        results = []
        errors = []
        for ch in req.channels:
            ch = ch.lower().strip()
            try:
                if ch == "webhook":
                    url = req.webhook_url or env["webhook_url"]
                    if not url:
                        raise RuntimeError("webhook_url 未配置")
                    results.append(send_webhook(url, req.title, req.message))
                elif ch in ("telegram", "tg"):
                    token = req.telegram_bot_token or env["telegram_bot_token"]
                    chat = req.telegram_chat_id or env["telegram_chat_id"]
                    if not token or not chat:
                        raise RuntimeError("Telegram bot_token/chat_id 未配置")
                    results.append(send_telegram(token, chat, req.title, req.message))
                elif ch == "emailjs":
                    sid = req.emailjs_service_id or env["emailjs_service_id"]
                    tid = req.emailjs_template_id or env["emailjs_template_id"]
                    pk = req.emailjs_public_key or env["emailjs_public_key"]
                    priv = req.emailjs_private_key or env["emailjs_private_key"] or None
                    if not sid or not tid or not pk:
                        raise RuntimeError("EmailJS 参数未配置")
                    params = dict(req.email_params or {})
                    params.setdefault("title", req.title)
                    params.setdefault("message", req.message)
                    if req.email_to:
                        params.setdefault("to_email", req.email_to)
                    results.append(send_emailjs(sid, tid, pk, params, priv))
                else:
                    raise RuntimeError(f"未知渠道: {ch}")
            except Exception as e:
                logger.warning(f"notify {ch} failed: {e}")
                errors.append({"channel": ch, "error": str(e)})
        if not results and errors:
            raise HTTPException(502, {"message": "全部渠道失败", "errors": errors})
        return {"ok": True, "results": results, "errors": errors}

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
