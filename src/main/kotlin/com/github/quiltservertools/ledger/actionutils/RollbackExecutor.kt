package com.github.quiltservertools.ledger.actionutils

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actions.ActionType
import com.github.quiltservertools.ledger.actions.BlockChangeActionType
import com.github.quiltservertools.ledger.config.DatabaseSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.logWarn
import com.github.quiltservertools.ledger.utility.getWorld
import com.github.quiltservertools.ledger.utility.ticks
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import kotlin.math.max

object RollbackExecutor {
    private const val MAX_FAILURE_LOGS = 10
    private val pacedExecutionMutex = Mutex()

    data class Selection(
        val actions: List<ActionType>,
        val actionIdsByRepresentative: Map<Int, List<Int>> = emptyMap(),
        val expectedActionByRepresentative: Map<Int, ActionType> = emptyMap(),
        val requestedActions: Int = actions.size
    )

    data class Result(
        val successfulActionIds: Set<Int>,
        val failures: Map<String, Int>,
        val requestedActions: Int,
        val appliedActions: Int
    )

    fun rollback(server: MinecraftServer, actions: List<ActionType>): Result =
        execute(server, Selection(actions), Mode.ROLLBACK)

    fun rollback(server: MinecraftServer, selection: Selection): Result =
        execute(server, selection, Mode.ROLLBACK)

    fun restore(server: MinecraftServer, actions: List<ActionType>): Result =
        execute(server, Selection(actions), Mode.RESTORE)

    fun restore(server: MinecraftServer, selection: Selection): Result =
        execute(server, selection, Mode.RESTORE)

    suspend fun rollbackPaced(server: MinecraftServer, selection: Selection): Result {
        pacedExecutionMutex.lock()
        return try {
            executePaced(server, selection, Mode.ROLLBACK)
        } finally {
            pacedExecutionMutex.unlock()
        }
    }

    suspend fun restorePaced(server: MinecraftServer, selection: Selection): Result {
        pacedExecutionMutex.lock()
        return try {
            executePaced(server, selection, Mode.RESTORE)
        } finally {
            pacedExecutionMutex.unlock()
        }
    }

    private fun execute(server: MinecraftServer, selection: Selection, mode: Mode): Result {
        val prepared = prepare(selection)
        return executePrepared(server, prepared, mode)
    }

    private suspend fun executePaced(server: MinecraftServer, selection: Selection, mode: Mode): Result {
        val prepared = prepare(selection)
        val pacing = RollbackPacing.fromConfig()
        val successfulActionIds = HashSet<Int>()
        val failures = HashMap<String, Int>()
        var appliedActions = 0
        var scannedActionsThisTick = 0
        var tickStartedNanos = System.nanoTime()
        var loggedFailures = 0

        for (action in prepared.actions) {
            if (prepared.shouldSkip(action)) {
                scannedActionsThisTick += 1
                if (pacing.shouldYield(scannedActionsThisTick, tickStartedNanos)) {
                    delay(1.ticks)
                    scannedActionsThisTick = 0
                    tickStartedNanos = System.nanoTime()
                }
                continue
            }

            val ok = runAction(
                server,
                action,
                mode,
                loggedFailures < MAX_FAILURE_LOGS,
                prepared.expectedActionFor(action)
            )
            if (!ok && loggedFailures < MAX_FAILURE_LOGS) loggedFailures += 1

            scannedActionsThisTick += 1
            appliedActions += 1
            if (ok) {
                successfulActionIds.addAll(prepared.successfulIdsFor(action))
            } else {
                failures[action.identifier] = failures.getOrPut(action.identifier) { 0 } + 1
            }

            if (pacing.shouldYield(scannedActionsThisTick, tickStartedNanos)) {
                delay(1.ticks)
                scannedActionsThisTick = 0
                tickStartedNanos = System.nanoTime()
            }
        }

        return Result(
            successfulActionIds = successfulActionIds,
            failures = failures,
            requestedActions = prepared.requestedActions,
            appliedActions = appliedActions
        )
    }

    private fun prepare(selection: Selection): PreparedSelection {
        if (selection.actionIdsByRepresentative.isNotEmpty()) {
            return PreparedSelection(
                actions = selection.actions,
            actionIdsByRepresentative = selection.actionIdsByRepresentative
                .mapValues { it.value.toList() },
            expectedActionByRepresentative = selection.expectedActionByRepresentative
                .mapNotNull { (id, action) -> (action as? BlockChangeActionType)?.let { id to it } }
                .toMap(),
            requestedActions = selection.requestedActions
        )
    }

        val actions = selection.actions
        val blockGroups = LinkedHashMap<BlockKey, BlockActionGroup>()
        val unsafeBlockKeys = HashSet<BlockKey>()

        actions.forEach { action ->
            val key = BlockKey(action.world, action.pos)
            if (action !is BlockChangeActionType) {
                unsafeBlockKeys.add(key)
            }
        }

        actions.forEach { action ->
            if (action is BlockChangeActionType) {
                val key = BlockKey(action.world, action.pos)
                if (!unsafeBlockKeys.contains(key)) {
                    blockGroups.computeIfAbsent(key) { BlockActionGroup() }.add(action)
                }
            }
        }

        return PreparedSelection(
            actions = actions,
            actionIdsByRepresentative = blockGroups.values.associate {
                it.representative.id to it.actionIds.toList()
            },
            expectedActionByRepresentative = blockGroups.values.associate {
                it.representative.id to it.expectedCurrent
            },
            dedupedBlockKeys = blockGroups.keys,
            requestedActions = selection.requestedActions
        )
    }

