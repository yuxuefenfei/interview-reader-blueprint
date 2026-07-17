# 面试题文档转换与碎片化阅读系统：可实施设计方案

## 1. 结论

这套系统不应把目标定义为“把 PDF 的版面原样变成 HTML”，因为 PDF 是固定纸张坐标模型，直接转换通常会得到大量绝对定位的 `span/div`，在手机上不可读，也无法稳定生成目录、搜索、进度和复习功能。

推荐路线是：

> PDF / Excel / Markdown → 统一结构化文档 AST → 校验与人工复核 → 版本化入库 → Vue 响应式阅读器 → JSON / Excel / Markdown / 静态 HTML 导出

Excel可以保留，但定位为“人工整理和批量修订的交换格式”，不作为系统内部唯一真相。系统内部的权威格式应是版本化 JSON AST + 关系数据库。

当前四份讲义很适合作为首批样本：都有文本层、明确的多级章节编号、目录与 PDF 书签，正文以段落、列表、代码块和表格为主，几乎没有必须依赖 OCR 的复杂图片。首版应优先利用书签和字体/坐标信息，而不是只用正则猜标题。

## 2. 设计目标与非目标

### 2.1 核心目标

1. 支持 PDF、结构化 Excel、JSON 包、Markdown 导入。
2. 自动识别任意 N 级章节，生成稳定目录和锚点。
3. 保留段落、列表、代码、表格、引用、公式、图片等语义。
4. PC 与移动端共用一套响应式阅读页面。
5. 退出后再次打开，恢复到“章节 + 内容块 + 块内偏移”，而不是脆弱的滚动像素。
6. 支持全文搜索、收藏、笔记、掌握度与快速复习。
7. 支持无损 JSON 备份、Excel 人工修订、Markdown 和静态 HTML 导出。
8. 采用轻量模块化单体，首版不引入 Redis、MQ、Elasticsearch。

### 2.2 首版非目标

1. 不追求任意复杂 PDF 的 100% 无人工纠错。
2. 不把 PDF 页面视觉排版逐像素复刻成 HTML。
3. 不在首版实现多人实时协同编辑。
4. 不把 Elasticsearch 当基础依赖；数据量足够大时再以插件方式接入。
5. 不在主进程中执行不受控 OCR 或外部脚本。

## 3. 推荐技术架构

### 3.1 总体形态

采用“模块化单体 + 后台任务执行器”：

```text
Browser / PWA
    |
    v
Vue 3 + TypeScript
    |
    v
Spring Boot API
    |-- 文档库
    |-- 导入任务
    |-- PDF/Excel/JSON 转换
    |-- 内容版本与发布
    |-- 阅读进度/收藏/笔记
    |-- 搜索
    |-- 导出
    |
    +--> MySQL
    +--> 本地文件目录（以后可切 S3/MinIO）
```

首版部署可只有两个持久组件：Java 应用和 MySQL。Vue 构建后的静态文件可由 Spring Boot 或 Nginx 提供。

### 3.2 后端建议

- Java 21 或团队统一的更高 LTS
- Spring Boot
- Spring MVC；后台转换任务可使用受控线程池或虚拟线程
- MyBatis-Flex；常规查询使用 QueryWrapper + APT 表定义
- Apache PDFBox：PDF 元数据、书签、文本位置和页面解析
- Tabula-java：规则表格的可选提取器
- Apache POI：Excel 读取；导出模板应走统一列定义
- Jackson：JSON AST
- 缓存按指标需要再引入；单实例部署优先本地缓存，需要跨实例时接单实例 Redis
- Flyway：数据库版本管理
- Micrometer：转换时长、失败率、阅读 API 延迟

### 3.3 前端建议

- Vue 3 + TypeScript + Vite
- Vue Router + Pinia
- 自定义 Reader 组件，后台管理页可使用轻量组件库
- PDF.js：导入复核页显示源 PDF
- IndexedDB：缓存最近阅读内容和离线进度队列
- Service Worker：PWA 应用壳与最近文档离线阅读
- DOMPurify：仅用于显示已审查的 HTML 片段；更推荐前端按 AST 渲染

