import { useState } from "react";
import { api } from "../api/client";
import {
  getPat,
  setPat,
  clearPat,
  getSyncRepo,
  setSyncRepo,
} from "../lib/patCookie";

/**
 * 敏感密钥与连接测试：
 * - GitHub PAT 仅存 Cookie，不写入 Config 服务
 * - HF / LLM / 通知 等可测有效性
 */
export default function SecretsTestPanel() {
  const [pat, setPatState] = useState(getPat());
  const [repo, setRepo] = useState(getSyncRepo());
  const [hfToken, setHfToken] = useState("");
  const [openaiKey, setOpenaiKey] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  async function run(label: string, fn: () => Promise<any>) {
    setBusy(true);
    setMsg("");
    try {
      const r = await fn();
      setMsg(`${label}: ${r.message || r.ok ? "OK" : "失败"} ${r.models ? "models=" + r.models : ""}`);
    } catch (e: any) {
      setMsg(`${label} 失败: ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="settings-form" style={{ marginTop: 24, maxWidth: 560 }}>
      <h3>密钥与连接测试</h3>
      <p className="hint">
        GitHub PAT <strong>只保存在浏览器 Cookie</strong>，不会写入服务器配置，避免公网实例被盗用。
        同步持久化文件时由前端把 PAT 放在请求头传给 Sync 服务（用完即弃，服务端不落盘）。
      </p>

      <label>私有同步仓库 (owner/repo)</label>
      <input value={repo} onChange={(e) => setRepo(e.target.value)} placeholder="you/crypto-agent-private" />

      <label>GitHub PAT（仅 Cookie）</label>
      <input
        type="password"
        value={pat}
        onChange={(e) => setPatState(e.target.value)}
        placeholder="ghp_..."
      />
      <div className="row" style={{ justifyContent: "flex-start", gap: 8, marginBottom: 12 }}>
        <button
          className="btn primary"
          type="button"
          disabled={busy}
          onClick={() => {
            setPat(pat);
            setSyncRepo(repo);
            setMsg("已写入浏览器 Cookie");
          }}
        >
          保存到 Cookie
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() =>
            run("PAT 测试", () => api.testGithubPat(pat || getPat(), repo || getSyncRepo()))
          }
        >
          测试 PAT
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() => run("拉取持久化", () => api.syncPull())}
        >
          拉取持久化
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() => run("推送持久化", () => api.syncPush())}
        >
          推送持久化
        </button>
        <button
          className="btn"
          type="button"
          onClick={() => {
            clearPat();
            setPatState("");
            setMsg("已清除 Cookie 中的 PAT");
          }}
        >
          清除 PAT
        </button>
      </div>

      <label>HF Token（测试用，不强制保存）</label>
      <input type="password" value={hfToken} onChange={(e) => setHfToken(e.target.value)} />
      <button
        className="btn"
        type="button"
        disabled={busy}
        onClick={() => run("HF", () => api.testConnection({ type: "hf", token: hfToken }))}
      >
        测试 HuggingFace
      </button>

      <label style={{ marginTop: 12 }}>OpenAI 兼容 API Key</label>
      <input type="password" value={openaiKey} onChange={(e) => setOpenaiKey(e.target.value)} />
      <div className="row" style={{ justifyContent: "flex-start", gap: 8 }}>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() =>
            run("OpenAI", () => api.testConnection({ type: "openai", api_key: openaiKey }))
          }
        >
          测试 OpenAI/兼容 API
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() => run("Ollama", () => api.testConnection({ type: "ollama" }))}
        >
          测试 Ollama
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() => run("Binance", () => api.testConnection({ type: "binance" }))}
        >
          测试 Binance
        </button>
        <button
          className="btn"
          type="button"
          disabled={busy}
          onClick={() =>
            run("Webhook 通知", () =>
              api.testNotify({ channels: ["webhook"], message: "前端配置测试" })
            )
          }
        >
          测试 Webhook 通知
        </button>
      </div>

      {msg && <p className={msg.includes("失败") ? "error-text" : "ok-text"}>{msg}</p>}
    </div>
  );
}
