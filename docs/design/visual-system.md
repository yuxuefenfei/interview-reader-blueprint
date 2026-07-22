# 视觉系统（Design Tokens）

在现有 `frontend/src/styles.css` 上收敛为统一 token，避免阅读器青绿与管理台亮蓝并存。所有新样式优先引用 CSS 变量，减少硬编码色值扩散。

## 1. 色彩

### 1.1 品牌与语义

| Token | 浅色值 | 用途 |
|---|---|---|
| `--brand-500` | `#0f766e` | 主操作、进度、焦点、选中 |
| `--brand-600` | `#0b6059` | 主按钮 hover / 强调文字 |
| `--brand-700` | `#075d56` | 选中态文字、深强调 |
| `--brand-100` | `#e7f7f3` | 选中底、轻提示底 |
| `--brand-050` | `#f3fbf8` | 当前版本面板等弱强调区 |
| `--ink-900` | `#172033` | 主文字 |
| `--ink-700` | `#334155` | 次级标题 |
| `--ink-500` | `#667085` | 辅助说明 |
| `--ink-300` | `#98a2b3` | 占位、禁用 |
| `--line-200` | `#dce4e9` | 分割线、输入边框 |
| `--line-100` | `#e8eef2` | 弱分割 |
| `--surface-0` | `#f3f6f8` | 页面底 |
| `--surface-1` | `#ffffff` | 面板 / 正文表面 |
| `--surface-2` | `#fbfcfd` | 侧栏弱底、树面板 |
| `--danger-600` | `#b42318` | 危险文字 / 失败阶段 |
| `--danger-050` | `#fff7f7` | 错误面板底 |
| `--warning-600` | `#a15c07` | 分支草稿提示 |
| `--success-600` | `#0f766e` | 与品牌合一，不另开绿 |

### 1.2 阅读器主题（三套）

在 `.reader-page` 上挂变量；主题切换只改变量。

| 变量 | 浅色 Light | 深色 Dark | 护眼 Sepia |
|---|---|---|---|
| `--reader-bg` | `#f5f7f8` | `#101820` | `#f3efe6` |
| `--reader-surface` | `#ffffff` | `#17232e` | `#faf6ee` |
| `--reader-text` | `#182230` | `#e9f0f5` | `#2c261c` |
| `--reader-muted` | `#667085` | `#a2b4c2` | `#6f6556` |
| `--reader-line` | `#dce4e9` | `#314454` | `#e0d6c4` |
| `--reader-accent` | `#0f766e` | `#5eead4` | `#0f766e` |
| `--reader-code-bg` | `#fbfcfe` | `#101923` | `#f7f1e6` |

护眼主题为系统方案已列、当前未实现的增强项；首轮可先完善浅 / 深对比与代码块对比度。

### 1.3 管理台侧栏

侧栏保持深海军，但**选中态改回品牌青绿**，去掉现有亮蓝 `#126fe8`，避免双主色：

| Token | 值 | 说明 |
|---|---|---|
| `--admin-sidebar-bg` | `#071c2f` | 侧栏底 |
| `--admin-sidebar-hover` | `#112f48` | hover |
| `--admin-sidebar-active` | `#0f766e` | 替代亮蓝 |
| `--admin-sidebar-text` | `#9fb2c3` | 默认链接 |
| `--admin-sidebar-text-strong` | `#ffffff` | 品牌与激活 |

## 2. 字体

### 2.1 加载策略

`styles.css` 已声明 Inter，但未加载。建议在 `frontend/index.html` 引入（或自托管）两套字体：

| 角色 | 推荐 | 回退 |
|---|---|---|
| UI / 界面 | **Source Sans 3** 或 **IBM Plex Sans** | `"PingFang SC", "Microsoft YaHei", system-ui` |
| 正文阅读 | **Source Serif 4**（可选，仅文章区）或继续无衬线加粗行高 | 同上中文回退 |
| 代码 | **IBM Plex Mono** / Cascadia Mono | Consolas, monospace |

