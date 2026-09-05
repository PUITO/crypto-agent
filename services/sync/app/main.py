"""
Sync Service —— 持久化文件通过 GitHub PAT 同步到私有仓库。
日志不同步。默认每天 12:00 push；可手动 pull/push。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException, Header, Request
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
from common.persist import ensure_persist_tree, list_sync_files, PERSIST_ROOT

from core.github_sync import GitHubSyncClient, load_sync_settings_from_env
from core.scheduler import SyncScheduler


class Settings(BaseServiceSettings):
    service_name: str = "sync-service"
    port: int = 8010
    github_pat: Optional[str] = None
    github_sync_repo: Optional[str] = None
    github_sync_branch: str = "main"
    github_sync_prefix: str = "persist"
    github_sync_cron: str = "0 12 * * *"
    github_sync_enabled: bool = True


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

_client: Optional[GitHubSyncClient] = None
_scheduler: Optional[SyncScheduler] = None


def _build_client(
    token_override: str | None = None,
    repo_override: str | None = None,
) -> GitHubSyncClient:
    """
    生产推荐：token 仅从请求头传入（浏览器 Cookie → 前端 → Authorization），
    服务端默认不持久化 PAT，避免公网实例被盗用。
    """
    env = load_sync_settings_from_env()
    token = (token_override or "").strip() or settings.github_pat or env["token"]
    repo = (repo_override or "").strip() or settings.github_sync_repo or env["repo"]
    if not token:
        raise HTTPException(
            401,
            "缺少 GitHub PAT。请在前端配置并保存在浏览器 Cookie（不要写入服务器）。请求头: Authorization: Bearer <pat> 或 X-GitHub-PAT",
        )
    if not repo:
        raise HTTPException(
            400,
            "缺少 GITHUB_SYNC_REPO（可在部署环境变量中配置公开的 owner/repo，不含 token）。",
        )
    return GitHubSyncClient(
        token=token,
        repo=repo,
        branch=settings.github_sync_branch or env["branch"],
        remote_prefix=settings.github_sync_prefix or env["remote_prefix"],
    )


def _pat_from_request(
    authorization: str | None = None,
    x_github_pat: str | None = None,
) -> str | None:
    if x_github_pat:
        return x_github_pat.strip()
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


class SyncConfigUpdate(BaseModel):
    cron: Optional[str] = Field(None, description="例如 0 12 * * * 表示每天 12:00")
    enabled: Optional[bool] = None
    repo: Optional[str] = None
    branch: Optional[str] = None
    prefix: Optional[str] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _scheduler, _client
    ensure_persist_tree()
    logger.info(f"{settings.service_name} starting persist_root={PERSIST_ROOT}")

    async def job():
        logger.info("Scheduled push starting")
        client = _build_client()
        result = client.push_all()
        logger.info(f"Scheduled push done: {result.get('meta')}")

    cron = settings.github_sync_cron or load_sync_settings_from_env()["cron"]
    if settings.github_sync_enabled:
        _scheduler = SyncScheduler(cron, job)
        await _scheduler.start()
    yield
    if _scheduler:
        await _scheduler.stop()


def create_app() -> FastAPI:
    app = FastAPI(
        title="Sync Service",
        description="持久化文件 GitHub PAT 同步（配置/行情/数据集等，不含日志）",
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
        return {
            "service": settings.service_name,
            "persist_root": str(PERSIST_ROOT),
            "cron": settings.github_sync_cron,
            "docs": "/docs",
        }

    @app.get("/api/v1/status")
    async def status():
        env = load_sync_settings_from_env()
        files = list_sync_files()
        return {
            "enabled": settings.github_sync_enabled,
            "cron": settings.github_sync_cron or env["cron"],
            "repo_configured": bool(settings.github_sync_repo or env["repo"]),
            "token_configured": bool(settings.github_pat or env["token"]),
            "local_file_count": len(files),
            "last_trigger": _scheduler.last_trigger if _scheduler else None,
            "persist_root": str(PERSIST_ROOT),
        }

    @app.get("/api/v1/files")
    async def files():
        return {
            "files": [str(p.relative_to(PERSIST_ROOT)) for p in list_sync_files()],
        }

    @app.post("/api/v1/push")
    async def push(
        authorization: str | None = Header(default=None),
        x_github_pat: str | None = Header(default=None, alias="X-GitHub-PAT"),
        x_sync_repo: str | None = Header(default=None, alias="X-Sync-Repo"),
    ):
        client = _build_client(_pat_from_request(authorization, x_github_pat), x_sync_repo)
        return client.push_all()

    @app.post("/api/v1/pull")
    async def pull(
        authorization: str | None = Header(default=None),
        x_github_pat: str | None = Header(default=None, alias="X-GitHub-PAT"),
        x_sync_repo: str | None = Header(default=None, alias="X-Sync-Repo"),
    ):
        """新机器/新部署后：用浏览器侧 PAT 从私有仓库拉回配置与数据（服务端不存 PAT）。"""
        client = _build_client(_pat_from_request(authorization, x_github_pat), x_sync_repo)
        return client.pull_all()

    @app.post("/api/v1/test-pat")
    async def test_pat(
        authorization: str | None = Header(default=None),
        x_github_pat: str | None = Header(default=None, alias="X-GitHub-PAT"),
        x_sync_repo: str | None = Header(default=None, alias="X-Sync-Repo"),
    ):
        """测试 PAT 与仓库是否可访问（不落盘、不写服务器）。"""
        import json
        from urllib.request import Request, urlopen
        token = _pat_from_request(authorization, x_github_pat)
        if not token:
            raise HTTPException(401, "缺少 PAT")
        env = load_sync_settings_from_env()
        repo = (x_sync_repo or "").strip() or settings.github_sync_repo or env["repo"]
        if not repo:
            raise HTTPException(400, "缺少 repo")
        req = Request(
            f"https://api.github.com/repos/{repo}",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "crypto-agent-sync",
            },
        )
        try:
            with urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode())
            return {
                "ok": True,
                "repo": repo,
                "private": data.get("private"),
                "full_name": data.get("full_name"),
                "message": "PAT 有效，可访问目标仓库",
            }
        except Exception as e:
            raise HTTPException(400, f"PAT 或仓库无效: {e}")

    @app.post("/api/v1/config")
    async def update_config(body: SyncConfigUpdate):
        global _scheduler
        if body.cron is not None:
            settings.github_sync_cron = body.cron
            if _scheduler:
                await _scheduler.stop()
                async def job():
                    client = _build_client()
                    client.push_all()
                _scheduler = SyncScheduler(body.cron, job)
                if settings.github_sync_enabled:
                    await _scheduler.start()
        if body.enabled is not None:
            settings.github_sync_enabled = body.enabled
        if body.repo:
            settings.github_sync_repo = body.repo
        if body.branch:
            settings.github_sync_branch = body.branch
        if body.prefix:
            settings.github_sync_prefix = body.prefix
        return {"ok": True, "cron": settings.github_sync_cron, "enabled": settings.github_sync_enabled}

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
