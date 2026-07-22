# 项目文档索引

本目录集中存放 Interview Reader 的设计、契约、运维、质量报告和导入样例。文档按用途分类，新增内容应放入对应目录，避免继续堆放在 `docs/` 根目录。

## 快速入口

| 分类 | 内容 | 入口 |
|---|---|---|
| 架构设计 | 系统目标、领域模型、导入流程、阅读器与实施路线 | [系统设计方案](architecture/system-design.md) |
| 前端展示设计 | 产品主题、视觉系统、页面规格、原型图与落地优先级 | [前端展示设计](design/README.md) · [原型图册](design/prototypes/index.html) |
| API 契约 | 前后端共同遵守的 OpenAPI 定义 | [OpenAPI 契约](api/openapi.yaml) |
| 数据库 | 数据库权威迁移位置说明 | [数据库结构说明](database/schema-mysql.sql) |
| 导入规范 | JSON Schema、示例包和 Excel 模板 | [导入资料](#导入资料) |
| 运维 | 生产部署、备份恢复、监控和故障处理 | [生产运行手册](operations/runbook.md) |
| 质量 | 项目事实核查、问题清单与优化结果 | [分析报告](quality/analysis-report.md) |
| 计划 | 项目分阶段实施和验收计划 | [实施计划](planning/implementation-plan.md) |
| 样例资料 | 用于导入、转换和回归测试的 Markdown/PDF 面试资料 | [样例资料](#样例资料) |

## 导入资料

- [JSON Package Schema](import/schemas/document-package.schema.json)：JSON 导入包的结构约束。
- [JSON Package 示例](import/examples/document-package.example.json)：后端集成测试使用的最小示例包。
- [Excel 导入模板](import/templates/interview-reader-import-template.xlsx)：人工整理和批量导入模板。

这些文件不仅是说明资料，也是测试夹具。移动或重命名时必须同步更新测试代码和根目录 README 中的路径。

## 样例资料

- `samples/markdown/`：结构化 Markdown 面试资料。
- `samples/pdf/`：PDF 转换与导入回归样本。

Markdown 与 PDF 文件按技术主题命名。Markdown 文件前的编号用于固定阅读顺序；PDF 保留原始文件名，便于核对导入结果。

## 维护约定

1. `docs/api/openapi.yaml` 是 API 契约的权威来源。
2. 数据库可执行结构以 `src/main/resources/db/migration/` 下的 Flyway 脚本为准，文档目录不复制运行时 SQL。
3. 系统设计决策放入 `architecture/`，前端展示与交互规格放入 `design/`，实施计划放入 `planning/`，核查与评审结果放入 `quality/`。
4. 运维步骤必须放入 `operations/`，并在影响部署或恢复流程时同步更新。
5. 导入格式变更必须同时检查 Schema、示例、模板、OpenAPI 和相关自动化测试。
