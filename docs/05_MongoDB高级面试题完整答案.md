---
title: "MongoDB 高级面试题完整答案"
subtitle: "从文档模型、索引与查询优化到复制集、分片、事务和生产排障"
author: "高级 Java / 分布式系统面试复习讲义"
date: "2026-07"
lang: zh-CN
---

# 阅读说明

本讲义面向具有 Java 后端、微服务、数据库或分布式系统经验的中高级工程师。内容不会把 MongoDB 简化为“可以存 JSON 的数据库”，而是从 BSON 文档、原子更新和数据建模开始，逐步推导到索引选择、查询规划、聚合、复制集、读写一致性、事务、WiredTiger、分片、备份恢复、Change Streams 和线上故障排查。

每道题按照真实面试中更容易获得高评价的顺序组织：**结论先行 -> 原理拆解 -> 示例 -> 生产边界 -> 常见误区 -> 面试追问**。高级岗位的回答重点不是罗列功能，而是说明：为什么这样设计、失败时会丢失什么保证、如何监控验证，以及何时不应该使用 MongoDB。

## 版本语境

- 截至 2026 年 7 月，MongoDB 官方发布页将 **8.3 系列**标记为当前稳定小版本；企业自建环境仍大量使用 6.0、7.0 和 8.0 大版本。本文以 MongoDB 8.0 的通用机制为主，并在必要处标注 8.1+ 的变化。
- 存储引擎默认按 WiredTiger 讨论。复制集、分片集群、事务、Change Streams 均假设使用官方支持的部署形态，而不是单机开发实例。
- Java 示例以 MongoDB Java Driver 及 Spring Data MongoDB 的通用原则为主。具体 API 可能随驱动版本变化，面试重点是连接池、超时、会话、重试和一致性语义。
- Atlas Search、Vector Search 等托管平台能力会作为扩展题介绍，但不会与 MongoDB Server 原生 B-Tree 索引、`$text` 索引混为一谈。

## 推荐的面试回答结构

1. **一句话结论**：先回答能不能做、适不适合做。
2. **数据路径**：文档如何写入、索引如何定位、复制或路由如何传播。
3. **并发与故障边界**：超时、重试、选主、网络分区、事务冲突时会怎样。
4. **工程方案**：索引、限流、容量、监控、备份、降级和恢复。
5. **验证方式**：用 `explain`、Profiler、日志、指标、压测和故障演练证明结论。

## 目录

1. MongoDB 是什么，与关系型数据库如何选型
2. BSON、Document、Collection 与 `_id` 有什么关键特性
3. MongoDB “无模式”是否意味着不需要数据建模
4. 嵌入与引用如何选择，如何设计一对多和多对多
5. 单文档原子性、更新操作符与幂等写如何理解
6. MongoDB 有哪些索引类型，分别适合什么场景
7. 联合索引、ESR 原则、排序和覆盖查询如何设计
8. 数组索引与 Multikey 有哪些限制和陷阱
9. 如何阅读 `explain` 并系统优化慢查询
10. 查询计划缓存为什么会导致“同一 SQL”忽快忽慢
11. Aggregation Pipeline 如何执行，怎样优化内存与性能
12. `$lookup` 能否替代关系型 Join，什么时候会很慢
13. Read Concern、Write Concern、Read Preference 如何组合
14. 复制集、oplog、选举与回滚是如何工作的
15. 主节点故障后是否可能丢数据，如何缩小风险窗口
16. MongoDB 事务如何实现，为什么不应滥用多文档事务
17. WiredTiger 缓存、Journal、Checkpoint 和压缩如何工作
18. 为什么 MongoDB 内存很高，Working Set 与磁盘 I/O 如何判断
19. 分片集群由哪些组件组成，请求是如何路由的
20. 如何选择分片键，Range 与 Hashed Sharding 如何取舍
21. Scatter-Gather、热点分片、Jumbo Chunk 和迁移如何治理
22. 分片集群如何扩容、重分片以及保证唯一性
23. Change Streams 如何实现实时事件，如何保证不重不漏
24. MongoDB 备份、PITR 和灾难恢复如何设计
25. MongoDB 延迟、CPU、锁或连接数异常时如何排查
26. Java/Spring Data MongoDB 与高并发系统设计综合题
27. 附录：高频追问速答、排障命令、上线检查清单与官方资料

# 第一部分：文档模型与基础原理

# 1. MongoDB 是什么，与关系型数据库如何选型？

## 1.1 结论先行

MongoDB 是面向文档的操作型数据库，使用 BSON 文档保存数据，支持二级索引、聚合、复制集、分片和 ACID 事务。它的核心价值不是“不要表结构”，而是让一组经常一起读取和更新的数据能够作为一个聚合文档保存，并通过复制和分片扩展可用性与容量。

MongoDB 适合以下类型的系统：

- 数据结构层次丰富、字段变化较快，例如商品目录、内容、用户画像、设备状态。
- 访问模式以聚合对象为中心，希望一次读取得到完整对象。
- 需要较高写入吞吐、水平扩展或跨地域部署。
- 需要灵活索引、地理位置、数组查询、聚合流水线或 Change Streams。

但它不是 MySQL/PostgreSQL 的全面替代品。若业务核心是复杂关系、强外键约束、频繁跨实体 Join、严格账务核算或大量即席分析，关系型数据库通常更自然。

## 1.2 MongoDB 与关系型数据库的核心差异

| 维度 | MongoDB | MySQL/PostgreSQL |
|---|---|---|
| 数据组织 | BSON 文档，可嵌套数组和子文档 | 行、列、表和关系 |
| 建模中心 | 聚合边界与访问模式 | 实体关系与规范化 |
| 原子性天然边界 | 单文档 | 单行或多行事务 |
| 关系处理 | 嵌入、引用、`$lookup` | Join、外键、约束 |
| 扩展方式 | 原生分片集群 | 常依赖分库分表或扩展方案 |
| Schema | 灵活，可使用 Validation | 显式 DDL 与约束 |
| 典型优势 | 开发迭代、对象聚合、水平扩展 | 约束、复杂查询、成熟事务 |

选型时不能只比较“QPS”。更重要的是写模型、查询模型、一致性要求、数据生命周期和运维能力。

## 1.3 适合 MongoDB 的场景

### 商品与内容目录

不同商品类目字段差异很大，手机有内存和屏幕，服装有尺码和材质。把公共字段与类目属性放在同一文档中，可以避免大量稀疏列和 EAV 模型。

```javascript
{
  _id: ObjectId("..."),
  sku: "P1001",
  category: "phone",
  title: "Example Phone",
  attributes: {
    memoryGb: 16,
    storageGb: 512,
    screen: "6.7"
  },
  tags: ["5G", "dual-sim"]
}
```

### 事件状态和设备数据

设备最新状态、工单快照、游戏角色状态等数据通常以单个聚合对象读写，MongoDB 文档模型可以减少多表 Join。

### 多租户业务

通过 `tenantId` 作为索引前缀或分片键的一部分，可以把租户隔离、路由和容量规划结合起来。但低基数租户或超级租户可能造成热点，需要进一步设计。

## 1.4 不适合或需要谨慎的场景

### 复杂财务账本

账务系统通常要求不可变流水、强约束、跨账户一致性、对账和审计。MongoDB 能做事务，但“支持事务”不代表文档模型天然适合所有账务模型。核心账本常优先采用关系型数据库，MongoDB 可作为查询视图或业务快照。

### 高度关系化且查询方式不可预测

如果产品经常临时要求任意维度 Join、复杂窗口函数和多表分析，文档冗余会带来更新一致性成本，聚合流水线也未必比 SQL 更简单。

### 超大二进制对象

单个 BSON 文档有大小上限。图片、视频和大文件应放对象存储；GridFS 仅在确有需要通过 MongoDB 管理分块文件时使用。

## 1.5 选型方法

一个高质量选型过程应回答：

1. 最重要的 5 个读写路径是什么？
2. 哪些字段必须一起原子更新？
3. 数据是否天然有聚合边界？
4. 是否需要跨实体约束和复杂 Join？
5. 单集合预计数据量、文档大小、读写 QPS 和增长速度是多少？
6. 是否需要跨机房、分片和在线扩容？
7. 团队是否有 MongoDB 监控、备份和故障处理能力？

## 1.6 面试中的高质量表达

> 我不会因为字段多或数据量大就直接选 MongoDB。先从访问模式定义聚合边界：经常一起读写、需要单文档原子的内容适合嵌入；关系复杂、约束强、查询不可预测的部分更适合关系型数据库。MongoDB 的优势是文档聚合和原生分布式能力，但代价是冗余一致性、分片键设计和更高的运维复杂度。

## 1.7 常见误区

- **误区：MongoDB 无模式，所以可以随便存。** Schema 只是从数据库 DDL 转移到了文档设计、校验规则和应用版本管理。
- **误区：有事务后，MongoDB 和关系型数据库没有区别。** 数据模型、索引、Join、约束与执行器仍然不同。
- **误区：分片可以解决所有慢查询。** 分片只增加并行资源，错误索引和 Scatter-Gather 可能让系统更慢。

# 2. BSON、Document、Collection 与 `_id` 有什么关键特性？

## 2.1 结论先行

MongoDB 存储的是 BSON，而不是纯 JSON。BSON 是带类型的二进制文档格式，支持日期、二进制、Decimal128、ObjectId 等 JSON 不具备或表达不精确的类型。一个文档是 MongoDB 原子读写和常见建模的基本单位，集合是文档的容器。

理解 BSON 的类型、字段顺序、大小限制和 `_id` 规则，是避免数据精度、索引和兼容性问题的基础。

## 2.2 BSON 与 JSON 的差异

```javascript
{
  _id: ObjectId("64f0..."),
  createdAt: ISODate("2026-07-20T10:00:00Z"),
  amount: NumberDecimal("19.99"),
  counter: NumberLong("9007199254740993"),
  payload: BinData(0, "...")
}
```

BSON 的常见类型包括：

- String、Boolean、Null。
- Int32、Int64、Double、Decimal128。
- Date、Timestamp。
- ObjectId。
- Embedded Document、Array。
- Binary、Regular Expression。

Java 中要特别关注数字映射。把金额保存为 Double 可能出现二进制浮点误差；金额可使用最小货币单位的 Int64，或在需要十进制定点语义时使用 Decimal128。

## 2.3 `_id` 的作用

每个普通集合文档都必须有唯一 `_id`。若客户端未提供，驱动通常生成 ObjectId。MongoDB 会为 `_id` 创建唯一索引。

ObjectId 通常包含时间相关部分、随机部分和计数部分，具备大致递增性，但它不是严格连续序列，也不应被当作安全随机令牌。

常见设计：

- 使用 ObjectId：简单、分布式生成、索引紧凑。
- 使用业务唯一键：减少一次业务键到 `_id` 的索引，但要控制长度和热点。
- 使用 UUID：跨系统方便，但随机键会影响局部性和索引大小；需选择合适的 UUID 表示方式。

## 2.4 文档大小和嵌套限制

普通 BSON 文档存在大小上限，设计时不应让数组无界增长。即使尚未达到硬限制，超大文档也会导致：

- 每次读取传输大量无关字段。
- 更新时增加 CPU、内存和写放大。
- 副本复制和迁移耗时增加。
- 事务与 Change Streams 事件体积增大。
- 工作集更难留在缓存中。

因此，“把订单所有历史操作都放一个数组”通常不是好设计。可使用子集合、桶模式或最近 N 条内嵌 + 历史归档。

## 2.5 字段命名与兼容性

字段名应稳定、简洁且具有业务含义。生产中常见问题包括：

- 同一字段不同文档类型不同，例如 `age` 有时是字符串、有时是整数，导致比较和索引语义混乱。
- 时间有的存 Date，有的存字符串，范围查询难以统一。
- 枚举值大小写不一致。
- 频繁改字段名导致双写和迁移复杂。

可通过 JSON Schema Validation 限制必填字段、类型、枚举和范围，并采用向前/向后兼容的滚动升级策略。

## 2.6 Java 映射注意事项

- `ObjectId` 与字符串 ID 不要混用，否则查询条件类型不匹配会查不到数据。
- `Instant`、`LocalDateTime` 和时区必须统一约定。BSON Date 保存 UTC 毫秒时间点，不保存业务时区。
- `BigDecimal` 若映射到 Decimal128，要确认精度和范围；也可存字符串或 Long，但查询能力不同。
- `Map<String, Object>` 虽灵活，却会弱化类型安全和 Schema 管理。
- 序列化字段名变化时，应考虑旧文档读取兼容和批量迁移。

## 2.7 面试追问

**问：为什么 ObjectId 大致有序，是否适合作为分片键？**

ObjectId 的前部包含时间信息，因此新值通常递增。作为范围分片键会让新写入集中到最大值区间，可能形成热点。可使用 hashed `_id` 分散写入，或选择能同时满足路由和分布的复合分片键。

**问：字段不存在与字段为 null 一样吗？**

不完全一样。查询 `{field: null}` 可能同时匹配值为 null 和字段缺失的文档，若要区分应结合 `$exists`。索引和 Partial Index 设计也要明确缺失值语义。

# 3. MongoDB “无模式”是否意味着不需要数据建模？

## 3.1 结论先行

MongoDB 是灵活模式，不是没有模式。每个业务系统实际上都有 Schema，只是 Schema 可能由代码、校验规则、数据迁移和约定共同维护，而不是完全由固定表结构声明。

数据建模应从访问模式出发，确定聚合边界、文档生命周期、增长上限、索引、原子性和分片键。随意写入会把复杂度推迟到查询、迁移和线上故障阶段。

## 3.2 访问模式驱动建模

设计一个订单系统时，应先列出高频操作：

- 根据订单号查询订单详情。
- 查询用户最近 100 个订单。
- 根据状态和时间扫描待处理订单。
- 更新支付状态和支付流水号。
- 统计商户日订单量。

不同访问模式可能要求不同的数据组织：