### 3.4 为什么首版不用 Redis、MQ、Elasticsearch

- 导入任务频率低，可用数据库任务表和单机 worker。
- 已发布内容是不可变版本，单实例场景可先不加服务端缓存；出现明确热点后再引入本地缓存或单实例 Redis。
- 中文搜索可先用 MySQL/H2 兼容的包含匹配和标题/标签索引，规模化后再替换为搜索引擎。
- 只有在多实例、十万级文档、高并发全文检索时，才引入 Redis、消息队列或 Elasticsearch。

## 4. 统一文档模型

### 4.1 核心原则

系统不要只存一大段 HTML，也不要只存“章节表 + content 字符串”。建议拆为：

```text
Document
  └─ DocumentVersion（发布后不可变）
       └─ ContentNode（N 级目录树）
            └─ ContentBlock（有序内容块）
                 └─ Asset（图片/附件）
```

目录树负责结构；内容块负责语义。渲染 HTML 是缓存或输出，不是权威源。

### 4.2 ContentNode

关键字段：

| 字段 | 含义 |
|---|---|
| node_key | 导入包内稳定键 |
| parent_id | 父节点 |
| level | 章节深度 |
| path | 排序和快速子树查询路径 |
| node_type | PART / CHAPTER / SECTION / QUESTION / APPENDIX |
| semantic_role | QUESTION / CONCLUSION / PRINCIPLE / PRACTICE / PITFALL / FOLLOW_UP |
| title | 标题 |
| anchor | URL 稳定锚点 |
| sort_order | 同级顺序 |
| source_page_start/end | 溯源页码 |
| source_bbox | 标题在 PDF 中的位置 |
| content_hash | 版本迁移和差异比较 |

### 4.3 ContentBlock

建议支持：

| block_type | payload 示例 |
|---|---|
| paragraph | `{"text":"..."}` |
| heading_note | `{"text":"..."}` |
| unordered_list | `{"items":[...]}` |
| ordered_list | `{"items":[...]}` |
| code | `{"language":"java","text":"..."}` |
| table | `{"columns":[...],"rows":[...],"align":[...]}` |
| quote | `{"text":"..."}` |
| callout | `{"kind":"warning","title":"...","text":"..."}` |
| formula | `{"latex":"..."}` |
| image | `{"assetKey":"img-1","alt":"..."}` |
| divider | `{}` |

每个 block 同时保存 `plain_text` 供搜索、`source_page/source_bbox` 供复核、`content_hash` 供版本迁移。

### 4.4 为什么使用 JSON AST 而不是 HTML

1. 能稳定生成移动端、PC、Markdown、Excel、静态 HTML 等多种输出。
2. 可以安全渲染，不需要信任导入的任意标签和脚本。
3. 表格、代码、列表不会被塞进一个难以再解析的字符串。
4. 后续可加入“答案折叠、代码复制、表格横向滚动、掌握度”等交互。
5. 可以做块级差异和阅读位置迁移。

## 5. PDF 转换流水线

### 5.1 任务状态

```text
UPLOADED
→ PREFLIGHT
→ EXTRACTING
→ NORMALIZING
→ VALIDATING
→ REVIEW_REQUIRED / READY
→ IMPORTED
→ PUBLISHED
```

失败状态保留阶段、异常、页码、块编号和可重试标志。

### 5.2 预检分类

上传后计算：

- SHA-256、MIME、页数、是否加密
- PDF 书签数量和层级
- 每页可提取字符数
- 字体与字号分布
- 文本块/图片块比例
- 重复页眉页脚
- 页面尺寸和旋转
- 是否混合扫描页

分类：

| 类型 | 策略 |
|---|---|
| TEXT_OUTLINE | 书签作为目录骨架，坐标文本作为内容 |
| TEXT_NO_OUTLINE | 字号、粗体、编号正则和留白识别标题 |
| HYBRID | 文本页正常解析，局部扫描页 OCR |
| SCANNED | OCR 后再走结构识别 |
| UNSUPPORTED | 进入人工整理或只保存原 PDF |

