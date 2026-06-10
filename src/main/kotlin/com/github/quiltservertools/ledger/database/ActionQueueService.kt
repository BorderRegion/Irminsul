package com.github.quiltservertools.ledger.database

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actions.AbstractActionType
import com.github.quiltservertools.ledger.actions.ActionType
import com.github.quiltservertools.ledger.actionutils.RollbackLogGuard
import com.github.quiltservertools.ledger.config.DatabaseSpec
import com.github.quiltservertools.ledger.config.getDatabasePath
import com.github.quiltservertools.ledger.logWarn
import com.github.quiltservertools.ledger.registry.ActionRegistry
import com.github.quiltservertools.ledger.utility.ticks
import com.mojang.authlib.GameProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.math.max

private const val SPILL_HEADER_BYTES = 8
private const val MAX_SPILL_STRING_BYTES = 1024 * 1024

object ActionQueueService {
    private const val FULL_QUEUE_WARNING_INTERVAL_MS = 10_000L
    private const val SPILL_MAGIC = 0x4C445153
    private const val SPILL_VERSION = 1
    private const val SPILL_FILE = "queue-spill.ldqs"
    private const val SPILL_PROCESSING_FILE = "queue-spill-processing.ldqs"
    private const val SPILL_PROCESSING_OFFSET_FILE = "queue-spill-processing.offset"

    private val queue = LinkedBlockingQueue<ActionType>()
    private lateinit var job: Job
    private lateinit var spillPath: Path
    private lateinit var spillProcessingPath: Path
    private lateinit var spillProcessingOffsetPath: Path
    private val queuedActions = AtomicInteger(0)
    private val droppedActions = AtomicLong(0)
    private val spilledActions = AtomicLong(0)
    private val lastFullQueueWarning = AtomicLong(0)
    private val spillLock = Any()

    val size: Int get() = queuedActions.get()
    val dropped: Long get() = droppedActions.get()
    val spilled: Long get() = spilledActions.get()
    val pending: Long get() = size.toLong() + if (spillPendingBytes > 0) 1L else 0L
    val spillPendingBytes: Long
        get() = computeSpillPendingBytes()

    fun start() {
        val root = Ledger.config.getDatabasePath().resolve("ledger-queue")
        root.createDirectories()
        spillPath = root.resolve(SPILL_FILE)
        spillProcessingPath = root.resolve(SPILL_PROCESSING_FILE)
        spillProcessingOffsetPath = root.resolve(SPILL_PROCESSING_OFFSET_FILE)
        job = Ledger.launch {
            drainLoop()
        }
    }

    fun addToQueue(action: ActionType): Boolean {
        if (RollbackLogGuard.isSuppressed) return false
        if (action.isBlacklisted()) return false

        if (!reserveQueueSlot()) {
            if (spillAction(action)) return true
            warnFullQueue()
            return false
        }

        queue.add(action)
        return true
    }

    private fun reserveQueueSlot(): Boolean {
        val maxQueueSize = Ledger.config[DatabaseSpec.maxQueueSize]
        while (true) {
            val current = queuedActions.get()
            if (maxQueueSize > 0 && current >= maxQueueSize) return false
            if (queuedActions.compareAndSet(current, current + 1)) return true
        }
    }

    private fun warnFullQueue() {
        val dropped = droppedActions.incrementAndGet()
        val now = System.currentTimeMillis()
        val lastWarning = lastFullQueueWarning.get()
        if (now - lastWarning > FULL_QUEUE_WARNING_INTERVAL_MS &&
            lastFullQueueWarning.compareAndSet(lastWarning, now)
        ) {
            logWarn(
                "Ledger database queue is full; dropping actions to protect server memory. " +
                "queueSize=${queue.size}, droppedActions=$dropped"
            )
        }
    }

    suspend fun drainAll() {
        if (::job.isInitialized) job.cancel()
        while (queuedActions.get() > 0 || hasSpill()) {
            drainBatch()
        }
    }

    private suspend fun drainBatch() {
        val batch = mutableListOf<ActionType>()
        val drained = queue.drainTo(batch, max(1, Ledger.config[DatabaseSpec.batchSize]))
        if (drained > 0) queuedActions.addAndGet(-drained)
        if (batch.isEmpty()) {
            replaySpillBatch()
            delay(1)
            return
        }

        DatabaseManager.logActionBatch(batch)
        replaySpillBatch()
    }

