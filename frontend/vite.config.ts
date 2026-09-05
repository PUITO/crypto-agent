import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 开发时把 /api 指到 Gateway，避免 CORS 麻烦（Gateway 已开 CORS 也可直连）
      "/gw": {
        target: "http://localhost:8000",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/gw/, ""),
      },
    },
  },
});
