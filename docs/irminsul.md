# Irminsul Backend

This branch adds a local append-only Ledger storage engine for high-write Minecraft
servers. It is aimed at single-server deployments on local SSDs where Ledger's
workload is mostly append-heavy logging, bounded recent-history queries, and
rollback/restore operations.

## Scope

Irminsul is selected with:

```toml
[database]
engine = "irminsul"
```

The original SQL/JDBC backend is still available with `engine = "sql"`.

## Main Changes From Upstream Ledger

- Added `LedgerStore`, `SqlLedgerStore`, and `IrminsulLedgerStore` so the runtime can
  switch between SQL and Irminsul.
- Added a local append-only Irminsul store under `world/ledger-irminsul`.
- Added runtime string dictionary encoding for action ids, world ids, object ids,
  block states, source names, and player names. Full registry strings are stored,
  so modded ids such as `modid:custom_block` are preserved.
- Left `extraData` as raw UTF-8 because it is often unique and dictionary encoding
  it increases memory and metadata churn.
- Added resident hot indexes for recent actions and cold scans for older actions.
  `irminsulHotActionLimit` controls only memory residency; older actions remain on disk.
- Added bounded BitSet index caching sized from JVM heap and host memory, with an
  optional `irminsulIndexCacheMiB` override.
- Added on-disk queue spill under `world/ledger-queue` when `maxQueueSize` is hit.
  This prevents the async action queue from growing without bound under extreme
  write bursts.
- Added `spillBatchSize` to bound how many spilled actions are replayed per database
  batch.
- Added rollback/restore pacing with `rollbackTickBudgetMillis` and
  `rollbackMaxActionsPerTick`.
- Added rollback log suppression so rollback itself does not write a second large
  Ledger workload.
- Added rollback conflict skipping (`rollbackSkipConflicts`) to avoid overwriting
  blocks changed after the selected history.
- Added block-position dedupe for rollback/restore selection.
- Added stream-oriented Irminsul cold query/count/page paths.
- Added stream-oriented Irminsul rollback planning, reducing peak heap use when
  selecting large cold-history rollbacks.
- Updated `ledger status` and shutdown draining so on-disk spill is treated as
  pending queue work.

## Important Configuration

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

Notes:

- `maxQueueSize` bounds in-memory queued actions. Overflow goes to disk instead of
  being dropped unless disk spill itself fails.
- `irminsulHotActionLimit` is a resident index limit, not a retention limit.
- `irminsulIndexCacheMiB = -1` auto-sizes the BitSet cache from heap and host memory.
- `irminsulFsyncOnBatch = true` is safer but slower.
- For real production servers, keep a normal world backup strategy. Irminsul is
  append-only but is not a replacement for backups.

## Current Validation Results

Built on remote host:

```text
/home/ubuntu/ledger-irminsul-dev/Ledger
./gradlew compileKotlin
./gradlew remapJar
```

Current jar:

```text
C:\Users\Borde\Desktop\irminsul\artifacts\ledger-1.3.5+local-irminsul.jar
SHA256: 3E6934053CBB25CDD943E795A354CE7508601F8F03D4B9247C4FA0805519F4F9
```

Extreme queue/cold-history validation:

```text
script: /home/ubuntu/ledger-irminsul-dev/validate_remote_spill_cold.sh
run:    /home/ubuntu/ledger-irminsul-dev/spill-cold-run-20260525-000107
config: maxQueueSize=256, spillBatchSize=512, irminsulHotActionLimit=10000
```

Observed:

```text
spill after write burst: 260596 spilled, 0 dropped
restart load: residentActions=10000, coldActions=307535, nextId=317536
cold search: 1011 ms
rollback: 6026 ms
rollback result: 12168/121680 selected actions after block-position dedupe
final queue: memory=0, spillBytes=0, dropped=0
irminsul size: 20029334 bytes
```

The only error-like log line in this run was a Starlight class-loading warning:

```text
Error loading class: ca/spottedleaf/starlight/common/thread/SchedulingUtil
```

Earlier Irminsul/MySQL comparison on the same remote test server showed:

```text
Irminsul queue drain after fill: about 1.0 s
MySQL queue drain after fill:  about 35.1 s
Irminsul rollback range test:  about 1.0 s without heavy pacing
MySQL rollback range test:     about 2.0 s
Irminsul storage:              about 20.0 MB
MySQL storage:                 about 68.7 MB
```

This means the measured improvement is roughly:

- Write drain: about 30x faster in the tested burst workload.
- Storage: about 70% smaller, or about 3.4x less space.
- Rollback command selection/application: about 2x faster in the direct comparison,
  with later paced rollback intentionally trading wall-clock time for server tick
  stability.
- Extreme queue overload: 0 dropped actions in the tested spill workload.

## Production Readiness

This branch is suitable for pre-production and gray-box server testing. Before
long-term production use, run:

- hard-kill tests during normal write, spill replay, and rollback state updates;
- million-to-ten-million record tests;
- 24-72 hour multi-player or scripted write tests;
- modded registry id tests with real mod blocks/entities/items;
- auto-purge and rewrite tests on large data;
- backup/restore and rollback-to-SQL operational drills.

## Development Artifacts

Local artifacts are under:

```text
C:\Users\Borde\Desktop\irminsul\artifacts
```

Useful scripts:

```text
validate_remote_spill_cold.sh
validate_remote_paced_rollback.sh
run_remote_ledger_perf_hotcap.sh
run_remote_ledger_perf.sh
```
