"""
Config Service —— 统一配置中心。
支持：读取、预览变更、确认生效、审计日志、预设管理。
Chat 修改配置时强制走 preview → confirm 流程。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Optional

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

from core.store import ConfigStore, DEFAULT_CONFIG, CONFIG_SECTIONS
from common.persist import path_for


class Settings(BaseServiceSettings):
    service_name: str = "config-service"
    port: int = 8002
    config_path: str = ""  # 空则使用统一 persist 路径


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

_cfg_path = settings.config_path or str(path_for("config", "runtime_config.json"))
store = ConfigStore(persist_path=_cfg_path)


class PatchRequest(BaseModel):
    """批量补丁更新"""
    patch: dict[str, Any]
    confirm: bool = False
    source: str = "api"
    note: str = ""


class KeyUpdateRequest(BaseModel):
    """单键更新（支持点号路径）"""
    key: str
    value: Any
    confirm: bool = False
    source: str = "api"
    note: str = ""


class PresetSaveRequest(BaseModel):
    name: str
    config: Optional[dict[str, Any]] = None  # None 表示保存当前配置


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting, config={settings.config_path}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="Config Service",
        description="统一配置中心：手动界面 + Chat 双通道，带预览确认与审计",
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
            "endpoints": [
                "GET  /api/v1/config",
                "GET  /api/v1/config/{key}",
                "POST /api/v1/config/preview",
                "POST /api/v1/config/apply",
                "PUT  /api/v1/config/key",
                "GET  /api/v1/config/audit",
                "GET  /api/v1/presets",
                "POST /api/v1/presets",
                "POST /api/v1/presets/{name}/load",
            ],
        }

    # ---------- 读取 ----------
    @app.get("/api/v1/config")
    async def get_all_config(raw: bool = False):
        """默认返回脱敏配置；raw=true 返回完整（仅内网服务使用）。"""
        cfg = store.get_all() if raw else store.public_view()
        return {"config": cfg, "defaults": DEFAULT_CONFIG}

    @app.get("/api/v1/config/schema")
    async def get_config_schema():
        """前端分栏表单元数据 + 重启策略说明。"""
        return {
            "sections": CONFIG_SECTIONS,
            "restart_policy": {
                "hot_reload": ["trading", "plugins", "risk", "backtest", "大部分 optimize"],
                "needs_restart": ["data", "agent(LLM相关)", "services", "optimize.max_concurrency"],
                "note": "保存后若 needs_restart，前端应提示并用 Ops 重启对应服务；环境变量仅启动兜底。",
            },
        }

    @app.get("/api/v1/config/section/{section}")
    async def get_config_section(section: str):
        val = store.get_section(section)
        if val is None and section not in store.get_all():
            raise HTTPException(404, f"section '{section}' not found")
        return {"section": section, "value": val}

    @app.get("/api/v1/config/{key:path}")
    async def get_config_key(key: str):
        val = store.get(key)
        if val is None and key not in store.get_all():
            # 再检查点号路径
            if store.get(key, "__missing__") == "__missing__":
                raise HTTPException(404, f"config key '{key}' not found")
        return {"key": key, "value": val}

    # ---------- 预览 / 生效 ----------
    @app.post("/api/v1/config/preview")
    async def preview_config(body: PatchRequest):
        """仅预览变更，不落库。Chat 修改配置的第一步。"""
        return store.preview(body.patch)

    @app.post("/api/v1/config/apply")
    async def apply_config(body: PatchRequest):
        """
        应用配置变更。
        - confirm=false 且 require_confirm 开启时，只返回预览
        - confirm=true 时真正写入
        """
        require = store.get("require_confirm_on_config_change", True)
        if require and not body.confirm:
            preview = store.preview(body.patch)
            preview["message"] = "需要确认后再生效，请带 confirm=true 重试"
            return preview
        return store.apply(body.patch, source=body.source, note=body.note)

    @app.put("/api/v1/config/key")
    async def update_key(body: KeyUpdateRequest):
        """单键更新（支持 strategy_params.kdj_rsi_event.rsi_oversold 这种路径）"""
        require = store.get("require_confirm_on_config_change", True)
        if require and not body.confirm:
            # 构造临时 patch 做预览
            parts = body.key.split(".")
            patch: dict[str, Any] = {}
            cur = patch
            for p in parts[:-1]:
                cur[p] = {}
                cur = cur[p]
            cur[parts[-1]] = body.value
            preview = store.preview(patch)
            preview["message"] = "需要确认后再生效，请带 confirm=true 重试"
            return preview
        return store.set_key(body.key, body.value, source=body.source, note=body.note)

    # ---------- 审计 ----------
    @app.get("/api/v1/config/audit")
    async def get_audit(limit: int = Query(50, ge=1, le=200)):
        return {"audit": store.audit_log(limit=limit)}

    # ---------- 预设 ----------
    @app.get("/api/v1/presets")
    async def list_presets():
        return {"presets": store.list_presets()}

    @app.post("/api/v1/presets")
    async def save_preset(body: PresetSaveRequest):
        return store.save_preset(body.name, body.config)

    @app.post("/api/v1/presets/{name}/load")
    async def load_preset(name: str, confirm: bool = True):
        try:
            if not confirm:
                # 预览：比较当前与预设
                presets = store.list_presets()
                if name not in presets:
                    raise HTTPException(404, f"preset '{name}' not found")
                # 简单返回提示
                return {
                    "preview": True,
                    "message": f"将加载预设 '{name}'，请带 confirm=true 确认",
                    "preset": name,
                }
            return store.load_preset(name)
        except KeyError:
            raise HTTPException(404, f"preset '{name}' not found")

    # ---------- 便捷：模式切换 ----------
    @app.post("/api/v1/mode/{mode}")
    async def switch_mode(mode: str, confirm: bool = False):
        if mode not in ("perpetual", "event_30m", "both"):
            raise HTTPException(400, "mode must be perpetual | event_30m | both")
        body = PatchRequest(
            patch={"mode": mode, "trading": {"mode": mode}},
            confirm=confirm,
            source="mode_api",
        )
        return await apply_config(body)

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
