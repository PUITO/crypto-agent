import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";

type Msg = { role: "user" | "assistant" | "system"; content: string };

type Props = {
  onConfigMaybeChanged?: () => void;
};

const SESSION_KEY = "crypto-agent-session";

function getSessionId() {
  let id = localStorage.getItem(SESSION_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

export default function ChatPanel({ onConfigMaybeChanged }: Props) {
  const [messages, setMessages] = useState<Msg[]>([
    {
      role: "system",
      content: "可以说：查看价格 / 查看配置 / 跑回测 / 切换到事件合约 / 画压力位",
    },
  ]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [sessionId] = useState(getSessionId);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, loading]);

  async function send(text?: string) {
    const content = (text ?? input).trim();
    if (!content || loading) return;
    setInput("");
    setMessages((m) => [...m, { role: "user", content }]);
    setLoading(true);
    try {
      const res = await api.chat(content, sessionId);
      const reply = res.reply || JSON.stringify(res);
      setMessages((m) => [...m, { role: "assistant", content: reply }]);
      if (res.pending_confirm) {
        setMessages((m) => [
          ...m,
          { role: "system", content: "有配置待确认：回复「确认」生效，或「取消」。" },
        ]);
      }
      if (content.includes("确认") || content.includes("配置") || content.includes("切换")) {
        onConfigMaybeChanged?.();
      }
    } catch (e: any) {
      setMessages((m) => [
        ...m,
        {
          role: "assistant",
          content: `请求失败：${e.message}\n请确认 Gateway(:8000) 与 Agent(:8006) 已启动。`,
        },
      ]);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="right">
      <div className="chat-header">
        <span>Chat</span>
        <div className="actions">
          <button className="btn" type="button" onClick={() => send("查看当前配置")}>
            配置
          </button>
          <button className="btn" type="button" onClick={() => send("跑一下回测")}>
            回测
          </button>
        </div>
      </div>
      <div className="messages">
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role}`}>
            {m.content}
          </div>
        ))}
        {loading && <div className="msg system">思考中…</div>}
        <div ref={bottomRef} />
      </div>
      <div className="composer">
        <input
          value={input}
          placeholder="输入消息，Enter 发送"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          disabled={loading}
        />
        <button className="btn primary" type="button" disabled={loading} onClick={() => send()}>
          发送
        </button>
      </div>
    </div>
  );
}
