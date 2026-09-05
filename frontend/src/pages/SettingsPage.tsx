import { useEffect, useState } from "react";
import { api } from "../api/client";

export default function SettingsPage() {
  const [mode, setMode] = useState("event_30m");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [plugin, setPlugin] = useState("kdj_rsi_event");
  const [holdBars, setHoldBars] = useState(6);
  const [requireConfirm, setRequireConfirm] = useState(true);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api
      .getConfig()
      .then((res) => {
        const c = res.config || {};
        setMode(c.mode || "event_30m");
        setSymbol(c.symbol || "BTCUSDT");
        setPlugin(c.active_plugin || "kdj_rsi_event");
        setHoldBars(c.hold_bars ?? 6);
        setRequireConfirm(c.require_confirm_on_config_change !== false);
      })
      .catch((e) => setErr(e.message));
  }, []);

  async function save() {
    setSaving(true);
    setMsg("");
    setErr("");
    try {
      await api.applyConfig({
        mode,
        symbol,
        active_plugin: plugin,
        enabled_plugins: [plugin],
        hold_bars: holdBars,
        require_confirm_on_config_change: requireConfirm,
      });
      setMsg("已保存");
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>基础设置</h2>
      </div>
      <div className="settings-form">
        {err && <p className="error-text">{err}</p>}
        {msg && <p className="ok-text">{msg}</p>}
        <label>交易模式</label>
        <select value={mode} onChange={(e) => setMode(e.target.value)}>
          <option value="event_30m">事件合约 30m</option>
          <option value="perpetual">永续合约</option>
          <option value="both">双模式</option>
        </select>
        <label>交易对</label>
        <input value={symbol} onChange={(e) => setSymbol(e.target.value.toUpperCase())} />
        <label>策略插件</label>
        <input value={plugin} onChange={(e) => setPlugin(e.target.value)} />
        <label>事件持有 K 线根数 (hold_bars)</label>
        <input
          type="number"
          value={holdBars}
          min={1}
          max={48}
          onChange={(e) => setHoldBars(Number(e.target.value))}
        />
        <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <input
            type="checkbox"
            checked={requireConfirm}
            onChange={(e) => setRequireConfirm(e.target.checked)}
          />
          Chat 修改配置需要确认
        </label>
        <div className="row" style={{ justifyContent: "flex-start", marginTop: 16 }}>
          <button className="btn primary" type="button" disabled={saving} onClick={save}>
            {saving ? "保存中…" : "保存配置"}
          </button>
        </div>
        <p className="hint">
          Chat 对话相关设置请在首页 Chat 右上角「Chat设置」中打开对话框修改。
        </p>
      </div>
    </div>
  );
}
