import { defineConfig } from "vitest/config";
import { loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { DEFAULT_API_PROXY_TARGET, FRONTEND_DEV_PORT } from "./src/shared/runtimeConfig";

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
  const backendTarget = env.VITE_API_PROXY_TARGET || DEFAULT_API_PROXY_TARGET;

  return {
    plugins: [vueUsePureAnnotationCompatibility, vue()],
    server: {
      port: FRONTEND_DEV_PORT,
      proxy: {
        "/api": { target: backendTarget, changeOrigin: true },
        "/actuator": { target: backendTarget, changeOrigin: true }
      }
    },
    build: {
      outDir: "../target/frontend-static",
      emptyOutDir: true,
      rollupOptions: {
        output: {
          manualChunks: {
            "vue-vendor": ["vue", "vue-router"]
          }
        }
      }
    },
    test: {
      environment: "jsdom",
      globals: true
    }
  };
});
