import {defineConfig} from "@hey-api/openapi-ts";

export default defineConfig({
    input: "../../docs/api/openapi.yaml",
    output: {
        path: "src/generated/api",
        header: "// 此文件由 @hey-api/openapi-ts 自动生成",
        clean: true
    },
    plugins: ["@hey-api/typescript"]
});
