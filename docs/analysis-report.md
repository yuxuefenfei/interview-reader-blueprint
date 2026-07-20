# Interview Reader 项目事实核查与优化改造报告

> 核查日期：2026-07-20
> 项目版本：0.1.0-SNAPSHOT
> 技术栈：Java 21、Spring Boot 3.3.5、MyBatis-Flex 1.10.9、MySQL/H2、Vue 3.5、TypeScript 6、Vite 7
> 核查范围：85 个后端主代码文件、6 个后端测试文件、10 个 Vue 文件、42 个 TypeScript 文件、Flyway 脚本、OpenAPI、CI、Nginx 与运行配置

## 1. 结论摘要

项目当前是结构清晰的模块化单体，导入、版本管理、阅读、离线缓存、文档下架与永久删除链路已经形成闭环。本轮已完成报告中能够在当前仓库内确定实施的 A～D 阶段改造：并发一致性、编辑器保存、安全基线、实体封装、查询与离线可靠性、PWA 更新、无障碍和运维基线均已落地。使用 JDK 21 实测后端 63 个用例全部通过，前端 16 个测试文件共 60 个用例全部通过，API 契约检查覆盖 48 个端点并通过。

本报告保留问题发现时的证据与修复计划，新增“实施结果”区分已完成项和环境验证项。真实 MySQL 并发/方言验证、代表性大数据基准，以及浏览器 PWA 升级与回滚演练仍需在具备对应基础设施的流水线或预发布环境执行，本文不把 H2/Vitest 结果冒充这些外部验证。原报告中的“缺少已有索引”“50MB+ 文件无界加载”“所有异常都按 ERROR 记录”“必须引入 Caffeine/Vuex/WebSocket”等结论不完全符合当前代码，继续不予采纳。

优先级定义：

- **P0**：已造成数据损坏、安全失守或发布阻断；当前静态核查未发现可直接定为 P0 的问题。
- **P1**：可能造成用户数据覆盖、核心操作失败或形成明显安全风险，下一轮应优先修复。
- **P2**：性能、可靠性、可维护性和体验问题，在 P1 后分批修复。
- **P3**：需用真实数据或指标验证后再决定，禁止仅凭推测引入基础设施。

## 2. 已验证的现状与配置契约

### 2.1 已保持一致的配置

| 契约 | 当前实现 | 结论 |
|---|---|---|
| 移动端后台断点 | `frontend/src/shared/responsive.ts` 统一定义 760px；路由守卫和 CSS 由契约脚本校验 | 一致，继续使用视口宽度，不引入 User-Agent 判断 |
| 上传上限 | Spring `10MB`、Nginx `10m`，契约脚本检查二者 | 当前一致 |
| API 路由 | Java Controller 与 `docs/api/openapi.yaml` | 48 个端点一致 |
| 枚举与前端类型 | Java 常量、OpenAPI、TypeScript | 当前一致 |
| 运行配置 | `ImportProperties`、`AuthProperties`、`DocumentDeletionProperties` 使用 `@ConfigurationProperties` 与校验注解 | 设计合理 |
| 环境隔离 | `dev/test/prod` 显式 Profile，生产凭据必须由环境变量提供 | 设计合理 |
| 数据库迁移 | H2/MySQL 均有 V1～V4 Flyway 脚本 | 版本一致，但仍缺真实 MySQL 自动验证 |

### 2.2 散落配置风险的实施结果

原核查发现默认端口、上传提示、存储/转换配置和前端策略常量分散。本轮已完成以下收口：

- 后端端口、上传上限、转换器版本和存储目录改为“环境变量 + 安全默认值”；README 与生产运行手册列出同名变量。
- 新增 `UploadProperties`，Spring multipart 限制和 `ApiExceptionHandler` 共用实际 `DataSize`，错误提示不再写死 10MB。
- 新增 `frontend/src/shared/runtimePolicy.ts`，统一导入轮询初始间隔、退避上限/因子和离线内容容量。
- 契约脚本现在解析并比较 Spring、Vite、Nginx、README 的默认端口，同时比较 Spring 与 Nginx 上传上限，不再依赖固定字符串。
- 应用启动完成后输出不含凭据的端口、上传上限、转换器、存储目录和 Worker 配置摘要，便于核对最终生效值。

