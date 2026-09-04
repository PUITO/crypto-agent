"""
插件加载器：扫描目录、动态 import、实例化 StrategyPlugin。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any, Optional

from common.logging import get_logger
from common.strategy import StrategyPlugin

logger = get_logger("plugin-service.loader")


class PluginLoader:
    def __init__(self, plugins_dir: str | Path):
        self.plugins_dir = Path(plugins_dir)
        self.plugins_dir.mkdir(parents=True, exist_ok=True)
        self._registry: dict[str, StrategyPlugin] = {}
        self._meta: dict[str, dict[str, Any]] = {}

    @property
    def registry(self) -> dict[str, StrategyPlugin]:
        return self._registry

    def list_plugins(self) -> list[dict[str, Any]]:
        return [self._meta[name] for name in sorted(self._registry.keys())]

    def get(self, name: str) -> Optional[StrategyPlugin]:
        return self._registry.get(name)

    def load_file(self, path: Path) -> Optional[StrategyPlugin]:
        """从单个 .py 文件加载插件"""
        if not path.exists() or path.suffix != ".py":
            return None
        if path.name.startswith("_"):
            return None

        module_name = f"plugins.{path.stem}"
        try:
            spec = importlib.util.spec_from_file_location(module_name, path)
            if spec is None or spec.loader is None:
                logger.warning(f"Cannot load spec for {path}")
                return None
            module = importlib.util.module_from_spec(spec)
            sys.modules[module_name] = module
            spec.loader.exec_module(module)

            # 优先找 create_plugin()，其次找 StrategyPlugin 子类
            plugin: Optional[StrategyPlugin] = None
            if hasattr(module, "create_plugin"):
                plugin = module.create_plugin()
            else:
                for attr_name in dir(module):
                    obj = getattr(module, attr_name)
                    if (
                        isinstance(obj, type)
                        and issubclass(obj, StrategyPlugin)
                        and obj is not StrategyPlugin
                    ):
                        plugin = obj()
                        break

            if plugin is None:
                logger.warning(f"No StrategyPlugin found in {path}")
                return None

            self._registry[plugin.name] = plugin
            self._meta[plugin.name] = {
                **plugin.info(),
                "status": "loaded",
                "source": str(path),
            }
            logger.info(f"Loaded plugin: {plugin.name} v{plugin.version} from {path.name}")
            return plugin
        except Exception as e:
            logger.exception(f"Failed to load plugin from {path}: {e}")
            return None

    def scan_and_load(self) -> int:
        """扫描 plugins_dir 下所有 .py 并加载"""
        count = 0
        for path in sorted(self.plugins_dir.glob("*.py")):
            if self.load_file(path):
                count += 1
        return count

    def unload(self, name: str) -> bool:
        if name in self._registry:
            del self._registry[name]
            if name in self._meta:
                self._meta[name]["status"] = "unloaded"
            logger.info(f"Unloaded plugin: {name}")
            return True
        return False

    def reload(self, name: str) -> bool:
        meta = self._meta.get(name)
        if not meta or "source" not in meta:
            return False
        self.unload(name)
        plugin = self.load_file(Path(meta["source"]))
        return plugin is not None
