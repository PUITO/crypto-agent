"""
通过 GitHub PAT 将 data/persist 下文件同步到私有仓库（Contents API）。
新机器部署时 pull；运行中按调度 push。
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from common.logging import get_logger
from common.persist import PERSIST_ROOT, ensure_persist_tree, list_sync_files

logger = get_logger("sync-service.github")

API = "https://api.github.com"


class GitHubSyncClient:
    def __init__(
        self,
        token: str,
        repo: str,  # owner/name
        branch: str = "main",
        remote_prefix: str = "persist",
    ):
        self.token = token
        self.repo = repo
        self.branch = branch
        self.remote_prefix = remote_prefix.strip("/")

    def _req(self, method: str, path: str, data: Any = None) -> dict:
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "crypto-agent-sync",
        }
        body = None
        if data is not None:
            body = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = Request(f"{API}{path}", data=body, headers=headers, method=method)
        try:
            with urlopen(req, timeout=60) as resp:
                raw = resp.read().decode()
                return json.loads(raw) if raw else {}
        except HTTPError as e:
            err_body = e.read().decode() if e.fp else ""
            raise RuntimeError(f"GitHub API {method} {path} -> {e.code}: {err_body[:500]}") from e

    def _remote_path(self, local: Path) -> str:
        rel = local.relative_to(PERSIST_ROOT).as_posix()
        return f"{self.remote_prefix}/{rel}"

    def push_all(self, message: Optional[str] = None) -> dict[str, Any]:
        ensure_persist_tree()
        files = list_sync_files()
        results = []
        for f in files:
            try:
                results.append(self._put_file(f))
            except Exception as e:
                logger.warning(f"push fail {f}: {e}")
                results.append({"path": str(f), "ok": False, "error": str(e)})
        meta = {
            "last_push_at": datetime.now(timezone.utc).isoformat(),
            "file_count": len(files),
            "ok_count": sum(1 for r in results if r.get("ok")),
        }
        meta_path = PERSIST_ROOT / "sync_meta" / "last_push.json"
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
        return {"ok": True, "meta": meta, "results": results}

    def _put_file(self, local: Path) -> dict[str, Any]:
        remote = self._remote_path(local)
        content_b64 = base64.b64encode(local.read_bytes()).decode()
        sha = None
        try:
            existing = self._req("GET", f"/repos/{self.repo}/contents/{remote}?ref={self.branch}")
            sha = existing.get("sha")
        except Exception:
            pass
        payload = {
            "message": f"sync: update {remote}",
            "content": content_b64,
            "branch": self.branch,
        }
        if sha:
            payload["sha"] = sha
        data = self._req("PUT", f"/repos/{self.repo}/contents/{remote}", payload)
        return {"ok": True, "path": remote, "sha": (data.get("content") or {}).get("sha")}

    def pull_all(self) -> dict[str, Any]:
        """递归拉取 remote_prefix 下文件到本地 persist。"""
        ensure_persist_tree()
        items = self._list_tree(self.remote_prefix)
        pulled = []
        for item in items:
            if item.get("type") != "file":
                continue
            path = item["path"]  # e.g. persist/config/runtime_config.json
            if not path.startswith(self.remote_prefix + "/"):
                continue
            rel = path[len(self.remote_prefix) + 1 :]
            local = PERSIST_ROOT / rel
            local.parent.mkdir(parents=True, exist_ok=True)
            file_data = self._req("GET", f"/repos/{self.repo}/contents/{path}?ref={self.branch}")
            raw = base64.b64decode(file_data.get("content") or "")
            local.write_bytes(raw)
            pulled.append(str(local))
        meta = {
            "last_pull_at": datetime.now(timezone.utc).isoformat(),
            "pulled": len(pulled),
        }
        (PERSIST_ROOT / "sync_meta" / "last_pull.json").write_text(
            json.dumps(meta, indent=2), encoding="utf-8"
        )
        return {"ok": True, "meta": meta, "files": pulled}

    def _list_tree(self, prefix: str) -> list[dict]:
        """用 git trees API 递归列出。"""
        ref = self._req("GET", f"/repos/{self.repo}/git/ref/heads/{self.branch}")
        commit_sha = ref["object"]["sha"]
        commit = self._req("GET", f"/repos/{self.repo}/git/commits/{commit_sha}")
        tree_sha = commit["tree"]["sha"]
        tree = self._req("GET", f"/repos/{self.repo}/git/trees/{tree_sha}?recursive=1")
        out = []
        for node in tree.get("tree") or []:
            p = node.get("path") or ""
            if p == prefix or p.startswith(prefix + "/"):
                out.append({"path": p, "type": "file" if node.get("type") == "blob" else node.get("type")})
        return out


def load_sync_settings_from_env() -> dict[str, Any]:
    return {
        "token": os.environ.get("GITHUB_PAT") or os.environ.get("GH_PAT") or "",
        "repo": os.environ.get("GITHUB_SYNC_REPO") or os.environ.get("GH_SYNC_REPO") or "",
        "branch": os.environ.get("GITHUB_SYNC_BRANCH") or "main",
        "remote_prefix": os.environ.get("GITHUB_SYNC_PREFIX") or "persist",
        "cron": os.environ.get("GITHUB_SYNC_CRON") or "0 12 * * *",  # 默认每天 12:00
        "enabled": (os.environ.get("GITHUB_SYNC_ENABLED") or "true").lower() in ("1", "true", "yes"),
    }
