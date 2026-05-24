# Irminsul

Irminsul 是一个基于 [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger)
的实验性性能分支，目标是改善大型 Fabric 服务器在高频方块更新、红石机器、爆炸测试、
大范围查询和回滚场景下的记录性能。

这个仓库不是 Ledger 上游的官方版本。原项目由 QuiltServerTools 维护，授权协议为
LGPL-3.0。本分支保留原始授权和源码开放要求，并在此基础上加入 FastDB 相关改动。感谢
Ledger 原作者和贡献者提供了完整、可用的 Fabric 方块记录基础。

## 当前状态

请把这个版本视为正在验证中的开发版本，而不是稳定发行版。

FastDB 已经通过了若干本地 SSD、单服场景下的压力测试和 MySQL 对比测试，但还没有经过足够长时间、
足够多真实玩家和足够复杂模组包的生产环境验证。极端情况下不建议直接用于主服务器，尤其是不建议在没有
完整备份、没有灰度测试、没有可回退方案的情况下替换现有 Ledger 数据库。

更详细的实现说明、配置项和测试记录见 [docs/fastdb.md](docs/fastdb.md)。

## 做了什么

这个分支主要加入了一套面向 Minecraft Ledger 工作负载的本地存储后端：

- 新增 `FastDB` 后端，可通过 `config/ledger.toml` 中的 `engine = "fastdb"` 启用。
- 保留原 SQL/JDBC 后端，可继续使用 MySQL、MariaDB 等数据库。
- 将 Ledger 的存储访问抽象为 `LedgerStore`，让 SQL 和 FastDB 可以共存。
- 使用本地 append-only 文件存储，数据默认位于世界目录下的 `ledger-fastdb`。
- 对 action id、世界 id、方块/物品/实体 id、玩家名、来源名、方块状态等做运行时字典编码，降低体积和索引成本。
- 字典保存完整字符串，`minecraft:*` 和模组注册的 `modid:*` 都会保留，不只针对原版 id。
- 对近期数据建立常驻热索引，旧数据落盘后走冷数据扫描，避免内存无限膨胀。
- 新增按 JVM heap 和主机内存自动估算的索引缓存，也可用 `fastIndexCacheMiB` 手动限制。
- 新增队列溢写机制，写入高峰超过内存队列上限时会写入 `ledger-queue`，而不是让内存继续失控增长。
- 新增回滚节流、每 tick 操作上限、回滚日志抑制、冲突跳过和按方块位置去重，减少回滚对主线程和在线玩家的影响。
- 冷数据查询、分页、计数和回滚规划改为流式处理，降低大范围查询时的峰值内存压力。
- `ledger status` 和关服 drain 会把磁盘溢写队列也算入待处理工作。

## 当前测试结果

以下数据来自同一台远程测试服务器上的阶段性测试，只能说明当前测试环境下的表现，不代表所有机器、
所有存档和所有模组包都会得到同样结果。

在一次 FastDB 与 MySQL 的对比中，观察到：

- 写入队列 drain：FastDB 约 1.0 秒，MySQL 约 35.1 秒，约 30 倍提升。
- 存储占用：FastDB 约 20.0 MB，MySQL 约 68.7 MB，减少约 70%，约为 MySQL 的三分之一。
- 回滚范围测试：FastDB 约 1.0 秒，MySQL 约 2.0 秒，约 2 倍提升。

在后续极端队列和冷数据测试中，使用 `maxQueueSize = 256`、`spillBatchSize = 512`、
`fastHotActionLimit = 10000`，观察到：

- 高峰写入期间约 260596 条记录进入磁盘溢写队列，丢弃数为 0。
- 重启后加载到 10000 条热数据，约 307535 条冷数据保留在磁盘。
- 冷数据搜索约 1011 ms。
- 带节流的回滚约 6026 ms；这是有意用更长墙钟时间换取更低的单 tick 压力。
- 测试结束后内存队列为 0，溢写队列清空，丢弃数为 0。

这些数字目前只能作为方向性参考。真实服务器里，硬盘、CPU、JVM 参数、区块加载情况、在线人数、
红石机器行为、模组事件量和回滚范围都会影响结果。

## 什么时候不该用

以下情况不建议直接使用：

- 没有世界备份，或备份没有做过恢复演练。
- 服务器已经是正式主服，且不能接受停服排查或回退。
- 模组包很复杂，但还没有用真实方块、实体和玩家行为做过长时间测试。
- 需要严格的数据可靠性保证，且不能接受实验性 append-only 存储的风险。
- 希望拿它替代备份、CoreProtect/Ledger 历史归档或其他运维流程。

比较合适的使用方式是先在测试服或镜像服跑一段时间，观察写入量、查询速度、回滚行为、内存曲线和磁盘增长，
确认符合预期后再考虑灰度上线。

