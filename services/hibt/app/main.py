"""
HiBT 事件合约辅助微服务（可选，PC/服务器侧）。
手机 App 已内置同等逻辑，可不启动本服务。
默认 dry_run=true。非官方 API，风险自担。
"""
from __future__ import annotations

import os
from typing import Optional

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="hibt-service", version="0.1.0")

API_BASE = os.getenv("HIBT_API_BASE", "https://api.hibt0.com")


class HibtConfig(BaseModel):
    api_base: str = API_BASE
    authorization: str = ""
    x_auth_token: str = ""
    v: str = ""


class PlaceRequest(BaseModel):
    config: HibtConfig
    symbol: str = "btc_usdt"
    direction: int = Field(1, description="1涨 0跌")
    amount: float = 3.0
    time_unit: int = 10
    dry_run: bool = True


@app.get("/health")
def health():
    return {"status": "ok", "service": "hibt"}


@app.post("/api/v1/test")
async def test_conn(cfg: HibtConfig):
    if not cfg.authorization and not cfg.x_auth_token:
        return {"ok": False, "message": "缺少 token"}
    headers = {
        "accept": "application/json",
        "client-type": "web",
        "platform": "PC",
    }
    if cfg.authorization:
        headers["Authorization"] = cfg.authorization
    if cfg.x_auth_token:
        headers["x-auth-token"] = cfg.x_auth_token
    url = cfg.api_base.rstrip("/") + "/option/option-account/info"
    if cfg.v:
        url += f"?v={cfg.v}"
    try:
        async with httpx.AsyncClient(timeout=20) as client:
            r = await client.get(url, headers=headers)
            return {"ok": r.status_code < 400, "status": r.status_code, "body": r.text[:500]}
    except Exception as e:
        return {"ok": False, "message": str(e)}


@app.post("/api/v1/place")
async def place(req: PlaceRequest):
    if req.dry_run:
        return {
            "ok": True,
            "dry_run": True,
            "message": f"DRY-RUN {req.symbol} dir={req.direction} amount={req.amount} unit={req.time_unit}",
        }
    cfg = req.config
    headers = {
        "accept": "application/json",
        "content-type": "application/x-www-form-urlencoded",
        "client-type": "web",
        "platform": "PC",
        "origin": "https://hibt.com",
        "referer": "https://hibt.com/",
    }
    if cfg.authorization:
        headers["Authorization"] = cfg.authorization
    if cfg.x_auth_token:
        headers["x-auth-token"] = cfg.x_auth_token
    url = cfg.api_base.rstrip("/") + "/option/option-order/place"
    if cfg.v:
        url += f"?v={cfg.v}"
    data = {
        "amount": str(req.amount),
        "direction": str(req.direction),
        "symbol": req.symbol,
        "timeUnit": str(req.time_unit),
        "langCode": "zh_CN",
    }
    async with httpx.AsyncClient(timeout=20) as client:
        r = await client.post(url, headers=headers, data=data)
        return {"ok": r.status_code < 400, "status": r.status_code, "body": r.text[:500]}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8012")))
