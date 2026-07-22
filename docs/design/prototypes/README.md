# 原型图与可交互原型

本目录提供两类材料：

1. **PNG 视觉稿**：快速对齐气氛与布局（AI 气氛图，个别文字可能有噪点）。
2. **HTML 高保真原型**：可在浏览器打开，文案与 token 准确，优先作为实现对照。

## 快速打开

用浏览器打开图册首页：

[`index.html`](index.html)

或直接打开各 HTML 原型：

| 页面 | HTML | PNG |
|---|---|---|
| 登录 | [html/login.html](html/login.html) | [01-login.png](01-login.png) |
| 阅读器 | [html/reader.html](html/reader.html) | [02-reader-desktop.png](02-reader-desktop.png) / [03-reader-mobile.png](03-reader-mobile.png) |
| 文档管理 | [html/admin.html](html/admin.html) | [04-admin-documents.png](04-admin-documents.png) |
| 导入中心 | [html/import.html](html/import.html) | [05-import-center.png](05-import-center.png) |
| 版本编辑器 | [html/editor.html](html/editor.html) | [06-version-editor.png](06-version-editor.png) |

## 阅读器 HTML 可交互点

- **搜索**：顶栏打开搜索面板
- **舒适度**：调节字号 / 行高 / 栏宽
- **主题**：浅色 ↔ 深色
- **窄窗口**：可模拟移动端单列

## 使用约定

- 实现时以 HTML 原型 + `../visual-system.md` + `../page-specs.md` 为准。
- PNG 仅作视觉沟通；若与 HTML 冲突，以 HTML 为准。
