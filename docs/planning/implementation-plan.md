# Codex 分阶段实施计划

## 使用方式

不要把整个需求一次交给 Codex。每个里程碑独立分支、独立验收，完成后再进入下一步。

## M0：仓库与基础设施

目标：

- `backend` Spring Boot
- `frontend` Vue + TypeScript
- MySQL + Flyway
- `/actuator/health`
- 前端能调用 `/api/health`

验收：

```bash
curl http://localhost:8080/actuator/health
npm run test
./mvnw test
```

Codex 提示词：

> 创建一个模块化单体项目，后端 Java + Spring Boot，前端 Vue 3 + TypeScript + Vite，数据库 MySQL。按 README 的模块目录初始化。加入 Flyway、H2 MySQL 模式测试、前后端 lint/test。不要实现业务，只完成可运行骨架、健康检查。

## M1：权威 JSON Package

目标：

- 实现 JSON Schema 对应的 Java record/DTO
- 校验器
- JSON Package 导入
- 数据库写入 staging
- commit 为草稿版本
- JSON 导出与导入 round-trip

测试：

- 示例包导入成功
- 重复 key、孤儿 parent、层级跳变失败
- 导出后重新导入，sections/blocks 哈希一致

Codex 提示词：

> 根据 docs/import/schemas/document-package.schema.json 和 docs/database/schema-mysql.sql，实现 JSON Package 导入。导入必须先 staging/validate，再事务提交。发布版本不可变。为所有规则写 JUnit + H2 MySQL 模式集成测试。

## M2：目录与阅读 API

目标：

- 文档列表
- TOC 树
- 节点正文分页
- ETag
- 进度读写

测试：

- 单查询取完整 TOC
- 章节 block 顺序正确
- 防 N+1
- 并发更新进度采用 revision 或 last-write 规则

## M3：Vue 阅读器

目标：

- PC 三栏、移动单栏
- 目录抽屉
- paragraph/list/code/table 渲染
- 字体/行高/主题
- block 级进度恢复
- 下一节预取

测试：

- Playwright：移动和桌面断点
- 离开页面再打开恢复到相同 block
- 表格不撑破页面
- 代码保留空格和换行

## M4：Excel

目标：

- 导入模板中的 Documents/Sections/Blocks/Assets
- 单元格级错误报告
- Excel 导出
- 保留代码换行和 JSON

测试：

- 示例模板导入
- 错误 `parent_section_key` 定位到具体单元格
- 导出再导入哈希一致

## M5：PDF 可行性命令行

目标：

- PDFBox 提取元数据、书签、TextPosition
- 输出 raw extraction JSON
- 页眉页脚识别
- 书签到标题坐标匹配
- 段落、列表、代码初步识别
- 输出规范化 JSON 和静态 HTML

必须用四份样本做黄金测试。不要先接 Web UI。

验收：

- 目录层级与书签一致
- 每页有 block 覆盖统计
- 代码空白不丢
- 低置信度表格标记而不是误转

## M6：PDF 导入任务和复核 UI

目标：

- import_job 状态机
- 有界后台 worker
- PDF.js 左右对照
- issue 列表
- 编辑 section/block
- commit/publish

## M7：搜索与复习

目标：

- MySQL 兼容搜索
- 收藏、笔记、掌握度
- 问题折叠
- 随机复习

## M8：PWA 离线

目标：

- 应用壳缓存
- 最近文档内容写 IndexedDB
- 离线进度队列
- 恢复网络后同步
- 用户可清理离线数据

## 每次 Codex 任务的固定约束

在每个提示词末尾附加：

> 先阅读项目 README、SQL、OpenAPI 和 JSON Schema。只实现本里程碑，不提前引入 Redis、MQ、Elasticsearch。必须提供数据库迁移、单元测试、集成测试和运行命令。任何解析启发式都要有黄金文件测试；任何导入必须幂等；发布版本不可修改。