```javascript
// 订单主文档
{
  _id: "order-20260720-001",
  userId: 1001,
  merchantId: 2001,
  status: "PAID",
  items: [
    { sku: "A1", nameSnapshot: "Keyboard", qty: 1, price: 29900 }
  ],
  totalAmount: 29900,
  payment: { channel: "CARD", transactionId: "T..." },
  createdAt: ISODate("...")
}
```

商品名称和成交价保存快照是合理冗余，因为订单需要保留成交时事实，而不是实时跟随商品主数据变化。

## 3.3 Schema Validation 的价值

可以为集合定义验证规则：

```javascript
db.createCollection("orders", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "userId", "status", "createdAt"],
      properties: {
        userId: { bsonType: "long" },
        status: { enum: ["CREATED", "PAID", "CANCELLED"] },
        createdAt: { bsonType: "date" }
      }
    }
  },
  validationLevel: "strict",
  validationAction: "error"
})
```

Validation 不是完整业务规则引擎，但能阻止明显坏数据。复杂状态机、跨文档约束仍需应用或事务保证。

## 3.4 Schema 演进

常见策略：

1. **读兼容**：新代码能读取旧字段和新字段。
2. **双写过渡**：一段时间同时写旧字段与新字段。
3. **后台迁移**：批量更新历史文档，限速并可重试。
4. **切换读取**：观测完整性后只读新字段。
5. **停止旧写并清理**：最后删除旧字段和旧索引。

不要在一次发布中直接重命名海量字段并假设所有文档已同步迁移。滚动发布期间不同版本应用可能并存。

## 3.5 常用文档建模模式

### Bucket Pattern

把大量细粒度事件按设备和时间窗口聚合到桶文档中，减少文档数和索引开销。适合时序数据，但桶大小必须受控。

### Outlier Pattern

大多数实体数据较小，少数异常实体非常大。主文档保留常见数据，异常部分拆到扩展集合，避免所有文档为极端情况付出成本。

### Extended Reference Pattern

引用另一个实体时，复制少量高频读取且变化不频繁的字段，减少 `$lookup`。例如订单中存用户昵称快照，但仍保存 `userId` 作为权威关联。

### Computed Pattern

把昂贵统计结果预计算并保存，例如商品评分、评论数。更新可同步或异步，需要明确一致性延迟和补偿机制。

## 3.6 常见误区

- 把关系型表逐表原样迁移成集合，只把外键换成 ObjectId，最终仍大量 `$lookup`。
- 把所有数据嵌入一个巨型文档，数组无限增长。
- 同一字段混合多种类型，依赖应用“自己知道”。
- 只根据写入结构建模，不考虑查询排序和索引。

# 4. 嵌入与引用如何选择，如何设计一对多和多对多？

## 4.1 结论先行

嵌入适合“共同读取、共同更新、生命周期一致、数量有界”的数据；引用适合“独立生命周期、多方共享、数量无界或独立查询”的数据。

不是所有一对多都嵌入，也不是所有多对多都引用。关键是访问模式、更新频率、数据规模和一致性边界。

## 4.2 嵌入的优势与代价

```javascript
{
  _id: 1001,
  name: "Alice",
  addresses: [
    { id: "home", city: "Hangzhou", detail: "..." },
    { id: "work", city: "Shanghai", detail: "..." }
  ]
}
```

优势：

- 一次查询得到完整聚合。
- 单文档更新具备原子性。
- 不需要 Join 或多次网络往返。
- 更容易与面向对象模型映射。

代价：

- 数据重复，主数据变化时需要同步。
- 文档可能持续膨胀。
- 更新大文档中的小字段仍会产生写成本。
- 同一子对象被多个父对象共享时难维护。

## 4.3 引用的优势与代价

```javascript
// users
{ _id: 1001, name: "Alice" }

// orders
{ _id: "O1", userId: 1001, total: 29900 }
```

优势：

- 避免大规模重复。
- 子实体可独立查询、更新和授权。
- 适合无界集合，例如用户的全部订单。

代价：

- 需要应用多次查询或 `$lookup`。
- 跨文档原子更新可能需要事务。
- 读取一致性和故障处理更复杂。

## 4.4 一对多设计示例

### 用户与最近登录设备

若只关心最近 5 个设备，可嵌入并通过 `$push + $slice` 控制大小：

```javascript
db.users.updateOne(
  { _id: 1001 },
  {
    $push: {
      recentDevices: {
        $each: [{ deviceId: "D9", loginAt: new Date() }],
        $position: 0,
        $slice: 5
      }
    }
  }
)
```

### 用户与全部订单

订单数量无界，应使用订单集合引用 `userId`，建立 `{userId: 1, createdAt: -1}` 索引，而不是把所有订单 ID 放入用户文档数组。

## 4.5 多对多设计示例

用户与角色、文章与标签等多对多关系有三种常见方式：

1. 在一侧嵌入 ID 数组，适合成员数量有界且查询方向明确。
2. 两侧都冗余 ID，读快但维护一致性成本高。
3. 单独关系集合，例如 `{userId, roleId}`，适合关系属性多、规模大或双向查询。

关系集合可以建立：

```javascript
db.userRoles.createIndex({ userId: 1, roleId: 1 }, { unique: true })
db.userRoles.createIndex({ roleId: 1, userId: 1 })
```

## 4.6 如何处理冗余一致性

冗余数据的维护策略：

- 强一致要求：使用事务同步更新，但要控制事务大小和冲突。
- 最终一致：主数据更新后写 Outbox/MQ，由消费者更新冗余副本。
- 快照语义：订单中的商品名和价格不需要跟随商品变化。
- 定期校验：离线任务扫描差异并修复。

高级回答应先定义冗余字段是“事实快照”还是“缓存副本”，两者的一致性要求完全不同。

# 5. 单文档原子性、更新操作符与幂等写如何理解？

## 5.1 结论先行

MongoDB 对单文档写操作提供原子性。只要相关业务状态能放在同一文档中，就可以通过条件更新和原子操作符避免读取后再写回的竞态。跨文档业务可以使用事务，但更优先考虑重新划分聚合边界。

## 5.2 避免 Read-Modify-Write 竞态

错误做法：

```text
1. find stock = 1
2. Java 中计算 stock - 1
3. update stock = 0
```

两个线程都可能读到 1，造成覆盖。正确做法是条件原子更新：

```javascript
const result = db.products.updateOne(
  { _id: "P1", stock: { $gte: 1 } },
  { $inc: { stock: -1 }, $set: { updatedAt: new Date() } }
)
```

通过 `matchedCount` 或 `modifiedCount` 判断是否扣减成功。

## 5.3 常见原子更新操作符

- `$set` / `$unset`：设置或删除字段。
- `$inc` / `$mul`：数值增减或乘法。
- `$min` / `$max`：仅在新值更小或更大时更新。
- `$push` / `$addToSet` / `$pull`：数组追加、去重追加和删除。
- `$currentDate`：服务端写入时间。
- `$setOnInsert`：仅 upsert 插入时设置。

数组更新可使用位置操作符、`arrayFilters` 精确修改匹配元素：

```javascript
db.orders.updateOne(
  { _id: "O1" },
  { $set: { "items.$[item].status": "SHIPPED" } },
  { arrayFilters: [{ "item.sku": "A1", "item.status": "PAID" }] }
)
```

## 5.4 乐观并发控制

可以增加 `version` 字段：

```javascript
const result = db.accounts.updateOne(
  { _id: 1001, version: 7 },
  {
    $set: { profile: newProfile },
    $inc: { version: 1 }
  }
)
```

若匹配数为 0，说明数据已被其他线程修改，应用重新读取并重试或提示冲突。这比无条件覆盖整文档更安全。

## 5.5 幂等写设计

网络超时不代表写入失败。客户端可能在服务端已提交后未收到响应，因此重试必须幂等。

常见方案：

- 使用业务唯一键和唯一索引，例如 `paymentRequestId`。
- 使用 `$setOnInsert` + upsert 实现“首次创建”。
- 状态机条件更新，例如只允许 `CREATED -> PAID`。
- 保存已处理事件 ID，或单独建幂等记录集合。
- 对计数型操作避免盲目重试 `$inc`，除非有幂等令牌。

```javascript
db.payments.updateOne(
  { requestId: "R10001" },
  {
    $setOnInsert: {
      orderId: "O1",
      amount: 29900,
      status: "SUCCESS",
      createdAt: new Date()
    }
  },
  { upsert: true }
)
```

唯一索引是最终防线：

```javascript
db.payments.createIndex({ requestId: 1 }, { unique: true })
```

## 5.6 面试追问

**问：`updateOne` 返回超时，能否认为没有更新？**

不能。超时可能发生在请求发送、服务端执行、复制等待或响应返回任一阶段。需要根据业务唯一键查询结果，或使用驱动的 Retryable Writes，并确保操作幂等。

**问：`modifiedCount = 0` 一定是失败吗？**

不一定。可能未匹配，也可能匹配但目标值已经等于新值。应结合 `matchedCount` 和业务状态判断。

# 第二部分：索引、查询规划与聚合

# 6. MongoDB 有哪些索引类型，分别适合什么场景？

## 6.1 结论先行

MongoDB 索引本质上是在额外空间和写入成本之间换取查询性能。索引设计必须由查询条件、排序、返回字段和数据分布共同决定，而不是看到字段就建索引。

常见索引包括单字段、联合、Multikey、唯一、稀疏、部分、TTL、文本、地理空间、Hashed、Wildcard 和 Clustered Index。不同索引有各自限制，尤其要关注数组、排序、唯一约束和分片兼容性。

## 6.2 单字段与联合索引

```javascript
db.orders.createIndex({ userId: 1 })
db.orders.createIndex({ userId: 1, status: 1, createdAt: -1 })
```

单字段索引适合条件简单的查询。联合索引适合同时过滤、排序或覆盖查询，但字段顺序非常关键。

## 6.3 唯一索引

```javascript
db.users.createIndex({ tenantId: 1, email: 1 }, { unique: true })
```

唯一索引可以实现业务约束，但要注意：

- 创建前必须清理重复数据。
- 在分片集合中，唯一约束与分片键存在限制，通常要求唯一索引的前缀包含分片键。
- 缺失字段和 null 的语义需要验证，必要时使用 Partial Index。

## 6.4 Partial Index 与 Sparse Index

Partial Index 只索引满足条件的文档：

```javascript
db.orders.createIndex(
  { merchantId: 1, createdAt: -1 },
  { partialFilterExpression: { status: "UNPAID" } }
)
```

它适合“活跃数据少、历史数据多”的场景，可显著降低索引大小和维护成本。查询只有在谓词能保证结果完整时才可能使用该索引。

Sparse Index 只索引存在该字段的文档，表达能力弱于 Partial Index。新设计通常优先考虑 Partial Index。

## 6.5 TTL Index

```javascript
db.sessions.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 })
```

TTL 索引适合会话、临时 Token 和日志保留。过期删除是后台异步执行，不保证到达时间点立即物理删除。因此不能把 TTL 当作精准定时任务，也不能依赖它在某毫秒删除敏感权限。

## 6.6 Text、Atlas Search 与 Vector Search

MongoDB Server 自带 `$text` 索引可做基础全文检索，但功能和排序能力有限。复杂中文分词、相关性调优、模糊查询、聚合和搜索分析通常使用 Atlas Search 或 Elasticsearch/OpenSearch。

Vector Search 属于向量近邻检索能力，不应与普通 B-Tree 索引混淆。选型时要比较数据同步、一致性、过滤能力、成本和搜索专业度。

## 6.7 Geospatial Index

`2dsphere` 索引支持 GeoJSON 和球面地理查询，例如附近门店、区域包含：

```javascript
db.stores.createIndex({ location: "2dsphere" })
```

```javascript
db.stores.find({
  location: {
    $near: {
      $geometry: { type: "Point", coordinates: [120.15, 30.28] },
      $maxDistance: 5000
    }
  }
})
```

坐标顺序是经度、纬度，错误顺序是常见故障。

## 6.8 Hashed Index

```javascript
db.events.createIndex({ userId: "hashed" })
```

Hashed Index 常用于分片，将连续或有偏值散列到更均匀的分布。它适合等值路由，不适合范围扫描；Hashed Index 也不能替代业务唯一索引。

## 6.9 Wildcard Index

对于字段路径高度动态的文档，可建立 Wildcard Index：

```javascript
db.products.createIndex({ "attributes.$**": 1 })
```

它能降低动态属性索引管理难度，但往往比精确索引更大，无法代替对核心查询的专门设计。应限制投影范围并监控实际使用率。

## 6.10 索引成本

每个索引都会带来：

- 插入、更新、删除时额外维护。
- 占用内存和磁盘。
- 备份、恢复、初始同步和迁移时间增长。
- Query Planner 候选计划增加。
- 某些字段更新时写放大。

可以通过 `$indexStats`、Profiler、Query Analyzer 或监控平台识别长期未使用索引，但删除前要考虑低频月报、故障脚本和季节性流量。

# 7. 联合索引、ESR 原则、排序和覆盖查询如何设计？

## 7.1 结论先行

联合索引字段顺序通常遵循 ESR：Equality、Sort、Range，即等值条件优先，其次排序字段，最后范围字段。但 ESR 是经验法则，不是不可变规则。数据选择性、查询比例、排序范围、分片键和覆盖需求都可能改变最佳顺序。

## 7.2 索引前缀

索引 `{tenantId: 1, status: 1, createdAt: -1}` 可支持：

- `{tenantId}`
- `{tenantId, status}`
- `{tenantId, status, createdAt}`

但通常不能高效支持只按 `status` 查询，因为缺失最左前缀。MongoDB 可能进行索引扫描，但扫描范围很大，不代表“用了 IXSCAN 就一定快”。

## 7.3 ESR 示例

查询：

```javascript
db.orders.find({
  tenantId: 10,
  status: "PAID",
  createdAt: { $gte: ISODate("2026-07-01") }
}).sort({ amount: -1 }).limit(50)
```

候选索引可能为：

```javascript
{ tenantId: 1, status: 1, amount: -1, createdAt: 1 }
```