## 启用 FastDB

在 `config/ledger.toml` 中配置：

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

几个需要注意的点：

- `maxQueueSize` 控制内存中的待写入队列上限，超过后会尝试写入磁盘溢写队列。
- `fastHotActionLimit` 是热索引驻留上限，不是数据保留天数，也不会删除旧记录。
- `fastIndexCacheMiB = -1` 表示根据 JVM heap 和主机内存自动估算缓存大小。
- `fastFsyncOnBatch = true` 会更偏向安全，但写入性能会下降。
- 无论是否启用 FastDB，都应该保留正常的世界备份和恢复流程。

## 上游 Ledger 使用说明

Ledger 是 Fabric 上的世界变更记录工具。更完整的原版使用文档可以参考
[Ledger wiki](https://quiltservertools.github.io/Ledger/latest/)。

安装时将本 mod 与 Fabric API、fabric-language-kotlin 一起放入 `mods` 目录。第一次启动服务器后，
Ledger 会自动生成 `config/ledger.toml`。

常用命令：

```text
/lg inspect
/lg search <args>
/lg rollback <args>
/lg page <index>
```

所有 Ledger 命令都支持 LuckPerms。未安装权限插件时，默认回退到权限等级 3。

## 授权与致谢

Irminsul 是 [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger)
的实验性性能分支。Ledger 是由 QuiltServerTools 贡献者维护的 Fabric 世界变更记录工具。

原项目使用 LGPL-3.0 授权。本分支保留原授权、原版权说明和源码开放要求。感谢 Ledger
原作者和贡献者，本项目的工作建立在他们已经完成的基础之上。

更多信息见 [NOTICE.md](NOTICE.md) 和 [LICENSE.md](LICENSE.md)。

---

# Irminsul English Notes

Irminsul is an experimental performance fork of
[QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger). It focuses
on high-write Minecraft server workloads: redstone machines, large block update
bursts, explosions, broad searches, and rollback operations.

This is not an official upstream Ledger release. Treat it as a development build
that still needs more real-world validation.

## Status

FastDB has passed several SSD-based single-server stress tests and MySQL
comparison tests, but it has not yet been proven by long-running production use
with many players and large modpacks.

Do not deploy it directly on an important production server without backups,
recovery drills, and a rollback plan.

## Main Changes

- Added a local `FastDB` backend selected with `engine = "fastdb"`.
- Kept the existing SQL/JDBC backend available.
- Added a `LedgerStore` abstraction so SQL and FastDB can share the runtime path.
- Added append-only local storage under `world/ledger-fastdb`.
- Added runtime dictionary encoding for action ids, registry ids, worlds, sources,
  players, and block states.
- Preserved full registry strings, so modded ids such as `modid:custom_block` are
  not collapsed into a vanilla-only mapping.
- Added resident hot indexes and streamed cold-data scans to control memory growth.
- Added auto-sized and manually bounded index caching.
- Added disk-backed queue spill under `world/ledger-queue` for extreme write bursts.
- Added rollback pacing, per-tick action limits, rollback log suppression, conflict
  skipping, and block-position dedupe.
- Added streamed cold query/count/page and rollback planning paths.

## Observed Performance

These numbers are observations from the current test environment, not guarantees.

FastDB vs MySQL on the same remote test server:

- Queue drain after write burst: about 1.0 s vs about 35.1 s, roughly 30x faster.
- Storage size: about 20.0 MB vs about 68.7 MB, roughly 70% smaller.
- Rollback range test: about 1.0 s vs about 2.0 s, roughly 2x faster.

Extreme spill and cold-history test:

- About 260596 actions spilled to disk during a burst, with 0 dropped actions.
- Restart loaded 10000 resident hot actions and kept about 307535 cold actions on disk.
- Cold search took about 1011 ms.
- Paced rollback took about 6026 ms, intentionally trading wall-clock time for lower
  per-tick pressure.
- Final memory queue and spill queue were both drained.

Actual results depend on hardware, JVM settings, disk latency, loaded chunks,
player count, mod event volume, and rollback range.

## Recommended Use

Use this first on a test server or mirrored world. Watch write rate, query latency,
rollback behavior, memory use, and disk growth before considering a staged
production rollout.

More implementation notes and validation details are available in
[docs/fastdb.md](docs/fastdb.md).

## License and Attribution

Irminsul is an experimental performance fork of
[QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger), a Fabric
world-change logging mod maintained by the QuiltServerTools contributors.

The original project is licensed under LGPL-3.0. This fork keeps the original
license, copyright notices, and source availability requirements. We thank the
original Ledger authors and contributors for the project this work is built on.

See [NOTICE.md](NOTICE.md) and [LICENSE.md](LICENSE.md) for details.