    private fun executePrepared(server: MinecraftServer, prepared: PreparedSelection, mode: Mode): Result {
        val successfulActionIds = HashSet<Int>()
        val failures = HashMap<String, Int>()
        var appliedActions = 0
        var loggedFailures = 0

        for (action in prepared.actions) {
            if (prepared.shouldSkip(action)) continue

            val ok = runAction(
                server,
                action,
                mode,
                loggedFailures < MAX_FAILURE_LOGS,
                prepared.expectedActionFor(action)
            )
            if (!ok && loggedFailures < MAX_FAILURE_LOGS) loggedFailures += 1
            appliedActions += 1
            if (ok) {
                successfulActionIds.addAll(prepared.successfulIdsFor(action))
            } else {
                failures[action.identifier] = failures.getOrPut(action.identifier) { 0 } + 1
            }
        }

        return Result(
            successfulActionIds = successfulActionIds,
            failures = failures,
            requestedActions = prepared.requestedActions,
            appliedActions = appliedActions
        )
    }

    private fun runAction(
        server: MinecraftServer,
        action: ActionType,
        mode: Mode,
        logFailure: Boolean,
        expectedCurrent: BlockChangeActionType?
    ): Boolean {
        return try {
            RollbackLogGuard.withSuppressedLogging {
                if (expectedCurrent != null) {
                    if (hasConflict(server, expectedCurrent, mode)) {
                        false
                    } else RollbackLogGuard.withSuppressedConflictCheck {
                        applyAction(server, action, mode)
                    }
                } else {
                    applyAction(server, action, mode)
                }
            }
        } catch (throwable: Throwable) {
            if (logFailure) {
                logWarn("Failed to apply Ledger ${mode.name.lowercase()} action ${action.id}", throwable)
            }
            false
        }
    }

    private fun applyAction(server: MinecraftServer, action: ActionType, mode: Mode): Boolean =
        when (mode) {
            Mode.ROLLBACK -> action.rollback(server)
            Mode.RESTORE -> action.restore(server)
        }

    private fun hasConflict(server: MinecraftServer, expectedCurrent: BlockChangeActionType, mode: Mode): Boolean {
        if (!config[DatabaseSpec.rollbackSkipConflicts]) return false
        val world = server.getWorld(expectedCurrent.world) ?: return true
        val expectedState = when (mode) {
            Mode.ROLLBACK -> expectedCurrent.newBlockState()
            Mode.RESTORE -> expectedCurrent.oldBlockState()
        }
        return world.getBlockState(expectedCurrent.pos) != expectedState
    }

    private enum class Mode {
        ROLLBACK,
        RESTORE
    }

    private data class BlockKey(val world: Identifier?, val pos: BlockPos)

    private data class PreparedSelection(
        val actions: List<ActionType>,
        val actionIdsByRepresentative: Map<Int, List<Int>>,
        val expectedActionByRepresentative: Map<Int, BlockChangeActionType> = emptyMap(),
        val dedupedBlockKeys: Set<BlockKey> = emptySet(),
        val requestedActions: Int
    ) {
        fun shouldSkip(action: ActionType): Boolean =
            action is BlockChangeActionType &&
                    BlockKey(action.world, action.pos) in dedupedBlockKeys &&
                    action.id !in actionIdsByRepresentative

        fun successfulIdsFor(action: ActionType): List<Int> =
            actionIdsByRepresentative[action.id] ?: listOf(action.id)

        fun expectedActionFor(action: ActionType): BlockChangeActionType? =
            expectedActionByRepresentative[action.id]
    }

    private data class RollbackPacing(
        val tickBudgetNanos: Long,
        val maxActionsPerTick: Int
    ) {
        fun shouldYield(actionsThisTick: Int, tickStartedNanos: Long): Boolean =
            actionsThisTick >= maxActionsPerTick || System.nanoTime() - tickStartedNanos >= tickBudgetNanos

        companion object {
            fun fromConfig(): RollbackPacing {
                val budgetMillis = Ledger.config[DatabaseSpec.rollbackTickBudgetMillis]
                val maxActions = Ledger.config[DatabaseSpec.rollbackMaxActionsPerTick]
                val budgetNanos = if (budgetMillis <= 0) Long.MAX_VALUE else budgetMillis * 1_000_000L
                val actionsPerTick = if (maxActions <= 0) Int.MAX_VALUE else max(1, maxActions)

                return RollbackPacing(budgetNanos, actionsPerTick)
            }
        }
    }

    private class BlockActionGroup {
        val actionIds = ArrayList<Int>()
        lateinit var representative: ActionType
        lateinit var expectedCurrent: BlockChangeActionType

        fun add(action: ActionType) {
            if (actionIds.isEmpty() && action is BlockChangeActionType) {
                expectedCurrent = action
            }
            actionIds.add(action.id)
            representative = action
        }
    }
}