等值条件先缩小范围，索引顺序再满足排序，范围字段放后。若时间范围极窄且排序结果很少，也可能把 `createdAt` 放在排序前获得更低扫描量，但此时排序可能需要内存阶段。应通过真实数据 `explain("executionStats")` 验证。

## 7.4 排序能否使用联合索引

索引 `{a: 1, b: -1}` 可按相同方向或整体反向遍历：

- `sort({a: 1, b: -1})`
- `sort({a: -1, b: 1})`

但 `sort({a: 1, b: 1})` 通常不能直接完全使用该顺序。

如果前缀字段是等值条件，可以跳过其排序影响。例如索引 `{tenantId: 1, createdAt: -1}` 能支持固定 tenant 下按时间倒序。

## 7.5 范围条件的影响

联合索引在遇到范围字段后，后续字段仍可能参与索引过滤，但通常无法继续形成同样紧凑的边界，也可能无法满足排序。面试中不要简单说“范围后字段完全失效”，更准确的表达是：

> 范围字段会切断后续字段用于构造连续索引边界或排序的能力，但后续字段仍可能在索引扫描阶段参与过滤，具体看执行计划和索引边界。

## 7.6 覆盖查询

若过滤和返回字段均可从索引获得，查询可以不读取完整文档：

```javascript
db.orders.createIndex({ userId: 1, createdAt: -1, status: 1 })

db.orders.find(
  { userId: 1001 },
  { _id: 0, createdAt: 1, status: 1 }
).sort({ createdAt: -1 }).limit(20)
```

判断是否覆盖要看 `explain`，通常表现为没有 FETCH 或 `totalDocsExamined` 为 0。注意 `_id` 默认返回，若索引不含 `_id`，需要显式排除。

## 7.7 选择性与基数

索引字段高选择性通常更能减少扫描，但联合索引设计不能只把“区分度最高”字段放前面。例如租户系统中的 `tenantId` 可能选择性一般，但它是权限边界、路由条件和绝大多数查询的固定前缀，放在首位更合理。

## 7.8 深分页

`skip(1000000).limit(20)` 需要扫描并丢弃大量结果。应使用基于稳定排序键的 Seek Pagination：

```javascript
db.orders.find({
  userId: 1001,
  $or: [
    { createdAt: { $lt: lastCreatedAt } },
    { createdAt: lastCreatedAt, _id: { $lt: lastId } }
  ]
}).sort({ createdAt: -1, _id: -1 }).limit(20)
```

对应索引：

```javascript
{ userId: 1, createdAt: -1, _id: -1 }
```

稳定的复合游标可以避免相同时间戳导致重复或遗漏。

# 8. 数组索引与 Multikey 有哪些限制和陷阱？

## 8.1 结论先行

当索引字段包含数组时，MongoDB 会为数组元素生成多个索引键，该索引成为 Multikey Index。它让数组元素查询很方便，但会增加索引条目、写放大和查询歧义，并对联合数组索引、覆盖查询、排序和分片键有约束。

## 8.2 数组索引示例

```javascript
{ _id: 1, tags: ["java", "mongodb", "backend"] }
```

```javascript
db.articles.createIndex({ tags: 1 })
db.articles.find({ tags: "mongodb" })
```

一个文档会产生多个索引键。数组越长，索引越大，更新数组的成本也越高。

## 8.3 `$elemMatch` 的必要性

文档：

```javascript
{
  scores: [
    { subject: "math", score: 95 },
    { subject: "english", score: 70 }
  ]
}
```

错误查询：

```javascript
{ "scores.subject": "math", "scores.score": { $gte: 90 } }
```

两个条件可能分别匹配不同数组元素。若要求同一元素同时满足，应使用：

```javascript
{
  scores: {
    $elemMatch: { subject: "math", score: { $gte: 90 } }
  }
}
```

## 8.4 联合 Multikey 限制

在同一联合索引中，通常不能让同一文档的多个索引字段都为数组，因为索引键笛卡尔积会爆炸。设计时应避免：

```javascript
{ tags: ["a", "b"], regions: ["cn", "us"] }
```

并尝试创建 `{tags: 1, regions: 1}`。

可重构为子文档数组、关系集合或只对一个数组建立联合索引。

## 8.5 数组整体匹配与元素匹配

```javascript
{ tags: ["a", "b"] }
```

- `{tags: "a"}` 匹配包含元素 a。
- `{tags: ["a", "b"]}` 匹配整个数组，顺序也有语义。
- `{tags: {$all: ["a", "b"]}}` 匹配同时包含两个元素，不要求相邻。
- `{tags: {$size: 2}}` 按数组长度匹配，但索引利用有限。

## 8.6 排序与覆盖查询

Multikey Index 可能支持排序，但当排序字段与数组路径边界冲突时，执行计划可能出现内存 SORT。覆盖查询也受限制，例如投影不能返回数组字段，且查询不能包含某些 `$elemMatch` 形式。

不要只看 `winningPlan` 中存在 IXSCAN，应同时检查是否有 FETCH、SORT 和扫描量。

## 8.7 无界数组治理

无界数组会导致：

- 文档持续变大。
- 索引键数量持续增加。
- 更新和复制成本上升。
- 触碰文档大小限制。

治理方式：

- 使用 `$slice` 保留最近 N 条。
- 历史数据拆分到子集合。
- 使用 Bucket Pattern。
- 为关系创建独立集合。
- 预计算计数，而不是每次读取整个数组长度。

# 9. 如何阅读 `explain` 并系统优化慢查询？

## 9.1 结论先行

慢查询优化不能停留在“有没有索引”。应通过 `explain("executionStats")` 比较返回行数、索引扫描数、文档扫描数、排序、执行时间和各阶段树，再结合数据分布、缓存命中、并发和网络判断瓶颈。

关键比率：

```text
totalKeysExamined / nReturned
totalDocsExamined / nReturned
```

比率越大，说明为了返回少量结果扫描了大量索引键或文档。

## 9.2 explain 的三种常用模式

```javascript
db.orders.explain("queryPlanner").find({...})
db.orders.explain("executionStats").find({...})
db.orders.explain("allPlansExecution").find({...})
```

- `queryPlanner`：查看候选和获胜计划，不完整执行查询。
- `executionStats`：执行获胜计划并返回统计，生产使用要控制影响。
- `allPlansExecution`：展示候选计划试跑信息，更重。

需要注意，`explain` 会绕开现有计划缓存来选择计划，因此它不完全等同于线上请求当时使用的缓存计划。

## 9.3 常见执行阶段

- `COLLSCAN`：集合扫描。
- `IXSCAN`：索引扫描。
- `FETCH`：根据 RecordId 读取文档并进一步过滤。
- `SORT`：内存或外部排序。
- `PROJECTION_COVERED`：覆盖投影。
- `OR`：多个索引分支。
- `SHARDING_FILTER`：分片环境中过滤不属于当前分片的数据。

不同 MongoDB 版本和查询引擎的输出结构可能变化，面试应解释语义，而不是死记 JSON 路径。

## 9.4 分析步骤

### 第一步：确认结果集和扫描量

```javascript
{
  nReturned: 20,
  totalKeysExamined: 150000,
  totalDocsExamined: 80000
}
```

返回 20 条却扫描 8 万文档，索引通常不匹配过滤或排序。

### 第二步：查看索引边界

检查 `indexBounds` 是否足够收敛。若某字段边界为 `[MinKey, MaxKey]`，说明该字段没有有效缩小扫描。

### 第三步：查看 FETCH 过滤

大量文档在 FETCH 后被丢弃，说明条件未进入索引边界，或字段顺序不合理。

### 第四步：查看排序

若存在阻塞 SORT，考虑把排序字段纳入联合索引，或先用高选择性条件缩小结果后再排序。

### 第五步：检查返回文档大小

查询本身很快但返回几十 MB，瓶颈可能是网络和反序列化。只投影需要字段，避免返回大数组。

## 9.5 示例

查询：

```javascript
db.orders.find({
  tenantId: 10,
  status: "PAID",
  createdAt: { $gte: ISODate("2026-07-01") }
}).sort({ createdAt: -1 }).limit(50)
```

只有 `{status: 1}` 索引时可能扫描全局所有已支付订单。更合理的索引：

```javascript
{ tenantId: 1, status: 1, createdAt: -1 }
```

优化后应观察：

- `nReturned` 接近 `totalKeysExamined`。
- `totalDocsExamined` 不显著高于返回数。
- 无阻塞 SORT。
- 执行时间在冷缓存和热缓存下都可接受。

## 9.6 线上慢查询来源

- 没有索引或索引顺序错误。
- 低选择性条件扫描大量索引。
- 正则表达式无法使用有效前缀。
- `$nin`、`$ne` 等否定条件选择性差。
- 大范围 `$in`。
- 深分页。
- 大文档或大数组投影。
- 工作集超出内存导致磁盘读取。
- 分片 Scatter-Gather。
- 计划缓存选中不适合当前参数分布的计划。

## 9.7 Profiler 与慢日志

可以通过数据库 Profiler 或慢查询日志定位问题，但生产开启全量 Profiler 会增加开销。常见方式是设置合理慢阈值、采样和过滤，并结合 Atlas Performance Advisor 或监控平台。

# 10. 查询计划缓存为什么会导致“同一查询”忽快忽慢？

## 10.1 结论先行

MongoDB 会为查询形状选择并缓存执行计划，以避免每次都重新竞争候选索引。若同一查询形状的参数分布差异很大，某次选出的计划可能适合小结果集，却不适合大结果集，导致延迟抖动。

此外，索引变化、数据分布变化、重启、内存压力和版本差异都可能改变计划选择。

## 10.2 查询形状

查询形状通常由过滤字段、排序、投影和选项决定，而不是具体参数值。例如：

```javascript
{ status: ?, createdAt: { $gte: ? } }
```

`status = "PAID"` 可能占 80%，`status = "FAILED"` 只占 0.1%。两者形状相同，但最佳索引或访问方式可能不同。

## 10.3 候选计划竞争

当多个索引可用时，Query Planner 会评估候选方案并选择获胜者。计划缓存可以提升稳定性，但也可能缓存次优计划。

例：

```javascript
{ tenantId: 1 }
{ status: 1, createdAt: -1 }
{ tenantId: 1, status: 1, createdAt: -1 }
```

索引过多不仅增加写成本，也增加候选计划复杂度。

## 10.4 如何判断计划缓存问题

- 同一查询形状延迟呈明显双峰。
- `explain` 很快，但真实请求仍慢，因为 `explain` 不使用线上缓存计划。
- 重启或清理计划缓存后短期恢复。
- 参数切换后，`totalKeysExamined` 差异巨大。
- 监控显示某查询形状计划发生变化。

可以使用计划缓存相关命令、Query Stats 或 Atlas 工具观察。但不要把“清理缓存”当长期优化，根因通常是索引和查询形状设计不稳定。

## 10.5 治理策略

- 建立与核心查询完全匹配的联合索引，减少歧义。
- 拆分差异极大的查询形状，例如不同状态使用不同 API 或增加固定谓词。
- 使用 Partial Index 对稀有状态建立专用索引。
- 定期更新统计和评估版本特性。
- 在支持的版本中使用 Query Settings 管理查询行为，但应谨慎测试。
- 删除冗余和重复索引。

## 10.6 Hint 的使用边界

`hint()` 可强制索引：

```javascript
db.orders.find({...}).hint({ tenantId: 1, status: 1, createdAt: -1 })
```

它适合诊断、临时止血或已充分验证的固定查询，但存在风险：

- 数据分布变化后仍锁死旧计划。
- 索引被删除或重建时查询失败。
- 掩盖真正的 Schema 和索引问题。

生产使用应有回滚方案、监控和版本兼容测试。

# 11. Aggregation Pipeline 如何执行，怎样优化内存与性能？

## 11.1 结论先行

Aggregation Pipeline 把文档依次传入多个 Stage 处理，适合过滤、变换、分组、排序、关联和统计。优化原则是：尽早减少文档数量和字段体积，利用索引完成首段过滤与排序，警惕 `$group`、`$sort`、`$lookup`、`$setWindowFields` 等阻塞或高内存 Stage，并避免把 OLAP 负载无边界地压在主业务集群上。

## 11.2 基本执行模型

```javascript
db.orders.aggregate([
  { $match: { status: "PAID", createdAt: { $gte: start } } },
  { $project: { merchantId: 1, amount: 1, day: { $dateTrunc: { date: "$createdAt", unit: "day" } } } },
  { $group: { _id: { merchantId: "$merchantId", day: "$day" }, total: { $sum: "$amount" }, count: { $sum: 1 } } },
  { $sort: { total: -1 } },
  { $limit: 100 }
])
```

逻辑上文档逐阶段流转，但某些 Stage 必须先收集大量输入才能输出，例如全局排序和分组，因此会成为阻塞点。

## 11.3 尽早 `$match`

若 `$match` 位于流水线开头，并且条件可索引，MongoDB 可以使用索引减少输入：

```javascript
{ status: 1, createdAt: 1 }
```

不要先 `$project` 计算复杂字段再过滤原始条件。优化器会自动重排部分 Stage，但不应依赖它修复所有复杂表达式。

## 11.4 `$project` 是否越早越好

只保留必要字段可减少后续内存和网络，但手工在最开头放简单字段投影不一定总能提升，因为查询执行器可能已做字段裁剪。真正有价值的是：

- 去掉大数组、大字符串和二进制字段。
- 避免后续 `$group` 携带整文档。
- 只把分组、排序和输出需要的字段传下去。

## 11.5 `$sort` 与索引

如果 `$sort` 前没有破坏索引顺序的 Stage，并且索引顺序匹配，排序可以由索引完成。若先 `$group` 或计算新字段，通常需要内存排序。

```javascript
[
  { $match: { userId: 1001 } },
  { $sort: { createdAt: -1 } },
  { $limit: 100 }
]
```

对应索引 `{userId: 1, createdAt: -1}`。

把 `$limit` 紧跟 `$sort`，执行器可能使用 Top-K 优化，避免保存全部结果。

## 11.6 `$group` 的风险

