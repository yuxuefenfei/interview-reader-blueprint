# Interview Reader

基于 `docs/` 蓝图落地的 Spring Boot + Vue 模块化单体。当前实现聚焦 JSON Package / Excel Package 导入导出、版本化入库、目录/正文阅读 API、搜索、阅读进度、收藏/笔记/掌握度、PWA 应用壳缓存和响应式阅读器。持久层使用 MyBatis-Flex，常规查询通过 QueryWrapper + APT 表定义完成。

## 环境

- JDK 21
- Maven 3.9+（推荐直接使用仓库内 Maven Wrapper）
- Node.js 20+ / npm 10+
- 不需要 Docker

当前机器如果默认 `JAVA_HOME` 不是 JDK 21，可以在 PowerShell 中临时切换：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
```

## 开发运行

开发环境默认使用 H2，并开启 MySQL 兼容模式：

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Dspring-boot.run.profiles=dev spring-boot:run
```

启动后访问：

- `http://localhost:28080/`
- `http://localhost:28080/api/health`
- `http://localhost:28080/actuator/health`
- `http://localhost:28080/h2-console`

H2 数据文件默认写入 `./data/interview-reader`。

前端开发模式：

```powershell
cd frontend
npm ci
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:28080`。

## Jar 方式运行

```powershell
.\mvnw.cmd clean package
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

`mvn package` 会先执行前端构建，并把 Vue 产物打进 jar 的静态资源中。

前端生产构建会包含 `manifest.webmanifest`、`sw.js` 和应用图标。浏览器支持 Service Worker 时会缓存应用壳；已成功打开的章节正文会写入 IndexedDB 作为最近内容缓存，网络失败时可回退显示；阅读进度写入失败会进入本地离线队列，并在恢复网络后按顺序同步。右侧复习面板提供“清理离线内容”，只清除正文缓存，不删除未同步进度队列。

导入任务在 jar/dev 模式默认使用 Java 21 虚拟线程后台 worker，最大并发数由 `interview-reader.import-worker.max-concurrency` 控制；测试 profile 关闭异步 worker，使用 H2 MySQL 兼容模式做确定性集成测试。

文档“下架”只切换为 `OFFLINE` 并保留版本与阅读数据；永久删除仅允许用于草稿或已下架文档，采用持久化后台任务删除版本、阅读数据、导入记录和托管文件。删除任务默认自动重试 3 次，保留 30 天最小墓碑供离线客户端清理缓存；并发数、重试间隔和墓碑期限统一由 `interview-reader.deletion.*` 配置。

PDF raw extraction 会保存预检摘要，包括 MIME、页数、书签深度、文本页估算、页面尺寸和每页 normalized block 覆盖统计，便于复核页定位未覆盖内容并支持后续转换规则回归。疑似 PDF 表格会先保存为低置信度 `table_snapshot` 并产生复核 issue，避免误转成高置信正文。

应用会在未显式设置 `pdfbox.fontcache` 时把 PDFBox 字体缓存放到 `./target/pdfbox-font-cache`，避免 Windows 用户目录权限导致 PDF 样本测试或 jar 运行时产生字体缓存写入告警。

当前代码没有引入服务端缓存，因此未加入 Redis 依赖；后续只有出现跨请求/跨实例缓存需求时再接入单实例 Redis。

## 生产 MySQL Profile

应用必须显式选择 `dev`、`test` 或 `prod` profile；未指定时会拒绝启动。生产运行使用 `prod`，数据库和登录凭据均必须通过环境变量显式提供：

```powershell
$env:DATABASE_URL='jdbc:mysql://localhost:3306/interview_reader?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:DATABASE_USERNAME='interview_reader'
$env:DATABASE_PASSWORD='<从秘密管理系统注入的强密码>'
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 登录配置

所有业务 API 都要求登录。开发 profile 默认账号为 `admin` / `admin`，可通过环境变量覆盖；生产 profile 必须显式提供两项凭据：

```powershell
$env:INTERVIEW_READER_USERNAME='reader-admin'
$env:INTERVIEW_READER_PASSWORD='replace-with-a-strong-password'
```

会话使用单实例内存存储、HttpOnly 且 SameSite=Lax 的 Cookie；`prod` profile 还会强制设置 `Secure`。应用重启后现有会话会失效；单实例部署无需 Redis。


## 配置与契约校验