当前四份样本应走 `TEXT_OUTLINE`。

### 5.3 提取层

PDFBox 提取：

1. 文档元数据、页数和书签树。
2. `TextPosition`：文字、字体、字号、坐标、基线。
3. 页面绘制指令中的背景矩形和表格线。
4. 嵌入图片和裁切区域。
5. 每页原始文本块与阅读顺序。

应保留“原始提取结果”，方便转换算法升级后重跑，而不必重新上传。

### 5.4 结构识别优先级

标题识别优先级：

1. PDF 书签目标位置。
2. 与书签标题相似且距离目标最近的文本行。
3. 多级编号：`1.`、`1.1`、`1.1.1`、`第一部分`、`附录 A`。
4. 字号、粗体、颜色、上下留白。
5. 人工修订。

样本 PDF 的标题、正文、代码字体差异明显，因此检测规则可为：

```text
headingScore =
  bookmarkMatch * 100
+ numberingMatch * 30
+ fontSizeZScore * 15
+ bold * 10
+ topSpacing * 5
```

不要只用字号，因为代码和表头也可能使用不同字号。

### 5.5 页眉页脚清理

按“规范化文本 + Y 坐标区间”统计。相同文本在 60% 以上页面的顶部或底部出现，即标记为页眉/页脚。页码使用位置和数字模式识别。

清理必须在保存原始块之后进行，便于审计和回放。

### 5.6 段落重建

PDF 换行不等于语义换段。合并条件：

- 基线间距接近正文行距
- 左缩进相同
- 上一行不是列表项或代码
- 中文行尾无明显终止符时优先合并
- 英文单词断行需要恢复连字符语义
- 下一行不是标题、表格单元格或页眉页脚

分页处的同一段落也要合并，但保留起止页码。

### 5.7 列表识别

识别 `•`、`-`、`1.`、`1)`、中文序号。按缩进生成嵌套层级。对连续编号异常、层级跳变和单项列表生成校验告警。

### 5.8 代码块识别

当前样本存在等宽字体和浅色背景代码框，可组合判断：

- 等宽字体字符占比
- 行首缩进和空格保留
- 背景矩形
- `{}`, `;`, SQL/HTTP 命令等特征
- 连续多行的字体和 X 坐标一致

代码必须原样保留空格与换行。语言可由章节标签和内容推断，但允许人工修正。

### 5.9 表格识别

顺序：

1. 检测水平/垂直线，形成网格。
2. 根据单元格坐标归并文字。
3. 无边框表格使用列对齐和间距聚类。
4. 置信度低时，不强行转成错表；保存为 `table_snapshot` 或普通文本并要求复核。

表格错误通常比暂时降级更危险，因此必须有 `confidence` 和复核标记。

### 5.10 质量评分与问题清单

每个 node/block 记录 `confidence`。自动检查：

- 章节层级从 1 跳到 4
- 父节点不存在
- 同一层级锚点重复
- 章节为空
- 段落只剩一个字符
- 代码括号明显不平衡
- 表格列数不一致
- 页眉残留
- 页码出现在正文
- PDF 页存在文本但未生成任何 block
- 书签目标未匹配到标题

复核页面应左右分栏：左侧 PDF.js，右侧结构树与 HTML 预览。点击 block 时高亮源页 bbox；修改后立即重新渲染。

## 6. Excel 交换格式

### 6.1 定位

Excel用于：

- 批量调整标题、层级、顺序和标签
- 人工修复代码语言和表格
- 运营人员录入非 PDF 内容
- 导出给非技术人员审阅

权威备份和系统间迁移仍使用 JSON Package。

### 6.2 工作簿

推荐工作表：

1. `README`
2. `Documents`
3. `Sections`
4. `Blocks`
5. `Assets`
6. `FieldDictionary`
7. `Lists`

