/**
 * 统一请求 Gateway。
 * 开发环境可用 /gw 代理，也可直连 VITE_GATEWAY_URL。
 */
const BASE =
  (import.meta as any).env?.VITE_GATEWAY_URL?.replace(/\/$/, "") ||
  (typeof window !== "undefined" && window.location.port === "5173"
    ? "/gw"
    : "http://localhost:8000");

async function request<T = any>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const url = `${BASE}${path.startsWith("/") ? path : `/${path}`}`;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };
  const res = await fetch(url, { ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = data?.message || data?.error || res.statusText;
    throw new Error(`${res.status}: ${msg}`);
  }
  return data as T;
}

export const api = {
  base: BASE,

  // Data
  latestPrice: (symbol = "BTCUSDT") =>
    request(`/data/api/v1/latest_price?symbol=${symbol}`),
  klines: (symbol = "BTCUSDT", interval = "5m", limit = 300, source = "local") =>
    request(
      `/data/api/v1/klines?symbol=${symbol}&interval=${interval}&limit=${limit}&source=${source}`
    ),

  // Config
  getConfig: () => request(`/config/api/v1/config`),
  previewConfig: (patch: object) =>
    request(`/config/api/v1/config/preview`, {
      method: "POST",
      body: JSON.stringify({ patch }),
    }),
  applyConfig: (patch: object, note = "frontend") =>
    request(`/config/api/v1/config/apply`, {
      method: "POST",
      body: JSON.stringify({ patch, confirm: true, source: "frontend", note }),
    }),

  // Chart
  listDrawings: (symbol?: string) =>
    request(`/chart/api/v1/drawings${symbol ? `?symbol=${symbol}` : ""}`),
  clearDrawings: (symbol?: string) =>
    request(`/chart/api/v1/drawings${symbol ? `?symbol=${symbol}` : ""}`, {
      method: "DELETE",
    }),

  // Agent Chat
  chat: (message: string, sessionId?: string) =>
    request(`/agent/api/v1/chat`, {
      method: "POST",
      body: JSON.stringify({ message, session_id: sessionId }),
    }),

  // Health
  healthAll: () => request(`/api/v1/health/all`),
};

export default api;
