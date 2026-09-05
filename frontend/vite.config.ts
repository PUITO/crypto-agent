import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// GitHub Pages 项目站需要 base: '/crypto-agent/'
const base = process.env.VITE_BASE || "/";

export default defineConfig({
  base,
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/gw": {
        target: "http://localhost:8000",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/gw/, ""),
      },
    },
  },
});
