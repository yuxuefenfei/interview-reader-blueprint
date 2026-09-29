# Interview Reader 生产运行手册

本文以当前生产使用的 [daemon.sh](../../deploy/daemon.sh) 为准。脚本只管理主应用进程；独立升级控制台仍处于[设计阶段](../architecture/system-upgrade.md)，当前没有页面升级、数据库自动恢复或自动回滚能力。

## 1. 运行目录

脚本用自身所在目录作为 APP_DIR，JAR 和子目录都据此定位。已确认 INTERVIEW_READER_STORAGE_DIR 为 /opt/ireader/data；下图以脚本安装在 /opt/ireader 为例，实际安装路径仍需在服务器上核对。

```text
/opt/ireader/
├── daemon.sh
├── interview-reader.jar
├── conf/
│   └── application.env
├── data/
│   ├── <哈希目录>/
│   ├── import-sources/
│   └── target/pdfbox-font-cache/
├── logs/
│   └── ireader.log
└── tmp/
    ├── ireader.pid
    └── ireader.lock
```

从仓库安装脚本时，应保留 LF 行尾并赋予可执行权限；例如部署在 /opt/ireader 时执行 `sudo install -o root -g ireader -m 0750 deploy/daemon.sh /opt/ireader/daemon.sh`。实际位置不同则替换目标路径。

脚本固定读取同目录的 interview-reader.jar，且拒绝符号链接。data、logs、tmp 必须事先存在并允许 ireader 写入；JAR 必须是 ireader 可读的普通文件。脚本以 data 为工作目录启动，因此 data/target/pdfbox-font-cache 是 PDFBox 的可重建缓存。

脚本必须由 ireader 用户执行。conf/application.env 必须由 root 持有、不能是符号链接，也不能被组或其他用户写入；脚本以 ireader 身份 source 它，因此还要授予 ireader 只读权限，例如 root:ireader、0640，并限制 conf 目录的读取权限（750）。不得把密码或现网配置提交到仓库。

## 2. 启停与检查

若脚本实际安装在 /opt/ireader，可使用：

```bash
sudo -u ireader /opt/ireader/daemon.sh status
sudo -u ireader /opt/ireader/daemon.sh start
sudo -u ireader /opt/ireader/daemon.sh stop
sudo -u ireader /opt/ireader/daemon.sh restart
```

status 在进程未运行时返回退出码 3。脚本通过 PID 和 /proc 命令行确认进程，并用 tmp/ireader.lock 对每次命令加锁。start 在 2 秒后只确认进程存活，不代表应用已就绪；还需检查：

```bash
curl -fsS http://127.0.0.1:28080/actuator/health/readiness
```

现有 stop 等待 30 秒后会发送 SIGKILL。运行中的导入和永久删除任务可能因此被截断；升级前必须先阻止新写入并等待任务排空。自动升级功能实施前，要先改造这一停止语义，超时应中止切换并保留维护状态。

主应用要求 Java 21、prod Profile，默认监听 127.0.0.1:28080。发布前核对脚本选中的 Java 版本。公网入口必须经过 Nginx/TLS。脚本通过 JAVA_BIN 选择 Java，HTTP_PORT 默认 28080，并用 --server.port 覆盖应用的 SERVER_PORT 环境变量。反向代理模板见 [Nginx 示例](../../deploy/nginx/interview-reader.conf.example)；它只代理当前主应用，独立升级站点需另行配置。

## 3. 配置

脱敏模板见 [application.env.example](../../deploy/conf/application.env.example)。在服务器上填写数据库、登录和允许来源的实际值；不要将真实配置提交到仓库，也不要用模板覆盖已有的生产配置。

已在服务器创建并填写实际文件后，设置权限（以下命令不复制或覆盖配置内容）：

```bash
sudo chown root:ireader /opt/ireader/conf
sudo chmod 0750 /opt/ireader/conf
sudo chown root:ireader /opt/ireader/conf/application.env
sudo chmod 0640 /opt/ireader/conf/application.env
sudo -u ireader test -x /opt/ireader/conf
sudo -u ireader test -r /opt/ireader/conf/application.env && echo 'ireader 可读取配置'
```

