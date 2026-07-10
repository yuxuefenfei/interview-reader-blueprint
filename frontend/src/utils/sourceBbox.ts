import type { CSSProperties } from "vue";
import type { SourceBbox } from "../types/api";

export type SourceOverlay = Pick<CSSProperties, "left" | "top" | "width" | "height">;

const DEFAULT_PDF_WIDTH = 595;
const DEFAULT_PDF_HEIGHT = 842;

export function pageForBbox(bbox: SourceBbox | null | undefined): number | null {
  const page = Number(bbox?.page);
  return Number.isFinite(page) && page > 0 ? page : null;
}

export function sourceOverlayStyle(bbox: SourceBbox | null | undefined): SourceOverlay | null {
  const x = Number(bbox?.x);
  const y = Number(bbox?.y);
  const width = Number(bbox?.width);
  const height = Number(bbox?.height);
  if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) {
    return null;
  }

  const normalized = x >= 0 && y >= 0 && x <= 1 && y <= 1 && width <= 1 && height <= 1;
  const pageWidth = normalized ? 1 : DEFAULT_PDF_WIDTH;
  const pageHeight = normalized ? 1 : DEFAULT_PDF_HEIGHT;
  const left = clamp((x / pageWidth) * 100, 0, 98);
  const top = clamp((y / pageHeight) * 100, 0, 98);
  const overlayWidth = clamp((width / pageWidth) * 100, 2, 100 - left);
  const overlayHeight = clamp((height / pageHeight) * 100, 2, 100 - top);

  return {
    left: `${roundPercent(left)}%`,
    top: `${roundPercent(top)}%`,
    width: `${roundPercent(overlayWidth)}%`,
    height: `${roundPercent(overlayHeight)}%`
  };
}

export function sourceOverlayLabel(bbox: SourceBbox | null | undefined, fallbackPage?: number | null): string {
  const x = Number(bbox?.x);
  const y = Number(bbox?.y);
  const width = Number(bbox?.width);
  const height = Number(bbox?.height);
  if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) {
    return "";
  }
  const page = pageForBbox(bbox) ?? fallbackPage;
  const prefix = page ? `p${page} · ` : "";
  return `${prefix}x${Math.round(x)} y${Math.round(y)} · ${Math.round(width)}x${Math.round(height)}`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function roundPercent(value: number): number {
  return Math.round(value * 100) / 100;
}