`Sections` 和 `Blocks` 分离，避免把长代码和复杂表格塞到章节行中。

### 6.3 Sections 必填字段

```text
document_key
version_key
section_key
parent_section_key
level
node_type
semantic_role
title
sort_order
source_page_start
source_page_end
tags
enabled
```

### 6.4 Blocks 必填字段

```text
version_key
block_key
section_key
seq
block_type
text_markdown
language
payload_json
source_page
source_bbox_json
confidence
enabled
```

规则：

- `payload_json` 为空时，根据 `block_type + text_markdown` 构建。
- table/image/formula 必须有 `payload_json`。
- code 优先使用 `text_markdown`，不得自动去除首尾之外的空格。
- `section_key`、`block_key` 在版本内唯一。
- 导入先写 staging，再验证，最后一次事务发布。

## 7. 导入、版本和发布

### 7.1 幂等

- 文件 SHA-256 + 转换器版本组成 `import_fingerprint`。
- 同一指纹重复上传可复用解析结果。
- 每次人工修订生成新 `DocumentVersion`。
- 已发布版本不可直接修改；修改时复制出草稿版本。

### 7.2 Staging

所有导入先进入暂存表或临时 JSON：

```text
staging_document
staging_section
staging_block
staging_asset
staging_issue
```

只有零阻断错误时才执行 `commit import`。提交时应原子写入版本、节点、块和资源。

### 7.3 版本迁移阅读进度

新版发布后，旧 block ID 会变化。迁移顺序：

1. 匹配相同 `block_key`。
2. 匹配 `content_hash`。
3. 匹配章节 path + 前 80 字摘要。
4. 退化到同章节首块。
5. 再退化到文档开头。

保留进度迁移结果和置信度，避免用户被无声跳转到错误位置。

## 8. 阅读器设计

### 8.1 页面布局

PC：

```text
左：可折叠目录  280px
中：正文 680-820px
右：当前小节/笔记/掌握度（可隐藏）
```

移动端：

- 顶部文档标题和进度
- 正文单列
- 目录使用抽屉
- 底部工具栏：目录、上一题、标记、字体、下一题
- 表格横向滚动，代码块带复制按钮
- 触控目标至少 40px 左右

### 8.2 阅读舒适性

默认建议：

- 正文宽度：`min(100%, 760px)`
- 中文正文字号：移动 17px，桌面 18px，可调
- 行高：1.75-1.9
- 段间距：0.75-1em
- 代码：14-15px、1.6 行高、横向滚动
- 深色/浅色/护眼主题
- 字体、行高、正文宽度按用户保存
- 标题锚点滚动时留出固定头部高度

### 8.3 分段加载

不要一次加载整本书：

1. 首次加载目录树和当前位置章节。
2. 每次返回一个章节或限定 50 个 block。
3. 空闲时预取下一章节。
4. 已发布版本使用 ETag 和长缓存。
5. 大章节使用块级虚拟列表，但代码、表格高度变化时需要稳定的测量缓存。

### 8.4 稳定阅读进度

保存：

```json
{
  "documentId": "...",
  "versionId": "...",
  "sectionId": "...",
  "blockId": "...",
  "charOffset": 0,
  "blockViewportOffset": 64,
  "progressRatio": 0.382,
  "clientUpdatedAt": "..."
}
```

`scrollY` 只能作为诊断信息，不能作为恢复主键，因为移动端和 PC 排版不同。

保存触发：

- 当前块变化后防抖 2-3 秒
- 切换章节立即保存
- `visibilitychange` / `pagehide` 使用 `sendBeacon`
- 离线时写 IndexedDB，恢复网络后按时间顺序同步

冲突策略：默认客户端时间 + 服务端接收时间综合判断，允许用户查看“其他设备最近位置”。

### 8.5 面试复习模式

这类文档天然以问题为主，应在模型中区分 `QUESTION` 和答案子章节。提供：

- 只显示问题，点击后展开答案
- “会 / 模糊 / 不会”三档
- 收藏与错题集
- 随机 5 题、按标签复习、继续未读
- 常见误区/追问单独折叠
- 简单 SM-2 或 Leitner 复习计划放到二期

