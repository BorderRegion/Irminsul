# Irminsul-银白古树

Irminsul 是 Ledger 的一个实验分支。它保留 Ledger 原本的记录、查询和回滚能力，额外加入了
一个同名的本地数据库后端。

这个项目的目标很直接：在红石机器、爆炸、大量方块更新和大范围回滚时，尽量减少数据库拖慢服务器的情况。

> 目前仍是测试版本。不要把它当成稳定版直接扔进主服，尤其不要在没有备份和回退方案的情况下使用。

## 现在的思路

Ledger 默认适合通用数据库。Irminsul 后端则更偏向 Minecraft 服务器的实际负载：大量追加写入、
按时间和位置查询、短时间内处理很多相似记录，以及尽可能平稳地执行回滚。

它牺牲了一些通用数据库能力，换取更低的写入开销、更小的数据体积，以及更可控的内存和回滚压力。

## 测试里的表现

下面的数据来自同一台远程测试机，只能代表这轮测试里的表现。不同硬件、JVM 参数、模组包、在线人数和区块加载情况，
都会让结果变化。

| 测试项 | Irminsul | 当前 Ledger MySQL 路径 | 差异 |
| --- | ---: | ---: | --- |
| 最新 8 条翻页 | 0.002 ms | 40.35 ms | 约 19000 倍更快 |
| 全量 search 首页 | 2.72 ms | 41.55 ms | 约 15 倍更快 |
| source 过滤 search | 3.55 ms | 57.28 ms | 约 16 倍更快 |
| 最热 chunk search | 0.47 ms | 2.59 ms | 约 5.5 倍更快 |
| 最热 chunk rollback 候选 | 0.30 ms | 1.86 ms | 约 6.3 倍更快 |
| 数据占用 | 约 1.50 MB | 约 4.83 MB | 约少 69% |

测试数据集包含 20588 条真实 Ledger action。MySQL 结果按当前 SQL/JDBC 查询形状测量；
如果手写优化 SQL，让 MySQL 先从 `actions` 表取候选再 join 维表，部分查询可以接近 Irminsul。
这说明 Irminsul 的优势来自更贴近 Ledger 访问模式的存储和索引设计，也说明 SQL 路径仍有优化空间。

另一次测试把 `maxQueueSize` 压到 256，强制触发磁盘溢写：

| 项目 | 结果 |
| --- | ---: |
| 溢写到磁盘的记录 | 260596 |
| 丢弃记录 | 0 |
| 重启后的热数据 | 10000 |
| 重启后的冷数据 | 307535 |
| 冷数据搜索 | 约 1011 ms |
| 带节流的回滚 | 约 6026 ms |

带节流的回滚会拉长总耗时，这是刻意保留的取舍。比起一次性把所有改动压到主线程里，它更倾向于把压力摊到多个
tick，避免回滚时把在线玩家一起卡住。

## 主要改动

主要改动集中在数据库和回滚路径：

- 新增 Irminsul 后端，通过 `engine = "irminsul"` 启用。
- 原 SQL/JDBC 后端仍然保留，可以继续使用 MySQL、MariaDB 等数据库。
- 把存储层抽成 `LedgerStore`，让 SQL 和 Irminsul 走同一套上层逻辑。
- Irminsul 使用本地 append-only 文件，默认数据目录是世界目录下的 `ledger-irminsul`。
- 对 action、世界、来源、玩家、方块状态、注册 id 等做字典编码，减少重复字符串带来的空间和索引开销。
- 字典保存完整注册名，不只处理 `minecraft:*`，也会保留模组的 `modid:*`。
- 近期数据保留热索引，旧数据留在磁盘上按需扫描，避免历史记录越多内存越失控。
- 索引缓存会根据 JVM heap 和主机内存估算，也可以用 `irminsulIndexCacheMiB` 手动限制。
- 写入队列达到上限后会落到 `ledger-queue`，防止极端写入时内存队列无限增长。
- 回滚加入每 tick 限额、时间预算、日志抑制、冲突跳过和同位置去重。
- 回滚、restore 和 purge 现在通过统一操作路径执行，会先 drain 写入队列，并阻止上一次状态持久化失败后继续误操作。
- 命令和网络请求增加了异步异常处理，失败时向调用方返回错误而不是静默丢失。
- 冷数据查询、分页、计数和回滚规划改成流式处理，降低大范围操作时的峰值内存。
- `ledger status` 和关服 drain 会把磁盘溢写队列也算进去。

## 使用建议

比较适合这些场景：

- 单个 Minecraft 服务器，本地 SSD。
- Ledger 写入量很大，MySQL 已经明显拖慢查询或回滚。
- 有测试服或镜像服，可以先跑一段时间观察。
- 可以接受实验版本的风险，并且有完整备份。

不建议这些场景直接使用：

- 正式主服没有备份，或者备份没有实际恢复过。
- 不能接受停服排查、回退或数据迁移。
- 模组包很复杂，但还没有用真实玩法做过长时间测试。
- 需要数据库级别的成熟事务、远程管理和审计能力。

## 配置

在 `config/ledger.toml` 中设置：

```toml
[database]
engine = "irminsul"
batchSize = 1000
batchDelay = 10
maxQueueSize = 250000
spillBatchSize = 5000
rollbackTickBudgetMillis = 5
rollbackMaxActionsPerTick = 2000
rollbackSkipConflicts = true
irminsulSegmentSizeMiB = 128
irminsulFsyncOnBatch = false
irminsulHotActionLimit = 2000000
irminsulIndexCacheMiB = -1
```

