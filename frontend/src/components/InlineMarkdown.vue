<script setup lang="ts">
import { computed } from "vue";
import { isExternalLink, parseInlineMarkdown, type InlineMarkdownToken } from "../utils/inlineMarkdown";

defineOptions({ name: "InlineMarkdown" });

const props = defineProps<{
  text?: string;
  tokens?: InlineMarkdownToken[];
}>();

const renderedTokens = computed(() => props.tokens ?? parseInlineMarkdown(props.text ?? ""));
</script>

<template>
  <span class="inline-markdown">
    <template v-for="(token, index) in renderedTokens" :key="index">
      <template v-if="token.type === 'text'">{{ token.text }}</template>
      <strong v-else-if="token.type === 'strong'"><InlineMarkdown :tokens="token.children" /></strong>
      <em v-else-if="token.type === 'emphasis'"><InlineMarkdown :tokens="token.children" /></em>
      <code v-else-if="token.type === 'code'">{{ token.text }}</code>
      <a v-else-if="token.type === 'link'" :href="token.href" :target="token.href && isExternalLink(token.href) ? '_blank' : undefined" :rel="token.href && isExternalLink(token.href) ? 'noopener noreferrer' : undefined"><InlineMarkdown :tokens="token.children" /></a>
      <template v-else><br /><br /></template>
    </template>
  </span>
</template>