`$group` 的内存取决于分组基数和累加器状态。按用户、设备、订单号等高基数字段对海量数据分组可能占用大量内存并落盘。

治理方式：

- 先按时间和租户过滤。
- 预聚合日表或小时表。
- 使用 Change Streams 或流式任务增量维护统计。
- 把离线报表放到分析副本、数据仓库或专门平台。
- 设置 `maxTimeMS` 和资源隔离。

## 11.7 `allowDiskUse` 不是性能开关

允许落盘可以避免某些内存限制错误，但磁盘临时文件可能导致延迟、I/O 抖动和磁盘空间风险。开启后仍要优化输入规模，并监控临时文件和磁盘使用。

## 11.8 `$facet` 的使用边界

`$facet` 可以在一次输入上运行多个子流水线，例如同时获取列表和统计：

```javascript
{
  $facet: {
    data: [{ $sort: { createdAt: -1 } }, { $skip: 0 }, { $limit: 20 }],
    total: [{ $count: "value" }]
  }
}
```

它看似减少一次请求，但可能把大结果集同时送入多个分支。深分页加全量 count 会很重。对于高并发 API，列表和精确总数未必应绑定在一次请求中。

## 11.9 如何分析聚合性能

```javascript
db.orders.explain("executionStats").aggregate([...])
```

重点观察：

- 开头是否使用 IXSCAN。
- 每个 Stage 输入输出数量。
- 是否有大规模 SORT、GROUP、LOOKUP。
- 是否落盘。
- 分片环境中是每个 Shard 先局部执行，还是在 Merge 节点集中处理。
- 返回文档大小和网络时间。

# 12. `$lookup` 能否替代关系型 Join，什么时候会很慢？

## 12.1 结论先行

`$lookup` 能实现集合间关联，但它不意味着 MongoDB 适合复制关系型数据库的任意 Join 模型。对小结果集、等值关联且外表有正确索引时，`$lookup` 可以高效；对大规模多对多、无索引关联、复杂子流水线或分片 Scatter-Gather，性能和资源成本会迅速上升。

MongoDB 建模应优先通过嵌入或有限冗余满足核心读路径，把 `$lookup` 用于确有独立生命周期的关系，而不是默认设计。

## 12.2 基本关联

```javascript
db.orders.aggregate([
  { $match: { userId: 1001 } },
  { $sort: { createdAt: -1 } },
  { $limit: 20 },
  {
    $lookup: {
      from: "users",
      localField: "userId",
      foreignField: "_id",
      as: "user"
    }
  },
  { $unwind: { path: "$user", preserveNullAndEmptyArrays: true } }
])
```

先把订单缩小到 20 条再关联，远好于先关联用户后分页。

## 12.3 外集合索引

`foreignField` 应有索引。若没有，外集合可能被反复扫描。对于 Pipeline 形式 `$lookup`，要确认 `$expr` 条件能否有效使用索引：

```javascript
{
  $lookup: {
    from: "inventory",
    let: { sku: "$sku", qty: "$qty" },
    pipeline: [
      { $match: { $expr: { $and: [
        { $eq: ["$sku", "$$sku"] },
        { $gte: ["$available", "$$qty"] }
      ] } } },
      { $project: { warehouse: 1, available: 1 } }
    ],
    as: "candidates"
  }
}
```

需根据版本和表达式形式用 `explain` 验证索引使用，不能仅凭存在索引推断。

## 12.4 大数组与 `$unwind`

`$lookup` 返回数组，随后 `$unwind` 会将一条主文档展开为多条。如果主表 10 万条，每条匹配 100 条，结果可能膨胀到 1000 万条，后续排序和分组成本巨大。

治理：

- 先过滤主集合。
- 在 `$lookup.pipeline` 中过滤和投影外集合。
- 控制一对多返回数量。
- 对需要的统计在外集合预计算。
- 避免无界多对多在线关联。

## 12.5 何时使用嵌入或冗余

适合冗余：

- 商品列表需要展示店铺名、头像，字段少且变化不频繁。
- 订单需要保存成交时商品快照。
- 文章列表需要作者昵称，但可接受秒级最终一致。

不适合复制：

- 强一致余额。
- 高频变化且被百万文档引用的字段。
- 受权限严格控制、不能在多份副本泄漏的数据。

## 12.6 应用层两次查询是否更好

有时先查主集合，收集外键后用 `$in` 批量查外集合，在 Java 中组装更易控制缓存和超时。但这不是绝对更快：

- 增加一次网络往返。
- 需要处理结果顺序和缺失数据。
- `$in` 列表过大同样昂贵。
- 两次查询不是同一快照。

应基于结果规模、缓存策略和一致性要求选择。

## 12.7 面试表达

> `$lookup` 是工具，不是把文档数据库重新关系化的理由。我会先通过访问模式决定是否嵌入或冗余；确实需要独立生命周期时再引用。使用 `$lookup` 时先缩小主集合，确保外键索引，在子流水线中投影和限流，并用 explain 验证是否出现输入放大与分片广播。

# 第三部分：一致性、复制集与事务

# 13. Read Concern、Write Concern、Read Preference 如何组合？

## 13.1 结论先行

这三个配置分别回答不同问题：

- **Write Concern**：写入需要多少节点确认、是否等待 Journal。
- **Read Concern**：读取的数据具有什么提交与快照保证。
- **Read Preference**：从 Primary 还是 Secondary 读取。

它们必须按业务一致性、延迟和可用性一起设计。只设置 `secondaryPreferred` 并不能保证读到最新数据；只设置 `w: majority` 也不能自动让所有读取线性一致。

## 13.2 Write Concern

常见设置：

```javascript
{ w: 1 }
{ w: "majority" }
{ w: "majority", j: true, wtimeout: 3000 }
```

- `w: 1`：Primary 接受写入后确认，尚未复制到多数节点时 Primary 故障可能回滚。
- `w: "majority"`：等待写入达到多数提交语义，显著降低故障切换后的回滚风险。
- `j: true`：要求等待 Journal 持久化语义，具体与部署配置相关。
- `wtimeout`：等待超时返回错误，但写入可能稍后完成，不能简单认为未写入。

现代版本默认写关注通常比早期版本更安全，但生产仍应显式确认全局默认和驱动覆盖。

## 13.3 Read Concern

常见级别：

- `local`：读取当前节点数据，不保证已被多数节点提交，可能在故障后回滚。
- `majority`：只返回多数提交的数据，适合避免读取未来会被回滚的内容。
- `snapshot`：在事务或支持的读取中提供一致快照。
- `linearizable`：只在 Primary 上用于更强实时顺序语义，延迟和可用性成本更高，通常需配合 `maxTimeMS`。
- `available`：偏向可用性，常见于特定分片读取场景。

## 13.4 Read Preference

模式包括：

- `primary`
- `primaryPreferred`
- `secondary`
- `secondaryPreferred`
- `nearest`

Secondary 读取的典型用途：报表、历史查询、允许延迟的数据。风险：

- 复制延迟导致旧读。
- 读后写或写后读不一致。
- 把重查询导向 Secondary 可能拖慢复制，进一步扩大延迟。
- Primary 故障时拓扑变化会带来重试和延迟抖动。

## 13.5 常见组合

### 核心支付状态

- 写：`w: majority`，合理 Journal 与超时。
- 读：Primary + `majority`；需要严格实时单文档语义时评估 linearizable。
- 应用：幂等键、状态机、对账补偿。

### 商品详情

- 写：`majority` 或由业务决定。
- 读：Primary 或 Secondary Preferred。
- 可接受秒级旧值，用缓存和版本号降低影响。

### 离线报表

- 读 Secondary，设置最大可接受延迟、标签和限流。
- 避免与复制线程争夺资源。

## 13.6 因果一致性会话

Causally Consistent Session 可以帮助实现“读到自己刚写入的数据”以及操作因果顺序，但需要正确的读写关注和同一 Session。它不是全局线性一致，也不能替代业务幂等。

## 13.7 一致性误区

- **误区：读 Secondary 就能扩展所有读。** 热数据仍需索引和缓存；Secondary 负载过高会造成复制延迟。
- **误区：`majority` 等于零丢失。** 硬件、配置、跨地域灾难和人为操作仍需备份与恢复。
- **误区：超时等于失败。** 客户端不知道最终结果，需要幂等和查询确认。

# 14. 复制集、oplog、选举与回滚是如何工作的？

## 14.1 结论先行

复制集由一个 Primary 和多个 Secondary 组成。客户端通常把写入发送到 Primary，Primary 将操作记录到 oplog，Secondary 持续拉取并重放。Primary 不可用时，具有多数投票能力的成员通过选举产生新 Primary。

复制集提供高可用，但不是同步共识数据库的简单同义词。写关注、复制延迟、选举多数、oplog 窗口和网络拓扑决定故障语义。

## 14.2 oplog

oplog 是本地数据库中的特殊固定大小集合，按顺序记录可复制操作。Secondary 根据 oplog 时间戳持续应用变更。

关键指标：

- oplog 总大小。
- 当前时间覆盖窗口，例如可容纳多少小时的写入历史。
- Secondary 落后时间。
- apply 吞吐是否跟得上 Primary 写入。

如果 Secondary 离线时间超过 oplog 覆盖窗口，无法继续增量追赶，可能需要 Initial Sync。

## 14.3 三节点部署

推荐生产最少三个数据承载成员：Primary + Secondary + Secondary。相比 Primary + Secondary + Arbiter，三数据节点拥有更多数据副本和更好的容灾能力。

Arbiter 只投票不保存数据。它能帮助形成奇数票，但不能提升数据冗余，且会影响某些 majority 语义和事务部署考虑，因此不应仅为节省成本随意使用。

## 14.4 心跳与选举

成员通过心跳感知状态。Primary 失联、优先级变化、维护命令等可能触发选举。候选 Secondary 需要：

- 处于可选状态。
- 能与多数投票成员通信。
- 日志足够新。
- 满足优先级、投票等配置。

选举期间短时间不能写入，驱动会重新发现拓扑并选择新 Primary。应用必须设置合理 Server Selection Timeout 和重试策略。

## 14.5 Rollback

旧 Primary 在网络分区中接受了未被多数提交的写入，之后它重新加入并发现新 Primary 日志已前进，冲突操作可能被回滚。

例如：

```text
T1: P1 接受 w:1 写入 A
T2: P1 与多数节点失联
T3: S1 被选为新 Primary
T4: 客户端在 S1 写入 B
T5: P1 恢复，A 未在多数提交，进入 rollback
```

使用 `w: majority` 可以显著避免已确认写入在正常选举中被回滚，但仍要正确处理超时和灾难恢复。

## 14.6 跨机房部署

三节点分布在两个机房时，多数票通常集中在一个机房。若拥有两票的机房整体故障，剩余单节点无法选主，系统只读或不可写。生产高可用通常使用三个故障域，或明确接受某机房故障后的可用性边界。

不要为了“就近写入”在多个机房各放一个 Primary；一个复制集任一时刻只有一个 Primary。

## 14.7 Hidden、Delayed 与 Priority 0 节点

- Hidden Secondary：不被普通客户端读取，可用于备份、报表。
- Delayed Secondary：延迟应用 oplog，为误删除提供时间窗口，但不是备份替代品。
- Priority 0：不能成为 Primary，适合特定地域或资源较弱节点。

它们会影响投票、多数和容量，应整体设计。

# 15. 主节点故障后是否可能丢数据，如何缩小风险窗口？

## 15.1 结论先行

可能。是否丢失取决于写入是否已达到多数提交、Journal、复制拓扑和故障类型。`w:1` 已返回成功的写入，若只存在旧 Primary 内存或本地日志中而未复制到多数，在故障切换后可能回滚。

`w: majority`、合理的 Journal、三个数据节点、跨故障域部署和幂等重试可以大幅缩小风险，但备份和恢复演练仍不可替代。

## 15.2 三个不同的“成功”

写入成功可能指：

1. Primary 内存中已修改。
2. Primary Journal 已持久化。
3. 多数成员已确认，达到 majority commit point。

这些保证强度不同。应用应根据业务事实重要性选择，而不是所有数据一律最低延迟或最高强度。

## 15.3 故障窗口示例

```text
Client -> Primary: 写入订单支付成功
Primary -> Client: w:1 返回成功
Primary 尚未把 oplog 复制给 Secondary
Primary 服务器永久损坏
Secondary 当选新 Primary
该支付状态不存在
```

若外部支付渠道已扣款，数据库状态丢失会造成严重不一致。因此还需要：

- 支付请求唯一键。
- 外部回调幂等。
- 对账任务。
- 业务状态机。
- Outbox 或可靠事件补偿。

数据库一致性配置不能取代业务闭环。

## 15.4 Retryable Writes

驱动可对符合条件的单文档写进行 Retryable Writes。它能处理选主和瞬时网络错误，但有两个前提：

- 操作和服务端支持重试语义。
- 业务仍应使用唯一键和幂等设计，因为应用层可能进行额外重试，复杂操作也未必自动重试。

不要在未知结果后盲目重试非幂等 `$inc` 或发放权益。

## 15.5 `wtimeout` 的语义

`wtimeout` 表示在规定时间内未满足写关注，客户端收到错误。但写操作可能已经在 Primary 完成，甚至稍后达到多数。正确处理：

1. 记录业务请求 ID。
2. 查询最终状态。
3. 若未完成再安全重试。
4. 对外返回“处理中”而不是直接重复扣款。

## 15.6 配置建议

核心数据：

- 三个数据承载节点，跨至少三个故障域。
- 显式 `w: majority`，确认默认 Journal 行为。
- 监控复制延迟和 majority commit lag。
- 合理 oplog 窗口。
- 驱动启用可控 Retryable Writes。
- 恢复测试和对账机制。

非核心缓存数据可接受更弱写关注以换取性能，但必须明确可重建来源。

# 16. MongoDB 事务如何实现，为什么不应滥用多文档事务？

## 16.1 结论先行

MongoDB 支持跨文档、跨集合、跨数据库以及分片集群中的 ACID 事务。事务在逻辑 Session 中运行，读取可使用一致快照，提交通过复制与协调保证原子性。