**验证结果**：配置属性正值校验和容量文案测试通过；`npm run contract:check` 覆盖 48 个端点及上述运行契约并通过。Nginx 的值仍需在部署模板中显式同步，这是不同进程的配置边界，契约检查会阻止仓库默认值漂移。

## 3. P1：问题、修复设计与验收

### 3.1 版本号生成同时存在全量查询和并发冲突

**证据**：`ImportPackageService.nextVersionNo()` 与 `VersionRevisionService.nextVersionNo()` 查询该文档的全部版本、在内存中取最大值再加一；数据库已有 `UNIQUE(document_id, version_no)`。原报告误列的 `MarkdownPackageService` 当前没有此实现。

**风险**：版本数增长后产生无意义的行加载；两个事务并发创建修订时仍可能计算出相同版本号，其中一个请求因唯一约束失败。

**修复计划**：

1. Mapper 只查询 `COALESCE(MAX(version_no), 0)`，先消除全量实体加载。
2. 版本号分配必须同时解决并发，不能只把 Java `max()` 换成 SQL `MAX()`：可对所属文档行加 `SELECT ... FOR UPDATE` 后分配，或使用单独序列表/CAS 重试。
3. 导入提交与“创建修订”复用同一个版本号分配组件，并保留数据库唯一约束作为最后防线。

**验收标准**：真实 MySQL 中对同一文档并发创建至少 20 个版本，全部成功且版本号连续、不重复；查询日志不再返回全部版本列。

### 3.2 阅读进度 upsert 存在 TOCTOU 与覆盖更新

**证据**：`DocumentQueryService.upsertProgress()` 先查询再 INSERT/UPDATE。数据库已有 `UNIQUE(user_id, document_id)`，因此不会永久插入重复行，但并发首次写入可能令后一个请求返回冲突；并发更新还可能基于同一旧 revision 相互覆盖。

**修复计划**：

- 明确进度合并规则，以 `clientUpdatedAt`、`deviceId` 和服务端 revision 判断新旧，而不是无条件“后到覆盖先到”。
- MySQL 使用原子 upsert 或带 revision 条件的 CAS 更新；冲突后按既定合并规则重试。
- 不在 Service 中简单吞掉唯一键异常后盲目 UPDATE，否则旧设备进度可能覆盖新进度。

**验收标准**：覆盖并发首次写入、同设备乱序、不同设备乱序和重复离线重放；最终仅一行记录，revision 单调增长，较旧的客户端状态不能覆盖较新的状态。

### 3.3 编辑器切换节点时可能静默丢失待保存内容

**证据**：`VersionEditorView.scheduleBlockSave()` 只捕获块 ID，700ms 后再从当前 `blocks` 查找；`selectNode()` 会先清空活动块并由 `loadBlocks()` 替换整个数组。定时器到期时找不到原块便静默结束。

**修复计划**：

- 为待保存块保留不可变编辑快照（字段、payload、draftRevision），不要延迟读取可被替换的响应式数组。
- 切换节点、刷新块列表、删除块和离开路由前执行 `flushPendingSave()`；保存失败时阻止切换或明确要求用户确认放弃。
- 为加载请求增加请求序号或 AbortController，防止旧节点响应覆盖新节点。

**验收标准**：使用假定时器覆盖“输入后 700ms 内切换节点/删除/刷新/离开页面”，不得静默丢失；冲突或网络失败必须有可恢复提示。

### 3.4 安全基线不完整

**已确认事实**：

- 登录接口没有频率限制。
- 认证使用 Cookie；`SameSite=Lax` 已降低普通跨站 POST 风险，但没有 CSRF Token 或 Origin 校验，不能视为完整防护。
- Spring 与 Nginx 模板均未统一设置 CSP、`nosniff`、frame 限制和 Referrer Policy。
- `AuthFilter` 使用 `startsWith("/actuator/health")`，公开范围宽于精确健康检查路径。

