"""
轻量 ReAct Agent 循环：
理解用户意图 → 决定调用工具 → 执行 → 再思考 → 最终回复。
配置类操作强制走预览，由用户确认后再 apply。
"""

from __future__ import annotations

import json
from typing import Any, Optional

from common.logging import get_logger

from .llm import LLMClient
from .tools import ToolRegistry

logger = get_logger("agent-service.agent")

SYSTEM_PROMPT = """你是加密市场（以 BTC 为主）分析与交易助手，负责：
1. 查询价格、配置、插件
2. 生成交易信号
3. 运行历史回测并解读胜率/回撤
4. 修改交易模式与策略参数（必须先 preview，得到用户确认后再 apply）

规则：
- 修改任何配置前，必须先调用 preview_config 或 switch_mode，把变更摘要用中文告诉用户，询问是否确认。
- 用户明确说「确认」「可以」「执行」后，再调用 apply_config。
- 回测结果用简洁中文总结：胜率、总收益、最大回撤、信号数。
- 不编造数据；没有工具结果时如实说明。
- 所有真实资金操作仅限模拟；提醒用户风险。
- 回复使用中文，简洁专业。
"""


class AgentSession:
    def __init__(self, session_id: str):
        self.session_id = session_id
        self.messages: list[dict[str, str]] = [
            {"role": "system", "content": SYSTEM_PROMPT}
        ]
        # 待确认的配置补丁
        self.pending_patch: Optional[dict] = None

    def add(self, role: str, content: str) -> None:
        self.messages.append({"role": role, "content": content})
        # 控制长度
        if len(self.messages) > 40:
            self.messages = [self.messages[0]] + self.messages[-30:]


