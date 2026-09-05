import { useCallback, useEffect, useState } from "react";
import { api } from "./api/client";
import PriceChart, { type Candle } from "./components/PriceChart";
import ChatPanel from "./components/ChatPanel";
import HealthPage from "./pages/HealthPage";
import SettingsPage from "./pages/SettingsPage";

type Page = "home" | "settings" | "health";

export default function App() {
  const [page, setPage] = useState<Page>("home");
  const [candles, setCandles] = useState<Candle[]>([]);
  const [drawings, setDrawings] = useState<any[]>([]);
  const [price, setPrice] = useState<number | null>(null);
  const [mode, setMode] = useState<string>("-");
  const [symbol, setSymbol] = useState("BTCUSDT");
  const [gwOk, setGwOk] = useState<boolean | null>(null);
  const [lastRefresh, setLastRefresh] = useState("");

  const refreshConfig = useCallback(async () => {
    try {
      const res = await api.getConfig();
      const c = res.config || {};
      setMode(c.mode || "-");
      setSymbol(c.symbol || "BTCUSDT");
    } catch {
      /* ignore */
    }
  }, []);

  const refreshMarket = useCallback(async () => {
    try {
      const [kl, px, dr] = await Promise.all([
        api.klines(symbol, "5m", 300, "binance").catch(() => api.klines(symbol, "5m", 300, "local")),
        api.latestPrice(symbol),
        api.listDrawings(symbol).catch(() => ({ drawings: [] })),
      ]);
      const data = (kl.data || []).map((r: any) => ({
        time: r.open_time,
        open: r.open,
        high: r.high,
        low: r.low,
        close: r.close,
      }));
      setCandles(data);
      setPrice(px.price ?? null);
      setDrawings(dr.drawings || []);
      setLastRefresh(new Date().toLocaleTimeString());
      setGwOk(true);
    } catch {
      setGwOk(false);
    }
  }, [symbol]);

  useEffect(() => {
    if (page !== "home") return;
    refreshConfig();
    refreshMarket();
    const t = setInterval(refreshMarket, 30_000);
    return () => clearInterval(t);
  }, [page, refreshConfig, refreshMarket]);

  return (
    <div className="app">
      <header className="header">
        <div className="brand">Crypto Agent</div>
        <nav className="nav">
          <button
            type="button"
            className={`nav-item ${page === "home" ? "active" : ""}`}
            onClick={() => setPage("home")}
          >
            首页
          </button>
          <button
            type="button"
            className={`nav-item ${page === "settings" ? "active" : ""}`}
            onClick={() => setPage("settings")}
          >
            基础设置
          </button>
          <button
            type="button"
            className={`nav-item ${page === "health" ? "active" : ""}`}
            onClick={() => setPage("health")}
          >
            健康运维
          </button>
        </nav>
        <div className="meta">
          <span className={`badge ${gwOk ? "ok" : gwOk === false ? "warn" : ""}`}>
            Gateway {gwOk === null ? "…" : gwOk ? "OK" : "离线"}
          </span>
          {page === "home" && (
            <>
              <span className="badge">模式 {mode}</span>
              <span className="badge">{symbol}</span>
              {price != null && (
                <span className="badge">
                  价格 <strong style={{ color: "var(--text)" }}>{price}</strong>
                </span>
              )}
              <button className="btn" type="button" onClick={refreshMarket}>
                刷新
              </button>
            </>
          )}
        </div>
      </header>

      {page === "home" && (
        <div className="main">
          <div className="left">
            <PriceChart candles={candles} drawings={drawings} />
            <div className="stats">
              <span>
                K线<strong>{candles.length}</strong>
              </span>
              <span>
                绘图<strong>{drawings.length}</strong>
              </span>
              <span>
                更新<strong>{lastRefresh || "-"}</strong>
              </span>
              <span style={{ color: "var(--muted)" }}>仅模拟分析，不构成投资建议</span>
            </div>
          </div>
          <ChatPanel onConfigMaybeChanged={refreshConfig} />
        </div>
      )}
      {page === "settings" && <SettingsPage />}
      {page === "health" && <HealthPage />}
    </div>
  );
}