**修复计划**：

1. 登录限流优先放在可信反向代理或应用入口，按“规范化客户端 IP + 用户名”组合限流，返回 429 与 `Retry-After`；多实例部署前不要采用无法共享状态的假分布式方案。
2. 增加 CSRF Token，或对所有状态变更请求严格校验 `Origin/Referer`；保留 `SameSite`、`HttpOnly`、生产 `Secure` Cookie。
3. 在一处集中配置安全响应头；CSP 需根据 Vite 构建产物实测，避免为通过页面而长期加入宽泛的 `unsafe-inline`。
4. 健康检查仅精确放行 `/actuator/health`；若需要组件级 health 子路径，应显式列出并继续由 Nginx 网络白名单限制。

**验收标准**：新增登录爆破、跨站状态变更、恶意 Origin、Cookie 属性、健康检查旁路和响应头集成测试；Nginx 与应用直连两种路径结果一致。

### 3.5 持久化实体公开字段，封装与演进能力不足

**证据**：`persistence/entity` 下 15 个实体共有 164 个 public 实例字段，Service 大量直接读写字段。Lombok 已在 Maven 编译与注解处理器中配置，但实体尚未使用访问器。

**修复目标**：实体字段一律私有，通过 Getter/Setter 访问；不再通过公开字段绕过封装。

**修复计划**：

1. 按业务模块分批迁移实体，字段改为 `private`，使用 Lombok `@Getter`、`@Setter`；不要使用会隐式生成 `equals/hashCode/toString` 的 `@Data`。
2. 将所有直接字段访问机械替换为 Getter/Setter，DTO 的 Java record 保持不变，常量的 `public static final` 不受此规则影响。
3. 先用一个实体验证 MyBatis-Flex 表定义生成、无参构造、结果映射、`insertSelective` 和更新行为，再分批扩展到全部实体。
4. 增加架构测试或静态检查，禁止 `persistence.entity` 出现 public 非静态字段，防止回退。

**注意事项**：实体是可变持久化对象，类级 `@Setter` 可以接受；若某字段存在受控状态转换，则仅给必要字段 Setter，并通过领域方法修改。不要为了使用 Lombok 将 Controller 请求、响应模型从 record 改回可变 Bean。

**验收标准**：15 个实体 public 实例字段计数为 0；后端编译、63 个现有测试及 MyBatis-Flex CRUD/大字段映射通过；真实 MySQL 冒烟作为环境验收项保留。

## 4. P2：可靠性、性能与体验改造

### 4.1 导出标签 N+1 查询

`DocumentPackageExportService` 先取文档标签关联，再对每个关联单独查询标签。改为一次 `IN` 批量查询或 JOIN，并按关联顺序稳定组装。验收时增加 SQL 次数断言：无论标签数量多少，标签读取最多 2 条查询。

### 4.2 草稿内容逐行加载、逐行删除

`VersionRevisionService.deleteContent()` 将 block、asset、node 全部载入内存后逐条 DELETE。改为按外键顺序执行条件批量删除；节点删除要验证自关联级联行为，不能只凭假设删除父节点。H2 与 MySQL 均需覆盖大批量数据和事务回滚。

### 4.3 相同导入文件无法从失败/取消状态自然重试

`findImportJobByFingerprint()` 不区分状态，FAILED/CANCELED 任务也可能被直接返回。先定义幂等策略：进行中或已成功任务返回原任务；失败/取消任务允许新建。并发上传还需通过锁、约束或幂等键避免同时创建两个活动任务。是否增加 `(owner_id, import_fingerprint, status, created_at)` 索引，必须先用真实查询与 `EXPLAIN` 判断。

### 4.4 会话清理只在登录时全量触发

`AuthSessionService` 使用进程内 `ConcurrentHashMap`，创建会话时才全量清理，按 token 查询只删除该 token 的过期记录。它不是立即失控的“高危内存泄漏”，但单实例长期运行且登录量高时会积累过期对象。加入低频定时清理或有上限的机会式清理，并注入 `Clock` 以测试过期边界；同时明确单实例会话模型，多实例前需改用共享会话存储或粘性会话。

