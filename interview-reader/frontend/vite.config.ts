import {defineConfig} from "vitest/config";
import {loadEnv} from "vite";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import {ElementPlusResolver} from "unplugin-vue-components/resolvers";
import {DEFAULT_API_PROXY_TARGET, FRONTEND_DEV_PORT} from "./src/shared/runtimeConfig";

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

export default defineConfig(({mode}) => {
    const env = loadEnv(mode, ".", "VITE_");
    const backendTarget = env.VITE_API_PROXY_TARGET || DEFAULT_API_PROXY_TARGET;
    const buildId = new Date().toISOString().replace(/\D/g, "");

    return {
        define: {__APP_BUILD_ID__: JSON.stringify(buildId)},
        plugins: [
            vueUsePureAnnotationCompatibility,
            Components({
                dts: mode === "development" ? "src/generated/components.d.ts" : false,
                // 组件样式应打包到生产资源中。单元测试使用组件替身，
                // 不能让 Node 的原生 ESM 加载器执行 CSS 文件。
                resolvers: [ElementPlusResolver({importStyle: mode === "test" ? false : "css"})]
            }),
            vue()
        ],
        server: {
            port: FRONTEND_DEV_PORT,
            proxy: {
                "/api": {target: backendTarget, changeOrigin: true},
                "/actuator": {target: backendTarget, changeOrigin: true}
            }
        },
        build: {
            outDir: "../target/frontend-static",
            emptyOutDir: true,
            manifest: true,
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
            globals: true,
            pool: "threads"
        }
    };
});
