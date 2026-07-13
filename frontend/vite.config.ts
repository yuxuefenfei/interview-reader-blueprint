import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": "http://localhost:28080",
      "/actuator": "http://localhost:28080"
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
