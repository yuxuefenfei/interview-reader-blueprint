# Interview Reader 生产运行手册

本文以生产使用的 [daemon.sh](../../deploy/daemon.sh) 为准。仓库已加入[独立升级控制台](../architecture/system-upgrade.md)
及配套脚本改动；在服务器安装、配置并完成隔离环境演练前，生产仍不能使用页面升级。后续生产发布统一从升级控制台发起，脚本命令仅用于初次引导或故障处置。

## 1. 运行目录

脚本用自身所在目录作为 APP_DIR，JAR 和子目录都据此定位。已确认 INTERVIEW_READER_STORAGE_DIR 为 /opt/ireader/data；下图以脚本安装在
/opt/ireader 为例，实际安装路径仍需在服务器上核对。

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
    ├── ireader.lock
    └── upgrade.maintenance
```

从仓库安装脚本时，应保留 LF 行尾并赋予可执行权限；例如部署在 /opt/ireader 时执行
`sudo install -o root -g ireader -m 0750 deploy/daemon.sh /opt/ireader/daemon.sh`。实际位置不同则替换目标路径。

脚本固定读取同目录的 interview-reader.jar，且拒绝符号链接。data、logs、tmp 必须事先存在并允许 ireader 写入；JAR 必须是
ireader 可读的普通文件。脚本以 data 为工作目录启动，因此 data/target/pdfbox-font-cache 是 PDFBox 的可重建缓存。

脚本必须由 ireader 用户执行。conf/application.env 必须由 root 持有、不能是符号链接，也不能被组或其他用户写入；脚本以 ireader
身份 source 它，因此还要授予 ireader 只读权限，例如 root:ireader、0640，并限制 conf 目录的读取权限（750）。不得把密码或现网配置提交到仓库。

## 2. 启停与检查

若脚本实际安装在 /opt/ireader，可使用：

```bash
sudo -u ireader /opt/ireader/daemon.sh status
sudo -u ireader /opt/ireader/daemon.sh start
sudo -u ireader /opt/ireader/daemon.sh stop
sudo -u ireader /opt/ireader/daemon.sh restart
```

status 在进程未运行时返回退出码 3。脚本通过 PID 和 /proc 命令行确认进程，并用 tmp/ireader.lock 对每次命令加锁。start 在 2
秒后只确认进程存活，不代表应用已就绪；还需检查：

```bash
curl -fsS http://127.0.0.1:28080/actuator/health/readiness
```

普通 stop 仍会在等待 30 秒后发送 SIGKILL，仅作人工故障处置。控制台固定调用 `stop-gracefully`：30
秒内未停止即中止切换，保留维护门禁，不强杀后继续备份。

主应用要求 Java 21、prod Profile，默认监听 127.0.0.1:28080。发布前核对脚本选中的 Java 版本。公网入口必须经过 Nginx/TLS。脚本通过
JAVA_BIN 选择 Java，HTTP_PORT 默认 28080，并用 --server.port 覆盖应用的 SERVER_PORT
环境变量。反向代理模板见 [Nginx 示例](../../deploy/nginx/interview-reader.conf.example)
；它只代理当前主应用；独立站点使用[升级控制台 Nginx 模板](../../deploy/nginx/interview-reader-upgrade.conf.example)。

## 3. 配置

脱敏模板见 [application.env.example](../../deploy/conf/application.env.example)
。在服务器上填写数据库、登录和允许来源的实际值；不要将真实配置提交到仓库，也不要用模板覆盖已有的生产配置。

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
- UPGRADE_MAINTENANCE_FILE='/opt/ireader/tmp/upgrade.maintenance'：与控制台使用同一个持久门禁文件
- UPGRADE_INTERNAL_TOKEN：至少 32 字符，与控制台配置一致，仅本机内部接口使用

脚本还支持 JAVA_BIN、HTTP_PORT、JAVA_OPTS、EXTRA_APP_OPTS。应用可选配置包括
UPLOAD_MAX_SIZE、INTERVIEW_READER_CONVERTER_VERSION 和 DATABASE_POOL_* 连接池参数。连接池上限应给数据库运维连接留出余量；泄漏检测只用于诊断。非测试环境不能关闭导入
Worker，启动保护会拒绝 interview-reader.import-worker.enabled=false。若不设存储根，脚本会默认指向自身目录下的
data。不要把生产配置内容复制到文档或示例文件中。

## 4. 健康检查与指标

- 存活：GET /actuator/health/liveness
- 就绪：GET /actuator/health/readiness，包含应用状态、数据库和磁盘空间
- 汇总：GET /actuator/health
- 指标：登录后访问 /actuator/metrics；关注 HTTP 延迟、JVM、Hikari、interview.reader.import.* 和 interview.reader.deletion.*

主应用仍只监听本机，Nginx 模板限制健康端点只允许本机或指定内网访问。独立升级页面把存活、就绪、数据库、磁盘空间以及写请求、导入和删除任务、JVM
内存、活跃连接和 HTTP 平均延迟可视化；所有必要健康项为 UP 且内部门禁接口可用时才允许开始发布。主应用停机时，独立页面继续显示持久操作状态和健康检查失败原因。告警可从
readiness 连续失败、磁盘空间、连接池等待和后台任务积压开始。

## 5. 备份与恢复

每日创建 MySQL 与文件备份，并将完整的 /opt/ireader/data、当前 interview-reader.jar
和恢复所需配置关联到同一批次。控制台的升级恢复点按“停写、排空、备份、进程停止、切换、就绪检查”的顺序制作；写入和后台任务完全排空后，主进程仍运行但不再写数据库与文件，控制台此时导出数据库和数据目录，然后优雅停机。日常在线备份只有使用经过恢复演练验证的一致性快照方案，才能视为数据库与文件的同一恢复点。现阶段必须包含
data 根层哈希目录及 import-sources；核清历史文件用途前不要只备份其中一个子目录。配置备份应限制访问，数据库和文件备份完成后校验完整性，并至少每季度在隔离环境恢复演练。logs
和 tmp 属于运行状态，不作为数据库与源文件恢复点。

恢复时先阻止写入并停止主应用，恢复同批次的数据库和 data，校验文件、属主与权限，再用匹配的旧 JAR 启动。检查 Flyway 校验结果和
readiness，抽查文档、原文件下载、导入与删除任务后才恢复流量。发布成功并重新开放写入后的旧备份恢复可能丢失之后的写入，必须由操作员确认恢复点。

## 6. Flyway 与发布边界

生产启动时使用 MySQL 和 Flyway。迁移脚本进入生产后不可修改；修复须新增更高版本脚本。结构变更优先采用向后兼容的“扩展—迁移—收缩”步骤。发布前应在生产同构的
MySQL 副本验证迁移。若迁移或新 JAR 启动失败，保留日志和 flyway_schema_history，使用同批次的数据库、文件和旧 JAR 恢复；不要直接删除
Flyway 历史记录。

脚本提供 start、stop、stop-gracefully、restart、status；备份、JAR 替换、数据库恢复和就绪判定由独立控制台统一管理。GitHub Actions
主分支成功的 verify 运行会归档正式 JAR 和 SHA-256；本地构建产物不能通过控制台校验。旧生产版本尚不具备维护门禁，首次接入需在维护窗口手动安装一次已通过测试的新主
JAR、更新脚本并配置令牌，此后生产发布只能从控制台发起。

## 7. 故障处置

导入或删除任务失败时，先记录任务 ID、文档 ID、阶段、traceId
和错误码，再修复根因并使用界面中的重试入口。磁盘或数据库故障时先停止写入，保留日志与快照。永久删除开始执行后不可撤销，恢复只能依靠删除前备份。

## 8. 升级控制台部署与唯一入口

升级服务是仓库中独立的 `upgrade-console/` Maven 项目，使用 Java 21 构建为独立 JAR，监听 `127.0.0.1:28081`。它不依赖主应用
JAR 或主数据库来展示历史和进度。主后台菜单中的“系统升级”仅使用 Actions 仓库变量 `UPGRADE_CONSOLE_URL` 构建为独立 HTTPS
站点链接；未配置或地址无效时，main 的 verify 构建失败，避免发布无效入口。服务端以独立管理员凭据认证，并用 Origin
校验写接口。升级站点应仅允许 VPN 或指定内网访问。

目录示例：

```text
/opt/ireader-upgrade/
├── upgrade-console.jar
├── conf/
│   ├── console.env
│   └── mysql.cnf
└── state/
    ├── console-state.mv.db
    ├── state.json.migrated  # 仅旧版本迁移后存在
    ├── releases/
    └── backups/
