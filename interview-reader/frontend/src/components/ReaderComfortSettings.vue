<script lang="ts" setup>
import {Check} from "@element-plus/icons-vue";
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
      <div><strong class="comfort-panel-title">阅读设置</strong><span>设置自动保存在当前设备</span></div>
      <button class="ui-button ui-button--text" type="button" @click="emit('reset')">恢复默认</button>
    </header>
    <fieldset>
      <legend>阅读主题</legend>
      <div class="comfort-option-grid theme-options">
        <button class="ui-button" :aria-pressed="theme === 'light'" :class="{ active: theme === 'light' }" type="button"
                @click="emit('update:theme', 'light')"><span aria-hidden="true" class="comfort-theme-swatch comfort-theme-swatch--light"></span>浅色<Check aria-hidden="true" class="comfort-option-check" />
        </button>
        <button class="ui-button" :aria-pressed="theme === 'sepia'" :class="{ active: theme === 'sepia' }" type="button"
                @click="emit('update:theme', 'sepia')"><span aria-hidden="true" class="comfort-theme-swatch comfort-theme-swatch--sepia"></span>护眼<Check aria-hidden="true" class="comfort-option-check" />
        </button>
        <button class="ui-button" :aria-pressed="theme === 'dark'" :class="{ active: theme === 'dark' }" type="button"
                @click="emit('update:theme', 'dark')"><span aria-hidden="true" class="comfort-theme-swatch comfort-theme-swatch--dark"></span>深色<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字体</legend>
      <div class="comfort-option-grid font-family-options">
        <button class="ui-button"
            v-for="option in FONT_FAMILY_OPTIONS"
            :key="option.value"
            :aria-pressed="fontFamily === option.value"
            :class="{ active: fontFamily === option.value, 'comfort-font-serif': option.value === 'serif' }"
            type="button"
            @click="emit('update:fontFamily', option.value)"
        >{{ option.label }}<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文字号
        <output>{{ fontSize }}px</output>
      </legend>
      <div class="comfort-option-grid font-options">
        <button class="ui-button" v-for="value in FONT_SIZE_OPTIONS" :key="value" :aria-pressed="fontSize === value" :class="{ active: fontSize === value }"
                type="button" @click="emit('update:fontSize', value)">{{ value }}<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>行距</legend>
      <div class="comfort-option-grid">
        <button class="ui-button" v-for="option in LINE_HEIGHT_OPTIONS" :key="option.value" :aria-pressed="lineHeight === option.value"
                :class="{ active: lineHeight === option.value }" type="button"
                @click="emit('update:lineHeight', option.value)">{{ option.label.replace(/\s[\d.]+$/, '') }}<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>正文栏宽</legend>
      <div class="comfort-option-grid">
        <button class="ui-button" v-for="option in COLUMN_WIDTH_OPTIONS" :key="option.value" :aria-pressed="columnWidth === option.value"
                :class="{ active: columnWidth === option.value }" type="button"
                @click="emit('update:columnWidth', option.value)">{{ option.label.replace(/\s\d+$/, '') }}<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
    <fieldset>
      <legend>代码显示</legend>
      <div class="comfort-option-grid code-options">
        <button class="ui-button" :aria-pressed="!codeWrap" :class="{ active: !codeWrap }" type="button"
                @click="emit('update:codeWrap', false)">不换行<Check aria-hidden="true" class="comfort-option-check" />
        </button>
        <button class="ui-button" :aria-pressed="codeWrap" :class="{ active: codeWrap }" type="button"
                @click="emit('update:codeWrap', true)">自动换行<Check aria-hidden="true" class="comfort-option-check" />
        </button>
      </div>
    </fieldset>
  </section>
</template>
