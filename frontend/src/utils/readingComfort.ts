const FONT_SIZE_KEY = "reader.fontSize";
const LINE_HEIGHT_KEY = "reader.lineHeight";
const COLUMN_WIDTH_KEY = "reader.columnWidth";

export type ReaderTheme = "light" | "dark" | "sepia";

export interface ReaderComfort {
  fontSize: number;
  lineHeight: number;
  columnWidth: number;
}

export const FONT_SIZE_OPTIONS = [16, 17, 18, 19, 20, 22] as const;
export const LINE_HEIGHT_OPTIONS = [
  { label: "紧凑 1.70", value: 1.7 },
  { label: "舒适 1.85", value: 1.85 },
  { label: "宽松 1.95", value: 1.95 },
] as const;
export const COLUMN_WIDTH_OPTIONS = [
  { label: "紧凑 640", value: 640 },
  { label: "舒适 740", value: 740 },
  { label: "宽松 860", value: 860 },
] as const;

const THEME_ORDER: ReaderTheme[] = ["light", "dark", "sepia"];

function readNumber(key: string, fallback: number, min: number, max: number): number {
  const stored = localStorage.getItem(key);
  if (stored === null || stored.trim() === "") return fallback;
  const raw = Number(stored);
  if (!Number.isFinite(raw)) return fallback;
  return Math.min(max, Math.max(min, raw));
}

export function loadReaderComfort(): ReaderComfort {
  return {
    fontSize: readNumber(FONT_SIZE_KEY, 18, 16, 22),
    lineHeight: readNumber(LINE_HEIGHT_KEY, 1.85, 1.6, 2.1),
    columnWidth: readNumber(COLUMN_WIDTH_KEY, 740, 560, 960),
  };
}

export function persistReaderComfort(comfort: ReaderComfort): void {
  localStorage.setItem(FONT_SIZE_KEY, String(comfort.fontSize));
  localStorage.setItem(LINE_HEIGHT_KEY, String(comfort.lineHeight));
  localStorage.setItem(COLUMN_WIDTH_KEY, String(comfort.columnWidth));
}

export function loadReaderTheme(): ReaderTheme {
  const value = localStorage.getItem("reader.theme");
  return value === "dark" || value === "sepia" ? value : "light";
}

export function nextReaderTheme(current: ReaderTheme): ReaderTheme {
  const index = THEME_ORDER.indexOf(current);
  return THEME_ORDER[(index + 1) % THEME_ORDER.length];
}

export function themeActionLabel(theme: ReaderTheme): string {
  if (theme === "dark") return "护眼";
  if (theme === "sepia") return "浅色";
  return "深色";
}

export function comfortStyle(comfort: ReaderComfort): Record<string, string> {
  return {
    "--reader-font-size": `${comfort.fontSize}px`,
    "--reader-line-height": String(comfort.lineHeight),
    "--reader-column": `${comfort.columnWidth}px`,
  };
}
