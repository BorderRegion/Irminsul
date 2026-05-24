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
    val fastSegmentSizeMiB by optional<Int>(128)
    val fastFsyncOnBatch by optional<Boolean>(false)
    val fastHotActionLimit by optional<Int>(2_000_000)
    val fastIndexCacheMiB by optional<Int>(-1)
}

fun Config.getDatabasePath(): Path {
    val location = config[DatabaseSpec.location]
    return if (location != null) {
        Path.of(location)
    } else {
        Ledger.server.getSavePath(WorldSavePath.ROOT)
    }
}
