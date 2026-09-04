"""
统一异常与错误响应格式。
"""

from typing import Any, Optional

from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel


class AppException(Exception):
    """业务异常基类，可被全局异常处理器捕获。"""

    def __init__(
        self,
        message: str,
        code: str = "APP_ERROR",
        status_code: int = 400,
        details: Optional[Any] = None,
    ):
        self.message = message
        self.code = code
        self.status_code = status_code
        self.details = details
        super().__init__(message)


class ErrorResponse(BaseModel):
    success: bool = False
    code: str
    message: str
    details: Optional[Any] = None


async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content=ErrorResponse(
            code=exc.code,
            message=exc.message,
            details=exc.details,
        ).model_dump(),
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(
        status_code=500,
        content=ErrorResponse(
            code="INTERNAL_ERROR",
            message="服务器内部错误",
            details=str(exc) if True else None,  # 生产环境可关闭 details
        ).model_dump(),
    )
