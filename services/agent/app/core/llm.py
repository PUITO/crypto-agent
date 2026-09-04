"""
统一 LLM 客户端：支持 Ollama（本地）与 OpenAI 兼容云端 API。
不强制依赖 langchain，仅用 httpx。
"""

from __future__ import annotations

import json
from typing import Any, Optional

import httpx

from common.logging import get_logger

logger = get_logger("agent-service.llm")


class LLMClient:
    def __init__(
        self,
        provider: str = "ollama",  # ollama | openai
        model: str = "qwen2.5:14b",
        base_url: str = "http://localhost:11434",
        api_key: Optional[str] = None,
        temperature: float = 0.2,
        timeout: float = 120.0,
    ):
        self.provider = provider
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.temperature = temperature
        self.timeout = timeout

    async def chat(
        self,
        messages: list[dict[str, str]],
        tools: Optional[list[dict]] = None,
        tool_choice: str = "auto",
    ) -> dict[str, Any]:
        """
        返回统一结构：
        {
          "content": str | None,
          "tool_calls": [ {"id", "name", "arguments": dict} ],
          "raw": ...
        }
        """
        if self.provider == "ollama":
            return await self._ollama_chat(messages, tools)
        return await self._openai_chat(messages, tools, tool_choice)

    async def _openai_chat(
        self,
        messages: list[dict[str, str]],
        tools: Optional[list[dict]],
        tool_choice: str,
    ) -> dict[str, Any]:
        url = f"{self.base_url}/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
        }
        if tools:
            # OpenAI tools format
            payload["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": t["name"],
                        "description": t.get("description", ""),
                        "parameters": t.get("parameters", {"type": "object", "properties": {}}),
                    },
                }
                for t in tools
            ]
            payload["tool_choice"] = tool_choice

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.post(url, headers=headers, json=payload)
            r.raise_for_status()
            data = r.json()

        choice = data["choices"][0]["message"]
        tool_calls = []
        for tc in choice.get("tool_calls") or []:
            args = tc["function"].get("arguments", "{}")
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {"raw": args}
            tool_calls.append({
                "id": tc.get("id", ""),
                "name": tc["function"]["name"],
                "arguments": args,
            })

        return {
            "content": choice.get("content"),
            "tool_calls": tool_calls,
            "raw": data,
        }

    async def _ollama_chat(
        self,
        messages: list[dict[str, str]],
        tools: Optional[list[dict]],
    ) -> dict[str, Any]:
        """
        Ollama /api/chat。新版本支持 tools；不支持时降级为纯文本 + 手动解析。
        """
        url = f"{self.base_url}/api/chat"
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "options": {"temperature": self.temperature},
        }
        if tools:
            payload["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": t["name"],
                        "description": t.get("description", ""),
                        "parameters": t.get("parameters", {"type": "object", "properties": {}}),
                    },
                }
                for t in tools
            ]

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            try:
                r = await client.post(url, json=payload)
                r.raise_for_status()
                data = r.json()
            except Exception as e:
                logger.warning(f"Ollama tools call failed, fallback text-only: {e}")
                payload.pop("tools", None)
                r = await client.post(url, json=payload)
                r.raise_for_status()
                data = r.json()

        msg = data.get("message", {})
        content = msg.get("content")
        tool_calls = []
        for tc in msg.get("tool_calls") or []:
            fn = tc.get("function", {})
            args = fn.get("arguments", {})
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {"raw": args}
            tool_calls.append({
                "id": tc.get("id", fn.get("name", "")),
                "name": fn.get("name", ""),
                "arguments": args,
            })

        # 简单兜底：若模型把工具调用写在文本里
        if not tool_calls and content and "tool_call" in content.lower():
            tool_calls = self._parse_text_tool_calls(content)

        return {"content": content, "tool_calls": tool_calls, "raw": data}

    @staticmethod
    def _parse_text_tool_calls(content: str) -> list[dict]:
        """极简文本解析兜底，不保证完备"""
        import re
        found = []
        for m in re.finditer(r'\{[^{}]*"name"\s*:\s*"(\w+)"[^{}]*\}', content):
            try:
                obj = json.loads(m.group(0))
                found.append({
                    "id": obj.get("name", ""),
                    "name": obj.get("name", ""),
                    "arguments": obj.get("arguments") or obj.get("params") or {},
                })
            except Exception:
                continue
        return found
