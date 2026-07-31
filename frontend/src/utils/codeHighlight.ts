import type { HLJSApi, LanguageFn } from "highlight.js";

type SupportedCodeLanguage = "bash" | "java" | "lua" | "python" | "sql";

const LANGUAGE_ALIASES: Record<string, SupportedCodeLanguage | "text"> = {
  bash: "bash",
  shell: "bash",
  shellscript: "bash",
  sh: "bash",
  java: "java",
  lua: "lua",
  py: "python",
  python: "python",
  mysql: "sql",
  pgsql: "sql",
  postgresql: "sql",
  sql: "sql",
  text: "text",
  plaintext: "text",
  plain: "text",
  txt: "text",
};

const LANGUAGE_LOADERS: Record<SupportedCodeLanguage, () => Promise<{ default: LanguageFn }>> = {
  bash: () => import("highlight.js/lib/languages/bash"),
  java: () => import("highlight.js/lib/languages/java"),
  lua: () => import("highlight.js/lib/languages/lua"),
  python: () => import("highlight.js/lib/languages/python"),
  sql: () => import("highlight.js/lib/languages/sql"),
};

let highlighterPromise: Promise<HLJSApi> | null = null;
const registeredLanguages = new Set<SupportedCodeLanguage>();

function normalizedLanguage(value: string): SupportedCodeLanguage | "text" {
  return LANGUAGE_ALIASES[value.trim().toLowerCase()] ?? "text";
}

async function highlighter(): Promise<HLJSApi> {
  highlighterPromise ??= import("highlight.js/lib/core").then((module) => module.default);
  return highlighterPromise;
}

export async function highlightCode(code: string, language: string): Promise<string | null> {
  const normalized = normalizedLanguage(language);
  if (normalized === "text") {
    return null;
  }
  const instance = await highlighter();
  if (!registeredLanguages.has(normalized)) {
    const definition = await LANGUAGE_LOADERS[normalized]();
    instance.registerLanguage(normalized, definition.default);
    registeredLanguages.add(normalized);
  }
  return instance.highlight(code, { language: normalized, ignoreIllegals: true }).value;
}
