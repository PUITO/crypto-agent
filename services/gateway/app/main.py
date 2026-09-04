"""
API Gateway / BFF —— 统一入口（骨架）。
可独立启动与测试。后期可在此做鉴权、限流、聚合。
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import httpx

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


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title="API Gateway",
        description="统一入口，路由到后端各微服务",
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
            "status": "running",
            "docs": "/docs",
            "services": {
                "data": settings.data_service_url,
                "config": settings.config_service_url,
                "plugin": settings.plugin_service_url,
                "backtest": settings.backtest_service_url,
                "chart": settings.chart_service_url,
                "agent": settings.agent_service_url,
                "multi_agent": settings.multi_agent_service_url,
            },
        }

    # 简单反向代理示例（骨架）
    @app.api_route("/data/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
    async def proxy_data(path: str, request: Request):
        url = f"{settings.data_service_url}/{path}"
        async with httpx.AsyncClient() as client:
            resp = await client.request(
                request.method,
                url,
                params=request.query_params,
                content=await request.body(),
            )
        return JSONResponse(content=resp.json(), status_code=resp.status_code)

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=settings.debug)
