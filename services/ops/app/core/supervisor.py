"""
本地开发用进程级微服务生命周期管理。
通过 subprocess 启动各服务的 main.py，记录 PID，支持停止/重启。
生产环境应改用 systemd / Docker / K8s，本模块定位为降运维成本的本地主入口。
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Optional

import httpx

from common.logging import get_logger, get_log_root, read_log_tail

logger = get_logger("ops-service.supervisor")

REPO_ROOT = Path(__file__).resolve().parents[4]


@dataclass
class ServiceSpec:
    name: str
    port: int
    cwd: str  # relative to repo root
    module_entry: str = "main.py"  # run as python main.py in cwd
    health_path: str = "/health"


# 固定注册表（与当前项目一致）
SERVICE_SPECS: list[ServiceSpec] = [
    ServiceSpec("log-service", 8009, "services/log/app"),
    ServiceSpec("gateway", 8000, "services/gateway/app"),
    ServiceSpec("data-service", 8001, "services/data/app"),
    ServiceSpec("config-service", 8002, "services/config/app"),
    ServiceSpec("plugin-service", 8003, "services/plugin/app"),
    ServiceSpec("backtest-service", 8004, "services/backtest/app"),
    ServiceSpec("chart-service", 8005, "services/chart/app"),
    ServiceSpec("agent-service", 8006, "services/agent/app"),
    ServiceSpec("multi-agent-service", 8007, "services/multi_agent/app"),
    ServiceSpec("sync-service", 8010, "services/sync/app"),
    ServiceSpec("notify-service", 8011, "services/notify/app"),
    # ops 自身不通过自己启动，避免递归
]


class Supervisor:
    def __init__(self, repo_root: Path | None = None, log_service_url: str = "http://localhost:8009"):
        self.repo_root = repo_root or REPO_ROOT
        self.log_service_url = log_service_url.rstrip("/")
        self._procs: dict[str, subprocess.Popen] = {}
        self._started_at: dict[str, float] = {}
        self.pid_dir = self.repo_root / "logs" / "pids"
        self.pid_dir.mkdir(parents=True, exist_ok=True)
        self._load_pid_files()

    def _pid_file(self, name: str) -> Path:
        return self.pid_dir / f"{name}.pid"

    def _load_pid_files(self) -> None:
        for spec in SERVICE_SPECS:
            pf = self._pid_file(spec.name)
            if not pf.exists():
                continue
            try:
                pid = int(pf.read_text().strip())
                # 进程是否仍在
                os.kill(pid, 0)
                # 无法恢复 Popen 对象，仅标记 externally managed
                self._started_at[spec.name] = pf.stat().st_mtime
            except Exception:
                pf.unlink(missing_ok=True)

    def _write_pid(self, name: str, pid: int) -> None:
        self._pid_file(name).write_text(str(pid), encoding="utf-8")

    def _clear_pid(self, name: str) -> None:
        self._pid_file(name).unlink(missing_ok=True)

    def _is_pid_alive(self, pid: int) -> bool:
        try:
            os.kill(pid, 0)
            return True
        except Exception:
            return False

    def _get_pid(self, name: str) -> Optional[int]:
        if name in self._procs and self._procs[name].poll() is None:
            return self._procs[name].pid
        pf = self._pid_file(name)
        if pf.exists():
            try:
                pid = int(pf.read_text().strip())
                if self._is_pid_alive(pid):
                    return pid
            except Exception:
                pass
        return None

    async def check_health(self, spec: ServiceSpec) -> dict[str, Any]:
        url = f"http://127.0.0.1:{spec.port}{spec.health_path}"
        try:
            async with httpx.AsyncClient(timeout=2.5) as client:
                r = await client.get(url)
                return {
                    "ok": r.status_code == 200,
                    "status_code": r.status_code,
                    "url": url,
                }
        except Exception as e:
            return {"ok": False, "error": str(e), "url": url}

    async def status_all(self) -> list[dict[str, Any]]:
        out = []
        for spec in SERVICE_SPECS:
            pid = self._get_pid(spec.name)
            health = await self.check_health(spec)
            out.append({
                "name": spec.name,
                "port": spec.port,
                "pid": pid,
                "running": pid is not None or health.get("ok"),
                "health": health,
                "log_file": str(get_log_root() / f"{spec.name}.log"),
            })
        return out

    def start(self, name: str) -> dict[str, Any]:
        spec = next((s for s in SERVICE_SPECS if s.name == name), None)
        if not spec:
            return {"ok": False, "error": f"unknown service {name}"}

        pid = self._get_pid(name)
        if pid:
            return {"ok": True, "message": "already running", "pid": pid}

        cwd = self.repo_root / spec.cwd
        if not cwd.exists():
            return {"ok": False, "error": f"cwd not found: {cwd}"}

        env = os.environ.copy()
        libs = str(self.repo_root / "libs")
        env["PYTHONPATH"] = libs + os.pathsep + env.get("PYTHONPATH", "")
        log_path = get_log_root() / f"{spec.name}.log"
        log_f = open(log_path, "a", encoding="utf-8")

        try:
            proc = subprocess.Popen(
                [sys.executable, spec.module_entry],
                cwd=str(cwd),
                env=env,
                stdout=log_f,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            self._procs[name] = proc
            self._started_at[name] = time.time()
            self._write_pid(name, proc.pid)
            logger.info(f"Started {name} pid={proc.pid}")
            return {"ok": True, "pid": proc.pid, "name": name}
        except Exception as e:
            logger.exception(f"Failed to start {name}")
            return {"ok": False, "error": str(e)}

    def stop(self, name: str) -> dict[str, Any]:
        pid = self._get_pid(name)
        if not pid:
            self._clear_pid(name)
            if name in self._procs:
                del self._procs[name]
            return {"ok": True, "message": "not running"}

        try:
            os.kill(pid, signal.SIGTERM)
            # 等待最多 5s
            for _ in range(50):
                if not self._is_pid_alive(pid):
                    break
                time.sleep(0.1)
            if self._is_pid_alive(pid):
                os.kill(pid, signal.SIGKILL)
            logger.info(f"Stopped {name} pid={pid}")
        except Exception as e:
            return {"ok": False, "error": str(e)}
        finally:
            self._clear_pid(name)
            if name in self._procs:
                del self._procs[name]
        return {"ok": True, "name": name, "stopped_pid": pid}

    def restart(self, name: str) -> dict[str, Any]:
        self.stop(name)
        time.sleep(0.3)
        return self.start(name)

    def start_all(self) -> list[dict[str, Any]]:
        # 先起依赖较少的服务，gateway 最后
        order = [
            "log-service",
            "config-service",
            "data-service",
            "plugin-service",
            "backtest-service",
            "chart-service",
            "agent-service",
            "multi-agent-service",
            "gateway",
        ]
        results = []
        for name in order:
            results.append(self.start(name))
            time.sleep(0.2)
        return results

    def stop_all(self) -> list[dict[str, Any]]:
        results = []
        for spec in reversed(SERVICE_SPECS):
            results.append(self.stop(spec.name))
        return results

    def logs(self, name: str, lines: int = 200) -> dict[str, Any]:
        """优先查 Log Service，失败再回落本地文件。"""
        # 尝试集中日志
        try:
            import httpx
            with httpx.Client(timeout=3.0) as client:
                r = client.get(
                    f"{self.log_service_url}/api/v1/logs/{name}/tail",
                    params={"lines": lines},
                )
                if r.status_code == 200:
                    data = r.json()
                    if data.get("lines") or data.get("logs"):
                        return {
                            "service": name,
                            "source": "log-service",
                            "log_key": name,
                            "lines": data.get("lines") or [
                                f"{x.get('ts','')} | {x.get('level','')} | {x.get('message','')}"
                                for x in (data.get("logs") or [])
                            ],
                        }
        except Exception:
            pass
        candidates = [name, name.replace("-service", ""), f"{name}-service"]
        lines_out: list[str] = []
        used = name
        for c in candidates:
            lines_out = read_log_tail(c, lines=lines)
            if lines_out:
                used = c
                break
        if not lines_out:
            lines_out = read_log_tail(name, lines=lines)
        return {"service": name, "source": "local-file", "log_key": used, "lines": lines_out}
