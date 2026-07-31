<script setup lang="ts">
import {
  COLUMN_WIDTH_OPTIONS,
  FONT_FAMILY_OPTIONS,
  FONT_SIZE_OPTIONS,
  LINE_HEIGHT_OPTIONS,
  type ReaderFontFamily,
  type ReaderTheme,
} from "../utils/readingComfort";

defineProps<{
  theme: ReaderTheme;
  fontSize: number;
  lineHeight: number;
  columnWidth: number;
  codeWrap: boolean;
  fontFamily: ReaderFontFamily;
}>();

const emit = defineEmits<{
  "update:theme": [value: ReaderTheme];
  "update:fontSize": [value: number];
  "update:lineHeight": [value: number];
  "update:columnWidth": [value: number];
  "update:codeWrap": [value: boolean];
  "update:fontFamily": [value: ReaderFontFamily];
  reset: [];
}>();
</script>

<template>
  <section class="reader-comfort-panel" aria-label="阅读舒适度设置">
    <header>
      <div><strong>阅读舒适度</strong><span>设置会自动保存在当前设备</span></div>
      <button type="button" @click="emit('reset')">恢复默认</button>
    </header>
    <fieldset>
      <legend>阅读主题</legend>
      <div class="comfort-option-grid theme-options">
        <button type="button" :class="{ active: theme === 'light' }" :aria-pressed="theme === 'light'" @click="emit('update:theme', 'light')">浅色</button>
        <button type="button" :class="{ active: theme === 'sepia' }" :aria-pressed="theme === 'sepia'" @click="emit('update:theme', 'sepia')">护眼</button>
        <button type="button" :class="{ active: theme === 'dark' }" :aria-pressed="theme === 'dark'" @click="emit('update:theme', 'dark')">深色</button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字体</legend>
      <div class="comfort-option-grid font-family-options">
        <button
          v-for="option in FONT_FAMILY_OPTIONS"
          :key="option.value"
          type="button"
          :class="{ active: fontFamily === option.value }"
          :aria-pressed="fontFamily === option.value"
          @click="emit('update:fontFamily', option.value)"
        >{{ option.label }}</button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字号 <output>{{ fontSize }}px</output></legend>
      <div class="comfort-option-grid font-options">
        <button v-for="value in FONT_SIZE_OPTIONS" :key="value" type="button" :class="{ active: fontSize === value }" :aria-pressed="fontSize === value" @click="emit('update:fontSize', value)">{{ value }}</button>
      </div>
    </fieldset>
    <fieldset>
      <legend>行距</legend>
      <div class="comfort-option-grid">
        <button v-for="option in LINE_HEIGHT_OPTIONS" :key="option.value" type="button" :class="{ active: lineHeight === option.value }" :aria-pressed="lineHeight === option.value" @click="emit('update:lineHeight', option.value)">{{ option.label.replace(/\s[\d.]+$/, '') }}</button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文栏宽</legend>
      <div class="comfort-option-grid">
        <button v-for="option in COLUMN_WIDTH_OPTIONS" :key="option.value" type="button" :class="{ active: columnWidth === option.value }" :aria-pressed="columnWidth === option.value" @click="emit('update:columnWidth', option.value)">{{ option.label.replace(/\s\d+$/, '') }}</button>
      </div>
    </fieldset>
    <fieldset>
      <legend>代码显示</legend>
      <div class="comfort-option-grid">
        <button type="button" :class="{ active: !codeWrap }" :aria-pressed="!codeWrap" @click="emit('update:codeWrap', false)">不换行</button>
        <button type="button" :class="{ active: codeWrap }" :aria-pressed="codeWrap" @click="emit('update:codeWrap', true)">自动换行</button>
      </div>
    </fieldset>
  </section>
</template>
