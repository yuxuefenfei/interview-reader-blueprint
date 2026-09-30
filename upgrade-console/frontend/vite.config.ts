import {defineConfig} from "vitest/config";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import {ElementPlusResolver} from "unplugin-vue-components/resolvers";

export default defineConfig(({mode}) => ({
    plugins: [
        Components({
            dts: mode === "development" ? "src/components.d.ts" : false,
            resolvers: [ElementPlusResolver({importStyle: mode === "test" ? false : "css"})]
        }),
        vue()
    ],
    server: {
        port: 28082,
        proxy: {
            "/api": {target: "http://127.0.0.1:28081", changeOrigin: true}
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
}));