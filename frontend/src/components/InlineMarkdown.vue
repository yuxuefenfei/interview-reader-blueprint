<script setup lang="ts">
import { computed } from "vue";
import { isExternalLink, parseInlineMarkdown, type InlineMarkdownToken } from "../utils/inlineMarkdown";

defineOptions({ name: "InlineMarkdown" });

const props = defineProps<{
  text?: string;
  tokens?: InlineMarkdownToken[];
  highlight?: string;
}>();

const renderedTokens = computed(() => props.tokens ?? parseInlineMarkdown(props.text ?? ""));

type TextSegment = { text: string; highlighted: boolean };

function textSegments(value: string | undefined): TextSegment[] {
  if (!value) return [];
  const needle = props.highlight?.trim();
  if (!needle) return [{ text: value, highlighted: false }];

  const source = value.toLocaleLowerCase();
  const query = needle.toLocaleLowerCase();
  const segments: TextSegment[] = [];
  let start = 0;
  let match = source.indexOf(query, start);
  while (match >= 0) {
    if (match > start) segments.push({ text: value.slice(start, match), highlighted: false });
    segments.push({ text: value.slice(match, match + needle.length), highlighted: true });
    start = match + needle.length;
    match = source.indexOf(query, start);
  }
  if (start < value.length) segments.push({ text: value.slice(start), highlighted: false });
  return segments;
}
</script>

<template>
  <span class="inline-markdown">
    <template v-for="(token, index) in renderedTokens" :key="index">
      <template v-if="token.type === 'text'">
        <template v-for="(segment, segmentIndex) in textSegments(token.text)" :key="segmentIndex">
          <mark v-if="segment.highlighted" class="inline-search-mark">{{ segment.text }}</mark>
          <template v-else>{{ segment.text }}</template>
        </template>
      </template>
      <strong v-else-if="token.type === 'strong'"><InlineMarkdown :tokens="token.children" :highlight="highlight" /></strong>
      <em v-else-if="token.type === 'emphasis'"><InlineMarkdown :tokens="token.children" :highlight="highlight" /></em>
      <code v-else-if="token.type === 'code'">{{ token.text }}</code>
      <a v-else-if="token.type === 'link'" :href="token.href" :target="token.href && isExternalLink(token.href) ? '_blank' : undefined" :rel="token.href && isExternalLink(token.href) ? 'noopener noreferrer' : undefined"><InlineMarkdown :tokens="token.children" :highlight="highlight" /></a>
      <template v-else><br /><br /></template>
    </template>
  </span>
</template>
