import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": { target: process.env.VITE_API_PROXY_TARGET || "http://127.0.0.1:28080", changeOrigin: true },
      "/actuator": { target: process.env.VITE_API_PROXY_TARGET || "http://127.0.0.1:28080", changeOrigin: true }
    }
  },
  build: {
    outDir: "../target/frontend-static",
    emptyOutDir: true
  },
  test: {
    environment: "jsdom",
    globals: true
  }
});
