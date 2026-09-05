"""
API Gateway / BFF —— 统一入口。
- 反向代理到各微服务
- 聚合健康检查
- CORS / 统一错误处理
前端与外部调用者只需访问 :8000
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Optional

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "libs"))

from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler


class Settings(BaseServiceSettings):
    service_name: str = "gateway"
    port: int = 8000
    proxy_timeout: float = 180.0


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)

# 路径前缀 -> 后端 base URL
SERVICE_MAP: dict[str, str] = {}


def _build_service_map() -> dict[str, str]:
    return {
        "data": settings.data_service_url.rstrip("/"),
        "config": settings.config_service_url.rstrip("/"),
        "plugin": settings.plugin_service_url.rstrip("/"),
        "backtest": settings.backtest_service_url.rstrip("/"),
        "chart": settings.chart_service_url.rstrip("/"),
        "agent": settings.agent_service_url.rstrip("/"),
        "multi-agent": settings.multi_agent_service_url.rstrip("/"),
        "multi_agent": settings.multi_agent_service_url.rstrip("/"),
        "ops": getattr(settings, "ops_service_url", "http://localhost:8008").rstrip("/"),
        "log": getattr(settings, "log_service_url", "http://localhost:8009").rstrip("/"),
    }


@asynccontextmanager
async def lifespan(app: FastAPI):
    global SERVICE_MAP
    SERVICE_MAP = _build_service_map()
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    logger.info(f"Service map: {SERVICE_MAP}")
    yield
    logger.info(f"{settings.service_name} shutting down")


async def _proxy(request: Request, base_url: str, path: str) -> Response:
    """通用反向代理：保留方法、query、body、部分 headers"""
    url = f"{base_url}/{path.lstrip('/')}" if path else base_url + "/"
    # 附带原始 query
    if request.url.query:
        url = f"{url}?{request.url.query}"

    headers = {}
    for k, v in request.headers.items():
        lk = k.lower()
        if lk in ("host", "content-length", "transfer-encoding"):
            continue
        headers[k] = v

    body = await request.body()
    timeout = httpx.Timeout(settings.proxy_timeout)

    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.request(
                method=request.method,
                url=url,
                headers=headers,
                content=body if body else None,
            )
    except httpx.ConnectError as e:
        logger.warning(f"Upstream connect error {url}: {e}")
        return JSONResponse(
            status_code=502,
            content={"error": "bad_gateway", "message": f"cannot connect to upstream: {base_url}", "path": path},
        )
    except httpx.TimeoutException:
        return JSONResponse(
            status_code=504,
            content={"error": "gateway_timeout", "message": f"upstream timeout: {base_url}", "path": path},
        )
    except Exception as e:
        logger.exception(f"Proxy error {url}")
        return JSONResponse(
            status_code=502,
            content={"error": "bad_gateway", "message": str(e)},
        )

    # 透传 JSON 或原始内容
    content_type = resp.headers.get("content-type", "")
    excluded = {"transfer-encoding", "content-encoding", "content-length", "connection"}
    out_headers = {k: v for k, v in resp.headers.items() if k.lower() not in excluded}

    if "application/json" in content_type:
        try:
            return JSONResponse(content=resp.json(), status_code=resp.status_code, headers=out_headers)
        except Exception:
            pass

    return Response(content=resp.content, status_code=resp.status_code, headers=out_headers, media_type=content_type or None)


def create_app() -> FastAPI:
    app = FastAPI(
        title="API Gateway",
        description="统一入口：/data /config /plugin /backtest /chart /agent /multi-agent",
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
            "routes": {
                "/data/*": "Data Service :8001",
                "/config/*": "Config Service :8002",
                "/plugin/*": "Plugin Service :8003",
                "/backtest/*": "Backtest Service :8004",
                "/chart/*": "Chart Service :8005",
                "/agent/*": "Agent Service :8006",
                "/multi-agent/*": "Multi-Agent Service :8007",
                "/ops/*": "Ops Service :8008",
                "/log/*": "Log Service :8009",
                "/api/v1/health/all": "聚合健康检查",
            },
            "upstreams": _build_service_map(),
        }

    @app.get("/api/v1/health/all")
    async def health_all():
        """并行探测所有上游 /health"""
        import asyncio

        async def check(name: str, base: str) -> dict[str, Any]:
            try:
                async with httpx.AsyncClient(timeout=3.0) as client:
                    r = await client.get(f"{base}/health")
                    return {
                        "service": name,
                        "url": base,
                        "status_code": r.status_code,
                        "ok": r.status_code == 200,
                        "body": r.json() if r.headers.get("content-type", "").startswith("application/json") else r.text[:200],
                    }
            except Exception as e:
                return {"service": name, "url": base, "ok": False, "error": str(e)}

        sm = _build_service_map()
        # 去重 multi-agent / multi_agent
        seen = set()
        tasks = []
        for name, base in sm.items():
            if base in seen:
                continue
            seen.add(base)
            tasks.append(check(name, base))

        results = await asyncio.gather(*tasks)
        all_ok = all(x.get("ok") for x in results)
        return {"ok": all_ok, "services": results}

    # 为每个服务注册代理路由
    def make_proxy(service_key: str):
        async def handler(request: Request, path: str = "") -> Response:
            sm = SERVICE_MAP or _build_service_map()
            base = sm.get(service_key)
            if not base:
                return JSONResponse(status_code=404, content={"error": f"unknown service {service_key}"})
            return await _proxy(request, base, path)

        return handler

    methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]

    for key in ("data", "config", "plugin", "backtest", "chart", "agent", "multi-agent", "multi_agent", "ops", "log"):
        # /data 与 /data/{path}
        app.add_api_route(
            f"/{key}",
            make_proxy(key),
            methods=methods,
            include_in_schema=True,
            name=f"proxy_{key}_root",
        )
        app.add_api_route(
            f"/{key}/{{path:path}}",
            make_proxy(key),
            methods=methods,
            include_in_schema=True,
            name=f"proxy_{key}",
        )

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