```

控制台的已验证版本、操作状态、阶段事件与本次 `daemon.sh` 启停命令日志保存在 `UPGRADE_STATE_DIR/console-state.mv.db`（本机嵌入式
H2），不依赖主程序的 MySQL。首次启动新版本会一次性导入旧 `state.json`，再将原文件改名为 `state.json.migrated`；后续状态只写入
H2。迁移前请在控制台停机后备份整个 `state/` 目录；恢复时也应先停控制台并恢复整个目录，不能仅复制旧 JSON 或删除 H2
文件。旧版控制台不能读取新数据库，不可直接回退到旧 JAR 后继续操作。新界面通过 `/api/feed/stream` 接收 SSE，断线时以 H2
游标续传，历史记录经 `/api/operations/{id}/feed` 分页读取。更新 Nginx 配置时须保留该路径的 `proxy_buffering off`
和长连接超时；主程序原始运行日志不进入此流。

配置模板见 [console.env.example](../../deploy/upgrade-console/console.env.example)、[mysql.cnf.example](../../deploy/upgrade-console/mysql.cnf.example)、[systemd 单元](../../deploy/systemd/interview-reader-upgrade.service.example)
与 [Nginx 模板](../../deploy/nginx/interview-reader-upgrade.conf.example)。`UPGRADE_PUBLIC_ORIGIN` 必须与实际 HTTPS 站点
Origin 完全一致。`UPGRADE_DB_NAME` 是要备份和恢复的数据库名；控制台会校验主应用连接与备份凭据连接的 MySQL 库名和
server_uuid，任何不一致都会阻断发布；MySQL 凭据仅用于该库，需有导出、DROP/CREATE 和恢复所需权限。`UPGRADE_INTERNAL_TOKEN`
与主应用同值，建议由 `openssl rand -hex 32` 生成，不要记录在终端共享日志或仓库中。

正式发布先等待目标提交的 main `verify` 运行成功，再在该提交创建并推送 `vX.Y.Z` 标签。`publish-release` 工作流会从该运行下载同一批
JAR，校验后发布 Release；备注中的 Actions 运行 ID 用于升级控制台上传主 JAR。Release 只提供下载渠道，控制台仍依赖原 Actions
校验产物；该产物过期后，即使 Release 附件仍在，当前控制台也不能将它作为新的升级版本上传。

首次安装时，优先从正式版本的 GitHub Release 下载 `interview-reader.jar`、`upgrade-console.jar` 与 `SHA256SUMS` 并运行
`sha256sum -c SHA256SUMS`；尚未发布正式版本时，从成功的 main 分支 Actions 运行下载 `production-jar` 与
`upgrade-console-jar`，在维护窗口安装主 JAR 和更新后的 `daemon.sh`，再部署独立站点。控制台 JAR 需固定保存为
`/opt/ireader-upgrade/upgrade-console.jar`，例如将下载且核对过的文件以
`sudo install -o root -g ireader -m 0640 <下载的控制台JAR> /opt/ireader-upgrade/upgrade-console.jar` 安装。更新主
`application.env` 中的两个 `UPGRADE_*` 值后重启主服务，确认内部状态接口和健康检查可用，再启用控制台。首次引导仍需人工维护窗口；后续发布由页面统一执行。

实际配置文件写好后，设置最小权限并核对：

```bash
sudo chown root:ireader /opt/ireader-upgrade/conf /opt/ireader-upgrade/conf/console.env
sudo chmod 0750 /opt/ireader-upgrade/conf
sudo chmod 0640 /opt/ireader-upgrade/conf/console.env
sudo chown ireader:ireader /opt/ireader-upgrade/conf/mysql.cnf /opt/ireader-upgrade/state
sudo chmod 0600 /opt/ireader-upgrade/conf/mysql.cnf
sudo chmod 0700 /opt/ireader-upgrade/state
sudo install -d -o ireader -g ireader -m 0700 /opt/ireader-upgrade/state/releases /opt/ireader-upgrade/state/backups
sudo -u ireader test -r /opt/ireader-upgrade/conf/mysql.cnf
sudo -u ireader test -w /opt/ireader-upgrade/state
sudo -u ireader test -w /opt/ireader-upgrade/state/releases
sudo -u ireader test -w /opt/ireader-upgrade/state/backups
sudo -u ireader test -w /opt/ireader
```

最后一项必须通过：控制台需在 `/opt/ireader` 中原子替换固定 JAR。若目录当前不可写，可给 `ireader` 用户加精确 ACL（例如
`sudo setfacl -m u:ireader:rwx /opt/ireader`），避免更改整棵目录的属主。控制台与主 JVM 当前同为 `ireader`
用户，故应同时限制主站网络入口、升级站点与主机登录权限；后续若需更强的主机级隔离，应把文件切换和备份动作迁入受限的专用执行器。

安装 [systemd 单元](../../deploy/systemd/interview-reader-upgrade.service.example)后以
`sudo systemctl enable --now interview-reader-upgrade` 启动独立服务，并通过
`sudo systemctl status interview-reader-upgrade` 检查；Nginx 配置通过 `sudo nginx -t` 后再重载。

控制台单元必须包含 `KillMode=process`。主程序由控制台调用 `daemon.sh start` 启动时，`nohup` 不会使它离开控制台的 systemd
cgroup；默认的 `KillMode=control-group` 会在重启控制台时一并向主程序发送 SIGTERM。更新现网单元后执行
`sudo systemctl daemon-reload`，并用 `systemctl show -p KillMode interview-reader-upgrade.service`
（若现网单元名不同，请使用实际名称）确认生效。重启控制台前，先确认页面没有 `RUNNING` 操作；`KillMode=process`
也会让正在运行的备份等子进程继续存活，因此不能在升级或恢复进行中重启控制台。长期应把主程序交给独立的 systemd 单元管理。

按顺序完成一次隔离环境演练：核对健康面板与门禁、上传 Actions 产物、正常升级、故意失败后的自动回滚、控制台中断后的人工恢复，并从同批次备份恢复数据库与文件。现网数据库迁移应先在生产同构
MySQL 副本上验证。控制台会在发布前再次核对 Actions 运行及校验值，拒绝非 main 分支、失败运行、过期或不匹配产物。

若页面显示 `NEEDS_OPERATOR`，不要删除 `/opt/ireader/tmp/upgrade.maintenance`，也不要手工启动不匹配的
JAR。查看页面操作事件和控制台日志，必要时停机后检查 `console-state.mv.db`
；有同批次备份时从页面明确确认恢复。成功发布后的人工恢复会先备份当前状态，但会丢失选定恢复点之后的写入，页面要求再次确认。控制台在发布中断后不会猜测成功状态或自动开放写入。文件恢复时被替换的现有数据目录会保留为同级
`data.failed-*`，应在恢复点验收且再次备份后再人工清理，避免占满磁盘。