但事务不是免费的。长事务会占用缓存、延迟历史版本回收、增加冲突和复制压力；跨分片事务还需要分布式协调。数据模型应优先利用单文档原子性，只有真实跨聚合不变量才使用事务。

## 16.2 事务示例

Java 驱动伪代码：

```java
try (ClientSession session = mongoClient.startSession()) {
    TransactionOptions options = TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT)
        .writeConcern(WriteConcern.MAJORITY)
        .readPreference(ReadPreference.primary())
        .build();

    session.withTransaction(() -> {
        accountCollection.updateOne(
            session,
            and(eq("_id", fromId), gte("balance", amount)),
            inc("balance", -amount)
        );
        accountCollection.updateOne(
            session,
            eq("_id", toId),
            inc("balance", amount)
        );
        transferCollection.insertOne(session, transferDocument);
        return null;
    }, options);
}
```

实际代码必须检查第一笔扣款是否匹配成功，并把业务唯一键作为幂等约束。

## 16.3 Snapshot 与冲突

事务读取的是一致视图。事务期间其他写入可能修改相同文档，提交时产生 Write Conflict 或 TransientTransactionError。驱动的事务回调 API 会对部分瞬时错误进行重试，但业务代码必须满足：

- 整个事务回调可安全重复执行。
- 不在回调中发送不可回滚的 HTTP 请求、短信或 MQ 消息。
- 外部副作用通过 Outbox 或提交后动作处理。

## 16.4 事务的性能成本

- 需要维护事务内版本和锁状态。
- 事务越长，其他操作冲突概率越高。
- 大量更新占用 WiredTiger Cache。
- 提交需要满足写关注和复制。
- 跨分片事务有协调和网络成本。
- 事务中一次性修改大量文档会造成日志、缓存和恢复压力。

生产建议：事务短、小、确定，避免用户交互和远程调用。

## 16.5 事务大小与时间限制

具体限制和行为会随版本变化，例如事务生命周期、缓存压力和大事务错误。面试中更重要的是原则：

- 不把百万文档批处理放进单事务。
- 批量迁移按小批次幂等执行。
- 监控 `currentOp`、事务指标、Write Conflict 和缓存压力。
- 设置合理超时并处理 `UnknownTransactionCommitResult`。

MongoDB 6.2+ 对某些 `TransactionTooLargeForCache` 错误不会自动重试；MongoDB 8.1+ 对事务内 upsert 遇到重复键的重试行为也有变化。版本升级应做故障语义回归测试。

## 16.6 Unknown Commit Result

客户端提交时网络中断，无法知道事务是否已经提交。正确做法是按驱动规范重试 `commitTransaction`，而不是重新执行整个业务事务。业务侧仍应有唯一请求 ID，便于最终确认。

## 16.7 如何减少事务

### 聚合建模

订单状态、支付信息和最近操作可放同一订单文档，通过条件更新保证状态机。

### 补偿事务

对可最终一致的流程，使用 Saga：每一步独立提交，失败后执行补偿。适合跨服务场景。

### Outbox Pattern

在同一数据库事务中写业务数据和 Outbox 文档，提交后由 Change Streams 或轮询发布事件。消费者幂等处理。

## 16.8 面试表达

> MongoDB 事务解决的是确实跨文档的不变量，但首选仍是把一致性边界建模到单文档。事务代码必须短、可重试，不能包含外部不可逆副作用；提交结果未知时重试 commit，而不是盲目重跑业务。跨分片事务只在必要时使用，并通过指标评估协调成本。

# 17. WiredTiger 缓存、Journal、Checkpoint 和压缩如何工作？

## 17.1 结论先行

WiredTiger 是 MongoDB 默认存储引擎，使用文档级并发控制、内部 Cache、Journal、Checkpoint 和压缩来平衡吞吐、恢复和存储效率。MongoDB 的内存使用不仅包括 WiredTiger Cache，还包括操作系统文件缓存、连接、排序、聚合和进程元数据。

理解 Journal 与 Checkpoint 的区别，是回答崩溃恢复和性能抖动的关键。

## 17.2 WiredTiger Cache

官方默认缓存大小通常按可用内存公式计算，并为操作系统文件缓存和其他进程留出空间。不要看到空闲内存少就直接判定泄漏，也不要盲目把 WiredTiger Cache 调到 80%-90%。

缓存中主要包含：

- 热数据页和索引页。
- 更新链和未清理历史版本。
- 脏页。
- 事务相关状态。

重要指标：

- `bytes currently in the cache`
- `maximum bytes configured`
- `tracked dirty bytes`
- `pages read into cache`
- `pages written from cache`
- `application threads page read from disk to cache`
- eviction 是否由应用线程参与

如果应用线程频繁帮助 Eviction，通常说明缓存压力已影响请求延迟。

## 17.3 操作系统文件缓存

WiredTiger 磁盘文件通常是压缩格式，OS 文件缓存保存文件块。数据进入 WiredTiger Cache 后会以适合操作的内存形式存在，可能不再压缩。

因此同一份热数据可能在不同缓存层以不同形式存在。容器部署必须确认 MongoDB 能正确识别 cgroup 内存限制，避免默认缓存按宿主机内存计算。

## 17.4 Journal

Journal 是预写式恢复日志，用于进程或机器异常后的崩溃恢复。写入先产生日志记录，随后数据页在 Checkpoint 中持久化。

`j: true` 控制写关注是否等待日志持久化语义，但实际耐久性还与复制、磁盘缓存和部署配置相关。

Journal 不等于 oplog：

- Journal 服务于本节点崩溃恢复。
- oplog 服务于复制集成员之间复制操作。

## 17.5 Checkpoint

Checkpoint 周期性创建数据文件的一致持久化视图。崩溃后，MongoDB 从最近 Checkpoint 加上 Journal 重放恢复。

Checkpoint 会带来磁盘写入和 I/O 波动。若存储延迟高、脏页很多或磁盘吞吐不足，可能出现周期性延迟尖峰。

## 17.6 压缩

WiredTiger 默认对集合和索引使用压缩策略。压缩能节约磁盘和 I/O，但消耗 CPU。选择 Snappy、Zstd 等需要基于：

- CPU 余量。
- 磁盘成本和吞吐。
- 文档重复度。
- 热数据是否能驻留缓存。
- 备份和恢复时间。

不要只追求最高压缩比。对于 CPU 紧张、高写入负载，压缩算法可能成为瓶颈。

## 17.7 并发控制

WiredTiger 使用文档级并发控制和乐观冲突机制。不同文档更新可以并发；同一文档热点会产生 Write Conflict 和重试。

因此把全局计数器、单一库存或超级租户状态放在一个文档中，虽然原子性简单，却可能成为单文档写热点。

## 17.8 面试追问

**问：MongoDB 是否使用操作系统 Page Cache？**

是。WiredTiger 内部 Cache 与 OS 文件缓存共同作用。观察内存时要区分进程 RSS、WiredTiger Cache 和系统可回收文件缓存。

**问：Journal 已 fsync 是否意味着跨机房绝不丢数据？**

不意味着。它只增强本节点耐久性。机房级故障需要复制到其他故障域、合适写关注和独立备份。

# 18. 为什么 MongoDB 内存很高，Working Set 与磁盘 I/O 如何判断？

## 18.1 结论先行

MongoDB 高内存通常是正常的缓存利用，而不是立即等于内存泄漏。关键要判断：工作集是否能留在 WiredTiger/OS 缓存中、是否发生持续 Eviction、磁盘读取是否增加、请求延迟是否与 Page Fault 或 I/O 同步上升。

## 18.2 Working Set

Working Set 是当前活跃查询频繁访问的数据页和索引页集合。若 Working Set 小于有效缓存，热请求大多命中内存；若明显大于内存，会反复把页换入换出，产生 Cache Thrashing。

工作集不等于数据库总大小。一个 5TB 数据库若只访问最近 20GB，可以运行良好；一个 200GB 数据库若随机扫描全部数据，在 64GB 内存上可能持续抖动。

## 18.3 判断 Cache Thrashing

典型现象：

- WiredTiger Cache 长期接近上限。
- Pages Read Into Cache 快速增长。
- Eviction 活跃，应用线程参与 Eviction。
- 磁盘读取 IOPS 和延迟升高。
- 查询 P95/P99 上升，但 CPU 未必满。
- 同一查询热缓存快、冷缓存慢。

处理方式：

- 优化索引和扫描量。
- 减少返回大文档。
- 将历史冷数据归档或分层。
- 增加内存或更快存储。
- 对工作负载分片或隔离。
- 避免后台全表扫描冲掉业务热页。

## 18.4 索引是否必须全部放内存

不需要所有索引完全驻留内存，但核心查询使用的索引路径和高频数据页应尽量在缓存中。过多索引会扩大工作集，导致真正重要的页被驱逐。

## 18.5 RSS、Virtual Memory 与 Cache

- Virtual Memory 很大不等于真实占用。
- RSS 包含进程当前驻留物理内存。
- OS 文件缓存通常可回收，不应简单视为“被 MongoDB 吃掉”。
- 容器 OOM 看的是 cgroup 限制，不是宿主机还有多少空闲。

需要同时看 `free`、`vmstat`、`iostat`、cgroup、`serverStatus.wiredTiger.cache` 和进程指标。

## 18.6 大文档的影响

即使查询只需要一个字段，如果没有覆盖索引，FETCH 可能加载完整文档页。大文档会：

- 降低每页可容纳记录数。
- 增加缓存压力。
- 增加网络和反序列化。
- 更新时产生更大写入。

使用投影、覆盖查询、拆分冷热字段和 GridFS/对象存储可以改善。

# 第四部分：分片与水平扩展

# 19. 分片集群由哪些组件组成，请求是如何路由的？

## 19.1 结论先行

MongoDB 分片集群由 Shard、Config Server Replica Set 和 `mongos` 组成。Shard 保存数据子集，Config Server 保存集群元数据，`mongos` 根据分片键和路由表把请求发送到目标 Shard，并合并结果。

分片解决的是单复制集容量或吞吐上限，但会引入路由、迁移、分布式事务、备份和故障排查复杂度。应先通过索引、建模和垂直扩展解决问题，再基于容量预测决定分片。

## 19.2 核心组件

### Shard

每个 Shard 通常是一个复制集，保存集合的一部分数据。分片提高容量，复制集提高每个 Shard 的可用性。

### Config Server

Config Server Replica Set 保存数据库、集合、分片键、Chunk/Range 和 Zone 等元数据。它不是业务数据的缓存节点，必须按官方要求部署和备份。

### mongos

`mongos` 是无状态路由进程，应用应连接到多个 `mongos` 或通过驱动 Seed List 发现。`mongos` 根据查询是否包含分片键决定定向路由还是广播。

## 19.3 写请求路由

对分片集合插入文档时，必须能从文档中得到分片键。`mongos` 查询元数据，将请求发到拥有对应 Key Range 的 Shard。

如果应用绕过 `mongos` 直接连接某个 Shard，会破坏全局路由语义，不是正常业务访问方式。

## 19.4 读请求路由

### Targeted Query

查询包含完整分片键等值或可定位范围，`mongos` 只请求一个或少量 Shard。

```javascript
{ tenantId: 1001, orderId: "O9" }
```

### Scatter-Gather

查询缺少可定位分片键，`mongos` 向所有 Shard 广播，再合并结果。

```javascript
{ status: "PAID" }
```

Scatter-Gather 不一定永远不可用，但 Shard 数越多，尾延迟、CPU 和网络成本越高。

## 19.5 Chunk/Range 与 Balancer

MongoDB 按分片键范围管理数据块。Balancer 根据数据分布和 Zone 规则在 Shard 间迁移范围。现代版本的内部术语和自动拆分实现有所演进，面试重点应放在：

- 路由元数据如何定位范围。
- 数据分布失衡时如何迁移。
- 迁移期间仍需服务读写。
- 分片键不可分或热点会限制平衡效果。

## 19.6 Merge 阶段

排序、聚合和分页可能先在各 Shard 局部执行，再由 `mongos` 或某个 Shard 合并。全局排序和高基数分组会产生网络与内存成本。

`explain` 应查看每个 Shard 的扫描量和 Merge Stage，不能只看总执行时间。

## 19.7 何时需要分片

- 数据量即将超过单复制集可管理容量。
- Working Set 或写入吞吐无法通过单机和副本隔离满足。
- 需要按租户或地域隔离数据。
- 单节点备份、恢复和维护窗口不可接受。

分片前应有：稳定 Schema、核心索引、可预测分片键、自动化部署、监控、备份和压测。

# 20. 如何选择分片键，Range 与 Hashed Sharding 如何取舍？

## 20.1 结论先行

理想分片键要同时满足：高基数、分布均匀、写入不单调集中、常见查询可定向路由、字段稳定且文档中始终可用。不存在只看“区分度”就能选出的万能分片键。

Range Sharding 保留范围局部性，适合范围查询和 Zone；Hashed Sharding 分布更均匀，适合等值访问和分散单调写入，但牺牲范围路由能力。

## 20.2 四个评价维度

### Cardinality

取值数量要足够多。`status` 只有几个值，不适合单独作为分片键。

### Frequency

不能让少数值占大部分文档，例如 90% 文档 `country = CN`。高基数也可能有严重偏斜。

### Monotonicity

时间戳、自增 ID 等单调键在 Range Sharding 下会把新写入集中到最后一个范围和一个 Shard。

### Query Isolation

常见查询应包含分片键，否则大量 Scatter-Gather。选择一个均匀但业务查询从不携带的随机键，也不是好设计。

## 20.3 Range Sharding

```javascript
{ tenantId: 1, createdAt: 1 }
```

优势：

- 同一租户和相邻时间数据有局部性。
- 支持按租户和时间定向范围查询。
- 可配合 Zone 把租户/地域放到指定 Shard。

风险：

- 超级租户造成单 Range 热点。
- 固定 tenant 下 createdAt 单调，新写入仍集中。
- 跨租户按时间查询可能广播。

## 20.4 Hashed Sharding

