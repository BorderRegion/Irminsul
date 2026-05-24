# Irminsul

Irminsul 是 Ledger 的一个实验分支。它保留 Ledger 原本的记录、查询和回滚能力，额外加入了
FastDB：一个为 Minecraft 服务器负载写的本地数据库后端。

这个项目的目标很直接：在红石机器、爆炸、大量方块更新和大范围回滚时，尽量减少数据库拖慢服务器的情况。

> 目前仍是测试版本。不要把它当成稳定版直接扔进主服，尤其不要在没有备份和回退方案的情况下使用。

## 一句话说明

Ledger 默认适合通用数据库。Irminsul 里的 FastDB 则更偏向 Minecraft 服务器的实际负载：大量追加写入、
按时间和位置查询、短时间内处理很多相似记录，以及尽可能平稳地执行回滚。

它牺牲了一些通用数据库能力，换取更低的写入开销、更小的数据体积，以及更可控的内存和回滚压力。

## 当前测试结果

下面的数据来自同一台远程测试机，只能代表这轮测试里的表现。不同硬件、JVM 参数、模组包、在线人数和区块加载情况，
都会让结果变化。

| 项目 | FastDB | MySQL | 观察结果 |
| --- | ---: | ---: | --- |
| 写入高峰后的队列 drain | 约 1.0 s | 约 35.1 s | 约 30 倍更快 |
| 数据占用 | 约 20.0 MB | 约 68.7 MB | 约少 70% |
| 回滚范围测试 | 约 1.0 s | 约 2.0 s | 约 2 倍更快 |

极端队列测试中，我把 `maxQueueSize` 压到 256，强制触发磁盘溢写：

| 项目 | 结果 |
| --- | ---: |
| 溢写到磁盘的记录 | 260596 |
| 丢弃记录 | 0 |
| 重启后的热数据 | 10000 |
| 重启后的冷数据 | 307535 |
| 冷数据搜索 | 约 1011 ms |
| 带节流的回滚 | 约 6026 ms |

带节流的回滚看起来墙钟时间更长，这是有意的。它的目的不是把所有改动一次性压到主线程里，而是把压力分散到多个
tick，尽量减少回滚时把在线玩家一起卡住。

## 改了什么

主要改动集中在数据库和回滚路径：

- 新增 FastDB 后端，通过 `engine = "fastdb"` 启用。
- 原 SQL/JDBC 后端仍然保留，可以继续使用 MySQL、MariaDB 等数据库。
- 把存储层抽成 `LedgerStore`，让 SQL 和 FastDB 走同一套上层逻辑。
- FastDB 使用本地 append-only 文件，默认数据目录是世界目录下的 `ledger-fastdb`。
- 对 action、世界、来源、玩家、方块状态、注册 id 等做字典编码，减少重复字符串带来的空间和索引开销。
- 字典保存完整注册名，不只处理 `minecraft:*`，也会保留模组的 `modid:*`。
- 近期数据保留热索引，旧数据留在磁盘上按需扫描，避免历史记录越多内存越失控。
- 索引缓存会根据 JVM heap 和主机内存估算，也可以用 `fastIndexCacheMiB` 手动限制。
- 写入队列达到上限后会落到 `ledger-queue`，防止极端写入时内存队列无限增长。
- 回滚加入每 tick 限额、时间预算、日志抑制、冲突跳过和同位置去重。
- 冷数据查询、分页、计数和回滚规划改成流式处理，降低大范围操作时的峰值内存。
- `ledger status` 和关服 drain 会把磁盘溢写队列也算进去。

## 什么时候适合试

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

## 启用方式

在 `config/ledger.toml` 中设置：

```toml
[database]
engine = "fastdb"
batchSize = 1000
batchDelay = 10
maxQueueSize = 250000
spillBatchSize = 5000
rollbackTickBudgetMillis = 5
rollbackMaxActionsPerTick = 2000
rollbackSkipConflicts = true
fastSegmentSizeMiB = 128
fastFsyncOnBatch = false
fastHotActionLimit = 2000000
fastIndexCacheMiB = -1
```

几个配置含义：

| 配置 | 作用 |
| --- | --- |
| `maxQueueSize` | 内存写入队列上限，超过后进入磁盘溢写队列。 |
| `spillBatchSize` | 每批从磁盘溢写队列回放多少记录。 |
| `rollbackTickBudgetMillis` | 每 tick 留给回滚的时间预算。 |
| `rollbackMaxActionsPerTick` | 每 tick 最多应用多少条回滚动作。 |
| `fastHotActionLimit` | 热索引驻留上限，不是数据保留天数。 |
| `fastIndexCacheMiB` | 索引缓存大小，`-1` 表示自动估算。 |
| `fastFsyncOnBatch` | 每批写入后是否强制 fsync，更安全但更慢。 |

## 风险说明

FastDB 不是备份系统，也不是已经长期验证过的通用数据库。它现在更适合作为测试服、镜像服、灰度服上的性能验证方案。

正式使用前至少应该做这些事：

- 保留世界和 Ledger 数据的可恢复备份。
- 在真实模组包里跑长时间写入测试。
- 测试服务器异常关机后的恢复情况。
- 测试大范围查询、回滚、restore 和关服 drain。
- 观察 JVM 内存、磁盘增长和 tick 时间。

## 与上游 Ledger 的关系

Irminsul 基于 [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger)。
Ledger 由 QuiltServerTools 贡献者维护，使用 LGPL-3.0 授权。

这个仓库保留原项目的授权、版权说明和源码开放要求。感谢 Ledger 原作者和贡献者，FastDB 的工作建立在他们已经完成的
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

Irminsul is an experimental Ledger fork with a new local database backend called
FastDB. It keeps Ledger's logging, search, and rollback model, but changes the
storage path for Minecraft server workloads.

The main target is simple: reduce database pressure during heavy block updates,
redstone machines, explosions, broad searches, and rollback operations.

> This is still a test build. Do not treat it as a stable production release,
> especially without backups and a rollback plan.

## What Changed

- Added the FastDB backend, selected with `engine = "fastdb"`.
- Kept the existing SQL/JDBC backend.
- Introduced `LedgerStore` so SQL and FastDB share the upper runtime path.
- Added append-only local storage under `world/ledger-fastdb`.
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

| Case | FastDB | MySQL | Result |
| --- | ---: | ---: | --- |
| Queue drain after burst | about 1.0 s | about 35.1 s | about 30x faster |
| Storage size | about 20.0 MB | about 68.7 MB | about 70% smaller |
| Rollback range test | about 1.0 s | about 2.0 s | about 2x faster |

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
[docs/fastdb.md](docs/fastdb.md) for more details.