面试讲义场景可用「界面无衬线 + 正文略偏书卷」的组合；若希望改动最小，至少**真正加载 Inter 或 Source Sans 3**，避免声明无效。

### 2.2 字号阶梯

| 级别 | 大小 | 行高 | 用途 |
|---|---|---|---|
| Display | 28–32px | 1.2 | 管理页 H1 |
| Title | clamp(26px, 3vw, 34px) | 1.28 | 章节 H1 |
| H2 | 20–22px | 1.35 | 小节 / 面板标题 |
| Body | 桌面 18px / 移动 17px | 1.78–1.85 | 正文（可调） |
| UI | 14–15px | 1.45 | 按钮、表格、导航 |
| Caption | 12–13px | 1.4 | 辅助、标签、页码 |
| Code | 14–15px | 1.65–1.7 | 代码块 |

中文阅读宽度：`min(100%, 720–760px)`（当前 900px 偏宽，建议收窄以提升舒适度）。

## 3. 间距与圆角

| Token | 值 | 用途 |
|---|---|---|
| `--space-1` … `--space-8` | 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 | 统一间距 |
| `--radius-sm` | 6px | 按钮、TOC 节点 |
| `--radius-md` | 8px | 卡片、输入、登录框 |
| `--radius-lg` | 12px | 大面板（慎用） |
| `--header-h` | 64px（移动 60px） | 阅读顶栏 |
| `--toc-w` | 280px | 与系统方案一致（现 284px 可取整） |

避免 `rounded-full` 药丸作为默认按钮形态；代码复制按钮可用小圆角方形，不必正圆。

## 4. 阴影与边框

原则：**边框优先，阴影克制**。

| 层级 | 规格 |
|---|---|
| 平面 | `1px solid var(--line-200)` |
| 抬升（登录卡、浮层） | `0 12px 32px rgba(23, 32, 51, .08)` 单层 |
| 禁止 | 多层叠加阴影、大面积彩色 glow |

## 5. 动效

用于建立层级与反馈，不做装饰动画。

| 场景 | 建议 |
|---|---|
| 侧栏折叠 | `grid-template-columns` 200ms ease |
| TOC / 文档选中 | 背景色 120–160ms |
| 主题切换 | 颜色 180ms；布局不变 |
| 移动 FAB 展开 | 现有 `fab-in` 可保留，幅度略减 |
| 章节切换 | 正文区轻微 fade（≤150ms）可选 |
| 减少动态 | 尊重 `prefers-reduced-motion` |

## 6. 图标与品牌标

- 继续使用 `@element-plus/icons-vue`，描边权重与字号对齐（导航约 18–20px）。
- `brand-mark`：保留青绿对角渐变或改为纯色 `#0f766e` + 内嵌白页线条（与 `icon.svg` 一致），避免再引入蓝色半区。
- PWA `theme-color` 保持 `#0f766e`。

## 7. Element Plus 主题对齐

在入口覆盖 Element 主色，使按钮 / Tag / Switch 与品牌一致：

```css
:root {
  --el-color-primary: #0f766e;
  --el-color-primary-light-3: #3d9a92;
  --el-color-primary-light-5: #6fb3ad;
  --el-color-primary-light-7: #a1ccc8;
  --el-color-primary-light-9: #e7f7f3;
  --el-color-primary-dark-2: #0b6059;
  --el-border-radius-base: 6px;
  --el-font-family: inherit;
}
```

管理台表格、表单沿用 Element；阅读器控件尽量用自定义轻量按钮，减少「后台组件感」渗入正文区。

## 8. 无障碍基线

- 正文对比度 ≥ 4.5:1；弱化字 `#667085` 仅用于辅助，不作唯一信息载体。
- 焦点环：`outline: 2px solid var(--brand-500); outline-offset: 2px`（已有，保持）。
- 保留 skip-link。
- 触控目标移动端 ≥ 44px；桌面 TOC 行高 ≥ 36px。
- 状态不只靠颜色：阶段轨配合文案「已完成 / 进行中 / 失败」。
