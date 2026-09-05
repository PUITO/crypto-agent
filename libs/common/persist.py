"""
统一持久化路径约定。
各微服务把「需要跨机器恢复」的文件放在 persist 根目录下对应子目录；
日志不进入同步范围。

目录结构（相对仓库根）:
  data/persist/
    config/          # Config 运行时配置
    market/          # K 线等行情本地缓存
    datasets/        # 训练数据集
    chart/           # 绘图对象快照（可选）
    plugins_state/   # 插件运行状态
    sync_meta/       # 同步元数据（本地）
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

# 仓库根：libs/common/persist.py -> parents[2]
REPO_ROOT = Path(__file__).resolve().parents[2]
PERSIST_ROOT = REPO_ROOT / "data" / "persist"

# 参与 GitHub 同步的相对路径（相对 PERSIST_ROOT）
SYNC_INCLUDE_DIRS = (
    "config",
    "market",
    "datasets",
    "chart",
    "plugins_state",
)

# 明确不同步
SYNC_EXCLUDE_NAMES = {
    "*.log",
    "*.pid",
    "__pycache__",
    ".git",
}


def ensure_persist_tree() -> Path:
    PERSIST_ROOT.mkdir(parents=True, exist_ok=True)
    for d in SYNC_INCLUDE_DIRS:
        (PERSIST_ROOT / d).mkdir(parents=True, exist_ok=True)
    (PERSIST_ROOT / "sync_meta").mkdir(parents=True, exist_ok=True)
    return PERSIST_ROOT


def path_for(namespace: str, *parts: str) -> Path:
    """namespace 例如 config / market / datasets"""
    ensure_persist_tree()
    base = PERSIST_ROOT / namespace
    base.mkdir(parents=True, exist_ok=True)
    return base.joinpath(*parts) if parts else base


def list_sync_files() -> list[Path]:
    """列出应同步到私有仓库的文件。"""
    ensure_persist_tree()
    files: list[Path] = []
    for d in SYNC_INCLUDE_DIRS:
        root = PERSIST_ROOT / d
        if not root.exists():
            continue
        for p in root.rglob("*"):
            if p.is_file() and not p.name.endswith(".log"):
                files.append(p)
    return files
