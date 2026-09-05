const BASE =
  (import.meta as any).env?.VITE_GATEWAY_URL?.replace(/\/$/, "") ||
  (typeof window !== "undefined" && window.location.port === "5173"
    ? "/gw"
    : "http://localhost:8000");

const OPS_DIRECT = "http://localhost:8008";

async function request<T = any>(
  path: string,
  options: RequestInit = {},
  base = BASE
): Promise<T> {
  const url = `${base}${path.startsWith("/") ? path : `/${path}`}`;
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

async function opsRequest<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  try {
    return await request<T>(`/ops${path}`, options);
  } catch {
    return await request<T>(path, options, OPS_DIRECT);
  }
}

export const api = {
  base: BASE,
  latestPrice: (symbol = "BTCUSDT") =>
    request(`/data/api/v1/latest_price?symbol=${symbol}`),
  klines: (symbol = "BTCUSDT", interval = "5m", limit = 300, source = "local") =>
    request(
      `/data/api/v1/klines?symbol=${symbol}&interval=${interval}&limit=${limit}&source=${source}`
    ),
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
  listDrawings: (symbol?: string) =>
    request(`/chart/api/v1/drawings${symbol ? `?symbol=${symbol}` : ""}`),
  clearDrawings: (symbol?: string) =>
    request(`/chart/api/v1/drawings${symbol ? `?symbol=${symbol}` : ""}`, {
      method: "DELETE",
    }),
  chat: (message: string, sessionId?: string) =>
    request(`/agent/api/v1/chat`, {
      method: "POST",
      body: JSON.stringify({ message, session_id: sessionId }),
    }),
  healthAll: () => request(`/api/v1/health/all`),
  listServices: () => opsRequest(`/api/v1/services`),
  startService: (name: string) =>
    opsRequest(`/api/v1/services/${name}/start`, { method: "POST" }),
  stopService: (name: string) =>
    opsRequest(`/api/v1/services/${name}/stop`, { method: "POST" }),
  restartService: (name: string) =>
    opsRequest(`/api/v1/services/${name}/restart`, { method: "POST" }),
  startAllServices: () =>
    opsRequest(`/api/v1/services/start_all`, { method: "POST" }),
  stopAllServices: () =>
    opsRequest(`/api/v1/services/stop_all`, { method: "POST" }),
  getLogs: (name: string, lines = 200) =>
    opsRequest(`/api/v1/logs/${name}?lines=${lines}`),
};

export default api;