常用配置：

| 配置 | 作用 |
| --- | --- |
| `maxQueueSize` | 内存写入队列上限，超过后进入磁盘溢写队列。 |
| `spillBatchSize` | 每批从磁盘溢写队列回放多少记录。 |
| `rollbackTickBudgetMillis` | 每 tick 留给回滚的时间预算。 |
| `rollbackMaxActionsPerTick` | 每 tick 最多应用多少条回滚动作。 |
| `irminsulHotActionLimit` | 热索引驻留上限，不是数据保留天数。 |
| `irminsulIndexCacheMiB` | 索引缓存大小，`-1` 表示自动估算。 |
| `irminsulFsyncOnBatch` | 每批写入后是否强制 fsync，更安全但更慢。 |

## 上线前需要注意

Irminsul 不是备份系统，也不是已经长期验证过的通用数据库。它现在更适合作为测试服、镜像服、灰度服上的性能验证方案。

正式使用前至少应该做这些事：

- 保留世界和 Ledger 数据的可恢复备份。
- 在真实模组包里跑长时间写入测试。
- 测试服务器异常关机后的恢复情况。
- 测试大范围查询、回滚、restore 和关服 drain。
- 观察 JVM 内存、磁盘增长和 tick 时间。

## 来源和授权

Irminsul 基于 [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger)。
Ledger 由 QuiltServerTools 贡献者维护，使用 LGPL-3.0 授权。

这个仓库保留原项目的授权、版权说明和源码开放要求。感谢 Ledger 原作者和贡献者，Irminsul 的工作建立在他们已经完成的
Fabric 记录系统之上。

Irminsul 不是上游 Ledger 的官方版本。更多授权和说明见 [NOTICE.md](NOTICE.md) 与 [LICENSE.md](LICENSE.md)。

## Ledger 基本命令

```text
/lg inspect
/lg search <args>
/lg rollback <args>
/lg page <index>
```

更完整的 Ledger 使用说明可以看上游文档：
[https://quiltservertools.github.io/Ledger/latest/](https://quiltservertools.github.io/Ledger/latest/)

---

# Irminsul English Notes

Irminsul is an experimental Ledger fork with a local database backend of the same
name. It keeps Ledger's logging, search, and rollback model, but changes the
storage path for Minecraft server workloads.

The main target is simple: reduce database pressure during heavy block updates,
redstone machines, explosions, broad searches, and rollback operations.

> This is still a test build. Do not treat it as a stable production release,
> especially without backups and a rollback plan.

## What Changed

- Added the Irminsul backend, selected with `engine = "irminsul"`.
- Kept the existing SQL/JDBC backend.
- Introduced `LedgerStore` so SQL and Irminsul share the upper runtime path.
- Added append-only local storage under `world/ledger-irminsul`.
- Added dictionary encoding for repeated Ledger strings and registry ids.
- Preserved full registry ids, including modded ids such as `modid:custom_block`.
- Added hot indexes for recent data and streamed cold scans for older data.
- Added bounded index caching based on JVM heap and host memory.
- Added disk-backed queue spill under `world/ledger-queue`.
- Added rollback pacing, per-tick limits, rollback log suppression, conflict skipping,
  and block-position dedupe.
- Reworked cold search, paging, counting, and rollback planning to stream data.

## Observed Performance

These are test observations, not guarantees.

| Case | Irminsul | Current Ledger MySQL path | Result |
| --- | ---: | ---: | --- |
| Latest 8-row page | 0.002 ms | 40.35 ms | about 19000x faster |
| Search first page over all rows | 2.72 ms | 41.55 ms | about 15x faster |
| Source-filtered search | 3.55 ms | 57.28 ms | about 16x faster |
| Hottest chunk search | 0.47 ms | 2.59 ms | about 5.5x faster |
| Hottest chunk rollback candidates | 0.30 ms | 1.86 ms | about 6.3x faster |
| Storage size | about 1.50 MB | about 4.83 MB | about 69% smaller |

The benchmark used 20588 real Ledger actions. MySQL was measured with the current
SQL/JDBC query shape. Hand-optimized MySQL queries that select candidate action ids
before joining dimension tables can close much of the gap, so these numbers should
be read as the current Ledger SQL path versus Irminsul rather than a limit of MySQL
itself.

In an extreme spill test with `maxQueueSize = 256`, about 260596 actions were
spilled to disk with 0 dropped actions. A cold search took about 1011 ms, and a
paced rollback took about 6026 ms.

Actual results depend on hardware, JVM settings, disk latency, loaded chunks,
player count, mod event volume, and rollback range.

## Recommended Use

Use this first on a test server or mirrored world. Watch write rate, query latency,
rollback behavior, memory use, and disk growth before considering any staged
production rollout.

## Attribution

Irminsul is based on [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger),
which is maintained by the QuiltServerTools contributors and licensed under
LGPL-3.0.

This fork keeps the original license, copyright notices, and source availability
requirements. Thank you to the original Ledger authors and contributors.

See [NOTICE.md](NOTICE.md), [LICENSE.md](LICENSE.md), and
[docs/irminsul.md](docs/irminsul.md) for more details.
