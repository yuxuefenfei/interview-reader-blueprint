export interface BlockViewport {
  id: string;
  top: number;
  bottom: number;
}

export function blockAtViewportAnchor(blocks: readonly BlockViewport[], anchorY: number): BlockViewport | null {
  return blocks.find((block) => block.top <= anchorY && block.bottom > anchorY)
    ?? blocks.find((block) => block.bottom > anchorY)
    ?? blocks.at(-1)
    ?? null;
}

export function scrollTopForBlockOffset(
  currentScrollTop: number,
  blockViewportTop: number,
  savedBlockViewportOffset: number
): number {
  return Math.max(0, Math.round(currentScrollTop + blockViewportTop - savedBlockViewportOffset));
}
