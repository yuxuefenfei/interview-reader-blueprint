# Interview Reader 生产运行手册

## 1. 部署边界

- 应用以 `prod` Profile 启动，只监听 `127.0.0.1:28080`，公网流量必须先经过 Nginx/TLS。
- Java 使用 21；数据库使用 MySQL，并由 Flyway 在启动时校验和迁移。
- `data/import-sources` 保存导入原文件与转换产物，必须与数据库一起备份。
- 非测试环境禁止关闭导入 Worker；启动保护会拒绝 `interview-reader.import-worker.enabled=false`。

## 2. 目录和权限

建议建立专用系统用户 `interview-reader`，应用目录只读，数据、日志和备份目录可写：

```text
/opt/interview-reader/app.jar
/etc/interview-reader/application.env
/var/lib/interview-reader/import-sources
/var/log/interview-reader
/var/backups/interview-reader
```

环境文件权限设为 `0600`，不得把数据库密码、后台密码或允许来源提交到仓库。数据目录仅授予服务用户读写权限。

## 3. systemd 示例

```ini
[Unit]
Description=Interview Reader
After=network-online.target mysql.service
Wants=network-online.target

[Service]
User=interview-reader
Group=interview-reader
WorkingDirectory=/var/lib/interview-reader
EnvironmentFile=/etc/interview-reader/application.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/interview-reader/app.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/lib/interview-reader /var/log/interview-reader

[Install]
WantedBy=multi-user.target
```

发布前执行 `java -version`、配置校验和数据库备份；发布后检查 readiness，再切换流量。JVM 参数应由压测和容器内存限制决定，不在模板中写死堆大小。

## 4. 必需环境变量

- `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD`
- `INTERVIEW_READER_USERNAME`、`INTERVIEW_READER_PASSWORD`
- `INTERVIEW_READER_ALLOWED_ORIGINS`，使用逗号分隔的完整 Origin，例如 `https://reader.example.com`
- 可选运行参数：`SERVER_PORT`、`UPLOAD_MAX_SIZE`、`INTERVIEW_READER_CONVERTER_VERSION`、`INTERVIEW_READER_STORAGE_DIR`
- 可选连接池参数：`DATABASE_POOL_MAX_SIZE`、`DATABASE_POOL_MIN_IDLE`、`DATABASE_CONNECTION_TIMEOUT_MS`、`DATABASE_VALIDATION_TIMEOUT_MS`、`DATABASE_LEAK_DETECTION_MS`

连接池大小按 MySQL 连接上限、应用实例数、并发量和查询 P95 测算，所有实例的最大连接数之和必须为数据库运维连接保留余量。泄漏检测只用于诊断窗口，避免长期设置过低阈值。

## 5. 健康检查与指标

- 存活：`GET /actuator/health/liveness`
- 就绪：`GET /actuator/health/readiness`，包含应用状态、数据库和磁盘空间
- 汇总：`GET /actuator/health`
- 指标：登录后访问 `/actuator/metrics`；重点关注 HTTP 延迟、JVM、Hikari，以及 `interview.reader.import.*`、`interview.reader.deletion.*`

Nginx 模板只允许本机或指定内网访问健康端点。建议告警：readiness 连续 3 次失败；磁盘剩余低于 15%；连接池等待持续升高；导入/删除已提交任务数持续 10 分钟不下降。阈值需按基线数据复核。

## 6. 备份与恢复

每日执行一致性数据库备份，并同步保存 `/var/lib/interview-reader/import-sources`。两者使用同一备份批次号，备份完成后校验文件哈希，并至少每季度做一次隔离环境恢复演练。

恢复顺序：

1. 停止应用并隔离外部流量。
2. 恢复 MySQL 到目标时间点。
3. 恢复同批次的导入源文件目录，校验属主、权限和哈希。
4. 用与数据库版本匹配的应用 JAR 启动，先检查 Flyway `validate` 和 readiness。
5. 抽查已发布文档、原文件下载、导入记录和永久删除任务，再恢复流量。

## 7. Flyway 发布与回滚

- 迁移脚本一旦进入生产不可修改；新增修复必须创建更高版本脚本。
- 发布前在生产版本同构的 MySQL 备份副本执行迁移和回归测试。
- 结构迁移优先使用向后兼容的“扩展—迁移—收缩”步骤，避免应用回滚后读取不了新结构。
- 若启动迁移失败，停止新版本，保留日志和 `flyway_schema_history`，从发布前备份恢复数据库与文件后再启动旧 JAR；不要手工删除 Flyway 历史行冒充回滚。

## 8. 故障处置

导入或删除任务失败时先记录任务 ID、文档 ID、阶段、traceId 和错误码。不要直接改任务为成功；修复根因后使用界面提供的重试入口。发生磁盘或数据库故障时先停止写入，再保存日志与快照。永久删除从开始执行即不可撤销，恢复只能依赖删除前备份。
