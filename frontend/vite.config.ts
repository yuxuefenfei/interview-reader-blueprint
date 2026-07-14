import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

const vueUsePureAnnotationCompatibility = {
  name: "strip-invalid-vueuse-pure-annotations",
  enforce: "pre" as const,
  transform(code: string, id: string) {
    if (!id.replaceAll("\\", "/").endsWith("/@vueuse/core/dist/index.js")) return null;
    return {
      code: code
        .replace("/* #__PURE__ */\nconst events", "const events")
        .replace("(/* #__PURE__ */ {", "({"),
      map: null
    };
  }
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "VITE_");
  const backendTarget = env.VITE_API_PROXY_TARGET || "http://127.0.0.1:28080";

  return {
    plugins: [vueUsePureAnnotationCompatibility, vue()],
    server: {
      proxy: {
        "/api": { target: backendTarget, changeOrigin: true },
        "/actuator": { target: backendTarget, changeOrigin: true }
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
  };
});