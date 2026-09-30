<script lang="ts" setup>
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
  <section aria-label="阅读舒适度设置" class="reader-comfort-panel">
    <header>
      <div><strong>阅读舒适度</strong><span>设置会自动保存在当前设备</span></div>
      <button type="button" @click="emit('reset')">恢复默认</button>
    </header>
    <fieldset>
      <legend>阅读主题</legend>
      <div class="comfort-option-grid theme-options">
        <button :aria-pressed="theme === 'light'" :class="{ active: theme === 'light' }" type="button"
                @click="emit('update:theme', 'light')">浅色
        </button>
        <button :aria-pressed="theme === 'sepia'" :class="{ active: theme === 'sepia' }" type="button"
                @click="emit('update:theme', 'sepia')">护眼
        </button>
        <button :aria-pressed="theme === 'dark'" :class="{ active: theme === 'dark' }" type="button"
                @click="emit('update:theme', 'dark')">深色
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字体</legend>
      <div class="comfort-option-grid font-family-options">
        <button
            v-for="option in FONT_FAMILY_OPTIONS"
            :key="option.value"
            :aria-pressed="fontFamily === option.value"
            :class="{ active: fontFamily === option.value }"
            type="button"
            @click="emit('update:fontFamily', option.value)"
        >{{ option.label }}
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字号
        <output>{{ fontSize }}px</output>
      </legend>
      <div class="comfort-option-grid font-options">
        <button v-for="value in FONT_SIZE_OPTIONS" :key="value" :aria-pressed="fontSize === value" :class="{ active: fontSize === value }"
                type="button" @click="emit('update:fontSize', value)">{{ value }}
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>行距</legend>
      <div class="comfort-option-grid">
        <button v-for="option in LINE_HEIGHT_OPTIONS" :key="option.value" :aria-pressed="lineHeight === option.value"
                :class="{ active: lineHeight === option.value }" type="button"
                @click="emit('update:lineHeight', option.value)">{{ option.label.replace(/\s[\d.]+$/, '') }}
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文栏宽</legend>
      <div class="comfort-option-grid">
        <button v-for="option in COLUMN_WIDTH_OPTIONS" :key="option.value" :aria-pressed="columnWidth === option.value"
                :class="{ active: columnWidth === option.value }" type="button"
                @click="emit('update:columnWidth', option.value)">{{ option.label.replace(/\s\d+$/, '') }}
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>代码显示</legend>
      <div class="comfort-option-grid">
        <button :aria-pressed="!codeWrap" :class="{ active: !codeWrap }" type="button"
                @click="emit('update:codeWrap', false)">不换行
        </button>
        <button :aria-pressed="codeWrap" :class="{ active: codeWrap }" type="button"
                @click="emit('update:codeWrap', true)">自动换行
        </button>
      </div>
    </fieldset>
  </section>
</template>