class AgentOrchestrator:
    def __init__(
        self,
        llm: LLMClient,
        tools: ToolRegistry,
        max_tool_rounds: int = 6,
    ):
        self.llm = llm
        self.tools = tools
        self.max_tool_rounds = max_tool_rounds
        self.sessions: dict[str, AgentSession] = {}

    def get_session(self, session_id: str) -> AgentSession:
        if session_id not in self.sessions:
            self.sessions[session_id] = AgentSession(session_id)
        return self.sessions[session_id]

    async def chat(self, session_id: str, user_message: str) -> dict[str, Any]:
        session = self.get_session(session_id)
        session.add("user", user_message)

        # 用户确认 pending 配置的快捷路径
        if session.pending_patch and self._is_confirm(user_message):
            result = await self.tools.execute(
                "apply_config",
                {"patch": session.pending_patch, "note": "user confirmed via chat"},
            )
            session.pending_patch = None
            summary = self._format_tool_result("apply_config", result)
            session.add("assistant", summary)
            return {
                "session_id": session_id,
                "reply": summary,
                "tool_trace": [{"tool": "apply_config", "result": result}],
                "pending_confirm": False,
            }

        if session.pending_patch and self._is_cancel(user_message):
            session.pending_patch = None
            reply = "已取消本次配置修改。"
            session.add("assistant", reply)
            return {
                "session_id": session_id,
                "reply": reply,
                "tool_trace": [],
                "pending_confirm": False,
            }

        tool_trace: list[dict] = []
        tool_schemas = self.tools.schemas()

        for round_i in range(self.max_tool_rounds):
            try:
                llm_out = await self.llm.chat(session.messages, tools=tool_schemas)
            except Exception as e:
                logger.exception("LLM call failed")
                # 无 LLM 时的降级：简单规则路由
                reply = await self._fallback_router(user_message, session)
                session.add("assistant", reply)
                return {
                    "session_id": session_id,
                    "reply": reply,
                    "tool_trace": tool_trace,
                    "pending_confirm": session.pending_patch is not None,
                    "llm_error": str(e),
                }

            content = llm_out.get("content")
            tool_calls = llm_out.get("tool_calls") or []

            if not tool_calls:
                reply = content or "我没有更多操作了。还有什么需要？"
                session.add("assistant", reply)
                return {
                    "session_id": session_id,
                    "reply": reply,
                    "tool_trace": tool_trace,
                    "pending_confirm": session.pending_patch is not None,
                }

            # 执行工具
            for tc in tool_calls:
                name = tc["name"]
                args = tc.get("arguments") or {}
                logger.info(f"Tool call: {name} {args}")
                result = await self.tools.execute(name, args)
                tool_trace.append({"tool": name, "arguments": args, "result": result})

                # 配置预览 → 记下 pending
                if name in ("preview_config", "switch_mode") and result.get("ok"):
                    res = result.get("result") or {}
                    if res.get("preview") or res.get("changes") is not None:
                        # 从 preview 结果还原 patch
                        if name == "switch_mode" and "mode" in args:
                            session.pending_patch = {"mode": args["mode"]}
                        elif "patch" in args:
                            session.pending_patch = args["patch"]

                # 把工具结果塞回对话（OpenAI 风格简化为 user/tool 文本）
                tool_text = self._format_tool_result(name, result)
                session.messages.append({
                    "role": "assistant",
                    "content": content or f"[调用工具 {name}]",
                })
                session.messages.append({
                    "role": "user",
                    "content": f"【工具 {name} 返回】\n{tool_text}\n请根据结果用中文回复用户。"
                    + (" 若这是配置预览，请列出变更并询问是否确认。" if name in ("preview_config", "switch_mode") else ""),
                })

        # 超过轮次仍未结束
        reply = "工具调用轮次已用尽。请根据已有结果继续提问，或简化你的需求。"
        session.add("assistant", reply)
        return {
            "session_id": session_id,
            "reply": reply,
            "tool_trace": tool_trace,
            "pending_confirm": session.pending_patch is not None,
        }

    async def _fallback_router(self, text: str, session: AgentSession) -> str:
        """LLM 不可用时的简单关键词路由，保证服务可测"""
        t = text.strip().lower()
        if any(k in t for k in ("价格", "price", "行情")):
            r = await self.tools.execute("get_latest_price", {"symbol": "BTCUSDT"})
            return self._format_tool_result("get_latest_price", r)
        if any(k in t for k in ("配置", "config", "当前模式")):
            r = await self.tools.execute("get_config", {})
            return self._format_tool_result("get_config", r)
        if any(k in t for k in ("回测", "backtest", "胜率")):
            r = await self.tools.execute("run_backtest", {"days": 7})
            return self._format_tool_result("run_backtest", r)
        if any(k in t for k in ("信号", "signal")):
            r = await self.tools.execute("generate_signals", {})
            return self._format_tool_result("generate_signals", r)
        if "事件合约" in text or "event" in t:
            r = await self.tools.execute("switch_mode", {"mode": "event_30m"})
            if r.get("ok"):
                session.pending_patch = {"mode": "event_30m"}
            return self._format_tool_result("switch_mode", r) + "\n\n回复「确认」以生效，或「取消」。"
        return (
            "当前 LLM 未连通，已启用降级路由。你可以试着说：\n"
            "- 查看价格\n- 查看配置\n- 跑一下回测\n- 生成信号\n- 切换到事件合约"
        )

    @staticmethod
    def _is_confirm(text: str) -> bool:
        t = text.strip().lower()
        return t in ("确认", "确定", "可以", "执行", "是", "yes", "ok", "confirm", "好", "行")

    @staticmethod
    def _is_cancel(text: str) -> bool:
        t = text.strip().lower()
        return t in ("取消", "不要", "算了", "cancel", "no", "否")

    @staticmethod
    def _format_tool_result(name: str, result: dict) -> str:
        if not result.get("ok"):
            return f"工具 {name} 失败：{result.get('error')}"
        data = result.get("result", {})
        try:
            # 针对常见工具做可读摘要
            if name == "get_latest_price":
                return f"{data.get('symbol')} 最新价：{data.get('price')}（{data.get('timestamp')}）"
            if name == "run_backtest":
                return (
                    f"回测完成 [{data.get('plugin_name')} / {data.get('mode')}]\n"
                    f"- 信号数：{data.get('total_signals')}\n"
                    f"- 胜率：{data.get('win_rate')}\n"
                    f"- 总收益：{data.get('total_return')}\n"
                    f"- 最大回撤：{data.get('max_drawdown')}\n"
                    f"- 盈亏比：{data.get('profit_factor')}\n"
                    f"- 区间：{data.get('data_start')} ~ {data.get('data_end')}"
                )
            if name in ("preview_config", "switch_mode"):
                changes = data.get("changes") or []
                lines = [f"  {c.get('path')}: {c.get('old')} → {c.get('new')}" for c in changes]
                return "配置变更预览：\n" + ("\n".join(lines) if lines else json.dumps(data, ensure_ascii=False, indent=2))
            if name == "apply_config":
                return "配置已生效。\n" + json.dumps(data.get("changes"), ensure_ascii=False, indent=2)
            if name == "generate_signals":
                cnt = data.get("count", 0)
                sigs = data.get("signals") or []
                tail = sigs[-3:] if sigs else []
                return f"生成信号 {cnt} 条。最近：\n" + json.dumps(tail, ensure_ascii=False, indent=2)
            return json.dumps(data, ensure_ascii=False, indent=2)[:3000]
        except Exception:
            return str(data)[:2000]
