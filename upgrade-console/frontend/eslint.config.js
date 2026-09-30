import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";

export default tseslint.config(
    {ignores: ["../target/**", "src/components.d.ts"]},
    js.configs.recommended,
    ...tseslint.configs.recommended,
    ...pluginVue.configs["flat/recommended"],
    {
        files: ["**/*.{ts,vue}"],
        languageOptions: {
            globals: {...globals.browser, ...globals.node},
            parserOptions: {parser: tseslint.parser, extraFileExtensions: [".vue"]}
        },
        rules: {
            "vue/multi-word-component-names": "off",
            "vue/max-attributes-per-line": "off",
            "vue/singleline-html-element-content-newline": "off",
            "vue/multiline-html-element-content-newline": "off",
            "vue/html-self-closing": "off",
            "vue/attributes-order": "off"
        }
    }
);