```javascript
{ userId: "hashed" }
```

优势：

- 值经哈希后分布均匀。
- 随机化单调 ID 写入。
- 等值 userId 查询可定向路由。

风险：

- userId 范围查询需要多个 Shard。
- 同一用户全部数据仍路由到一个 Shard；超级用户仍可能热点。
- Hashed Index 不能提供普通范围排序能力。

## 20.5 复合分片键

多租户订单可考虑：

```javascript
{ tenantId: 1, orderId: "hashed" }
```

或：

```javascript
{ region: 1, tenantId: 1, createdAt: 1 }
```

复合键可以兼顾路由、Zone 和分布，但字段越多，应用查询和唯一索引要求越复杂。

## 20.6 低频查询怎么办

不应为了一个月一次的全局报表牺牲所有在线请求的路由。低频跨租户分析可使用：

- 数据仓库或湖仓。
- Change Streams 同步到分析系统。
- 专用只读集群。
- 预聚合集合。

## 20.7 分片键评估方法

分片前应基于真实样本运行分片键分析工具或离线统计：

- 不同值数量和频率分布。
- 单位时间写入热点。
- 主要查询是否携带完整前缀。
- 单个分片键值对应数据量。
- 未来 2-3 年增长和超级租户。
- 是否需要 Zone、唯一约束和重分片。

## 20.8 面试表达

> 我会把分片键看成数据分布键和查询路由键的统一设计。高基数只是起点，还要看频率偏斜、是否单调以及核心查询能否携带。Range 保留局部性但容易热点，Hashed 更均匀但范围查询差。最终必须用真实数据分布和访问日志验证，而不是凭字段名称猜测。

# 21. Scatter-Gather、热点分片、Jumbo Chunk 和迁移如何治理？

## 21.1 结论先行

分片集群常见性能问题不是“节点不够”，而是请求无法定向路由、数据或写入集中、单个分片键值过大，以及迁移与业务负载互相争抢资源。治理必须从查询形状、分片键分布、Chunk/Range 大小和 Balancer 状态共同分析。

## 21.2 Scatter-Gather 的代价

假设集群有 20 个 Shard，一个不含分片键的查询会：

1. `mongos` 向 20 个 Shard 发送请求。
2. 每个 Shard 独立扫描和排序。
3. `mongos` 等待最慢 Shard。
4. 合并结果并返回。

总吞吐消耗约随 Shard 数增加，P99 受最慢节点影响。即使每个 Shard 只扫描少量数据，高并发广播也会放大连接、CPU 和网络。

治理：

- API 强制携带 tenantId、region 等路由条件。
- 为后台管理查询单独限流。
- 建立预聚合集合或搜索/分析系统。
- 对无法携带分片键的核心查询重新评估分片键。

## 21.3 热点分片

热点来源：

- 单调 Range Key，新写入集中到末端。
- 超级租户或超级用户。
- 热点商品、直播房间或全局计数器。
- 查询条件只命中某个 Zone。
- Balancer 暂停或迁移跟不上增长。

治理策略：

- 对写入键增加散列后缀或桶号，例如 `{tenantId, bucket}`。
- 把全局计数拆为分片计数并异步聚合。
- 超级租户单独集合或专属 Shard/Zone。
- 通过 Hashed Sharding 分散单调写入。
- 预分割和预热关键范围。

加随机后缀会让读取需要聚合多个桶，是写扩展与读复杂度的交换。

## 21.4 Jumbo Range/Chunk

当某个不可再拆分的分片键值对应的数据过大，范围可能无法正常迁移，形成 Jumbo 问题。典型原因是低基数分片键或某个值数据量巨大。

例如全部历史数据的 `tenantId = 1` 占 2TB，而分片键只有 tenantId。这个值不能被拆到多个 Shard。

根治不是手工反复移动，而是：

- 优化/细化分片键，增加高基数字段。
- 使用 Resharding 重新分布。
- 将异常租户拆到独立集合。
- 在设计阶段限制单键值数据规模。

## 21.5 Chunk Migration 的影响

迁移需要克隆数据、追赶增量、进入临界区并更新元数据。业务侧可能看到：

- 源/目标 Shard 磁盘和网络上升。
- 缓存被迁移数据冲刷。
- 写延迟抖动。
- Balancer 窗口内后台任务竞争。

生产建议：

- 监控迁移速率、失败和剩余不平衡。
- 在低峰设置 Balancer Window，但避免长期关闭。
- 保证 Shard 间网络和磁盘能力对称。
- 扩容提前做，不要等磁盘 95% 才开始迁移。
- 压测时包含 Balancer 和故障切换场景。

## 21.6 Stale Config

`mongos` 或 Shard 路由元数据暂时过期时，服务端会刷新并重试。少量 Stale Config 属于分布式路由正常现象；持续大量出现可能表示迁移频繁、网络异常或组件版本/健康问题。

## 21.7 排查思路

1. `explain` 判断查询命中多少 Shard。
2. 查看每个 Shard 的 `nReturned`、Keys/Docs Examined。
3. 统计分片键值分布和热 Key。
4. 检查 Balancer、迁移和 Chunk/Range 分布。
5. 对比各 Shard CPU、磁盘、缓存和连接。
6. 确认应用是否遗漏分片键或使用错误类型。

# 22. 分片集群如何扩容、重分片以及保证唯一性？

## 22.1 结论先行

扩容不是简单添加服务器。新增 Shard 后需要 Balancer 将数据逐步迁移，短期内会增加 I/O 和网络。若原分片键不合理，可使用 Resharding 或 Refine Shard Key 等能力调整，但这是重资源操作，需要容量、时间窗口、回滚和业务验证。

分片集合的唯一索引受到分片键约束，不能假设任意业务字段都能跨 Shard 全局唯一。

## 22.2 新增 Shard 流程

高层步骤：

1. 部署并验证新的 Replica Set。
2. 配置认证、TLS、监控和备份。
3. 将 Replica Set 加入分片集群。
4. 观察 Balancer 分配范围。
5. 监控源/目标磁盘、网络和缓存。
6. 验证查询路由与数据均衡。

新增后数据不会瞬间均匀。迁移速度应与业务负载平衡，过快可能冲击线上，过慢则旧 Shard 长期高水位。

## 22.3 下线 Shard

下线通常需要 Draining，把该 Shard 上的数据和未分片数据库迁移出去。必须确认：

- 剩余集群容量充足。
- Zone 约束允许迁移。
- Jumbo 范围已处理。
- Primary Shard 上的未分片集合已迁移或评估。
- 备份和恢复点可用。

## 22.4 Refine Shard Key

Refine 是在现有分片键后追加字段，使键更细。例如：

```text
原键: { tenantId: 1 }
新键: { tenantId: 1, orderId: 1 }
```

它不会像完整 Resharding 一样重新计算所有文档的分片键分布，但可以为未来拆分提供更细粒度。旧数据范围和查询都需要评估。

## 22.5 Resharding

Resharding 用新分片键重新分布集合。适合原键热点、查询路由不匹配或业务演进。

风险和准备：

- 需要额外磁盘和网络空间。
- 运行时间与数据量、写入速率、索引和集群能力相关。
- 期间业务仍写入，需要追赶增量。
- 必须评估变更流、连接、备份和监控兼容。
- 完成后所有查询和索引设计要切换到新键语义。

应先在相似数据规模预演，并定义中止条件。

## 22.6 全局唯一性问题

在分片集合中，MongoDB 只能在满足分片键相关规则的索引上有效保证全局唯一。若业务唯一键不是分片键前缀，常见方案：

### 方案一：把业务唯一键纳入分片键

例如 `{tenantId, email}`，适合查询和分布也匹配时。

### 方案二：唯一键注册表

建立未分片或按唯一键自身分片的 Registry 集合：

```javascript
{ _id: "tenant:10:alice@example.com", userId: 1001 }
```

先原子插入 Registry，再创建业务记录。跨集合需要事务或补偿，且 Registry 会成为关键服务。

### 方案三：应用生成全局唯一 ID

UUID/ObjectId 可避免 ID 冲突，但“生成唯一”不等于“业务字段唯一”，例如手机号仍需单独约束。

## 22.7 扩容误区

- **误区：加 Shard 后吞吐立即翻倍。** 如果查询广播或热点仍在一个键值，扩容无效。
- **误区：Balancer 会自动修复坏分片键。** 它只能移动可切分范围，无法拆开单个热点键值。
- **误区：分片后所有唯一索引照常工作。** 必须检查分片键前缀和版本规则。

# 23. Change Streams 如何实现实时事件，如何保证不重不漏？

## 23.1 结论先行

Change Streams 基于复制集 oplog 提供结构化变更订阅，应用可以监听集合、数据库或整个部署的插入、更新、删除等事件。它比手工 tail oplog 更安全，但仍是至少一次风格的消费：网络重连、消费者崩溃和提交点处理不当会产生重复；oplog 窗口不足会导致无法续接。

要实现工程上的“不重不漏”，需要 Resume Token 持久化、幂等消费、正确的提交顺序、监控延迟以及超出窗口后的全量补偿方案。

## 23.2 基本示例

```javascript
const stream = db.orders.watch([
  { $match: { "operationType": { $in: ["insert", "update", "replace"] } } }
], {
  fullDocument: "updateLookup"
})

while (stream.hasNext()) {
  const event = stream.next()
  // process event
}
```

事件通常包含：

- `_id`：Resume Token。
- `operationType`。
- `clusterTime`。
- `ns`、`documentKey`。
- `updateDescription`。
- 可选 `fullDocument`。

## 23.3 Resume Token

消费者重启后可使用最后成功处理事件的 Token 恢复：

```text
resumeAfter / startAfter / startAtOperationTime
```

Token 必须在业务处理成功后再持久化。若先保存 Token 再处理业务，崩溃会漏事件；若先处理再保存，崩溃会重复事件。因此消费者必须幂等。

## 23.4 正确处理顺序

```text
读取 event
  -> 幂等检查
  -> 执行业务写入
  -> 提交业务事务/结果
  -> 持久化 resume token
  -> 拉取下一条
```

若业务结果和 Token 在同一支持事务的存储中，可放同一事务。否则接受至少一次并通过事件 ID 去重。

## 23.5 幂等方案

- 使用 Change Event Token 作为去重键。
- 使用业务文档版本号，只接受更高版本。
- 目标表建立唯一索引。
- 使用状态机条件更新。
- 对计数场景保存已应用事件 ID，避免重复 `$inc`。

## 23.6 oplog 窗口

Change Stream 能续接的前提是相关历史仍在 oplog 中。若消费者停机超过窗口，Resume Token 对应数据被覆盖，会收到不可恢复错误。

需要监控：

- oplog 时间窗口。
- 消费延迟。
- 最后 Token 时间。
- 错误和重连次数。

超窗恢复方案：

1. 记录切换时间点。
2. 全量扫描构建目标状态。
3. 从可用时间点继续消费增量。
4. 通过版本/时间校验消除扫描与增量竞态。

## 23.7 `fullDocument: updateLookup` 的代价

更新事件默认只包含变化字段。开启 `updateLookup` 会额外查询更新后的完整文档，增加读负载，而且读到的是 lookup 时的最新版本，不一定严格是该事件瞬间的版本。

如果下游只需要变更字段，应直接使用 `updateDescription`；需要完整快照时评估一致性语义和读取成本。

## 23.8 Change Streams 与 MQ

Change Streams 适合数据库变更驱动的同步、搜索索引、缓存失效和审计。它不完全替代 Kafka/RabbitMQ：

- 保留时间受 oplog 限制。
- 消费者组和重放治理能力不同。
- 跨系统事件不一定都源于 MongoDB。
- 业务事件语义可能比“字段变更”更高层。

常见架构是 Change Streams/Outbox -> MQ -> 多消费者。

# 24. MongoDB 备份、PITR 和灾难恢复如何设计？

## 24.1 结论先行

复制集不是备份。误删除、逻辑 Bug、勒索软件和错误脚本会复制到所有节点。完整方案必须明确 RPO、RTO，使用快照或云备份、oplog/PITR、异地副本，并定期做真实恢复演练。

备份是否成功的唯一可信标准是：能够在隔离环境恢复、校验并满足时间目标。

## 24.2 RPO 与 RTO

- **RPO**：最多允许丢失多少时间的数据，例如 5 分钟。
- **RTO**：故障后多久恢复服务，例如 30 分钟。

不同业务集合可有不同等级：订单支付 RPO 接近 0，临时推荐缓存可重建。

## 24.3 备份方法

### `mongodump` / `mongorestore`

逻辑备份，适合数据量较小、开发迁移和选择性恢复。优点是可读性和灵活性；缺点是大数据量耗时、占用 CPU/网络，重建索引慢。

在复制集上可结合 oplog 选项获得更一致的时间点语义，但分片集群的完整一致备份需要严格遵循对应版本官方流程。

### 文件系统快照

对数据卷做块级快照，通常恢复快、适合大数据。要求底层存储支持一致快照，并确保所有相关卷和分片在一致时间点协调。

### Atlas/Ops Manager/Cloud Manager 备份

托管或企业平台可提供连续备份和 PITR，减少自建复杂度，但仍要验证保留策略、跨区域、加密密钥和恢复流程。

## 24.4 PITR

Point-in-Time Recovery 通常依赖基础快照加连续 oplog/增量日志，将系统恢复到特定时间点。

关键风险：

- oplog/增量保留不足。
- 备份与加密密钥不在同一灾备计划。
- 分片集群各组件时间点不一致。
- 恢复后驱动连接、用户权限、索引和配置缺失。

## 24.5 误删除恢复

场景：10:00 执行错误 `deleteMany`，10:05 发现。

推荐流程：

1. 立即停止错误程序并保存审计信息。
2. 判断是否可从业务日志/软删除直接恢复。
3. 在隔离环境恢复到 09:59:59。
4. 导出被删除文档。
5. 与当前线上新增/更新数据做冲突分析。
6. 小批次幂等回灌。
7. 校验数量、哈希、业务状态并记录审计。