## 9. 搜索

MVP 使用 MySQL：

- 标题、标签：普通 B-Tree
- 中文正文：包含匹配；规模化后接 Elasticsearch/OpenSearch
- 过滤：文档、章节、标签、掌握度
- 结果返回标题、命中摘要、章节路径和 blockId

搜索索引字段应预先归一化：

- 全角/半角
- 大小写
- 多空白
- 常见符号
- 可选同义词表

数据规模超过约十万篇文档、需要分词相关性和聚合时，再实现 `SearchProvider` 接口接 Elasticsearch/OpenSearch。

## 10. API 边界

实际接口以 `api/openapi.yaml` 为权威契约。当前命名空间按用途隔离：

```text
POST   /api/auth/login
GET    /api/auth/session
POST   /api/auth/logout

POST   /api/admin/import-jobs
GET    /api/admin/import-jobs/{id}
GET    /api/admin/import-jobs/{id}/issues
POST   /api/admin/import-jobs/{id}/commit
POST   /api/admin/documents/{id}/versions/{versionId}/publish
GET    /api/admin/documents
GET    /api/admin/documents/{id}
GET    /api/admin/documents/{id}/versions
GET    /api/admin/versions/{versionId}/editor
GET    /api/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks
POST   /api/admin/exports

GET    /api/reader/documents
GET    /api/reader/documents/{id}
GET    /api/reader/versions/{versionId}/toc
GET    /api/reader/versions/{versionId}/nodes/{nodeId}/content
GET    /api/reader/search?q=...
GET    /api/reader/reading-progress/{documentId}
PUT    /api/reader/reading-progress/{documentId}
POST   /api/reader/bookmarks
DELETE /api/reader/bookmarks/{id}
POST   /api/reader/notes
PUT    /api/reader/review-states/{nodeId}
GET    /api/reader/review-queue
```

后台编辑器还提供节点、结构和内容块的增量写接口，详见 OpenAPI。内容 API 使用稳定版本 ID；不要让阅读端隐式读取会变化的草稿。目录和正文支持 ETag，正文通过 `afterSeq + limit` 分页。

## 11. 性能设计

### 11.1 数据库

关键索引：

- `content_node(version_id, parent_id, sort_order)`
- `content_node(version_id, path)`
- `content_block(node_id, seq)`
- `reading_progress(user_id, document_id)`
- `bookmark(user_id, block_id)`
- `content_node(title)` / `content_block(plain_text)` 按 MySQL 能力选择前缀、全文或外部搜索索引

目录树一次查询返回，服务端组树；正文按 node 批量查询，避免 N+1。

### 11.2 缓存

发布版本不可变，因此适合：

- 本地缓存或 Redis：TOC、节点正文、文档元信息（按指标需要再启用）
- HTTP：ETag、`Cache-Control: public/max-age` 或私有长缓存
- 资源：内容哈希文件名，长期缓存
- PWA：最近 3-10 篇文档按容量淘汰

### 11.3 转换任务

- 独立有界线程池
- 每个 PDF 限制页数、文件大小和单页文本块数
- 同一文档同一时间只允许一个活动转换任务
- 任务支持取消
- OCR 与高风险工具放在受限子进程/容器
- 定期清理过期暂存文件

### 11.4 建议目标

这些是验收目标，不是未经压测的承诺：

- 目录接口 P95 < 150ms
- 缓存命中后的章节接口 P95 < 100ms
- 移动端恢复到上次 block 的成功率 > 99%
- JSON 导出再导入的块级哈希完全一致
- 样本 PDF 标题层级自动识别率目标 > 98%
- 低置信度表格必须进入人工复核，不以“错误结构化”换取表面成功率

## 12. 安全