### 4.5 离线进度队列缺少串行化与错误分类

- IndexedDB 刷新中，“发送成功、删除失败”会留下已发送项并中断后续处理，形成重复重放和队列阻塞，而不是原报告所称的“已确认数据丢失”。
- localStorage 回退采用读-改-写，并发入队/刷新可能相互覆盖。
- `ReaderView.saveProgressOfflineAware()` 把 400/401/409 等非重试错误也加入离线队列。

改造为单实例 flush mutex，IndexedDB 使用清晰的事务边界；只对网络错误、429 和可重试 5xx 入队，认证/校验/冲突错误反馈给用户。服务端原子 upsert 是离线重复投递安全的前置依赖。

### 4.6 IndexedDB 缓存逐项开启事务

`contentCache` 的文档清理与 LRU 修剪为每个 key 新建 readwrite 事务。改为单事务 cursor/批量 delete，并补充部分失败、容量边界与文档永久删除后的清理测试。

### 4.7 导入轮询可能重叠且频率偏高

`setInterval(800ms)` 不等待上一次请求结束，慢网络下会出现并行查询。先改为“请求完成后再 `setTimeout`”，运行中使用 1～2 秒并逐步退避，页面隐藏时暂停，恢复后立即刷新。当前规模无需直接引入 WebSocket；只有任务数量和实时性指标证明轮询不足时再评估 SSE/WebSocket。

### 4.8 PWA 缓存更新策略不完整

Service Worker 使用固定 `interview-reader-app-shell-v1`、安装后立即 `skipWaiting()`，静态资源采用 cache-first 且同一缓存内没有构建级清理。结果可能是旧页面与新 worker/资源混用，历史 hash 资源持续残留。改为构建版本化缓存，定义“提示用户刷新”或“受控自动刷新”策略，并验证升级、回滚、离线首次打开及缓存回收。 同时增加在线/离线状态提示，但不得遮挡阅读正文或把暂时断网误报为内容丢失。

### 4.9 前端路由 HMR 监听器未释放

`router/index.ts` 在模块作用域注册 media query change 监听器，HMR 重载会累积。将回调命名，并在 `import.meta.hot.dispose` 中移除；生产行为不变。

### 4.10 无障碍与阅读体验

- 登录用户名/密码、阅读搜索框需要可访问名称；主题切换补 `aria-pressed`，移动工具按钮补 `aria-expanded`。
- `TocTree` 当前嵌套 `ol + button` 已有基础列表语义。若升级为 `role=tree`，必须同时实现方向键、Home/End、展开/折叠和 roving tabindex；不能只加 ARIA role。
- 编辑器内嵌预览是非模态面板，应使用命名 `region`，不是 `aria-modal=true` 的 dialog。
- 路由切换后将焦点移动到页面主标题/主内容，并提供跳到正文入口。
- 原报告称 `#667085` 在白色背景对比度为 4.01:1；按 WCAG 公式实算约 4.97:1，满足 AA 普通文本要求，此项不作为缺陷。

### 4.11 代码重复和死代码

抽取统一的 `toUserMessage(error, fallback)`，但保留各场景自己的 fallback 文案；删除未引用的 `IconButton.vue`，或在确认需要统一图标按钮后再接入。不要为了消除很小的 `id/uuid` 重复创建无业务含义的工具类。

### 4.12 生产运维基线

- Actuator 已存在且暴露 health/info，原报告“需要添加 Actuator”不成立；后续补 readiness/liveness、磁盘空间、导入/删除队列指标和告警阈值。
- HikariCP 暂用默认值不能直接判定为缺陷。根据数据库连接上限、请求并发和查询耗时测算池大小，再外部化 connection/validation/leak detection 超时。
- 生产默认保持 INFO；为导入任务、删除任务、文档、traceId 增加结构化上下文与日志保留策略，不在生产全局开启应用 DEBUG。
- 增加可重复的 JAR 服务管理示例、数据目录权限、备份恢复、Flyway 升级与回滚运行手册。
- `import-worker.enabled=false` 时会在 HTTP 线程同步执行导入；test Profile 可保留该模式，非测试环境应在启动时拒绝此配置，或始终提交异步任务并在 Worker 不可用时返回 503。

