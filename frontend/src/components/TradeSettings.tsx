import { useEffect, useState } from "react";
import { api } from "../api/client";

type Props = {
  open: boolean;
  onClose: () => void;
  onSaved?: () => void;
};

export default function TradeSettings({ open, onClose, onSaved }: Props) {
  const [mode, setMode] = useState("event_30m");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [plugin, setPlugin] = useState("kdj_rsi_event");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    api
      .getConfig()
      .then((res) => {
        const c = res.config || {};
        setMode(c.mode || "event_30m");
        setSymbol(c.symbol || "BTCUSDT");
        setPlugin(c.active_plugin || "kdj_rsi_event");
      })
      .catch((e) => setError(e.message));
  }, [open]);

  if (!open) return null;

  async function save() {
    setSaving(true);
    setError("");
    try {
      await api.applyConfig({
        mode,
        symbol,
        active_plugin: plugin,
        enabled_plugins: [plugin],
      });
      onSaved?.();
      onClose();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>交易设置</h3>
        {error && <p style={{ color: "var(--red)", fontSize: 13 }}>{error}</p>}
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
        <div className="row">
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="button" disabled={saving} onClick={save}>
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}