conf/application.env 至少提供：

- DATABASE_URL、DATABASE_USERNAME、DATABASE_PASSWORD
- INTERVIEW_READER_USERNAME、INTERVIEW_READER_PASSWORD
- INTERVIEW_READER_ALLOWED_ORIGINS：完整 Origin 的逗号分隔列表
- INTERVIEW_READER_STORAGE_DIR='/opt/ireader/data'：当前生产实例已确认的文件存储根

脚本还支持 JAVA_BIN、HTTP_PORT、JAVA_OPTS、EXTRA_APP_OPTS。应用可选配置包括 UPLOAD_MAX_SIZE、INTERVIEW_READER_CONVERTER_VERSION 和 DATABASE_POOL_* 连接池参数。连接池上限应给数据库运维连接留出余量；泄漏检测只用于诊断。非测试环境不能关闭导入 Worker，启动保护会拒绝 interview-reader.import-worker.enabled=false。若不设存储根，脚本会默认指向自身目录下的 data。不要把生产配置内容复制到文档或示例文件中。

## 4. 健康检查与指标

- 存活：GET /actuator/health/liveness
- 就绪：GET /actuator/health/readiness，包含应用状态、数据库和磁盘空间
- 汇总：GET /actuator/health
- 指标：登录后访问 /actuator/metrics；关注 HTTP 延迟、JVM、Hikari、interview.reader.import.* 和 interview.reader.deletion.*

Nginx 模板限制健康端点只允许本机或指定内网访问。告警可从 readiness 连续失败、磁盘空间、连接池等待和后台任务积压开始，并按实际基线调整。

## 5. 备份与恢复

每日创建 MySQL 与文件备份，并将完整的 /opt/ireader/data、当前 interview-reader.jar 和恢复所需配置关联到同一批次。升级前的恢复点必须在阻止新写入、后台任务排空且主进程停止后制作；日常在线备份只有使用经过恢复演练验证的一致性快照方案，才能视为数据库与文件的同一恢复点。现阶段必须包含 data 根层哈希目录及 import-sources；核清历史文件用途前不要只备份其中一个子目录。配置备份应限制访问，数据库和文件备份完成后校验完整性，并至少每季度在隔离环境恢复演练。logs 和 tmp 属于运行状态，不作为数据库与源文件恢复点。

恢复时先阻止写入并停止主应用，恢复同批次的数据库和 data，校验文件、属主与权限，再用匹配的旧 JAR 启动。检查 Flyway 校验结果和 readiness，抽查文档、原文件下载、导入与删除任务后才恢复流量。发布成功并重新开放写入后的旧备份恢复可能丢失之后的写入，必须由操作员确认恢复点。

## 6. Flyway 与发布边界

生产启动时使用 MySQL 和 Flyway。迁移脚本进入生产后不可修改；修复须新增更高版本脚本。结构变更优先采用向后兼容的“扩展—迁移—收缩”步骤。发布前应在生产同构的 MySQL 副本验证迁移。若迁移或新 JAR 启动失败，保留日志和 flyway_schema_history，使用同批次的数据库、文件和旧 JAR 恢复；不要直接删除 Flyway 历史记录。

目前脚本只有 start、stop、restart、status，不负责备份、JAR 替换、数据库回滚或发布验证。生产 JAR 目前在本地构建；计划将 GitHub Actions 作为正式产物的唯一构建来源。独立升级流程落地前，任何手工替换都应遵守停写、排空、备份、进程停止、切换、就绪检查的顺序，不能把脚本返回“启动成功”当作发布完成。

## 7. 故障处置

导入或删除任务失败时，先记录任务 ID、文档 ID、阶段、traceId 和错误码，再修复根因并使用界面中的重试入口。磁盘或数据库故障时先停止写入，保留日志与快照。永久删除开始执行后不可撤销，恢复只能依靠删除前备份。