不要直接把整个集群回滚到 09:59，这会丢失 10:00 后其他正常写入。

## 24.6 恢复演练检查项

- 备份文件是否完整、可解密。
- 从零部署到服务可用耗时。
- 索引重建时间。
- 用户、角色、TLS 证书和密钥恢复。
- 分片元数据和 Zone 配置。
- 应用连接串、DNS 和流量切换。
- Change Streams Resume Token 的处理。
- 恢复后数据校验和业务对账。

## 24.7 3-2-1 思路

至少保留多份副本、不同介质和异地备份。备份账号与生产写账号隔离，开启不可变或防删除策略，避免攻击者同时删除生产和备份。

# 第五部分：性能排障、Java 工程实践与系统设计

# 25. MongoDB 延迟、CPU、锁或连接数异常时如何排查？

## 25.1 结论先行

MongoDB 线上排障要按“客户端 -> 网络与连接池 -> mongos/Primary/Secondary -> 查询计划 -> WiredTiger Cache -> 磁盘与系统 -> 复制/迁移”的链路逐层定位。不能只看到 CPU 高就加机器，也不能只看慢日志；客户端等待连接、Server Selection、网络重传和大结果反序列化都可能不出现在服务端执行时间中。

## 25.2 先明确故障形态

收集时间线和影响范围：

- 延迟是平均值上升还是 P99 尖峰？
- 只影响某个 API、某个租户、某个集合还是全局？
- 读慢、写慢还是连接失败？
- Primary 与 Secondary 是否同时异常？
- 是否刚发布、建索引、扩容、备份或 Balancer 迁移？
- 错误是 `MongoTimeoutException`、SocketTimeout、WriteConflict、ExceededTimeLimit 还是 DuplicateKey？

## 25.3 客户端连接池排查

Java 驱动为拓扑中的每个 Server 维护连接池。连接池耗尽时，请求会在应用侧等待，即使 MongoDB CPU 很低。

关注：

- Checked-out connections。
- Pool wait queue 和 wait time。
- Connection creation rate。
- `serverSelectionTimeoutMS`。
- `connectTimeoutMS`、`socketTimeoutMS` 或统一 `timeoutMS`。
- 每个 Pod 是否创建过多 `MongoClient`。

正确做法是单例复用 `MongoClient`。每个请求创建 Client 会产生大量连接、握手和监控线程。

## 25.4 Server Selection Timeout

错误示例：

```text
Timed out while waiting for a server that matches ReadPreferenceServerSelector{primary}
```

可能原因：

- 没有 Primary，正在选举。
- DNS/SRV、TLS 或认证失败。
- 防火墙、网络路由或连接数限制。
- 驱动拓扑配置与实际 Replica Set Name 不一致。
- 所有连接都无法建立，而不是查询执行慢。

应检查驱动 Cluster Description、MongoDB 日志、`rs.status()` 和网络连通性。

## 25.5 慢查询排查

1. 从 APM/日志取得查询形状、库、集合、耗时和返回量。
2. 使用真实代表参数执行 `explain("executionStats")`。
3. 检查 Keys/Docs Examined、SORT、FETCH、Shard 数。
4. 检查 Profiler、Query Stats 和慢日志。
5. 比较热缓存与冷缓存。
6. 评估大文档、投影和网络响应体。

生产 Profiler 应采用合适阈值和采样，避免全量记录造成额外开销和敏感数据泄漏。

## 25.6 CPU 高

常见原因：

- 大量 COLLSCAN 或低效 IXSCAN。
- 正则、JavaScript 表达式或复杂 Aggregation。
- 高压缩/解压成本。
- 索引过多导致高写放大。
- TLS 加密和大量短连接。
- Change Streams 大量 `updateLookup`。
- 分片 Merge、排序和分组。

排查：

- `mongostat`/监控查看 opcounters、queryExecutor、connections。
- `$currentOp` 找长操作。
- Profiler 找高频与高 CPU 查询。
- OS `top -H`、`pidstat`、perf/火焰图在允许时定位线程。
- 对比发布前后查询形状和索引。

## 25.7 磁盘 I/O 高

常见原因：

- Working Set 超出内存。
- 后台建索引、Initial Sync、备份或迁移。
- Checkpoint/Eviction 写入。
- 全集合扫描冲刷缓存。
- Secondary 应用 oplog 落后后追赶。
- 磁盘容量过高导致性能下降。

使用 `iostat -x` 观察延迟、队列和利用率；结合 WiredTiger Cache 的 pages read/write、dirty bytes 和 eviction 判断。

## 25.8 锁与 Write Conflict

MongoDB 采用细粒度锁和 WiredTiger 乐观并发。`currentOp` 中 waitingForLock、锁等待时间增长，可能来自：

- DDL、索引操作与业务冲突。
- 长事务。
- 热点文档高并发更新。
- 集合/数据库级管理操作。

Write Conflict 多表示同一文档或页的并发竞争，存储引擎会重试部分操作。若冲突持续高，应拆分热点文档、分桶计数或减少事务范围。

## 25.9 复制延迟

检查：

- `rs.printSecondaryReplicationInfo()` 或平台复制延迟指标。
- Secondary CPU、磁盘和网络。
- Oplog apply rate。
- 是否有长查询占用 Secondary 资源。
- Oplog 窗口是否足够。

读 Secondary 的业务会直接感知旧数据；Change Streams 也会积压。

## 25.10 常用诊断命令

```javascript
// 当前操作
db.getSiblingDB("admin").aggregate([{ $currentOp: { allUsers: true, idleConnections: false } }])

// 服务指标
db.serverStatus()

// 复制集状态
rs.status()
rs.printReplicationInfo()
rs.printSecondaryReplicationInfo()

// 索引使用统计
db.orders.aggregate([{ $indexStats: {} }])

// 集合统计
db.orders.stats()

// 查询计划
db.orders.explain("executionStats").find({...})
```

## 25.11 故障止损顺序

- 对异常 API 限流、熔断或降级。
- Kill 明确失控且可安全终止的长查询。
- 暂停大报表、后台迁移和非必要建索引。
- 扩大连接池前先确认服务端有容量，避免雪崩。
- 不要在未知情况下立即 Step Down 或重启全部节点。
- 保存日志、currentOp、serverStatus 和系统指标用于复盘。

# 26. Java/Spring Data MongoDB 与高并发系统设计综合题

## 26.1 面试题

设计一个多租户商品与库存平台：

- 1 万个商户，商品总量 5 亿。
- 商品属性按类目动态变化。
- 商品详情读 QPS 20 万，更新 QPS 2 万。
- 支持按商户查询最近更新商品、按 SKU 精确查询。
- 库存扣减不能超卖，同一请求必须幂等。
- 商品变更需要同步到 Elasticsearch 和缓存。
- 系统需要跨可用区高可用，可在线扩容。
- Java/Spring Boot 服务访问 MongoDB。

请给出 Schema、索引、分片键、一致性、缓存、事件、Java 客户端和故障处理方案。

## 26.2 总体结论

MongoDB 适合承载商品主数据和动态属性；库存是否与商品放同一文档，要根据写热点和一致性边界决定。商品详情可使用 Cache Aside；索引和分片键围绕 `tenantId + sku/productId` 设计；商品变更通过 Outbox/Change Streams 进入 MQ，再更新 Elasticsearch 和缓存。库存扣减使用条件原子更新和业务请求唯一键，热点 SKU 需要分桶或独立库存服务。

核心原则：MongoDB 是商品事实源，Redis 是可失效缓存，Elasticsearch 是可重建搜索索引，MQ 负责可靠分发。不能让三个系统互相双写而没有权威源和补偿。

## 26.3 Schema 设计

### 商品主文档

```javascript
{
  _id: ObjectId("..."),
  tenantId: NumberLong(1001),
  sku: "SKU-2026-0001",
  categoryId: 301,
  title: "Mechanical Keyboard",
  status: "ONLINE",
  attributes: {
    switchType: "red",
    layout: "87-key",
    connection: ["usb", "bluetooth"]
  },
  price: {
    currency: "CNY",
    amount: NumberLong(29900)
  },
  version: NumberLong(18),
  createdAt: ISODate("..."),
  updatedAt: ISODate("...")
}
```

商品属性嵌入，因为详情页共同读取，数量和生命周期受控。图片只存 URL 和元数据，二进制放对象存储。

### 库存文档

普通 SKU 可与商品拆分：

```javascript
{
  _id: "1001:SKU-2026-0001",
  tenantId: NumberLong(1001),
  sku: "SKU-2026-0001",
  available: 1000,
  reserved: 0,
  version: 51,
  updatedAt: ISODate("...")
}
```

拆分理由：库存更新频率高，避免每次扣库存重写和复制整个商品文档；库存集合可独立分片和容量规划。

### 幂等请求集合

```javascript
{
  _id: "tenant1001:req-8899",
  tenantId: 1001,
  requestId: "req-8899",
  sku: "SKU-2026-0001",
  quantity: 2,
  status: "SUCCESS",
  createdAt: ISODate("..."),
  expireAt: ISODate("...")
}
```

`_id` 直接保证全局唯一，并使用 TTL 清理已过业务保留期记录。TTL 只做异步清理，不影响幂等判断的业务有效期。

## 26.4 索引设计

```javascript
// SKU 精确查询和唯一性
db.products.createIndex({ tenantId: 1, sku: 1 }, { unique: true })

// 商户最近更新商品
db.products.createIndex({ tenantId: 1, updatedAt: -1, _id: -1 })

// 在线商品管理
db.products.createIndex(
  { tenantId: 1, categoryId: 1, updatedAt: -1 },
  { partialFilterExpression: { status: "ONLINE" } }
)

// 库存业务键
db.inventory.createIndex({ tenantId: 1, sku: 1 }, { unique: true })

// 幂等记录过期
db.idempotency.createIndex({ expireAt: 1 }, { expireAfterSeconds: 0 })
```

动态 attributes 不应全部建立 Wildcard Index。核心精确筛选可建立有限索引；复杂搜索和多属性组合交给 Elasticsearch。

## 26.5 分片键

候选：

```javascript
{ tenantId: 1, sku: "hashed" }
```

或在业务允许时使用 hashed `_id`。设计目标：

- 精确商品查询可定向。
- 单调 SKU 不集中写入。
- 同一租户数据不全部压在单 Shard。
- 超级租户可以跨多个 Shard。

若大量查询按 tenantId 最近更新时间范围扫描，`{tenantId, sku hashed}` 会让该查询广播到租户涉及的多个 Shard。可以：

- 接受并限制后台列表查询。
- 建立按 tenantId 的读模型集合。
- 使用复合 Range Key 并为超级租户分桶。
- 根据真实流量在路由与均衡间取舍。

库存热点 SKU 仍然只落一个 Shard。对于秒杀热点，可使用 Redis/Lua 预扣 + MQ 排队 + MongoDB 最终确认，或库存分桶；但必须设计回补、对账和权威库存。

## 26.6 库存原子扣减

```javascript
const result = db.inventory.updateOne(
  {
    tenantId: 1001,
    sku: "SKU-2026-0001",
    available: { $gte: 2 }
  },
  {
    $inc: { available: -2, reserved: 2, version: 1 },
    $set: { updatedAt: new Date() }
  }
)
```

只有匹配成功才能创建预占。跨库存和幂等记录可使用短事务：

1. 插入幂等请求，唯一冲突则返回历史结果。
2. 条件扣减库存。
3. 写库存流水/Outbox。
4. 提交事务。

若热点导致事务冲突，可把幂等登记放在请求入口，库存使用单文档原子更新，并通过状态查询处理未知结果。

## 26.7 缓存设计

商品详情使用 Cache Aside：

```text
Read:
  GET Redis product:{tenant}:{sku}
  miss -> MongoDB -> set TTL + random jitter

Write:
  update MongoDB with version condition
  commit
  publish product.changed(version)
  consumer deletes or updates Redis
```

缓存 Value 包含 `version`，消费者只应用更高版本，防止乱序事件把新值覆盖为旧值。热点 Key 重建使用逻辑过期、互斥重建或请求合并。

数据库更新成功但缓存删除失败时，Outbox/MQ 重试最终修复。不要直接在请求线程对 MongoDB、Redis、Elasticsearch 做三次无事务双写。

## 26.8 Elasticsearch 同步

推荐链路：

```text
MongoDB Transaction
  -> update product
  -> insert outbox event
Commit
  -> Change Stream / publisher
  -> MQ topic product-change
  -> ES consumer upsert by productId + version
  -> Cache consumer invalidate/update
```

消费者幂等：

- 以 `productId + version` 判断顺序。
- ES 使用 External Version 或脚本比较版本。
- 失败进入重试和死信队列。
- 定期全量校验 MongoDB 与 ES 文档数量、版本和哈希。

Change Streams 超过 oplog 窗口时，应从 MongoDB 全量重建 ES，再切回增量。

## 26.9 复制集和一致性

每个 Shard 使用跨三个可用区的三数据节点复制集。商品更新使用 `w: majority`；详情读取根据一致性要求选择 Primary 或允许延迟的 Secondary。

库存和幂等结果读取优先 Primary，必要时 `majority`。客户端超时后按 requestId 查询最终结果，不能重复扣减。

## 26.10 Java Driver 配置原则

### 单例 MongoClient

Spring Boot 中 MongoClient 是线程安全、重量级对象，应全局复用。每个 Client 对每个 Server 有连接池和监控连接。

### 连接池容量

不要简单设置为线程数或 QPS。估算：

```text
并发数据库操作数约等于 QPS × 单次数据库平均耗时
```

若单实例 2000 QPS、DB 平均 20ms，平均并发约 40；考虑 P99、突发和事务可设置合理上限，例如 100-200，再通过压测验证。连接过多会把排队从应用转移到数据库。

### 超时分层

- `serverSelectionTimeoutMS`：等待可用节点。
- `connectTimeoutMS`：建立 TCP/TLS 连接。
- Socket/操作级 `timeoutMS`：请求执行与网络等待。
- `maxTimeMS`：服务端限制查询执行时间。
- 业务总超时必须大于数据库子超时，并预留降级时间。

