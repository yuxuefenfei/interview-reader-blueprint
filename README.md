# Interview Reader

基于 `docs/` 蓝图落地的 Spring Boot + Vue 模块化单体。当前实现聚焦 JSON Package / Excel Package 导入导出、版本化入库、目录/正文阅读 API、搜索、阅读进度、收藏/笔记/掌握度、PWA 应用壳缓存和响应式阅读器。

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

开发环境默认使用 H2，并开启 PostgreSQL 兼容模式：

```powershell
mvn test
mvn spring-boot:run
```

启动后访问：

- `http://localhost:8080/`
- `http://localhost:8080/api/health`
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/h2-console`

H2 数据文件默认写入 `./data/interview-reader`。

前端开发模式：

```powershell
cd frontend
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:8080`。

## Jar 方式运行

```powershell
mvn clean package
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar
```

`mvn package` 会先执行前端构建，并把 Vue 产物打进 jar 的静态资源中。

前端生产构建会包含 `manifest.webmanifest`、`sw.js` 和应用图标。浏览器支持 Service Worker 时会缓存应用壳；阅读进度写入失败会进入本地离线队列，并在恢复网络后按顺序同步。

## 生产 PostgreSQL Profile

生产运行使用 `prod` profile，并通过环境变量传入数据库连接：

```powershell
$env:DATABASE_URL='jdbc:postgresql://localhost:5432/interview_reader'
$env:DATABASE_USERNAME='interview_reader'
$env:DATABASE_PASSWORD='interview_reader'
java -jar target/interview-reader-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 已实现 API

- `POST /api/import-jobs`
- `GET /api/import-jobs/{jobId}`
- `GET /api/import-jobs/{jobId}/issues`
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

当前 `POST /api/exports` 支持同步导出 JSON Package、Excel Package、Markdown 与静态 HTML：

```json
{
  "documentId": "00000000-0000-0000-0000-000000000000",
  "versionId": "00000000-0000-0000-0000-000000000000",
  "format": "JSON_PACKAGE"
}
```

将 `format` 改为 `EXCEL` 会返回 `.xlsx` 文件；改为 `MARKDOWN` 会返回 `.md` 文本；改为 `STATIC_HTML` 会返回由受信任 renderer 生成的 `.html` 文件。