## 5. 大数据量问题：先测量再决定（P3）

以下代码路径客观存在，但是否值得结构性改造取决于真实文档规模：

1. 导入任务在 LONGTEXT 保存 `rawExtractionJson` 与规范化 JSON，单项修订会整包反序列化/序列化。
2. 内容列表的部分查询使用 `ALL_COLUMNS`，可能读取大 payload。
3. 源文件下载返回 `byte[]`，但当前 Spring 与 Nginx 均限制上传为 10MB，不是原报告描述的无界“50MB+”风险。
4. 编辑器搜索会递归克隆过滤后的树；只有大目录基准能证明需要扁平化。
5. Element Plus 全量 CSS 构建产物较大，但是否优化应由 bundle budget 和首屏指标决定。

**测量门槛**：建立 10MB 源文件、1000 节点、10000 内容块、100 个版本的基准数据；记录接口 P95、SQL 次数、堆峰值、JSON 大小和前端交互耗时。超过约定预算后，再选择对象存储 JSON、投影查询、流式下载或按需 CSS。当前不直接引入 Caffeine：目录/文档列表缓存需先证明数据库是瓶颈，并设计发布、下架、修订和永久删除时的失效策略。

## 6. 中文注释与代码可读性规范

当前关键业务代码的注释密度偏低，尤其是版本不变性、导入状态机、永久删除阶段、事务提交后调度、离线重放和并发合并规则。后续改造统一补充中文注释，但不要求给每行代码添加翻译式注释。

### 6.1 必须添加中文注释的位置

- 公共 Service 方法：说明业务前置条件、状态变化、事务边界、幂等性和异常语义。
- 并发与一致性代码：说明为什么加锁/CAS、revision 如何比较、冲突后为何能安全重试。
- 导入、发布、下架、永久删除状态机：列出允许的来源状态、目标状态和失败恢复方式。
- `TransactionSynchronization`、异步 Worker、离线队列与 Service Worker：说明提交时机、重复执行保证和进程崩溃后的行为。
- 安全路径校验、文件删除和解析启发式：说明保护的不变量与拒绝条件。
- 难以从命名看出的 SQL、查询投影和数据库方言差异。

### 6.2 不应添加的注释

- 不给 Getter/Setter、构造器赋值、简单条件分支写“获取字段/设置字段”式注释。
- 不重复代码表面行为；注释应解释“为什么、约束是什么、失败后怎么办”。
- 不在注释中保存已经失效的需求或临时调试记录；行为变化时必须同步更新注释。

### 6.3 验收标准

- P1/P2 改造 PR 的关键状态转换和并发方案均有中文说明。
- Code Review 清单新增“注释是否解释不变量、是否与实现一致”。
- 公共 API 仍以 OpenAPI 为契约，中文代码注释不能替代接口文档和测试。

## 7. 测试与质量门禁补强

### 7.1 当前实测基线

| 检查 | 结果 |
|---|---|
| 后端测试（JDK 21） | 63 passed |
| 前端 Vitest | 16 files / 60 passed |
| API/配置契约 | 48 endpoints，响应模型、断点、端口与上传上限 passed |
| 前端生产构建 | passed；最大 JS 451,816 B、JS 合计 648,596 B、CSS 合计 386,999 B |
| CI | 已执行前端测试/构建、后端测试/打包与包体预算；暂无浏览器 E2E 和真实 MySQL 自动测试 |

现有 `InterviewReaderApiTests` 已覆盖核心导入、版本生命周期、下架/恢复、永久删除失败重试等链路；`progressQueue` 也已有基础测试。因此原报告中“ImportPackageService、DocumentLifecycleService、DocumentDeletionProcessor、progressQueue 无覆盖”的表述已删除。

### 7.2 测试状态与剩余项

