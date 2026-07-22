# 落地优先级与验收

将设计材料落到 `frontend/` 时按阶段推进，优先改善读者日常路径与品牌一致性，再增强管理与复习能力。

## 现状差距摘要

| 维度 | 现状 | 目标 |
|---|---|---|
| 品牌色 | 阅读青绿 + 管理亮蓝 | 统一青绿 token |
| 字体 | 声明 Inter 未加载 | 加载 UI 字体并设中文回退 |
| 阅读宽度 / 舒适度 | 约 900px，仅主题切换 | 740px 默认 + 字号/行高/栏宽 |
| 桌面搜索 | 入口弱 | Header 明确入口 |
| 登录 | 功能卡 | 品牌叙事 + 统一按钮 |
| 内容块 | 公式/图片占位简陋 | 可读占位 → 后续 KaTeX/真实图 |
| 笔记/掌握度/导出 | API 有、UI 无 | 增强阶段三栏与导出入口 |
| CSS 组织 | 单文件大杂烩 | 先变量化，再按需拆文件 |

## 阶段 A — 视觉统一（建议 1–2 天）

**范围：** 不改业务逻辑，只改观感与可达入口。

1. 在 `:root` / `.reader-page` / `.admin-layout` 落地 [visual-system.md](visual-system.md) 变量。
2. Element Plus primary 覆写为 `#0f766e`。
3. 管理侧栏 `router-link-active` 由亮蓝改为品牌色。
4. `index.html` 加载选定 UI 字体；`body` font-family 同步。
5. 登录页文案与背景微调；主按钮全宽品牌色。
6. 阅读器正文宽度改为约 740px；Header 增加搜索按钮。
7. `brand-mark` 与侧栏激活阴影去掉蓝色倾向。

**验收**

- [ ] 管理台与阅读器主色目测一致（青绿）。
- [ ] 桌面阅读器可不靠 FAB 打开搜索。
- [ ] 字体网络加载失败时中文回退仍可读。
- [ ] 浅/深主题切换无布局跳动。

## 阶段 B — 阅读舒适（建议 2–3 天）

1. 舒适度面板：字号、行高、栏宽；`localStorage` 持久化。
2. 护眼主题第三套变量。
3. 桌面顶栏细进度条；章节切换反馈。
4. 代码块复制反馈、表格/callout token 化。
5. 图片/公式占位文案中文化与样式统一。
6. `prefers-reduced-motion` 处理。

**验收**

- [ ] 调整字号后刷新仍保留。
- [ ] 移动端 17px / 桌面默认 18px，行高 ≥ 1.78。
- [ ] 长代码与宽表可横向滚动且不撑破布局。
- [ ] 连续阅读场景下对比度符合 4.5:1 基线。

## 阶段 C — 管理台操作舒适（建议 2–3 天）

1. 文档详情：按钮分组、危险操作收纳。
2. 导入中心：单一主 CTA、问题列表可读性、阶段轨 token。
3. 版本编辑器：Header 主按钮收敛、「更多」菜单、节点属性默认折叠。
4. 空状态与错误面板统一。
5. 版本历史高亮去蓝改品牌浅色。

**验收**

- [ ] 导入路径始终可见「当前阶段 + 下一步」。
- [ ] 编辑器首屏主操作 ≤ 2 个实心按钮。
- [ ] 误删类操作均有确认。

## 阶段 D — 能力增强（与后端已有 API 对齐）

按 `architecture/system-design.md` Phase 3：

1. 阅读器右侧栏：笔记、收藏、掌握度。
2. 复习模式（答案折叠 + 三档标记）。
3. 管理台导出入口（JSON / Excel / Markdown / HTML）。
4. 导入 PDF 对照复核（PDF.js）——独立大项。
5. 清理离线内容面板（若 README 承诺需兑现）。

**验收**

- [ ] 与 OpenAPI 字段一致；无 API 的 UI 不先做空壳。
- [ ] 右侧栏可完全折叠且正文居中不偏。
- [ ] 移动端复习手势不与滚动冲突。

## 建议改动文件（阶段 A/B）

```text
frontend/index.html
frontend/src/styles.css
frontend/src/App.vue
frontend/src/views/ReaderView.vue
frontend/src/layouts/AdminLayout.vue
frontend/src/components/ContentBlockView.vue
frontend/src/components/TocTree.vue
frontend/public/manifest.webmanifest   # 文案如需微调
```

阶段 C 另含：

```text
frontend/src/views/AdminDocumentsView.vue
frontend/src/views/AdminDocumentDetailView.vue
frontend/src/views/ImportCenterView.vue
frontend/src/views/VersionEditorView.vue
```

## 非目标（本设计轮次）

- 不更换 Vue / Element Plus 技术栈。
- 不强制上 Pinia（可用本地状态与 composable）。
- 不重做 PWA 图标体系（可在品牌统一后微调 SVG）。
- 不做营销型落地首页。

## 评审检查清单（设计本身）

- [ ] 是否紧贴「面试讲义阅读」而非通用后台模板。
- [ ] 是否避免紫渐变 / 奶油衬线海报 / 报纸栏等常见 AI 审美簇。
- [ ] 品牌名在登录与管理侧栏是否足够强。
- [ ] 阅读器是否仍以正文为绝对中心。
- [ ] 文档是否已挂到 `docs/README.md` 索引。
