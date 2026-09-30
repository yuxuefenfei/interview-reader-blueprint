export function clampProgressRatio(value: number | null | undefined): number {
  return Math.min(1, Math.max(0, typeof value === "number" && Number.isFinite(value) ? value : 0));
}

export function viewportReadingProgress(
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  hasMoreContent: boolean,
): number {
  const viewportHeight = Math.max(0, clientHeight);
  const contentHeight = Math.max(0, scrollHeight);
  const normalizedScrollTop = Math.max(0, scrollTop);
  const scrollDistance = Math.max(0, contentHeight - viewportHeight);
  const contentBottomIsVisible = normalizedScrollTop + viewportHeight >= contentHeight - 1;

  if (!hasMoreContent && contentBottomIsVisible) return 1;
  if (scrollDistance === 0) return 0;

  const ratio = clampProgressRatio(normalizedScrollTop / scrollDistance);
  return hasMoreContent ? Math.min(0.99, ratio) : ratio;
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
