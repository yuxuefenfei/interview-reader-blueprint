export function clampProgressRatio(value: number | null | undefined): number {
  return Math.min(1, Math.max(0, typeof value === "number" && Number.isFinite(value) ? value : 0));
}

export function documentReadingPositionRatio(
  readableIndex: number,
  readableCount: number,
  chapterProgressRatio: number,
): number {
  if (readableIndex < 0 || readableCount <= 0 || readableIndex >= readableCount) return 0;
  return clampProgressRatio(
    (readableIndex + clampProgressRatio(chapterProgressRatio)) / readableCount,
  );
}

export function formatProgressPercent(value: number | null | undefined): string {
  const ratio = clampProgressRatio(value);
  if (ratio === 0) return "0%";
  if (ratio < 0.01) return "<1%";
  if (ratio === 1) return "100%";
  return `${Math.min(99, Math.round(ratio * 100))}%`;
}

export function progressWidth(value: number | null | undefined): string {
  return `${clampProgressRatio(value) * 100}%`;
}
