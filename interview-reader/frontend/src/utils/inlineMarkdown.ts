export type InlineMarkdownToken = {
  type: "text" | "strong" | "emphasis" | "code" | "link" | "paragraph-break";
  text?: string;
  href?: string;
  children?: InlineMarkdownToken[];
};

function isSafeLink(href: string): boolean {
  return href.length > 0
    && !/\s/.test(href)
    && (!/^[a-z][a-z\d+.-]*:/i.test(href) || /^(?:https?:)?\/\//i.test(href));
}

function findClosing(source: string, start: number, marker: string): number {
  for (let index = start; index < source.length; index++) {
    if (source[index] === "\\") {
      index++;
      continue;
    }
    if (source.startsWith(marker, index)) return index;
  }
  return -1;
}

function parseLink(source: string, start: number): { end: number; token: InlineMarkdownToken } | null {
  const labelEnd = findClosing(source, start + 1, "]");
  if (labelEnd < 0 || source[labelEnd + 1] !== "(") return null;
  const hrefEnd = findClosing(source, labelEnd + 2, ")");
  if (hrefEnd < 0) return null;

  const label = source.slice(start + 1, labelEnd);
  const href = source.slice(labelEnd + 2, hrefEnd).trim();
  if (!label || !isSafeLink(href)) return null;
  return { end: hrefEnd + 1, token: { type: "link", href, children: parseInlineMarkdown(label) } };
}

function parseDelimited(source: string, start: number, marker: string, type: InlineMarkdownToken["type"]): { end: number; token: InlineMarkdownToken } | null {
  const end = findClosing(source, start + marker.length, marker);
  if (end <= start + marker.length) return null;
  const value = source.slice(start + marker.length, end);
  return type === "code"
    ? { end: end + marker.length, token: { type, text: value } }
    : { end: end + marker.length, token: { type, children: parseInlineMarkdown(value) } };
}

export function isExternalLink(href: string): boolean {
  return /^(?:https?:)?\/\//i.test(href);
}

export function parseInlineMarkdown(value: string): InlineMarkdownToken[] {
  const source = value.replace(/\r\n?/g, "\n");
  const tokens: InlineMarkdownToken[] = [];
  let text = "";
  let index = 0;
  const flushText = () => {
    if (text) tokens.push({ type: "text", text });
    text = "";
  };

  while (index < source.length) {
    const character = source[index];
    if (character === "\\" && index + 1 < source.length) {
      text += source[index + 1];
      index += 2;
      continue;
    }
    if (character === "\n") {
      flushText();
      let next = index + 1;
      while (source[next] === "\n") next++;
      if (next > index + 1) tokens.push({ type: "paragraph-break" });
      else text = " ";
      index = next;
      continue;
    }

    const parsed = source.startsWith("**", index)
      ? parseDelimited(source, index, "**", "strong")
      : character === "*"
        ? parseDelimited(source, index, "*", "emphasis")
        : character === "`"
          ? parseDelimited(source, index, "`", "code")
          : character === "["
            ? parseLink(source, index)
            : null;
    if (parsed) {
      flushText();
      tokens.push(parsed.token);
      index = parsed.end;
      continue;
    }

    text += character;
    index++;
  }
  flushText();
  return tokens;
}
