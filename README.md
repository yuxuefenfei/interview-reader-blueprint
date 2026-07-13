# Interview Reader

基于 `docs/` 蓝图落地的 Spring Boot + Vue 模块化单体。当前实现聚焦 JSON Package / Excel Package 导入导出、版本化入库、目录/正文阅读 API、搜索、阅读进度、收藏/笔记/掌握度、PWA 应用壳缓存和响应式阅读器。持久层使用 MyBatis-Flex，常规查询通过 QueryWrapper + APT 表定义完成。

## 环境

- JDK 21
- Maven 3.9+
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
mvn test
mvn spring-boot:run
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
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:28080`。

## Jar 方式运行

```powershell
mvn clean package
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar
```

`mvn package` 会先执行前端构建，并把 Vue 产物打进 jar 的静态资源中。

前端生产构建会包含 `manifest.webmanifest`、`sw.js` 和应用图标。浏览器支持 Service Worker 时会缓存应用壳；已成功打开的章节正文会写入 IndexedDB 作为最近内容缓存，网络失败时可回退显示；阅读进度写入失败会进入本地离线队列，并在恢复网络后按顺序同步。右侧复习面板提供“清理离线内容”，只清除正文缓存，不删除未同步进度队列。

导入任务在 jar/dev 模式默认使用 Java 21 虚拟线程后台 worker，最大并发数由 `interview-reader.import-worker.max-concurrency` 控制；测试 profile 关闭异步 worker，使用 H2 MySQL 兼容模式做确定性集成测试。

PDF raw extraction 会保存预检摘要，包括 MIME、页数、书签深度、文本页估算、页面尺寸和每页 normalized block 覆盖统计，便于复核页定位未覆盖内容并支持后续转换规则回归。疑似 PDF 表格会先保存为低置信度 `table_snapshot` 并产生复核 issue，避免误转成高置信正文。

应用会在未显式设置 `pdfbox.fontcache` 时把 PDFBox 字体缓存放到 `./target/pdfbox-font-cache`，避免 Windows 用户目录权限导致 PDF 样本测试或 jar 运行时产生字体缓存写入告警。

当前代码没有引入服务端缓存，因此未加入 Redis 依赖；后续只有出现跨请求/跨实例缓存需求时再接入单实例 Redis。

## 生产 MySQL Profile

生产运行使用 `prod` profile，并通过环境变量传入数据库连接：

```powershell
$env:DATABASE_URL='jdbc:mysql://localhost:3306/interview_reader?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC'
$env:DATABASE_USERNAME='interview_reader'
$env:DATABASE_PASSWORD='interview_reader'
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 登录配置

所有业务 API 都要求登录。开发 profile 默认账号为 `admin` / `admin`，可通过环境变量覆盖；生产 profile 必须显式提供两项凭据：

```powershell
$env:INTERVIEW_READER_USERNAME='reader-admin'
$env:INTERVIEW_READER_PASSWORD='replace-with-a-strong-password'
```

会话使用单实例内存存储、HttpOnly 且 SameSite=Lax 的 Cookie。应用重启后现有会话会失效；单实例部署无需 Redis。

## 已实现 API

- `POST /api/import-jobs`
- `GET /api/import-jobs/{jobId}`
- `GET /api/import-jobs/{jobId}/issues`
- `GET /api/import-jobs/{jobId}/raw-extraction`
- `GET /api/import-jobs/{jobId}/source-file`
- `GET /api/import-jobs/{jobId}/normalized-package`
- `PATCH /api/import-jobs/{jobId}/normalized-package/sections/{sectionKey}`
- `PATCH /api/import-jobs/{jobId}/normalized-package/blocks/{blockKey}`
- `POST /api/import-jobs/{jobId}/cancel`
- `POST /api/import-jobs/{jobId}/commit`
- `GET /api/documents`
- `GET /api/documents/{documentId}`
- `POST /api/documents/{documentId}/versions/{versionId}/publish`
- `GET /api/versions/{versionId}/toc`
- `GET /api/versions/{versionId}/nodes/{nodeId}/content`
- `GET /api/search?q=...`
- `GET /api/reading-progress/{documentId}`
- `PUT /api/reading-progress/{documentId}`
- `POST /api/bookmarks`
- `DELETE /api/bookmarks/{bookmarkId}`
- `POST /api/notes`
- `PUT /api/review-states/{nodeId}`
- `POST /api/exports`

示例包位于 `docs/examples/document-package.example.json`。Excel 导入模板位于 `docs/templates/interview-reader-import-template.xlsx`。

`POST /api/import-jobs` 使用 `multipart/form-data`：

- `sourceType=JSON_PACKAGE`，上传 JSON 文件。
- `sourceType=EXCEL`，上传 `.xlsx` 文件。
- `sourceType=MARKDOWN`，上传 `.md` / `.markdown` 文件。
- `sourceType=PDF`，上传 `.pdf` 文件。PDF 导入会保存原始源文件、raw extraction 和 normalized package，复核页可按 issue/block 定位源页。

当前 `POST /api/exports` 支持同步导出 JSON Package、Excel Package、Markdown 与静态 HTML：

```json
{
  "documentId": "00000000-0000-0000-0000-000000000000",
  "versionId": "00000000-0000-0000-0000-000000000000",
  "format": "JSON_PACKAGE"
}
```

将 `format` 改为 `EXCEL` 会返回 `.xlsx` 文件；改为 `MARKDOWN` 会返回 `.md` 文本；改为 `STATIC_HTML` 会返回由受信任 renderer 生成的 `.html` 文件。
