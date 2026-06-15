package com.github.quiltservertools.ledger.actionutils

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.config.getDatabasePath
import com.github.quiltservertools.ledger.database.ActionQueueService
import com.github.quiltservertools.ledger.database.DatabaseManager
import com.github.quiltservertools.ledger.logWarn
import com.github.quiltservertools.ledger.utility.launchMain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant

object RollbackOperations {
    private const val QUEUE_FLUSH_TIMEOUT_SECONDS = 30L
    private const val OPERATION_MARKER = "ledger-rollback-operation-in-progress.txt"
    private val operationMutex = Mutex()
    private var statePersistenceFailed = false

    enum class Mode {
        ROLLBACK,
        RESTORE
    }

    suspend fun execute(
        server: MinecraftServer,
        params: ActionSearchParams,
        mode: Mode,
        dedupeBlockActions: Boolean = params.canDedupeBlockActions(),
        onSelection: suspend (RollbackExecutor.Selection) -> Unit = {}
    ): RollbackExecutor.Result =
        operationMutex.withLock {
            if (statePersistenceFailed) {
                throw IllegalStateException(
                    "A previous Ledger rollback or restore changed the world but failed to update Ledger state. " +
                        "Restart the server after inspecting the logs before running another rollback or restore."
                )
            }
            failIfOperationMarkerExists()
            awaitQueueBarrier(mode.name.lowercase())
            val selection = when (mode) {
                Mode.ROLLBACK -> selectRollbackPlan(params, dedupeBlockActions)
                Mode.RESTORE -> selectRestorePlan(params, dedupeBlockActions)
            }
            if (selection.actions.isEmpty()) return@withLock emptyResult()
            onSelection(selection)

            createOperationMarker(mode, selection)
            var persisted = false
            try {
                val worldResult = executeOnMain(server, selection, mode)
                withContext(Ledger.coroutineContext) {
                    persistSuccessfulIds(worldResult, mode)
                }
                persisted = true
                worldResult
            } finally {
                if (persisted) clearOperationMarker()
            }
        }

    suspend fun purge(params: ActionSearchParams) {
        operationMutex.withLock {
            failIfOperationMarkerExists()
            ActionQueueService.awaitPersistenceBarrier(operation = "purge")
            DatabaseManager.purgeActions(params)
        }
    }

    private suspend fun awaitQueueBarrier(operation: String) =
        ActionQueueService.awaitPersistenceBarrier(
            timeoutSeconds = QUEUE_FLUSH_TIMEOUT_SECONDS,
            operation = operation,
            failOnDroppedActions = true
        )

    private suspend fun selectRollbackPlan(
        params: ActionSearchParams,
        dedupeBlockActions: Boolean
    ): RollbackExecutor.Selection =
        if (dedupeBlockActions) {
            DatabaseManager.selectRollbackPlan(params)
        } else {
            RollbackExecutor.Selection(
                DatabaseManager.selectRollback(params),
                dedupeBlockActions = false
            )
        }

    private suspend fun selectRestorePlan(
        params: ActionSearchParams,
        dedupeBlockActions: Boolean
    ): RollbackExecutor.Selection =
        if (dedupeBlockActions) {
            DatabaseManager.selectRestorePlan(params)
        } else {
            RollbackExecutor.Selection(
                DatabaseManager.selectRestore(params),
                dedupeBlockActions = false
            )
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeOnMain(
        server: MinecraftServer,
        selection: RollbackExecutor.Selection,
        mode: Mode
    ): RollbackExecutor.Result {
        val result = CompletableDeferred<RollbackExecutor.Result>()
        launchMain({ runnable -> server.execute(runnable) }) {
            try {
                val executorResult = when (mode) {
                    Mode.ROLLBACK -> RollbackExecutor.rollbackPaced(server, selection)
                    Mode.RESTORE -> RollbackExecutor.restorePaced(server, selection)
                }
                result.complete(executorResult)
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }
        return result.await()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistSuccessfulIds(result: RollbackExecutor.Result, mode: Mode) {
        if (result.successfulActionIds.isEmpty()) return

        try {
            when (mode) {
                Mode.ROLLBACK -> DatabaseManager.rollbackActions(result.successfulActionIds)
                Mode.RESTORE -> DatabaseManager.restoreActions(result.successfulActionIds)
            }
        } catch (throwable: Throwable) {
            statePersistenceFailed = true
            logWarn("Failed to persist Ledger ${mode.name.lowercase()} state", throwable)
            throw IllegalStateException(
                "Ledger ${mode.name.lowercase()} changed the world, but failed to update rollback state.",
                throwable
            )
        }
    }

    private fun failIfOperationMarkerExists() {
        val marker = operationMarkerPath()
        if (!Files.exists(marker)) return
        val details = runCatching { Files.readString(marker) }.getOrDefault("")
        throw IllegalStateException(
            "A previous Ledger rollback or restore may have changed the world before Ledger could confirm its state. " +
                "Inspect ${marker.toAbsolutePath()} and the server logs before running another rollback or restore. " +
                details.trim()
        )
    }

    private fun createOperationMarker(mode: Mode, selection: RollbackExecutor.Selection) {
        val marker = operationMarkerPath()
        Files.createDirectories(marker.parent)
        val content = buildString {
            appendLine("mode=${mode.name}")
            appendLine("requestedActions=${selection.requestedActions}")
            appendLine("createdAt=${Instant.now()}")
            appendLine("Delete this file only after confirming the world and Ledger rollback state are consistent.")
        }
        FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun clearOperationMarker() {
        val marker = operationMarkerPath()
        try {
            Files.deleteIfExists(marker)
        } catch (throwable: Throwable) {
            logWarn("Failed to delete Ledger rollback operation marker", throwable)
            throw IllegalStateException(
                "Ledger rollback state was updated, but the operation marker could not be removed: " +
                    marker.toAbsolutePath(),
                throwable
            )
        }
    }

    private fun operationMarkerPath() =
        Ledger.config.getDatabasePath().resolve(OPERATION_MARKER)

    private fun emptyResult(): RollbackExecutor.Result =
        RollbackExecutor.Result(
            successfulActionIds = emptySet(),
            failures = emptyMap(),
            requestedActions = 0,
            appliedActions = 0,
            attemptedActions = 0
        )
}
