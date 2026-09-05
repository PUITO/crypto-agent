"""通知渠道：Webhook / Telegram / EmailJS。"""

from __future__ import annotations

import json
import os
from typing import Any, Optional
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from common.logging import get_logger

logger = get_logger("notify-service.channels")


def _http_json(method: str, url: str, payload: dict, headers: Optional[dict] = None) -> dict:
    h = {"Content-Type": "application/json", "User-Agent": "crypto-agent-notify"}
    if headers:
        h.update(headers)
    data = json.dumps(payload).encode("utf-8")
    req = Request(url, data=data, headers=h, method=method)
    try:
        with urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {"ok": True}
    except HTTPError as e:
        body = e.read().decode() if e.fp else ""
        raise RuntimeError(f"{method} {url} -> {e.code}: {body[:400]}") from e


def send_webhook(url: str, title: str, message: str, extra: Optional[dict] = None) -> dict:
    payload = {"title": title, "message": message, **(extra or {})}
    _http_json("POST", url, payload)
    return {"ok": True, "channel": "webhook"}


def send_telegram(bot_token: str, chat_id: str, title: str, message: str) -> dict:
    text = f"*{title}*\n{message}" if title else message
    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    _http_json("POST", url, {"chat_id": chat_id, "text": text, "parse_mode": "Markdown"})
    return {"ok": True, "channel": "telegram"}


def send_emailjs(
    service_id: str,
    template_id: str,
    public_key: str,
    template_params: dict[str, Any],
    private_key: Optional[str] = None,
) -> dict:
    """EmailJS REST: https://www.emailjs.com/docs/rest-api/send/"""
    url = "https://api.emailjs.com/api/v1.0/email/send"
    payload = {
        "service_id": service_id,
        "template_id": template_id,
        "user_id": public_key,
        "template_params": template_params,
    }
    if private_key:
        payload["accessToken"] = private_key
    _http_json("POST", url, payload)
    return {"ok": True, "channel": "emailjs"}


def channels_from_env() -> dict[str, Any]:
    return {
        "webhook_url": os.environ.get("NOTIFY_WEBHOOK_URL") or "",
        "telegram_bot_token": os.environ.get("NOTIFY_TG_BOT_TOKEN") or "",
        "telegram_chat_id": os.environ.get("NOTIFY_TG_CHAT_ID") or "",
        "emailjs_service_id": os.environ.get("NOTIFY_EMAILJS_SERVICE_ID") or "",
        "emailjs_template_id": os.environ.get("NOTIFY_EMAILJS_TEMPLATE_ID") or "",
        "emailjs_public_key": os.environ.get("NOTIFY_EMAILJS_PUBLIC_KEY") or "",
        "emailjs_private_key": os.environ.get("NOTIFY_EMAILJS_PRIVATE_KEY") or "",
    }