### Read/Write Concern

在 MongoClient、Database 或 Collection 级显式设置，避免不同模块默认不一致。事务级设置覆盖事务内操作。

### 重试

启用驱动 Retryable Reads/Writes，但业务必须幂等。事务使用 `withTransaction` 时，回调不得包含发送 MQ、HTTP 扣款等不可回滚副作用。

## 26.11 Spring Data 常见陷阱

- Repository 方法名自动生成查询，但复杂查询必须查看实际 BSON 和 explain。
- `save()` 可能替换整个文档，热点更新应使用 `MongoTemplate.updateFirst` 和原子操作符。
- `@Transactional` 需要正确的 `MongoTransactionManager`、Replica Set 和 Session；同类自调用等 Spring AOP 问题仍存在。
- Java 字段重命名和类型变化要兼容旧文档。
- 不要把所有动态字段映射成 Object 后失去校验。
- 注意 Lazy Reference/DBRef 可能产生 N+1 查询，通常使用手工引用 ID 更透明。

## 26.12 容量与监控

监控至少包括：

- QPS、P50/P95/P99、超时和错误码。
- 连接池 Checked-out、Wait Queue。
- Keys/Docs Examined、慢查询形状。
- WiredTiger Cache、Eviction、Dirty Bytes。
- 磁盘 IOPS、延迟、容量。
- Replica Lag、oplog window、选举。
- Shard 数据分布、Balancer、迁移。
- Change Stream 消费延迟和 Dead Letter。
- Redis 命中率、ES 同步延迟和版本差异。

## 26.13 降级和容灾

- MongoDB 短时不可用：商品详情读取缓存，写入快速失败或进入可靠队列，不能无限重试耗尽线程。
- Redis 不可用：限流回源 MongoDB，热点商品使用本地缓存并限制并发。
- ES 不可用：商品主数据写入继续，搜索降级，事件积压后重放。
- MQ 不可用：Outbox 保留未发布事件，恢复后重试。
- 单 Shard 热点：临时限流，扩容无法立即解决时拆分热点键或业务隔离。

## 26.14 面试总结回答

> 这套系统以 MongoDB 为商品事实源，文档模型承载动态属性，库存独立为高频原子更新集合。分片键必须同时满足 tenant 路由和写入均衡，不能只看高基数。库存扣减用条件更新和 requestId 幂等，跨幂等记录与流水时使用短事务。缓存和 Elasticsearch 都通过 Outbox/Change Streams + MQ 异步更新，并用 version 保证乱序幂等。Java 侧复用单例 MongoClient，连接池和超时通过 Little's Law 与压测配置。所有组件都定义故障降级和全量重建路径。

# 附录 A：高频追问速答

## A.1 MongoDB 单文档是否支持 ACID？

支持。单文档写是原子的，适当建模可以把许多业务不变量放在一个文档中。跨文档需要事务或最终一致方案。

## A.2 MongoDB 索引是 B+ Tree 吗？

官方通常描述为 B-Tree 索引。面试不要套用 InnoDB 聚簇 B+ Tree 的叶子结构和回表细节。应讨论 MongoDB 索引键、RecordId、覆盖查询和 WiredTiger 存储实现。

## A.3 `_id` 索引是否是聚簇索引？

普通集合的 `_id` 唯一索引不等同于 InnoDB 聚簇主键。MongoDB 支持 Clustered Collection，但它是明确创建的特殊集合类型，不能把所有集合都称为按 `_id` 聚簇。

## A.4 MongoDB 支持外键吗？

不提供关系型数据库式声明外键约束。引用完整性由应用、事务、校验任务或建模保证。

## A.5 Secondary 能否提高写吞吐？

不能直接分担 Primary 写入。Secondary 重放 oplog，提高可用性并可承担读，但写入入口仍在 Primary。要扩展写吞吐需分片或重新设计热点。

## A.6 `w: majority` 是否要求所有节点写入？

不要求所有节点，而是满足多数提交所需的投票数据成员语义。具体确认与部署成员、仲裁节点和配置相关。

## A.7 复制延迟为 0 是否绝对一致？

监控粒度可能看不见毫秒级延迟，且读偏好、读关注和选主仍影响语义。不能用一个延迟指标替代一致性配置。

## A.8 为什么不建议大量使用 `skip`？

`skip` 越深，需要扫描并丢弃的记录越多。应使用稳定排序键的 Seek Pagination。

## A.9 `$in` 是否一定走索引？

可能走索引，但列表很大时会产生大量 Seek 和合并成本。要看索引、值数量、选择性和 explain。

## A.10 正则能否走索引？

前缀锚定且大小写/Collation 适配的正则可能利用索引前缀；包含前导通配符或复杂模式通常难以高效使用 B-Tree。

## A.11 为什么索引很多反而变慢？

写操作要维护每个索引，索引扩大 Working Set，候选计划增多，备份和迁移更慢。

## A.12 建索引会锁表吗？

现代版本支持更在线的构建流程，但仍消耗 CPU、磁盘、内存和复制资源，并在某些阶段获取锁。大集合生产建索引要评估版本行为、资源和失败恢复。

## A.13 TTL 能做延时队列吗？

不适合精准调度。TTL 删除是后台异步扫描，触发时间不精确。延时任务应使用专门队列或按时间索引轮询。

## A.14 Change Streams 是否保证只消费一次？

不保证。它提供恢复能力，应用应按至少一次消费设计，持久化 Resume Token 并幂等处理。

## A.15 Replica Set 是否替代备份？

不能。逻辑误操作会复制到所有副本，必须有独立、异地、可恢复的备份。

## A.16 分片键能否修改？

现代 MongoDB 支持更新非 `_id` 的分片键值，也支持 Refine 和 Resharding，但都有条件和成本。不能把它理解为随意无成本修改。

## A.17 为什么不建议直接连接 Secondary 做备份查询？

重查询会竞争磁盘和缓存，拖慢 oplog 应用，导致复制延迟。应使用 Hidden/专用节点或平台备份能力。

## A.18 多文档事务能否包含并行操作？

Java Driver 不支持在同一个事务 Session 中并行执行操作。事务应在单线程顺序执行。

## A.19 MongoDB 是否适合日志系统？

适合可查询、保留期受控、规模可管理的操作日志或时序数据；超大不可变日志、长期重放和流计算通常更适合 Kafka + 数据湖/分析系统。

## A.20 MongoDB 与 Elasticsearch 如何分工？

MongoDB 作为权威文档数据库，提供事务性 CRUD 和主数据；Elasticsearch 提供全文检索、相关性和复杂搜索。通过可靠事件同步，ES 可全量重建。

# 附录 B：常用命令与排障速查

## B.1 查询与索引

```javascript
// 执行计划
db.collection.explain("executionStats").find({ ... }).sort({ ... })

// 聚合执行计划
db.collection.explain("executionStats").aggregate([ ... ])

// 索引列表
db.collection.getIndexes()

// 索引使用
db.collection.aggregate([{ $indexStats: {} }])

// 集合统计
db.collection.stats()

// 验证集合
db.collection.validate({ full: true })
```

## B.2 当前操作与慢查询

```javascript
// 当前操作
db.getSiblingDB("admin").aggregate([
  { $currentOp: { allUsers: true, idleConnections: false } },
  { $match: { secs_running: { $gte: 5 } } }
])

// 终止操作
db.killOp(<opid>)

// Profiler 状态
db.getProfilingStatus()

// 设置慢操作采样，生产需谨慎
db.setProfilingLevel(1, { slowms: 200, sampleRate: 0.1 })

// 查看 Profiler
db.system.profile.find().sort({ ts: -1 }).limit(20)
```

## B.3 复制集

```javascript
rs.status()
rs.conf()
rs.printReplicationInfo()
rs.printSecondaryReplicationInfo()

db.getSiblingDB("local").oplog.rs.stats()
```

## B.4 服务指标

```javascript
const s = db.serverStatus()
s.connections
s.opcounters
s.network
s.wiredTiger.cache
s.transactions
s.metrics
```

## B.5 分片

```javascript
sh.status()
sh.getBalancerState()
sh.isBalancerRunning()

db.collection.getShardDistribution()

db.collection.explain("executionStats").find({ ... })
```

## B.6 Linux

```bash
# CPU / 线程
top -H -p <pid>
pidstat -p <pid> 1

# 内存
free -h
vmstat 1
cat /sys/fs/cgroup/memory.max

# 磁盘
iostat -x 1
lsblk

# 网络
ss -s
sar -n DEV 1
```

# 附录 C：生产上线检查清单

## C.1 数据模型

- 核心访问模式已列出并通过样本验证。
- 文档和数组有明确大小上限。
- 金额、时间、ID 类型统一。
- 使用 Schema Validation 防止明显坏数据。
- 冗余字段已定义快照或最终一致语义。
- Schema 演进支持滚动发布。

## C.2 索引

- 每个核心查询有 `explain` 证据。
- 联合索引同时考虑过滤、排序和投影。
- 避免重复、前缀冗余和长期未使用索引。
- Partial/TTL/Multikey 行为已测试。
- 深分页改为 Seek Pagination。
- 索引构建资源和回滚方案明确。

## C.3 一致性

- Read/Write Concern 和 Read Preference 显式配置。
- 网络超时后的未知结果有幂等处理。
- 核心状态有业务唯一索引和状态机。
- 事务短小、可重试，不含外部副作用。
- Secondary 旧读对业务影响已评估。

## C.4 复制与高可用

- 三个数据节点跨故障域部署。
- 选举、Stepdown、节点重启已演练。
- oplog 窗口覆盖最长维护和消费中断时间。
- 复制延迟和 Majority Commit Lag 有告警。
- 驱动连接串包含完整拓扑与 Replica Set Name。

## C.5 分片

- 分片键用真实数据评估基数、频率、单调性和路由率。
- 超级租户和热点 Key 有单独方案。
- 核心查询 Targeted Ratio 可观测。
- Balancer、迁移和数据分布有告警。
- 扩容提前量和 Resharding 预案明确。
- 唯一索引与分片键规则已验证。

## C.6 Java 客户端

- 全局复用 MongoClient。
- 连接池按并发和 P99 压测配置。
- Server Selection、Connect、Operation、Business Timeout 分层。
- Retryable Writes/Reads 与业务幂等一致。
- APM 记录 Query Shape，不泄漏敏感值。
- Spring Data 生成查询已通过 explain 验证。

## C.7 备份与安全

- RPO/RTO 文档化。
- 备份异地、加密、权限隔离。
- 定期从零恢复并校验业务数据。
- TLS、认证、最小权限、审计和密钥轮换到位。
- 禁止 MongoDB 直接暴露公网。
- 备份保留和不可变策略可抵抗误删与攻击。

# 附录 D：官方资料与版本说明

以下资料用于校验本文的版本敏感内容。生产实施时，应打开与实际 Server 和 Driver 版本一致的文档页面。

1. MongoDB Release Notes  
   https://www.mongodb.com/docs/manual/release-notes/

2. MongoDB Versioning  
   https://www.mongodb.com/docs/manual/reference/versioning/

3. MongoDB Manual  
   https://www.mongodb.com/docs/manual/

4. Data Modeling / Schema Design  
   https://www.mongodb.com/docs/manual/data-modeling/schema-design-process/

5. Embedded Data and References  
   https://www.mongodb.com/docs/manual/data-modeling/embedding/  
   https://www.mongodb.com/docs/manual/data-modeling/referencing/

6. Index Types and Multikey Indexes  
   https://www.mongodb.com/docs/manual/core/indexes/index-types/  
   https://www.mongodb.com/docs/manual/core/indexes/index-types/index-multikey/

7. Explain and Query Performance  
   https://www.mongodb.com/docs/manual/reference/explain-results/  
   https://www.mongodb.com/docs/manual/tutorial/analyze-query-plan/  
   https://www.mongodb.com/docs/manual/tutorial/evaluate-operation-performance/

8. Read Concern, Write Concern and Read Preference  
   https://www.mongodb.com/docs/manual/reference/read-concern/  
   https://www.mongodb.com/docs/manual/reference/write-concern/  
   https://www.mongodb.com/docs/manual/core/read-preference/

9. Replica Set Architecture  
   https://www.mongodb.com/docs/manual/replication/  
   https://www.mongodb.com/docs/manual/core/replica-set-architecture-three-members/

10. Transactions  
    https://www.mongodb.com/docs/manual/core/transactions/  
    https://www.mongodb.com/docs/manual/core/transactions-production-consideration/

11. WiredTiger  
    https://www.mongodb.com/docs/manual/core/wiredtiger/

12. Sharding and Shard Key Selection  
    https://www.mongodb.com/docs/manual/sharding/  
    https://www.mongodb.com/docs/manual/core/sharding-choose-a-shard-key/

13. Change Streams  
    https://www.mongodb.com/docs/manual/changestreams/

14. Backup Methods  
    https://www.mongodb.com/docs/manual/core/backups/  
    https://www.mongodb.com/docs/manual/tutorial/backup-and-restore-tools/

15. Java Sync Driver - Connection Pools and Transactions  
    https://www.mongodb.com/docs/drivers/java/sync/current/connection/specify-connection-options/connection-pools/  
    https://www.mongodb.com/docs/drivers/java/sync/current/crud/transactions/

16. Production Notes and Operations Checklist  
    https://www.mongodb.com/docs/manual/administration/production-notes/  
    https://www.mongodb.com/docs/manual/administration/production-checklist-operations/

# 结束语

MongoDB 高级面试的核心，不是记住多少命令，而是能否从文档聚合边界推导索引和原子性，从读写关注推导故障语义，从分片键推导路由与热点，并用可观测性、幂等、备份和恢复闭环把数据库能力转化为可靠系统。

当面试官继续追问时，可以始终回到五个问题：

1. 数据如何组织，是否有界？
2. 查询是否能被索引和分片键精确定位？
3. 超时、重试和选主后，业务是否仍然正确？
4. 缓存、搜索和消息系统是否有唯一事实源与重建路径？
5. 是否能用指标、压测和恢复演练证明方案成立？