- 检查 PDF/ZIP 魔数，不信任扩展名
- 文件大小、页数、压缩比和解压后总大小限制
- ZIP 防目录穿越与压缩炸弹
- 禁止导入 HTML 中的脚本、事件属性和外链 iframe
- 静态 HTML 导出也要经过白名单渲染
- 文件名不直接作为磁盘路径
- 导入日志不记录原文敏感内容
- 管理、导入、发布、导出分权限
- OCR/转换子进程设置 CPU、内存、时间和文件系统配额

## 13. 可观测性与运维

指标：

- 导入任务数、各阶段耗时、失败率
- 每页 block 数、低置信度比例
- 人工修改率
- 文档/版本/节点/块数量
- 搜索延迟和无结果率
- 阅读进度写入失败率
- PWA 离线队列积压
- 文件存储使用量

日志必须带 `jobId/documentId/versionId/pageNo/blockKey`。

备份应同时覆盖 MySQL 和文件目录。定期做“JSON 包导出后在空库恢复”的演练。

## 14. 模块和代码目录

```text
backend/
  app/
  document/
  importjob/
  converter/
    spi/
    pdf/
    excel/
    jsonpkg/
    markdown/
  content/
  reader/
  search/
  export/
  asset/
  security/
  common/

frontend/
  src/
    pages/library/
    pages/import/
    pages/review/
    pages/reader/
    components/content-blocks/
    components/toc/
    stores/
    offline/
```

转换器 SPI：

```java
public interface DocumentConverter {
    boolean supports(SourceDescriptor source);
    PreflightResult preflight(SourceHandle source);
    RawExtraction extract(SourceHandle source, ConversionContext context);
    NormalizedDocument normalize(RawExtraction raw, ConversionContext context);
    ValidationReport validate(NormalizedDocument document);
}
```

每一步输入输出都可序列化，便于失败重试和回归测试。

## 15. 实施路线

### Phase 0：转换可行性验证

只做命令行程序：

1. 输出四份 PDF 的元数据、书签树、字体分布。
2. 输出统一 JSON AST。
3. 输出静态 HTML 预览。
4. 对 30-50 个代表页人工标注。
5. 固化页眉、标题、段落、代码、表格测试样本。

通过条件：目录准确、正文顺序稳定、代码空白不丢、表格能识别或正确降级。

### Phase 1：MVP

- 登录可先简化为单用户
- 文档库
- JSON/Excel 导入
- 目录和章节阅读
- 进度恢复
- 搜索
- JSON/Excel/Markdown 导出
- MySQL + 本地文件存储

### Phase 2：PDF 导入中心

- PDF 预检和后台任务
- 自动结构化
- 低置信度问题列表
- PDF.js 对照复核
- 版本发布

### Phase 3：阅读增强

- PWA 离线
- 收藏、笔记、掌握度
- 问答折叠和随机复习
- 版本间进度迁移
- 静态 HTML 包导出

### Phase 4：规模化

只有指标证明需要时再加入：

- 多实例 worker
- 对象存储
- MQ
- Elasticsearch/OpenSearch
- 多租户与团队协作

## 16. Codex 实现原则

1. 每个阶段先写可运行的验收测试，再写实现。
2. 第一批集成测试固定使用这四份 PDF。
3. 不让 Codex 一次生成完整系统；按模块和垂直切片提交。
4. 每次提交必须包含数据库迁移、API 测试和最小前端路径。
5. 转换器的原始抽取结果、规范化结果和最终 AST 均保存测试快照。
6. 对解析规则使用黄金文件测试，避免修改一个规则破坏其他文档。
7. 发布版本不可变；所有编辑发生在草稿版本。
8. HTML 只能由受信任渲染器生成，不直接保存和执行用户 HTML。

## 17. 最终建议

首个可交付版本应选择：

- Java 模块化单体
- MySQL
- 本地文件存储抽象
- Vue 响应式 PWA
- PDFBox + 书签优先解析
- JSON AST 作为权威格式
- Excel 作为人工交换格式
- 按章节懒加载
- block 级阅读进度

这条路线能够保持轻量，也为后续大量同类文档、搜索、离线阅读和复习算法留下扩展空间。
