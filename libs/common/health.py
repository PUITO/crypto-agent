"""
标准健康检查路由。
所有微服务直接 include 即可。
"""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str = "ok"
    service: str
    version: str = "0.1.0"


@router.get("/health", response_model=HealthResponse)
async def health_check():
    # 这里可以扩展：检查 Redis、数据库连接等
    from .config import get_base_settings
    settings = get_base_settings()
    return HealthResponse(service=settings.service_name)


@router.get("/ready")
async def readiness():
    """K8s readiness 探针可使用。"""
    return {"ready": True}
