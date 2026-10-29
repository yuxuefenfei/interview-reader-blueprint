# 阅读器优化与无效代码检查（2026-10-29）

本轮按电脑、手机同等优先落实阅读器改进，保留按小节翻页。未调整 API、数据库结构或发布版本。

## 阅读器改进

- 阅读位置保存失败只显示状态提示，正文保持可读；后续保存成功会清除提示。
- 搜索命中首批 100 块以后的内容时，沿现有分页接口加载到目标块；失败时不再假装已经定位。恢复阅读位置复用同一加载流程。
- 章节 URL 使用 `section`，搜索定位附带 `block`。历史条目保存文档、版本、块与块相对视口的偏移，浏览器后退恢复原阅读点；保留 Vue Router 自己的历史状态。
- 正文上方显示完整目录上下文，上一节／下一节显示目标标题；当前目录项不再截断。
- 文档筛选为少量或空结果时仍保留输入框；分页文档列表也允许筛选。
- 手机实际字号与设置一致；窄屏隐藏不能生效的正文栏宽选项；代码复制有可见的成功／失败反馈。
- “阅读进度”改为“阅读位置”；取消自动完成弹窗，但保留位置计算和保存。最后一节一屏显示完整时仍为 100%。
- 搜索达到接口的 12 条上限时明确说明只显示前 12 条，并提示增加关键词。

## 无效代码检查与清理

检查范围为两个后端模块的 142 个 Java 文件（主应用 114、升级控制台 28），以及两套前端源码、入口、路由、自动组件注册、共享 CSS、测试、构建脚本和相关 API/资源注册。

| 范围 | 已清理内容 | 判断依据 |
| --- | --- | --- |
| 主前端 | `utils/contentBlocks.ts` 与专属测试 | 内容修复逻辑没有生产或脚本调用，仅对应测试导入；后端内容规范化功能保留 |
| 主前端 | `utils/sourceBbox.ts` 与专属测试 | PDF 来源坐标展示工具没有生产调用；API 类型和后端来源字段保留 |
| 主前端 | `nextReaderTheme`、`themeActionLabel` 与专用主题顺序常量 | 设置面板已改为直接选择主题，没有调用方 |
| 主前端 | `questionAnswerNodes`、`progressRatioForNode` 与专属断言 | 旧折叠复习和进度算法只由测试调用；实际位置算法位于 `readingProgress.ts` |
| 主前端 | 未读取的 `username` prop 及 App 传递状态 | 阅读器没有消费者；登录表单、会话和离线访问校验保留 |
| 主前端 | `clearNodeContentCache()` 与专属测试 | 没有运行入口或生产调用；同步移除 README 中过期的清理入口说明 |
| 主前端 | `StagedSection`、`StagedBlock` 类型别名及专用导入 | 全仓无调用方；生成的 API 模型保留 |
| 主前端 | `DOCUMENT_STATUSES`、`DELETION_JOB_STATUSES`、`DELETION_STAGES` 运行时数组 | 页面、脚本及契约检查均不使用；对应类型与生成的契约模型保留 |
| 控制台前端 | `Overview` interface | 当前页面与 API 使用 `Dashboard` 和分页模型 |
| 主后端 | `ImportPackageService.emptyToNull()` | 私有方法没有调用或方法引用 |
| 主后端 | `ImportJobStatus.isCancelable()`、专用集合和导入 | 没有调用或框架注册；取消枚举值、状态校验及实际取消流程保留 |
| 主后端 | `updateImportStage()` 中重复的取消查询 | 紧接着的 `requireJob()` 已查询同一任务并保留取消检查，减少一次重复查询 |

没有发现能够根据当前证据直接删除的完整后端类。框架扫描入口、配置、定时任务、Jackson/MyBatis 注册均按间接调用处理。

明确保留以下容易误判的路径：

- 收藏、笔记和复习接口：有 OpenAPI 契约和集成测试，不能仅因当前页面没有入口而删除。
- 旧图片路由：当前导出流程仍使用它。
- 控制台旧状态迁移、事件流回填与旧应用入口校验：仍有执行路径和兼容需求。
- 动态拼接的 CSS 类、路由懒加载视图、自动注册组件和契约检查使用的导出。

`clearNodeContentCache()` 没有生产调用或界面入口，已删除该函数与专属测试，并移除 README 中不存在的清理入口说明。仍在使用的章节缓存、离线进度队列和按文档删除缓存的同步流程保留。

## 验证与边界

验证包括两套前端的测试、lint、类型检查和生产构建，API 契约检查，以及 JDK 21 下两个后端模块的测试。浏览器使用本机生产构建与模拟 API，覆盖导航返回、分页搜索、进度保存失败、手机筛选和字号、复制反馈，以及 320／375／390／414／768／1440px 宽度。

无效代码结论来自全仓符号筛查、重点调用链复核和回归测试，不代表已经形式化证明所有运行时分支可达；没有使用生产流量或外部客户端使用统计。
实际结果：主前端 136 条测试、控制台前端 15 条测试、后端 121 条测试通过，两套前端 lint、类型检查、生产构建和主应用 API 契约检查通过。浏览器确认同文档后退恢复 500px、跨文档搜索后退恢复 650px、同文档及跨文档搜索定位第 105 块、冷打开 block URL 定位、末节短小节保存 100%。

追加清理的验证：主前端 136 条测试全部通过。本机首跑的路由冷启动用例超过默认 5 秒，复跑仅通过命令行临时设置 `--testTimeout=15000`，未修改项目测试配置；lint、类型检查、生产构建与 54 个接口的契约检查通过。后端和控制台代码本轮没有修改，未重复运行其测试。

可复查的主要命令（从仓库根目录执行，后端先按 `.java-version` 选择 JDK 21）：

```powershell
npm --prefix interview-reader/frontend test -- --maxWorkers=1
npm --prefix upgrade-console/frontend test -- --maxWorkers=1
npm --prefix interview-reader/frontend run lint -- --quiet
npm --prefix upgrade-console/frontend run lint -- --quiet
npm --prefix interview-reader/frontend run contract:check
npm --prefix interview-reader/frontend run build
npm --prefix upgrade-console/frontend run build
.\mvnw.cmd --batch-mode -DskipFrontend=true test
```