1. **真实 MySQL 集成测试（待环境补充）**：Flyway 迁移、版本号并发、进度 upsert、批量删除、索引与关键 SQL 方言。
2. **组件测试（本轮已补充）**：VersionEditor 自动保存、节点切换、旧请求返回，以及离线进度队列并发、错误分类和回退存储。
3. **浏览器 E2E（待环境补充）**：登录、移动端 `≤760px` 禁止后台、桌面端管理、导入到发布、下架、彻底删除、PWA 升级。
4. **安全测试（本轮已补充）**：登录限流、Origin/Referer、Cookie 属性、安全头和 actuator 精确路径白名单。
5. **架构规则（本轮已补充）**：实体无 public 实例字段；生产 Profile 不允许同步导入，并且只允许单一受支持 Profile。
6. **性能基准（待代表性数据）**：大文档导入、目录加载、版本列表和永久删除的 SQL 数与耗时预算。

### 7.3 CI 补充顺序

先增加实体规则、格式/静态检查和组件测试，再增加 Testcontainers MySQL，最后增加关键浏览器 E2E。依赖漏洞扫描与 SBOM 可加入发布流水线，但不得用无负责人、无处置 SLA 的告警堆积替代治理。

## 8. 本轮实施结果（2026-07-20）

| 范围 | 状态 | 已落地内容 | 尚需环境验证 |
|---|---|---|---|
| 配置契约 | 已完成 | 环境变量默认值、`UploadProperties`、非敏感配置摘要、前端 `runtimePolicy`、端口/上传契约检查 | 部署时按实际 Nginx 与环境变量做启动核对 |
| A：并发与安全 | 代码与自动测试完成 | SQL 最大版本号 + 文档行锁；进度时间戳防旧状态覆盖；编辑保存串行快照；登录限流、Origin/Referer、安全头、精确 health 白名单 | 真实 MySQL 同文档 20 路并发；Nginx 直连/代理双路径安全验证 |
| B：实体与可读性 | 已完成 | 15 个实体字段全部私有，使用 Lombok `@Getter`/`@Setter`；直接字段访问已迁移；架构测试防回退；关键并发、事务、离线与状态转换补中文注释 | 真实 MySQL CRUD 冒烟 |
| C：查询与离线可靠性 | 代码与自动测试完成 | 标签固定两次查询；内容按条件批量删除；失败/取消导入可重试且并发复用活动任务；会话定时清理；离线队列互斥、事务提交和错误分类；轮询串行退避 | MySQL 大批量删除回滚与 `EXPLAIN` |
| D：PWA、A11y 与运维 | 代码与自动测试完成 | 构建级 SW 缓存和用户确认更新；在线/离线提示；焦点、可访问名称与状态属性；队列指标、健康分组、连接池外部化、运行手册 | 浏览器升级/回滚/离线首次打开 E2E；备份恢复演练 |
| E：按指标扩展 | 已完成本轮决策 | 建立构建包体门禁；当前最大 JS 451,816 B、JS 合计 648,596 B、CSS 合计 386,999 B，均低于预算 | 10MB/1000 节点/10000 块/100 版本基准；超过预算后才做结构性改造 |

### 8.1 自动验证汇总

- 后端：63 passed，覆盖 H2/Flyway、核心 API、并发导入/版本/进度、安全、会话到期边界、实体访问器和查询次数。
- 前端：16 files / 60 passed，覆盖移动端后台拦截边界、离线队列并发与错误分类、编辑器串行保存及既有阅读功能。
- 构建：TypeScript 类型检查、OpenAPI/配置契约、Vite 生产构建和包体预算均通过。
- 静态检查：`git diff --check` 无空白错误；15 个实体 public 实例字段为 0。

### 8.2 明确保留的后续工作

真实 MySQL Testcontainers、浏览器 E2E 和代表性性能基准需要 Docker/浏览器及预发布部署环境。本轮代码已经为这些验证准备了锁、批量 SQL、契约门禁和可观测指标，但未在当前仅 H2/jsdom 的环境中伪造通过结论。后续优化应先补这三类环境验证；只有指标越过第 5 节门槛时，才评估对象存储、流式下载、查询投影或 CSS 按需引入。
## 9. 分阶段实施顺序