    private suspend fun drainLoop() {
        while (currentCoroutineContext().isActive) {
            if (queuedActions.get() < Ledger.config[DatabaseSpec.batchSize] && !hasSpill()) {
                delay(Ledger.config[DatabaseSpec.batchDelay].ticks)
            }
            if (queue.isNotEmpty() || hasSpill()) drainBatch()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun spillAction(action: ActionType): Boolean {
        return try {
            synchronized(spillLock) {
                val appendExisting = ::spillPath.isInitialized &&
                    spillPath.exists() &&
                    runCatching { Files.size(spillPath) >= SPILL_HEADER_BYTES }.getOrDefault(false)
                val spillOutputStream = if (appendExisting) {
                    spillPath.outputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                } else {
                    spillPath.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                DataOutputStream(BufferedOutputStream(spillOutputStream)).use { output ->
                    if (!appendExisting) {
                        output.writeInt(SPILL_MAGIC)
                        output.writeInt(SPILL_VERSION)
                    }
                    writeAction(output, action)
                }
                spilledActions.incrementAndGet()
            }
            true
        } catch (throwable: Throwable) {
            logWarn("Failed to spill Ledger action queue to disk", throwable)
            false
        }
    }

    private suspend fun replaySpillBatch() {
        if (!hasSpill()) return

        val spillBatch = synchronized(spillLock) {
            rotateSpillToProcessing()
            if (processingHasSpillRecords()) {
                readSpillBatch(
                    spillProcessingPath,
                    Ledger.config[DatabaseSpec.spillBatchSize].coerceAtLeast(1),
                    readProcessingOffset()
                )
            } else {
                SpillBatch(emptyList(), 0L, exhausted = false)
            }
        }
        if (spillBatch.actions.isEmpty()) {
            if (spillBatch.exhausted) {
                synchronized(spillLock) {
                    deleteProcessingSpill()
                }
            }
            return
        }

        DatabaseManager.logActionBatch(spillBatch.actions)

        synchronized(spillLock) {
            finishSpillBatch(spillProcessingPath, spillBatch)
        }
    }

    private fun hasSpill(): Boolean =
        ::spillPath.isInitialized && spillPath.hasSpillRecords() ||
            ::spillProcessingPath.isInitialized && processingHasSpillRecords()

    private fun computeSpillPendingBytes(): Long {
        if (!::spillPath.isInitialized || !::spillProcessingPath.isInitialized) return 0L
        val writePending = runCatching {
            (Files.size(spillPath) - SPILL_HEADER_BYTES).coerceAtLeast(0L)
        }.getOrDefault(0L)
        val processingSize = runCatching {
            Files.size(spillProcessingPath)
        }.getOrDefault(0L)
        val processingPending = (processingSize - readProcessingOffset()).coerceAtLeast(0L)
        return writePending + processingPending
    }

    private fun rotateSpillToProcessing() {
        synchronized(spillLock) {
            if (processingHasSpillRecords()) return
            if (spillProcessingPath.exists()) deleteProcessingSpill()
            if (!spillPath.hasSpillRecords()) return

            runCatching {
                Files.move(spillPath, spillProcessingPath, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(spillPath, spillProcessingPath, StandardCopyOption.REPLACE_EXISTING)
            }
            writeProcessingOffset(SPILL_HEADER_BYTES.toLong())
        }
    }

    private fun readSpillBatch(path: Path, limit: Int, startOffset: Long): SpillBatch {
        if (!path.hasSpillRecords()) return SpillBatch(emptyList(), 0L, exhausted = false)

        val actions = ArrayList<ActionType>(limit)
        val fileSize = Files.size(path)
        val normalizedStart = startOffset.coerceAtLeast(SPILL_HEADER_BYTES.toLong()).coerceAtMost(fileSize)
        var consumedBytes = normalizedStart
        try {
            val countingInput = CountingInputStream(BufferedInputStream(path.inputStream()))
            DataInputStream(countingInput).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != SPILL_MAGIC || version != SPILL_VERSION) {
                    Files.deleteIfExists(path)
                    return SpillBatch(emptyList(), 0L, exhausted = true)
                }
                val skipBytes = normalizedStart - countingInput.bytesRead
                if (skipBytes > 0) input.skipFully(skipBytes)
                consumedBytes = countingInput.bytesRead

                while (actions.size < limit) {
                    try {
                        val action = readAction(input)
                        actions.add(action)
                        consumedBytes = countingInput.bytesRead
                    } catch (_: EOFException) {
                        return SpillBatch(actions, consumedBytes, exhausted = true)
                    }
                }
            }
        } catch (_: EOFException) {
            // A partial tail can happen after a hard crash. Valid records before it are replayed.
            return SpillBatch(actions, consumedBytes, exhausted = true)
        }

        return SpillBatch(actions, consumedBytes, exhausted = consumedBytes >= fileSize)
    }

    private fun finishSpillBatch(path: Path, spillBatch: SpillBatch) {
        if (spillBatch.exhausted) {
            deleteProcessingSpill()
            return
        }

        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size <= spillBatch.consumedBytes) {
            deleteProcessingSpill()
            return
        }

        writeProcessingOffset(spillBatch.consumedBytes)
    }

    private fun processingHasSpillRecords(): Boolean =
        spillProcessingPath.hasSpillRecords(readProcessingOffset())

    private fun readProcessingOffset(): Long {
        if (!::spillProcessingOffsetPath.isInitialized || !spillProcessingOffsetPath.exists()) {
            return SPILL_HEADER_BYTES.toLong()
        }
        return runCatching {
            DataInputStream(BufferedInputStream(spillProcessingOffsetPath.inputStream())).use { input ->
                input.readLong().coerceAtLeast(SPILL_HEADER_BYTES.toLong())
            }
        }.getOrDefault(SPILL_HEADER_BYTES.toLong())
    }

    private fun writeProcessingOffset(offset: Long) {
        val temp = spillProcessingOffsetPath.resolveSibling("${spillProcessingOffsetPath.fileName}.tmp")
        DataOutputStream(
            BufferedOutputStream(
                temp.outputStream(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            )
        ).use { output ->
            output.writeLong(offset.coerceAtLeast(SPILL_HEADER_BYTES.toLong()))
        }
        runCatching {
            Files.move(
                temp,
                spillProcessingOffsetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(temp, spillProcessingOffsetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteProcessingSpill() {
        Files.deleteIfExists(spillProcessingPath)
        Files.deleteIfExists(spillProcessingOffsetPath)
    }

    private fun writeAction(output: DataOutputStream, action: ActionType) {
        output.writeUtf8(action.identifier)
        output.writeInt(action.id)
        output.writeLong(action.timestamp.epochSecond)
        output.writeInt(action.timestamp.nano)
        output.writeInt(action.pos.x)
        output.writeInt(action.pos.y)
        output.writeInt(action.pos.z)
        output.writeNullableUtf8(action.world?.toString())
        output.writeUtf8(action.objectIdentifier.toString())
        output.writeUtf8(action.oldObjectIdentifier.toString())
        output.writeNullableUtf8(action.objectState)
        output.writeNullableUtf8(action.oldObjectState)
        output.writeUtf8(action.sourceName)
        output.writeNullableUuid(action.sourceProfile?.id)
        output.writeNullableUtf8(action.sourceProfile?.name)
        output.writeNullableUtf8(action.extraData)
        output.writeBoolean(action.rolledBack)
    }

    private fun readAction(input: DataInputStream): ActionType {
        val identifier = input.readUtf8()
        val typeSupplier = ActionRegistry.getType(identifier)
        val action = typeSupplier?.get() ?: SpillActionType(identifier)
        action.id = input.readInt()
        action.timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
        action.pos = BlockPos(input.readInt(), input.readInt(), input.readInt())
        action.world = input.readNullableUtf8()?.let(Identifier::tryParse)
        action.objectIdentifier = Identifier.tryParse(input.readUtf8()) ?: Identifier.ofVanilla("air")
        action.oldObjectIdentifier = Identifier.tryParse(input.readUtf8()) ?: Identifier.ofVanilla("air")
        action.objectState = input.readNullableUtf8()
        action.oldObjectState = input.readNullableUtf8()
        action.sourceName = input.readUtf8()
        val playerId = input.readNullableUuid()
        val playerName = input.readNullableUtf8()
        action.sourceProfile = playerId?.let { GameProfile(it, playerName ?: "") }
        action.extraData = input.readNullableUtf8()
        action.rolledBack = input.readBoolean()
        return action
    }

    private class SpillActionType(
        override val identifier: String
    ) : AbstractActionType() {
        override fun getTranslationType(): String = "block"
    }

    private data class SpillBatch(
        val actions: List<ActionType>,
        val consumedBytes: Long,
        val exhausted: Boolean
    )

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead += 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count.toLong()
            return count
        }

        override fun skip(length: Long): Long {
            val skipped = super.skip(length)
            if (skipped > 0) bytesRead += skipped
            return skipped
        }
    }
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readUtf8(): String {
    val length = readInt()
    if (length < 0 || length > MAX_SPILL_STRING_BYTES) throw EOFException()
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

private fun DataInputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) throw EOFException()
        remaining -= skipped
    }
}

private fun DataOutputStream.writeNullableUtf8(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUtf8(value)
}

private fun DataInputStream.readNullableUtf8(): String? =
    if (readBoolean()) readUtf8() else null

private fun DataOutputStream.writeNullableUuid(value: UUID?) {
    writeBoolean(value != null)
    if (value != null) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }
}

private fun DataInputStream.readNullableUuid(): UUID? =
    if (readBoolean()) UUID(readLong(), readLong()) else null

private fun Path.hasSpillRecords(offset: Long = SPILL_HEADER_BYTES.toLong()): Boolean =
    exists() && runCatching { Files.size(this) > offset.coerceAtLeast(SPILL_HEADER_BYTES.toLong()) }.getOrDefault(false)
