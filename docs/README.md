# 项目文档索引

本目录集中存放 Interview Reader 的设计、契约、运维和导入样例。文档按用途分类，新增内容应放入对应目录，避免继续堆放在 `docs/` 根目录。

## 快速入口

| 分类     | 内容                                       | 入口                                                                                         |
|----------|--------------------------------------------|----------------------------------------------------------------------------------------------|
| API 契约 | 前后端共同遵守的 OpenAPI 定义              | [OpenAPI 契约](api/openapi.yaml)                                                             |
| 数据库   | MySQL 数据库结构的权威 Flyway 迁移         | [初始迁移](../interview-reader/src/main/resources/db/migration/mysql/V1__initial_schema.sql) |
| 运维     | 生产部署、备份恢复、监控和故障处理         | [生产运行手册](operations/runbook.md)                                                        |
| 架构     | 独立升级控制台的实现、发布、备份与回滚流程 | [系统升级控制台](architecture/system-upgrade.md)                                             |

## 导入资料

- [JSON Package Schema](import/schemas/document-package.schema.json)：JSON 导入包的结构约束。
- [JSON Package 示例](import/examples/document-package.example.json)：后端集成测试使用的最小示例包。
- [Excel 导入模板](import/templates/interview-reader-import-template.xlsx)：人工整理和批量导入模板。

JSON 示例和 Excel 模板也被后端测试使用；移动或重命名时须同步更新测试代码和根目录 README 中的路径。Schema 用于说明导入格式。

## 样例资料

- `samples/markdown/`：结构化 Markdown 面试资料。
- `samples/pdf/`：PDF 转换与导入回归样本。

Markdown 文件按技术主题命名；PDF 保留原始文件名，便于核对导入结果。

## 维护约定

1. `docs/api/openapi.yaml` 是 API 契约的权威来源。
2. 数据库可执行结构以 `interview-reader/src/main/resources/db/migration/` 下的 Flyway 脚本为准，文档目录不复制运行时 SQL。
3. 系统设计决策放入 `architecture/`；其他文档按实际用途建立目录，不预留空目录。
4. 运维步骤必须放入 `operations/`，并在影响部署或恢复流程时同步更新。
5. 导入格式变更必须同时检查 Schema、示例、模板、OpenAPI 和相关自动化测试。
