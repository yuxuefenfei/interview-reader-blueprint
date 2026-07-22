# 前端展示设计材料

本目录存放 **Interview Reader** 的前端视觉与交互优化设计，作为 `architecture/system-design.md` §8 阅读器设计的展示层补充。目标是让界面更贴合「结构化面试题碎片化阅读」主题，并提升长时间阅读与后台操作的舒适度。

## 文档清单

| 文档 | 用途 |
|---|---|
| [产品主题与设计原则](product-theme.md) | 产品定位、视觉叙事、设计原则与反模式 |
| [视觉系统](visual-system.md) | 色板、字体、间距、圆角、阴影、组件 token |
| [页面规格](page-specs.md) | 登录、阅读器、管理台、导入中心、版本编辑器的布局与交互规格 |
| [落地优先级](implementation-priority.md) | 与现网差距、分阶段改造建议、验收清单 |
| [原型图册](prototypes/index.html) | PNG 视觉稿 + 可交互 HTML 原型（推荐浏览器打开） |
| [原型说明](prototypes/README.md) | 原型文件索引与使用约定 |

## 与现有实现的关系

- **不推翻** 现有 Element Plus + 自定义 CSS 技术选型。
- **延续** 品牌主色青石青绿 `#0f766e` 与阅读器 CSS 变量体系。
- **对齐** 系统方案中的三栏阅读器、复习模式、舒适性参数；当前前端尚未完全落地的能力在规格中标注为「增强」而非阻塞首轮视觉改造。
- 代码落点主要在 `frontend/src/styles.css`、各 `views/*.vue`、`components/*`、`index.html`（字体加载）与 `public/icon.svg`。

## 使用方式

1. 评审本目录材料，确认视觉方向与页面优先级。
2. 按 [落地优先级](implementation-priority.md) 分批改前端，避免一次性大改编辑器。
3. 改动后对照验收清单做桌面 / 移动双端回归。
