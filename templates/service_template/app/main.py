"""
标准微服务入口模板。
复制此文件到新服务后，只需修改 service_name、port 和自己的路由即可。
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# 注意：实际使用时需要把 libs 加入 PYTHONPATH 或做成可安装包
from common.config import BaseServiceSettings
from common.logging import setup_logging, get_logger
from common.health import router as health_router
from common.exceptions import AppException, app_exception_handler, unhandled_exception_handler


class Settings(BaseServiceSettings):
    service_name: str = "template-service"
    port: int = 8010


settings = Settings()
setup_logging(settings)
logger = get_logger(settings.service_name)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"{settings.service_name} starting on port {settings.port}")
    # 这里可做：连接 Redis、加载插件、预热缓存等
    yield
    logger.info(f"{settings.service_name} shutting down")


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.service_name,
        version="0.1.0",
        lifespan=lifespan,
    )

    # CORS（开发阶段放开，生产收紧）
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 全局异常处理
    app.add_exception_handler(AppException, app_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)

    # 健康检查
    app.include_router(health_router)

    # ========== 业务路由在这里挂载 ==========
    # from .api import xxx
    # app.include_router(xxx.router, prefix="/api/v1")

    @app.get("/")
    async def root():
        return {
            "service": settings.service_name,
            "status": "running",
            "docs": "/docs",
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
