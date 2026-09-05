import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";

type Svc = {
  name: string;
  port: number;
  pid?: number | null;
  running?: boolean;
  health?: { ok?: boolean; error?: string; url?: string };
  log_file?: string;
};

export default function HealthPage() {
  const [services, setServices] = useState<Svc[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const res = await api.listServices();
      setServices(res.services || []);
    } catch (e: any) {
      setError(e.message + "（请先启动 Ops Service :8008）");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 10_000);
    return () => clearInterval(t);
  }, [refresh]);

  async function act(name: string, action: "start" | "stop" | "restart") {
    setBusy(`${action}:${name}`);
    try {
      if (action === "start") await api.startService(name);
      if (action === "stop") await api.stopService(name);
      if (action === "restart") await api.restartService(name);
      await refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  async function loadLogs(name: string) {
    setSelected(name);
    try {
      const res = await api.getLogs(name, 300);
      setLogs(res.lines || []);
    } catch (e: any) {
      setLogs([`加载日志失败: ${e.message}`]);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>健康与运维</h2>
        <div className="page-actions">
          <button className="btn" type="button" onClick={refresh} disabled={loading}>
            刷新
          </button>
          <button
            className="btn primary"
            type="button"
            disabled={!!busy}
            onClick={async () => {
              setBusy("start_all");
              try {
                await api.startAllServices();
                await refresh();
              } catch (e: any) {
                setError(e.message);
              } finally {
                setBusy(null);
              }
            }}
          >
            全部启动
          </button>
          <button
            className="btn"
            type="button"
            disabled={!!busy}
            onClick={async () => {
              if (!confirm("确认停止全部托管服务？")) return;
              setBusy("stop_all");
              try {
                await api.stopAllServices();
                await refresh();
              } catch (e: any) {
                setError(e.message);
              } finally {
                setBusy(null);
              }
            }}
          >
            全部停止
          </button>
        </div>
      </div>
      {error && <p className="error-text">{error}</p>}
      <div className="health-grid">
        <div className="health-table-wrap">
          <table className="health-table">
            <thead>
              <tr>
                <th>服务</th>
                <th>端口</th>
                <th>状态</th>
                <th>PID</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {services.map((s) => {
                const ok = s.health?.ok || s.running;
                return (
                  <tr key={s.name} className={selected === s.name ? "selected" : ""}>
                    <td>
                      <button className="linkish" type="button" onClick={() => loadLogs(s.name)}>
                        {s.name}
                      </button>
                    </td>
                    <td>{s.port}</td>
                    <td>
                      <span className={`badge ${ok ? "ok" : "warn"}`}>
                        {ok ? "健康" : "异常/停止"}
                      </span>
                    </td>
                    <td>{s.pid ?? "-"}</td>
                    <td className="ops-btns">
                      <button
                        className="btn"
                        type="button"
                        disabled={!!busy}
                        onClick={() => act(s.name, "start")}
                      >
                        启动
                      </button>
                      <button
                        className="btn"
                        type="button"
                        disabled={!!busy}
                        onClick={() => act(s.name, "stop")}
                      >
                        停止
                      </button>
                      <button
                        className="btn"
                        type="button"
                        disabled={!!busy}
                        onClick={() => act(s.name, "restart")}
                      >
                        重启
                      </button>
                      <button className="btn" type="button" onClick={() => loadLogs(s.name)}>
                        日志
                      </button>
                    </td>
                  </tr>
                );
              })}
              {!services.length && (
                <tr>
                  <td colSpan={5} style={{ color: "var(--muted)" }}>
                    {loading ? "加载中…" : "无数据"}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="log-panel">
          <div className="log-header">
            日志 {selected ? `— ${selected}` : ""}
            {selected && (
              <button className="btn" type="button" onClick={() => loadLogs(selected)}>
                刷新日志
              </button>
            )}
          </div>
          <pre className="log-body">
            {selected
              ? logs.length
                ? logs.join("\n")
                : "（空日志或文件尚未生成）"
              : "点击服务名或「日志」查看"}
          </pre>
        </div>
      </div>
    </div>
  );
}