| 阶段 | 内容 | 依赖与退出条件 |
|---|---|---|
| A：并发与安全 | 版本号分配、进度 upsert、编辑器自动保存、登录限流、CSRF、安全头 | MySQL 并发测试与安全集成测试通过 |
| B：实体与可读性 | 15 个实体私有字段、Lombok Getter/Setter、迁移直接字段访问、关键逻辑中文注释 | public 实例字段为 0，现有 123 个前后端测试全部通过 |
| C：查询与离线可靠性 | 标签 N+1、批量删除、导入幂等、会话清理、离线队列与缓存事务 | SQL 次数、失败恢复与重复重放测试通过 |
| D：PWA、A11y 与运维 | SW 版本策略、离线提示、键盘/焦点、指标、运行手册 | 浏览器 E2E 与升级/回滚演练通过 |
| E：按指标扩展 | 大 JSON、流式下载、投影、CSS、缓存 | 只有基准超过预算才实施 |

所有阶段都应保持：移动端 `≤760px` 仅开放阅读功能；已发布文档允许先下架再进入不可撤销的阶段式永久删除；永久删除继续覆盖版本、导入记录、原始文件和转换中间产物。

## 10. 本轮不采纳或暂缓的原建议

| 原建议 | 处理结论 |
|---|---|
| 立即引入 Caffeine 缓存 | 暂缓，缺少瓶颈数据和完整失效设计 |
| 新增 reading_progress、content_node 等索引 | 多数已存在；先用 MySQL `EXPLAIN`，禁止重复建索引 |
| 800ms 轮询直接改 WebSocket | 不采纳；先解决重叠请求并退避，SSE/WebSocket 由规模决定 |
| 前端统一使用 Vuex | 不采纳；当前局部状态可控，需要全局状态时优先评估 Pinia |
| Worker 立即抽象公共基类 | 暂缓；导入和删除的持久化语义不同，过早抽象会隐藏差异 |
| 硬编码 `LOCAL_USER_ID` 是现存多用户缺陷 | 当前产品是明确的单用户模型，不按缺陷修复；只有多用户需求获批后，才整体引入认证主体上下文和 owner 过滤测试 |
| `ApiExceptionHandler` 将所有异常记为 ERROR | 不准确；当前仅 catch-all `Exception` 分支记 ERROR。可单独识别客户端断连以减少噪声 |
| `orderByUnSafely()` 是当前 SQL 注入漏洞 | 不成立；参数是硬编码表达式，可用常量与安全注释约束，但不是现存注入点 |
| `readerApi.progress` 依赖 Axios 204 的未文档行为 | 不成立；代码显式按 HTTP 204 返回 null，可补类型和回归测试 |
| `touchDocument()` 必然产生外部可触发 NPE | 仅属防御性改进；当前调用链通常已校验文档，建议改为按 ID 更新并检查受影响行数 |
| `#667085` 不满足 WCAG AA | 计算错误；白底对比度约 4.97:1，满足普通文本 AA |

## 11. 保留的项目亮点

- Controller → Service → Mapper 分层明确，DTO 使用 Java record。
- 导入管线具有 Preflight → Extract → Normalize → Validate → Commit 阶段模型。
- 已发布版本不可原地修改，通过修订草稿演进。
- 文档支持下架、恢复与不可撤销的阶段式永久删除，并清理导入及文件产物。
- 事务提交后再调度异步任务，避免未提交数据被 Worker 读取。
- 阅读端具备 IndexedDB、localStorage、内存回退和跨窗口预览通信。
- OpenAPI、Java、TypeScript、响应式断点和上传上限已有自动契约校验。
- RuntimeProfileGuard、Maven Enforcer 与生产凭据校验能阻止错误环境启动。
- 当前未过度依赖 Redis、MQ、Elasticsearch，适合在指标出现前保持模块化单体。

---

本报告只把可由当前代码、配置、迁移脚本和测试证明的问题纳入确定性改造计划；所有容量与性能建议必须在真实 MySQL 和代表性数据上复测后再实施。
