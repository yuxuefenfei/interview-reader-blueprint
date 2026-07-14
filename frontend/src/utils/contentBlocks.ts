import type { ContentBlock } from "../types/api";

function payloadText(block: ContentBlock): string {
  return typeof block.payload.text === "string" ? block.payload.text : block.plainText;
}

function normalizedCodeFragment(value: string): string {
  return value
    .replace(/\r\n?/g, "\n")
    .replace(/}\s+@(?=[A-Za-z])/g, "}\n@");
}

function codeAndProse(value: string): { code: string; prose: string | null } | null {
  const text = normalizedCodeFragment(value).trim();
  if (!text) {
    return null;
  }
  const mixed = text.match(/^((?:\/\/[^\n}]*?)?})\s*([\u4e00-\u9fff].*)$/s);
  if (mixed) {
    return { code: mixed[1], prose: mixed[2] };
  }
  if (/^(?:}|@|\/\/|\/\*|\*\/|\*\s)/.test(text) || /^(?:else|catch|finally)\b/.test(text)) {
    return { code: text, prose: null };
  }
  return null;
}

function withCodeText(block: ContentBlock, text: string): ContentBlock {
  return {
    ...block,
    payload: { ...block.payload, text },
    plainText: text
  };
}

function withParagraphText(block: ContentBlock, text: string): ContentBlock {
  return {
    ...block,
    payload: { ...block.payload, text },
    plainText: text
  };
}

export function normalizeReaderBlocks(blocks: ContentBlock[]): ContentBlock[] {
  const normalized: ContentBlock[] = [];

  for (const block of blocks) {
    const previous = normalized.at(-1);
    if (previous?.blockType === "code" && block.blockType === "paragraph") {
      const fragment = codeAndProse(payloadText(block));
      if (fragment) {
        normalized[normalized.length - 1] = withCodeText(
          previous,
          `${normalizedCodeFragment(payloadText(previous)).trimEnd()}\n${fragment.code}`
        );
        if (fragment.prose) {
          normalized.push(withParagraphText(block, fragment.prose));
        }
        continue;
      }
    }
    normalized.push(block);
  }

  return normalized;
}