- 复制 `frontend/.env.example` 为 `frontend/.env.local` 后可覆盖本机 Vite 代理地址；`.env.local` 不提交版本库。
- Spring 与 Nginx 示例的上传上限统一为 10 MiB。
- `frontend/src/shared/runtimeConfig.ts` 统一维护前端开发端口与代理默认值；`frontend/src/offline/database.ts` 统一维护 IndexedDB 名称、版本和 store。
- `npm run contract:check` 会比较 OpenAPI、Java Controller、前后端枚举、TypeScript 响应字段、响应式断点和上传限制；前端生产构建与 CI 都会自动运行该检查。
- `pom.xml` 会拒绝非 JDK 21 或低于 Maven 3.9 的构建环境，并通过 `npm ci` 使用锁文件安装前端依赖。
## 已实现 API

完整契约位于 `docs/api/openapi.yaml`。除登录和会话查询外，业务 API 均要求 `IR_SESSION` Cookie。

认证：

- `POST /api/auth/login`
- `GET /api/auth/session`
- `POST /api/auth/logout`

阅读端：

- `GET /api/reader/documents?query=...&cursor=...&limit=16`
- `GET /api/reader/documents/{documentId}`
- `GET /api/reader/versions/{versionId}/toc`
- `GET /api/reader/versions/{versionId}/nodes/{nodeId}/content?afterSeq=...&limit=50`
- `GET /api/reader/search?q=...&documentId=...&limit=20`
- `GET /api/reader/reading-progress/{documentId}`
- `PUT /api/reader/reading-progress/{documentId}`
- `POST /api/reader/bookmarks`
- `DELETE /api/reader/bookmarks/{bookmarkId}`
- `POST /api/reader/notes`
- `PUT /api/reader/review-states/{nodeId}`
- `GET /api/reader/review-queue?documentId=...&limit=5&dueOnly=false`

后台文档与版本：

- `GET /api/admin/documents?query=...&page=1&size=20`
- `GET /api/admin/documents/{documentId}`
- `GET /api/admin/documents/{documentId}/versions`
- `POST /api/admin/documents/{documentId}/versions/{sourceVersionId}/revisions`
- `POST /api/admin/documents/{documentId}/versions/{versionId}/publish`
- `GET /api/admin/versions/{versionId}/editor`
- `PUT /api/admin/versions/{versionId}/editor`（兼容整包保存）
- `DELETE /api/admin/versions/{versionId}/editor`
- `GET /api/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks?cursor=...&limit=40`
- `POST /api/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks`
- `PATCH /api/admin/versions/{versionId}/editor/nodes/{nodeId}`
- `PATCH /api/admin/versions/{versionId}/editor/structure`
- `PATCH /api/admin/versions/{versionId}/editor/blocks/{blockId}`
- `DELETE /api/admin/versions/{versionId}/editor/blocks/{blockId}?draftRevision=...`
- `POST /api/admin/versions/{versionId}/editor/blocks/cleanup-empty`

导入与导出：

- `POST /api/admin/import-jobs`
- `GET /api/admin/import-jobs/{jobId}`
- `GET /api/admin/import-jobs/{jobId}/issues`
- `GET /api/admin/import-jobs/{jobId}/raw-extraction`
- `GET /api/admin/import-jobs/{jobId}/source-file`
- `GET /api/admin/import-jobs/{jobId}/normalized-package`
- `PATCH /api/admin/import-jobs/{jobId}/normalized-package/sections/{sectionKey}`
- `PATCH /api/admin/import-jobs/{jobId}/normalized-package/blocks/{blockKey}`
- `POST /api/admin/import-jobs/{jobId}/cancel`
- `POST /api/admin/import-jobs/{jobId}/commit`
- `POST /api/admin/exports`

示例包位于 `docs/examples/document-package.example.json`。Excel 导入模板位于 `docs/templates/interview-reader-import-template.xlsx`。

`POST /api/admin/import-jobs` 使用 `multipart/form-data`，必填字段为 `file`，可选字段为 `targetDocumentId`。服务端根据文件内容与扩展名识别类型，支持：

- JSON 文档包（`.json`）
- Excel OOXML 工作簿（`.xlsx`，不支持旧版二进制 `.xls`）
- Markdown（`.md` / `.markdown`）
- PDF（`.pdf`）

PDF 导入会保存原始源文件、raw extraction 和 normalized package，复核页可按 issue/block 定位源页。

`POST /api/admin/exports` 同步导出 JSON Package、Excel Package、Markdown 或静态 HTML：

```json
{
  "documentId": "00000000-0000-0000-0000-000000000000",
  "versionId": "00000000-0000-0000-0000-000000000000",
  "format": "JSON_PACKAGE"
}
```

将 `format` 改为 `EXCEL` 会返回 `.xlsx` 文件；改为 `MARKDOWN` 会返回 `.md` 文本；改为 `STATIC_HTML` 会返回由受信任 renderer 生成的 `.html` 文件。API 中的时间字段使用带时区偏移的 ISO 8601 字符串。
