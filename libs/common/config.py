"""
统一配置基类。
每个微服务继承 BaseServiceSettings，只声明自己的额外字段即可。
"""

from functools import lru_cache
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class BaseServiceSettings(BaseSettings):
    """所有微服务共享的基础配置"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # 服务基础信息
    service_name: str = "unknown-service"
    environment: str = "development"  # development / staging / production
    debug: bool = True
    host: str = "0.0.0.0"
    port: int = 8000

    # 日志
    log_level: str = "INFO"
    log_json: bool = False  # True 时输出 JSON 格式，方便后期采集
    log_dir: str = ""  # 空则使用仓库根目录 logs/

    # Redis（消息队列 + 缓存）
    redis_url: str = "redis://localhost:6379/0"

    # 内部服务发现（早期用环境变量，后期可换 Consul/K8s）
    data_service_url: str = "http://localhost:8001"
    config_service_url: str = "http://localhost:8002"
    plugin_service_url: str = "http://localhost:8003"
    backtest_service_url: str = "http://localhost:8004"
    chart_service_url: str = "http://localhost:8005"
    agent_service_url: str = "http://localhost:8006"
    multi_agent_service_url: str = "http://localhost:8007"
    ops_service_url: str = "http://localhost:8008"
    log_service_url: str = "http://localhost:8009"
    sync_service_url: str = "http://localhost:8010"
    notify_service_url: str = "http://localhost:8011"
    log_remote_enabled: bool = True  # 是否上报到 Log Service

    # 可选：LLM 相关（Agent 服务会用到）
    ollama_base_url: str = "http://localhost:11434"
    openai_api_key: Optional[str] = None
    openai_base_url: Optional[str] = None  # 兼容各类云端 API


@lru_cache
def get_base_settings() -> BaseServiceSettings:
    return BaseServiceSettings()
