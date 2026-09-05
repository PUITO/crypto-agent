import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api/client";

type Field = {
  key: string;
  label: string;
  type: string;
  options?: string[];
  min?: number;
  max?: number;
};

type Section = {
  id: string;
  title: string;
  description?: string;
  fields: Field[];
};

function getByPath(obj: any, path: string): any {
  return path.split(".").reduce((acc, k) => (acc == null ? undefined : acc[k]), obj);
}

function setByPath(obj: any, path: string, value: any) {
  const parts = path.split(".");
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const p = parts[i];
    if (typeof cur[p] !== "object" || cur[p] === null) cur[p] = {};
    cur = cur[p];
  }
  cur[parts[parts.length - 1]] = value;
}

function pathToPatch(path: string, value: any): object {
  const parts = path.split(".");
  const patch: any = {};
  let cur = patch;
  for (let i = 0; i < parts.length - 1; i++) {
    cur[parts[i]] = {};
    cur = cur[parts[i]];
  }
  cur[parts[parts.length - 1]] = value;
  return patch;
}

export default function SettingsPage() {
  const [sections, setSections] = useState<Section[]>([]);
  const [config, setConfig] = useState<any>({});
  const [tab, setTab] = useState("trading");
  const [draft, setDraft] = useState<Record<string, any>>({});
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setErr("");
    try {
      const [cfgRes, schemaRes] = await Promise.all([
        api.getConfig(),
        api.getConfigSchema(),
      ]);
      const c = cfgRes.config || {};
      setConfig(c);
      const secs: Section[] = schemaRes.sections || [];
      setSections(secs);
      if (secs.length && !secs.find((s) => s.id === tab)) {
        setTab(secs[0].id);
      }
      const d: Record<string, any> = {};
      for (const s of secs) {
        for (const f of s.fields) {
          let v = getByPath(c, f.key);
          if (f.type === "string_list" && Array.isArray(v)) v = v.join(",");
          d[f.key] = v;
        }
      }
      setDraft(d);
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => {
    load();
  }, []);

  const current = useMemo(
    () => sections.find((s) => s.id === tab) || sections[0],
    [sections, tab]
  );

  function updateField(key: string, value: any) {
    setDraft((prev) => ({ ...prev, [key]: value }));
  }

  async function saveSection() {
    if (!current) return;
    setSaving(true);
    setMsg("");
    setErr("");
    try {
      let patch: any = {};
      for (const f of current.fields) {
        let v = draft[f.key];
        if (f.type === "number") v = v === "" || v == null ? null : Number(v);
        if (f.type === "string_list") {
          v = String(v || "")
            .split(",")
            .map((x) => x.trim())
            .filter(Boolean);
        }
        if (f.type === "password" && (v === "***" || v === "" || v == null)) {
          continue; // 不覆盖脱敏/空密码
        }
        const part = pathToPatch(f.key, v);
        // deep merge part into patch
        const merge = (a: any, b: any) => {
          for (const k of Object.keys(b)) {
            if (typeof b[k] === "object" && b[k] && !Array.isArray(b[k])) {
              a[k] = merge(a[k] || {}, b[k]);
            } else a[k] = b[k];
          }
          return a;
        };
        patch = merge(patch, part);
      }
      // 兼容扁平字段
      if (patch.trading?.mode) patch.mode = patch.trading.mode;
      if (patch.trading?.symbol) patch.symbol = patch.trading.symbol;
      if (patch.plugins?.active_plugin) patch.active_plugin = patch.plugins.active_plugin;
      if (patch.plugins?.enabled_plugins) patch.enabled_plugins = patch.plugins.enabled_plugins;
      if (patch.plugins?.strategy_params) patch.strategy_params = patch.plugins.strategy_params;
      if (patch.agent?.require_confirm_on_config_change != null) {
        patch.require_confirm_on_config_change = patch.agent.require_confirm_on_config_change;
      }

      await api.applyConfig(patch, `settings:${current.id}`);
      setMsg(`${current.title} 已保存`);
      await load();
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="page">
        <p style={{ color: "var(--muted)" }}>加载配置中…</p>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>基础设置</h2>
        <div className="page-actions">
          <button className="btn" type="button" onClick={load}>
            重新加载
          </button>
          <button className="btn primary" type="button" disabled={saving} onClick={saveSection}>
            {saving ? "保存中…" : "保存当前分栏"}
          </button>
        </div>
      </div>
      {err && <p className="error-text">{err}</p>}
      {msg && <p className="ok-text">{msg}</p>}

      <div className="settings-layout">
        <aside className="settings-tabs">
          {sections.map((s) => (
            <button
              key={s.id}
              type="button"
              className={`settings-tab ${tab === s.id ? "active" : ""}`}
              onClick={() => {
                setTab(s.id);
                setMsg("");
              }}
            >
              {s.title}
            </button>
          ))}
        </aside>
        <div className="settings-form">
          {current && (
            <>
              <h3 style={{ marginTop: 0 }}>{current.title}</h3>
              {current.description && <p className="hint">{current.description}</p>}
              {current.fields.map((f) => {
                const val = draft[f.key];
                if (f.type === "boolean") {
                  return (
                    <label
                      key={f.key}
                      style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 12 }}
                    >
                      <input
                        type="checkbox"
                        checked={!!val}
                        onChange={(e) => updateField(f.key, e.target.checked)}
                      />
                      {f.label}
                    </label>
                  );
                }
                if (f.type === "select") {
                  return (
                    <div key={f.key}>
                      <label>{f.label}</label>
                      <select
                        value={val ?? ""}
                        onChange={(e) => updateField(f.key, e.target.value)}
                      >
                        {(f.options || []).map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                      </select>
                    </div>
                  );
                }
                return (
                  <div key={f.key}>
                    <label>{f.label}</label>
                    <input
                      type={f.type === "password" ? "password" : f.type === "number" ? "number" : "text"}
                      value={val ?? ""}
                      min={f.min}
                      max={f.max}
                      onChange={(e) =>
                        updateField(
                          f.key,
                          f.type === "number" ? e.target.value : e.target.value
                        )
                      }
                      placeholder={f.type === "password" ? "留空则不修改" : ""}
                    />
                  </div>
                );
              })}
              <div className="row" style={{ justifyContent: "flex-start", marginTop: 16 }}>
                <button className="btn primary" type="button" disabled={saving} onClick={saveSection}>
                  {saving ? "保存中…" : "保存当前分栏"}
                </button>
              </div>
            </>
          )}
          <p className="hint">
            所有微服务运行参数统一由 Config 服务管理。Chat 设置仍在首页 Chat 右上角 Dialog。
          </p>
        </div>
      </div>
    </div>
  );
}
