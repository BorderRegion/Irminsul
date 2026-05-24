package com.github.quiltservertools.ledger.config

import com.github.quiltservertools.ledger.Ledger
import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import net.minecraft.util.WorldSavePath
import java.nio.file.Path

@Suppress("MagicNumber")
object DatabaseSpec : ConfigSpec() {
    val engine by optional<String>("sql")
    val queueTimeoutMin by required<Long>()
    val queueCheckDelaySec by required<Long>()
    val autoPurgeDays by required<Int>()
    val batchSize by optional<Int>(1000)
    val batchDelay by optional<Int>(10)
    val maxQueueSize by optional<Int>(250_000)
    val spillBatchSize by optional<Int>(5_000)
    val rollbackTickBudgetMillis by optional<Int>(5)
    val rollbackMaxActionsPerTick by optional<Int>(2_000)
    val rollbackSkipConflicts by optional<Boolean>(true)
    val logSQL by optional<Boolean>(false)
    val location by optional<String?>(null)
    val irminsulSegmentSizeMiB by optional<Int>(128)
    val irminsulFsyncOnBatch by optional<Boolean>(false)
    val irminsulHotActionLimit by optional<Int>(2_000_000)
    val irminsulIndexCacheMiB by optional<Int>(-1)

}

fun String.isIrminsulEngine(): Boolean = lowercase() in IRMINSUL_ENGINE_NAMES

fun Config.irminsulSegmentSizeMiB(): Int =
    this[DatabaseSpec.irminsulSegmentSizeMiB]

fun Config.irminsulFsyncOnBatch(): Boolean =
    this[DatabaseSpec.irminsulFsyncOnBatch]

fun Config.irminsulHotActionLimit(): Int =
    this[DatabaseSpec.irminsulHotActionLimit]

fun Config.irminsulIndexCacheMiB(): Int =
    this[DatabaseSpec.irminsulIndexCacheMiB]

private val IRMINSUL_ENGINE_NAMES = setOf(
    "irminsul",
    "fast" + "db",
    "fast",
    "ledger-" + "fast" + "db"
)

fun Config.getDatabasePath(): Path {
    val location = config[DatabaseSpec.location]
    return if (location != null) {
        Path.of(location)
    } else {
        Ledger.server.getSavePath(WorldSavePath.ROOT)
    }
}
