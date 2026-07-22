@file:Suppress("MagicNumber")

package com.github.quiltservertools.ledger.database.irminsul

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actions.ActionType
import com.github.quiltservertools.ledger.actions.BlockChangeActionType
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.Preview
import com.github.quiltservertools.ledger.actionutils.RollbackExecutor
import com.github.quiltservertools.ledger.actionutils.SearchResults
import com.github.quiltservertools.ledger.config.DatabaseSpec
import com.github.quiltservertools.ledger.config.SearchSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.config.getDatabasePath
import com.github.quiltservertools.ledger.config.irminsulFsyncOnBatch
import com.github.quiltservertools.ledger.config.irminsulHotActionLimit
import com.github.quiltservertools.ledger.config.irminsulIndexCacheMiB
import com.github.quiltservertools.ledger.config.irminsulSegmentSizeMiB
import com.github.quiltservertools.ledger.database.LedgerStore
import com.github.quiltservertools.ledger.logInfo
import com.github.quiltservertools.ledger.logWarn
import com.github.quiltservertools.ledger.registry.ActionRegistry
import com.github.quiltservertools.ledger.utility.Negatable
import com.github.quiltservertools.ledger.utility.PlayerResult
import com.mojang.authlib.GameProfile
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Arrays
import java.util.BitSet
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.math.max
import kotlin.streams.toList

private const val ACTION_MAGIC = 0x4C464441
private const val STATE_MAGIC = 0x4C464453
private const val FORMAT_VERSION = 1
private const val ACTION_RECORD = 1
private const val STRING_DICTIONARY_RECORD = 2
private const val ACTION_RECORD_V2 = 3
private const val STATE_ROLLBACK_RECORD = 1
private const val STATE_PLAYER_RECORD = 2
private const val STATE_ROLLBACK_RANGE_RECORD = 3
private const val MAX_RECORD_BYTES = 16 * 1024 * 1024
private const val MAX_STRING_BYTES = 16 * 1024 * 1024
private const val ACTION_FILE_HEADER_BYTES = 8L
private const val SEGMENT_PREFIX = "actions-"
private const val SEGMENT_SUFFIX = ".lfda"
private const val STATE_FILE = "state.lfds"
private const val MIB_BYTES = 1024L * 1024L
private const val ACTION_RECORD_V3 = 4
private const val ACTION_RECORD_V4 = 5
private const val ADAPTIVE_NULL = 0
private const val ADAPTIVE_DICTIONARY = 1
private const val ADAPTIVE_INLINE = 2
private const val MIN_HOT_ACTION_LIMIT = 10_000
private const val MIN_HOT_TRIM_OVERFLOW = 10_000
private const val LEGACY_DATA_DIR = "ledger-" + "fast" + "db"
private const val MAX_STATE_DICTIONARY_CHARS = 512
private const val STATE_DICTIONARY_PROBATION_SIZE = 16_384
private const val REVERSE_CHECKPOINT_STRIDE = 256
private const val CHUNK_BLOOM_BITS = 1 shl 18
private const val MAX_CHUNK_BLOOM_PROBES = 4096L
private const val MAX_BLOCK_CHUNK_PROBES = 512L
private const val PURGE_STAGE_SUFFIX = ".purge-new"
private const val PURGE_BACKUP_SUFFIX = ".purge-backup"
private const val PURGE_READY_FILE = ".purge-ready"

class IrminsulLedgerStore : LedgerStore {
    override val databaseType: String = "irminsul"

    private lateinit var root: Path
    private lateinit var actionsDir: Path
    private lateinit var statePath: Path
    private lateinit var writer: SegmentWriter
    private lateinit var stateWriter: StateWriter
    private lateinit var bitSetCache: BitSetCachePolicy
    private var hotActionLimit = 0
    private var coldActionsOnDisk = 0
    private var nextId = 1
    private var currentSegment = 0
    private val lock = Any()

    private val actionsById = DenseActionTable()
    private val orderedIds = IntRingBuffer()
    private val rolledBack = BitSet()
    private val players = HashMap<UUID, PlayerResult>()
    private val playerIdsByName = HashMap<String, MutableSet<UUID>>()
    private val knownSources = ConcurrentHashMap.newKeySet<String>()
    private val stringDictionary = HashMap<Int, String>()
    private val stringIds = HashMap<String, Int>()
    private val pendingStrings = ArrayList<Pair<Int, String>>()
    private val stateStringAdmission = StringAdmissionPolicy()
    private val retainedStringIds = BitSet()
    private val dictionaryLocations = DictionaryLocations()
    private val blockActionTypes = HashMap<String, Boolean>()
    private val startupRolledBackIds = HashSet<Int>()
    private var nextStringId = 1

    private val idsByWorld = HashMap<Identifier, IntList>()
    private val idsByAction = HashMap<String, IntList>()
    private val idsByObject = HashMap<Identifier, IntList>()
    private val idsByOldObject = HashMap<Identifier, IntList>()
    private val idsBySource = HashMap<String, IntList>()
    private val idsByPlayer = HashMap<UUID, IntList>()
    private val idsByChunk = HashMap<LocationKey, IntList>()
    private val segmentSummaries = HashMap<Int, SegmentSummary>()
    private val segmentReverseIndexes = HashMap<Int, SegmentReverseIndex>()

    override fun setup() {
        synchronized(lock) {
            root = resolveRoot()
            actionsDir = root.resolve("actions")
            statePath = root.resolve(STATE_FILE)
            hotActionLimit = config.irminsulHotActionLimit().coerceAtLeast(MIN_HOT_ACTION_LIMIT)
            actionsDir.createDirectories()
            loadActions()
            loadState()
            applyStartupRollbackState()
            bitSetCache = BitSetCachePolicy(Runtime.getRuntime().maxMemory(), physicalMemoryBytes())
            openWriters()
            logInfo(
                "Irminsul ready. residentActions=${actionsById.size}, coldActions=$coldActionsOnDisk, " +
                        "nextId=$nextId, segments=${segmentFiles().size}, path=$root"
            )
            logMemoryProfile()
        }
    }

    override fun ensureTables() = Unit
    override suspend fun setupCache() = Unit

    private fun resolveRoot(): Path {
        val databasePath = config.getDatabasePath()
        val root = databasePath.resolve("ledger-irminsul")
        val legacyRoot = databasePath.resolve(LEGACY_DATA_DIR)
        recoverInterruptedPurge(root)
        recoverInterruptedPurge(legacyRoot)
        return if (!root.exists() && legacyRoot.exists()) {
            logWarn("Using legacy data directory for Irminsul. New worlds use ledger-irminsul.")
            legacyRoot
        } else {
            root
        }
    }

    private fun recoverInterruptedPurge(targetRoot: Path) {
        val stageRoot = targetRoot.resolveSibling("${targetRoot.fileName}$PURGE_STAGE_SUFFIX")
        val backupRoot = targetRoot.resolveSibling("${targetRoot.fileName}$PURGE_BACKUP_SUFFIX")
        val stagedRewriteMarked = stageRoot.resolve(PURGE_READY_FILE).exists()

        if (!targetRoot.exists()) {
            when {
                stagedRewriteMarked && validatePurgeRoot(stageRoot) -> {
                    movePath(stageRoot, targetRoot)
                    finalizeRecoveredPurge(targetRoot, backupRoot)
                }
                backupRoot.exists() -> {
                    if (stagedRewriteMarked) {
                        logWarn("Discarding an invalid interrupted Irminsul purge rewrite at $stageRoot")
                    }
                    movePath(backupRoot, targetRoot)
                    deleteRecursivelyIfPossible(stageRoot)
                }
                stagedRewriteMarked -> {
                    logWarn(
                        "Interrupted Irminsul purge rewrite at $stageRoot failed validation and has no backup; " +
                                "attempting normal tail recovery"
                    )
                    movePath(stageRoot, targetRoot)
                    deleteFileIfPossible(targetRoot.resolve(PURGE_READY_FILE))
                }
                else -> {
                    deleteRecursivelyIfPossible(stageRoot)
                }
            }
            return
        }

        if (targetRoot.resolve(PURGE_READY_FILE).exists()) {
            if (validatePurgeRoot(targetRoot)) {
                finalizeRecoveredPurge(targetRoot, backupRoot)
            } else if (backupRoot.exists()) {
                logWarn("Restoring Irminsul backup after an installed purge rewrite failed validation")
                deleteRecursively(targetRoot)
                movePath(backupRoot, targetRoot)
                deleteRecursivelyIfPossible(stageRoot)
            } else {
                logWarn(
                    "Installed Irminsul purge rewrite at $targetRoot failed validation and has no backup; " +
                            "attempting normal tail recovery"
                )
                deleteFileIfPossible(targetRoot.resolve(PURGE_READY_FILE))
                deleteRecursivelyIfPossible(stageRoot)
            }
        } else {
            deleteRecursivelyIfPossible(backupRoot)
            deleteRecursivelyIfPossible(stageRoot)
        }
    }

    private fun finalizeRecoveredPurge(targetRoot: Path, backupRoot: Path) {
        if (deleteRecursivelyIfPossible(backupRoot)) {
            deleteFileIfPossible(targetRoot.resolve(PURGE_READY_FILE))
        }
    }

    override suspend fun autoPurge() {
        val days = config[DatabaseSpec.autoPurgeDays]
        if (days <= 0) return

        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val purgeParams = ActionSearchParams.build {
            before = cutoff
        }
        val deleted = synchronized(lock) {
            purgeMatching(purgeParams)
        }
        logInfo("Irminsul purged $deleted actions older than $days days")
    }

    override suspend fun searchActionPages(
        params: ActionSearchParams,
        firstPage: Int,
        pageCount: Int
    ): List<SearchResults> = synchronized(lock) {
        val normalizedPage = firstPage.coerceAtLeast(1)
        val normalizedPageCount = pageCount.coerceAtLeast(1)
        val pageSize = config[SearchSpec.pageSize].coerceAtLeast(1)
        val offset = pageSize.toLong() * (normalizedPage - 1).toLong()
        val requestedActions = (pageSize.toLong() * normalizedPageCount.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val fastCount = countMatchingActionsWithoutScan(params)
        val result = if (fastCount != null) {
            val totalMatches = fastCount
            val actions = if (offset >= totalMatches) {
                emptyList()
            } else {
                pageMatchingActions(params, newestFirst = true, offset, requestedActions)
            }
            PageScan(actions, totalMatches)
        } else {
            scanMatchingPage(params, newestFirst = true, offset, requestedActions)
        }
        if (result.totalMatches == 0L) {
            listOf(SearchResults(emptyList(), params, normalizedPage, 0))
        } else {
            val totalPages = ((result.totalMatches - 1L) / pageSize.toLong() + 1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (normalizedPage > totalPages) {
                listOf(SearchResults(emptyList(), params, normalizedPage, totalPages))
            } else {
                result.actions.chunked(pageSize).mapIndexed { pageOffset, storedActions ->
                    SearchResults(
                        storedActions.mapNotNull { it.toActionType() },
                        params,
                        normalizedPage + pageOffset,
                        totalPages
                    )
                }
            }
        }
    }

    override suspend fun countActions(params: ActionSearchParams): Long = synchronized(lock) {
        countMatchingActions(params)
    }

    override suspend fun selectRollback(params: ActionSearchParams): List<ActionType> = synchronized(lock) {
        matchingActions(params.copy(rolledBack = false), newestFirst = true).mapNotNull { it.toActionType() }
    }

    override suspend fun selectRestore(params: ActionSearchParams): List<ActionType> = synchronized(lock) {
        matchingActions(params.copy(rolledBack = true), newestFirst = false).mapNotNull { it.toActionType() }
    }

    override suspend fun selectRollbackPlan(params: ActionSearchParams): RollbackExecutor.Selection =
        synchronized(lock) {
            selectBlockPlan(params.copy(rolledBack = false), newestFirst = true)
        }

    override suspend fun selectRestorePlan(params: ActionSearchParams): RollbackExecutor.Selection =
        synchronized(lock) {
            selectBlockPlan(params.copy(rolledBack = true), newestFirst = false)
        }

    override suspend fun previewActions(params: ActionSearchParams, type: Preview.Type): List<ActionType> =
        when (type) {
            Preview.Type.ROLLBACK -> selectRollback(params)
            Preview.Type.RESTORE -> selectRestore(params)
        }

    override suspend fun logActionBatch(actions: List<ActionType>) = synchronized(lock) {
        if (actions.isNotEmpty()) {
            val records = actions.mapIndexed { index, action -> StoredAction.from(action, nextId + index) }
            records.forEach(::registerStrings)
            val writeResult = writer.write(pendingStrings, records, stringIds::get)
            currentSegment = writer.segmentNumber
            pendingStrings.clear()
            nextId += records.size
            records.forEachIndexed { index, record ->
                val segment = writeResult.segments[index]
                segmentSummaries.computeIfAbsent(segment) { SegmentSummary() }.add(record)
                segmentReverseIndexes.computeIfAbsent(segment) { SegmentReverseIndex() }
                    .add(record, writeResult.offsets[index])
                addAction(record)
            }
            trimResidentHotWindowIfNeeded()
        }
    }

    override suspend fun registerWorld(identifier: Identifier) = Unit
    override suspend fun registerActionType(id: String) = Unit
    override suspend fun insertIdentifiers(identifiers: Collection<Identifier>) = Unit

    override suspend fun logPlayer(uuid: UUID, name: String) = synchronized(lock) {
        val now = Instant.now()
        val existing = players[uuid]
        val updated = if (existing == null) {
            PlayerResult(uuid, name, now, now)
        } else {
            existing.copy(name = name, lastJoin = now)
        }
        stateWriter.writePlayer(updated)
        players[uuid] = updated
        addKnownPlayerName(uuid, name)
    }

    override suspend fun rollbackActions(actionIds: Set<Int>) = synchronized(lock) {
        updateRollbackState(actionIds, true)
    }

    override suspend fun restoreActions(actionIds: Set<Int>) = synchronized(lock) {
        updateRollbackState(actionIds, false)
    }

    override suspend fun purgeActions(params: ActionSearchParams) {
        synchronized(lock) {
            purgeMatching(params)
        }
    }

    private fun purgeMatching(params: ActionSearchParams): Int {
        val stageRoot = root.resolveSibling("${root.fileName}$PURGE_STAGE_SUFFIX")
        val backupRoot = root.resolveSibling("${root.fileName}$PURGE_BACKUP_SUFFIX")
        deleteRecursively(stageRoot)
        if (!hasMatchingAction(params)) return 0

        val stageActions = stageRoot.resolve("actions")
        val stageState = stageRoot.resolve(STATE_FILE)
        stageActions.createDirectories()

        val fsyncOnBatch = config.irminsulFsyncOnBatch()
        val rewriteWriter = SegmentWriter(stageActions, 0, maxSegmentBytes(), fsyncOnBatch)
        val rewriteState = runCatching { StateWriter(stageState, fsyncOnBatch) }.getOrElse { throwable ->
            runCatching(rewriteWriter::close).onFailure(throwable::addSuppressed)
            throw throwable
        }
        val rewriteDictionary = RewriteDictionary()
        val rewriteBatchSize = config[DatabaseSpec.batchSize].coerceAtLeast(1)
        val batch = ArrayList<StoredAction>(rewriteBatchSize)
        val rollbackIds = IntArray(rewriteBatchSize)
        var rollbackCount = 0
        var retainedCount = 0
        var deletedCount = 0

        fun flushBatch() {
            if (batch.isEmpty()) return
            rewriteWriter.write(rewriteDictionary.pendingStrings, batch, rewriteDictionary::id)
            rewriteDictionary.pendingStrings.clear()
            rewriteState.writeRollbackStatesCompressed(rollbackIds, rollbackCount, true)
            batch.clear()
            rollbackCount = 0
        }

        rewriteWriter.use {
            rewriteState.use {
                forEachDiskAction(newestFirst = false, maxExclusiveId = Int.MAX_VALUE) { action ->
                    if (action.matches(params)) {
                        deletedCount += 1
                    } else {
                        retainedCount += 1
                        val retained = action.withId(retainedCount)
                        rewriteDictionary.register(retained)
                        batch.add(retained)
                        if (rolledBack[action.id]) {
                            rollbackIds[rollbackCount] = retained.id
                            rollbackCount += 1
                        }
                        if (batch.size >= rewriteBatchSize) flushBatch()
                    }
                    true
                }
                flushBatch()
                players.values.forEach(rewriteState::writePlayer)
            }
        }

        if (deletedCount == 0) {
            deleteRecursively(stageRoot)
            return 0
        }
        check(validateRewrite(stageActions, stageState, retainedCount)) {
            "Irminsul purge rewrite validation failed"
        }
        Files.writeString(stageRoot.resolve(PURGE_READY_FILE), retainedCount.toString())
        installPurgeRewrite(stageRoot, backupRoot)
        return deletedCount
    }

    private fun hasMatchingAction(params: ActionSearchParams): Boolean {
        if (!forEachMatchingHotAction(params, newestFirst = true) { false }) return true
        return !forEachMatchingColdRecord(params, newestFirst = true) { _, _, _ ->
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun installPurgeRewrite(stageRoot: Path, backupRoot: Path) {
        var originalMoved = false
        try {
            closeStoreHandles()
            deleteRecursively(backupRoot)
            movePath(root, backupRoot)
            originalMoved = true
            movePath(stageRoot, root)
            resetStoreFromDisk()
        } catch (throwable: Throwable) {
            val rootWasMoved = originalMoved || !root.exists() && backupRoot.exists()
            recoverFailedPurgeInstall(stageRoot, backupRoot, rootWasMoved)?.let(throwable::addSuppressed)
            throw throwable
        }
        if (deleteRecursivelyIfPossible(backupRoot)) {
            deleteFileIfPossible(root.resolve(PURGE_READY_FILE))
        }
    }

    private fun recoverFailedPurgeInstall(stageRoot: Path, backupRoot: Path, originalMoved: Boolean): Throwable? {
        var failure: Throwable? = null

        fun attempt(action: () -> Unit): Boolean {
            val current = runCatching(action).exceptionOrNull() ?: return true
            if (failure == null) {
                failure = current
            } else {
                failure.addSuppressed(current)
            }
            return false
        }

        attempt(::closeStoreHandles)
        val recovered = if (originalMoved) {
            if (!backupRoot.exists()) {
                attempt { error("Irminsul purge backup is missing at $backupRoot") }
                false
            } else {
                val removedReplacement = !root.exists() || attempt { deleteRecursively(root) }
                val restoredOriginal = removedReplacement && attempt { movePath(backupRoot, root) }
                restoredOriginal && attempt(::resetStoreFromDisk)
            }
        } else {
            attempt(::openWriters)
        }

        if (recovered) attempt { deleteRecursively(stageRoot) }
        if (!recovered) {
            logWarn("Irminsul could not fully recover its open store after a failed purge installation")
        }
        return failure
    }

    private fun resetStoreFromDisk() {
        clearMemory()
        players.clear()
        nextId = 1
        currentSegment = 0
        coldActionsOnDisk = 0
        actionsDir = root.resolve("actions")
        statePath = root.resolve(STATE_FILE)
        loadActions()
        loadState()
        applyStartupRollbackState()
        openWriters()
    }

    internal fun validatePurgeRoot(candidateRoot: Path): Boolean {
        val marker = candidateRoot.resolve(PURGE_READY_FILE)
        val expectedActions = runCatching { Files.readString(marker).trim().toIntOrNull() }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: return false
        return runCatching {
            validateRewrite(candidateRoot.resolve("actions"), candidateRoot.resolve(STATE_FILE), expectedActions)
        }.getOrDefault(false)
    }

    internal fun validateRewrite(actions: Path, state: Path, expectedActions: Int): Boolean {
        var expectedId = 1
        val actionsValid = segmentFiles(actions).all { file ->
            runCatching {
                DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                    if (input.readInt() != ACTION_MAGIC || input.readInt() != FORMAT_VERSION) return@runCatching false
                    while (true) {
                        when (input.read()) {
                            -1 -> {
                                break
                            }
                            STRING_DICTIONARY_RECORD -> {
                                if (input.readInt() <= 0) return@runCatching false
                                input.skipUtf8()
                            }
                            ACTION_RECORD_V4 -> {
                                if (input.readActionIdAndSkipV4() != expectedId) return@runCatching false
                                expectedId += 1
                            }
                            else -> {
                                return@runCatching false
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
        }
        if (!actionsValid || expectedId - 1 != expectedActions) return false

        return runCatching {
            DataInputStream(BufferedInputStream(state.inputStream())).use { input ->
                if (input.readInt() != STATE_MAGIC || input.readInt() != FORMAT_VERSION) return@runCatching false
                while (true) {
                    when (input.read()) {
                        -1 -> {
                            break
                        }
                        STATE_ROLLBACK_RECORD -> {
                            val id = input.readInt()
                            input.readBoolean()
                            if (id <= 0 || id > expectedActions) return@runCatching false
                        }
                        STATE_ROLLBACK_RANGE_RECORD -> {
                            val startId = input.readInt()
                            val count = input.readInt()
                            input.readBoolean()
                            val endExclusive = startId.toLong() + count.toLong()
                            if (startId <= 0 || count <= 0 || endExclusive > expectedActions.toLong() + 1L) {
                                return@runCatching false
                            }
                        }
                        STATE_PLAYER_RECORD -> {
                            readPlayer(input)
                        }
                        else -> {
                            return@runCatching false
                        }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun movePath(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteRecursivelyIfPossible(path: Path): Boolean =
        runCatching { deleteRecursively(path) }
            .onFailure { logWarn("Unable to remove stale Irminsul purge path $path: ${it.message}") }
            .isSuccess

    private fun deleteFileIfPossible(path: Path): Boolean =
        runCatching { Files.deleteIfExists(path) }
            .onFailure { logWarn("Unable to remove stale Irminsul purge marker $path: ${it.message}") }
            .isSuccess

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    override suspend fun searchPlayers(playerIds: Set<UUID>): List<PlayerResult> = synchronized(lock) {
        playerIds.mapNotNull(players::get)
    }

    override fun getKnownPlayerIdsByName(name: String): Set<UUID> = synchronized(lock) {
        playerIdsByName[name.lowercase()].orEmpty().toSet()
    }

    override fun getKnownPlayerNames(): Set<String> = synchronized(lock) {
        playerIdsByName.keys.toSet()
    }

    override fun getKnownSources(): Set<String> = knownSources

    override fun close() {
        synchronized(lock) {
            closeStoreHandles()
        }
    }

    private fun loadActions() {
        val retainedActions = ArrayDeque<StoredAction>()
        val loadedSources = HashSet<String>()
        val loadedPlayerIdsByName = HashMap<String, MutableSet<UUID>>()
        segmentSummaries.clear()
        segmentReverseIndexes.clear()
        retainedStringIds.clear()
        dictionaryLocations.clear()
        coldActionsOnDisk = 0
        try {
            segmentFiles().forEach { file ->
                val segment = parseSegmentNumber(file)
                val summary = SegmentSummary()
                val reverseIndex = SegmentReverseIndex()
                if (segment >= currentSegment) currentSegment = segment

                var validBytes = 0L
                var deleteIncompleteHeader = false
                var skipTruncate = false
                val countingInput = CountingInputStream(BufferedInputStream(file.inputStream()))
                DataInputStream(countingInput).use { input ->
                    val magic = try {
                        input.readInt()
                    } catch (_: EOFException) {
                        logWarn("Deleting incomplete Irminsul segment header $file")
                        deleteIncompleteHeader = true
                        return@use
                    }
                    val version = try {
                        input.readInt()
                    } catch (_: EOFException) {
                        logWarn("Deleting incomplete Irminsul segment header $file")
                        deleteIncompleteHeader = true
                        return@use
                    }
                    if (magic != ACTION_MAGIC || version != FORMAT_VERSION) {
                        logWarn("Skipping unknown Irminsul segment $file")
                        skipTruncate = true
                        return@use
                    }
                    validBytes = countingInput.bytesRead

                    while (true) {
                        val recordOffset = countingInput.bytesRead
                        try {
                            when (val record = input.readUnsignedByte()) {
                                ACTION_RECORD -> {
                                    val size = input.readInt()
                                    if (size <= 0 || size > MAX_RECORD_BYTES) {
                                        logWarn("Stopping at invalid Irminsul record in $file")
                                        return@use
                                    }
                                    val payload = ByteArray(size)
                                    input.readFully(payload)
                                    val action = StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
                                    registerLoadedAction(action, loadedSources, loadedPlayerIdsByName, retainedActions)
                                    summary.add(action)
                                    reverseIndex.add(action, recordOffset)
                                }
                                STRING_DICTIONARY_RECORD -> {
                                    val id = input.readInt()
                                    val valueOffset = countingInput.bytesRead
                                    val value = input.readUtf8()
                                    dictionaryLocations.put(id, segment, file, valueOffset)
                                    stringDictionary[id] = value
                                    stringIds[value] = id
                                    nextStringId = max(nextStringId, id + 1)
                                }
                                ACTION_RECORD_V2 -> {
                                    val action = StoredAction.readV2(input, ::dictionaryValue, ::retainStringId)
                                    registerLoadedAction(action, loadedSources, loadedPlayerIdsByName, retainedActions)
                                    summary.add(action)
                                    reverseIndex.add(action, recordOffset)
                                }
                                ACTION_RECORD_V3 -> {
                                    val action = StoredAction.readV3(input, ::dictionaryValue, ::retainStringId)
                                    registerLoadedAction(action, loadedSources, loadedPlayerIdsByName, retainedActions)
                                    summary.add(action)
                                    reverseIndex.add(action, recordOffset)
                                }
                                ACTION_RECORD_V4 -> {
                                    val action = StoredAction.readV4(input, ::dictionaryValue, ::retainStringId)
                                    registerLoadedAction(action, loadedSources, loadedPlayerIdsByName, retainedActions)
                                    summary.add(action)
                                    reverseIndex.add(action, recordOffset)
                                }
                                else -> {
                                    logWarn("Stopping at unknown Irminsul record $record in $file")
                                    return@use
                                }
                            }
                            validBytes = countingInput.bytesRead
                        } catch (_: EOFException) {
                            return@use
                        }
                    }
                }
                if (deleteIncompleteHeader) {
                    Files.deleteIfExists(file)
                } else if (!skipTruncate) {
                    truncateToValidBytes(file, validBytes)
                    if (summary.actionCount > 0) {
                        segmentSummaries[segment] = summary
                        segmentReverseIndexes[segment] = reverseIndex
                    }
                    pruneDictionaryCache()
                }
            }
        } finally {
            clearMemory(clearStrings = false)
            knownSources.addAll(loadedSources)
            loadedPlayerIdsByName.forEach { (name, ids) ->
                playerIdsByName.computeIfAbsent(name) { HashSet() }.addAll(ids)
            }
            retainedActions.forEach { action ->
                registerStrings(action)
                addAction(action)
            }
            if (coldActionsOnDisk > 0) {
                logInfo(
                    "Irminsul loaded newest $hotActionLimit actions into memory; " +
                            "$coldActionsOnDisk older records remain on disk for cold scans"
                )
            }
        }
    }

    private fun registerLoadedAction(
        action: StoredAction,
        loadedSources: MutableSet<String>,
        loadedPlayerIdsByName: MutableMap<String, MutableSet<UUID>>,
        retainedActions: ArrayDeque<StoredAction>
    ) {
        if (action.id != nextId) throw EOFException()
        loadedSources.add(action.sourceName)
        loadedPlayerIdsByName.add(action.sourcePlayerName, action.sourcePlayerId)
        retainedActions.retainLoadedAction(action)
        nextId += 1
    }

    private fun ArrayDeque<StoredAction>.retainLoadedAction(action: StoredAction) {
        if (size >= hotActionLimit) {
            removeFirst()
            coldActionsOnDisk += 1
        }
        addLast(action)
    }

    private fun MutableMap<String, MutableSet<UUID>>.add(name: String?, uuid: UUID?) {
        if (name == null || uuid == null) return
        computeIfAbsent(name.lowercase()) { HashSet() }.add(uuid)
    }

    private fun loadState() {
        if (!statePath.exists()) return

        var validBytes = 0L
        var deleteIncompleteHeader = false
        DataInputStream(BufferedInputStream(statePath.inputStream())).use { input ->
            val magic = try {
                input.readInt()
            } catch (_: EOFException) {
                logWarn("Deleting incomplete Irminsul state log header $statePath")
                deleteIncompleteHeader = true
                return@use
            }
            val version = try {
                input.readInt()
            } catch (_: EOFException) {
                logWarn("Deleting incomplete Irminsul state log header $statePath")
                deleteIncompleteHeader = true
                return@use
            }
            if (magic != STATE_MAGIC || version != FORMAT_VERSION) {
                logWarn("Skipping unknown Irminsul state log $statePath")
                return
            }
            validBytes = 8L

            while (true) {
                try {
                    when (val type = input.readUnsignedByte()) {
                        STATE_ROLLBACK_RECORD -> {
                            val id = input.readInt()
                            val value = input.readBoolean()
                            applyStartupRollbackState(id, value)
                            validBytes += 1L + Integer.BYTES + 1L
                        }
                        STATE_PLAYER_RECORD -> {
                            val player = readPlayer(input)
                            players[player.uuid] = player
                            addKnownPlayerName(player.uuid, player.name)
                            validBytes += 1L + playerRecordSize(player)
                        }
                        STATE_ROLLBACK_RANGE_RECORD -> {
                            val startId = input.readInt()
                            val count = input.readInt()
                            val value = input.readBoolean()
                            applyStartupRollbackStateRange(startId, count, value)
                            validBytes += 1L + Integer.BYTES + Integer.BYTES + 1L
                        }
                        else -> {
                            logWarn("Stopping at unknown Irminsul state record $type")
                            return@use
                        }
                    }
                } catch (_: EOFException) {
                    return@use
                }
            }
        }
        if (deleteIncompleteHeader) {
            Files.deleteIfExists(statePath)
        } else {
            truncateToValidBytes(statePath, validBytes)
        }
    }

    private fun truncateToValidBytes(file: Path, validBytes: Long) {
        if (validBytes <= 0L || file.fileSize() == validBytes) return
        RandomAccessFile(file.toFile(), "rw").use { it.setLength(validBytes) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun openWriters() {
        val fsyncOnBatch = config.irminsulFsyncOnBatch()
        val newWriter = SegmentWriter(actionsDir, currentSegment, maxSegmentBytes(), fsyncOnBatch)
        try {
            val newStateWriter = StateWriter(statePath, fsyncOnBatch)
            writer = newWriter
            stateWriter = newStateWriter
            currentSegment = newWriter.segmentNumber
        } catch (throwable: Throwable) {
            runCatching(newWriter::close).onFailure(throwable::addSuppressed)
            throw throwable
        }
    }

    private fun closeWriters() {
        val writerFailure = if (::writer.isInitialized) runCatching(writer::close).exceptionOrNull() else null
        val stateFailure = if (::stateWriter.isInitialized) runCatching(stateWriter::close).exceptionOrNull() else null
        writerFailure?.let { failure ->
            stateFailure?.let(failure::addSuppressed)
            throw failure
        }
        stateFailure?.let { throw it }
    }

    private fun closeStoreHandles() {
        val writerFailure = runCatching(::closeWriters).exceptionOrNull()
        val dictionaryFailure = runCatching(dictionaryLocations::close).exceptionOrNull()
        writerFailure?.let { failure ->
            dictionaryFailure?.let(failure::addSuppressed)
            throw failure
        }
        dictionaryFailure?.let { throw it }
    }

    private fun applyStartupRollbackState() {
        if (startupRolledBackIds.isEmpty()) return

        startupRolledBackIds.forEach { id -> rolledBack.set(id) }
    }

    private fun applyStartupRollbackState(id: Int, value: Boolean) {
        if (id <= 0 || id >= nextId) throw EOFException()
        if (value) startupRolledBackIds.add(id) else startupRolledBackIds.remove(id)
    }

    private fun applyStartupRollbackStateRange(startId: Int, count: Int, value: Boolean) {
        val endExclusive = startId.toLong() + count.toLong()
        if (startId <= 0 || count <= 0 || endExclusive > nextId.toLong()) throw EOFException()
        repeat(count) { offset ->
            val id = startId + offset
            if (value) startupRolledBackIds.add(id) else startupRolledBackIds.remove(id)
        }
    }

    private fun updateRollbackState(actionIds: Set<Int>, value: Boolean) {
        if (actionIds.isEmpty()) return

        val updates = IntArray(actionIds.size)
        var count = 0
        actionIds.forEach { id ->
            if (id > 0 && id < nextId) {
                updates[count] = id
                count += 1
            }
        }
        stateWriter.writeRollbackStatesCompressed(updates, count, value)
        for (index in 0 until count) setRolledBack(updates[index], value)
    }

    private fun setRolledBack(id: Int, value: Boolean) {
        if (value) {
            rolledBack.set(id)
        } else {
            rolledBack.clear(id)
        }
    }

    private fun addAction(action: StoredAction) {
        actionsById[action.id] = action
        orderedIds.add(action.id)
        knownSources.add(action.sourceName)
        idsByAction.add(action.action, action.id)
        idsByWorld.add(action.world, action.id)
        idsByObject.add(action.objectIdentifier, action.id)
        idsByOldObject.add(action.oldObjectIdentifier, action.id)
        idsBySource.add(action.sourceName, action.id)
        action.sourcePlayerId?.let { idsByPlayer.add(it, action.id) }
        if (action.sourcePlayerId != null && action.sourcePlayerName != null) {
            addKnownPlayerName(action.sourcePlayerId, action.sourcePlayerName)
        }
        idsByChunk.add(LocationKey(action.world, action.x shr 4, action.z shr 4), action.id)
    }

    private fun addKnownPlayerName(uuid: UUID, name: String) {
        playerIdsByName.computeIfAbsent(name.lowercase()) { HashSet() }.add(uuid)
    }

    private fun registerStrings(action: StoredAction) {
        // Dictionary ids are assigned to the full registry string at runtime. This is intentionally
        // namespace-agnostic, so modded ids like "modid:custom_block" are stored exactly as seen.
        internString(action.action)
        internString(action.world.toString())
        internString(action.objectIdentifier.toString())
        internString(action.oldObjectIdentifier.toString())
        action.objectState?.let(::registerStateString)
        action.oldObjectState?.let(::registerStateString)
        internString(action.sourceName)
        action.sourcePlayerName?.let(::internString)
    }

    private fun registerStateString(value: String) {
        if (stringIds.containsKey(value) || !stateStringAdmission.shouldIntern(value)) return
        internString(value)
    }

    private fun retainStringId(id: Int) {
        if (id > 0) retainedStringIds.set(id)
    }

    private fun pruneDictionaryCache() {
        val iterator = stringDictionary.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!retainedStringIds[entry.key]) {
                stringIds.remove(entry.value, entry.key)
                iterator.remove()
            }
        }
    }

    private fun internString(value: String): Int {
        stringIds[value]?.let { return it }
        val id = nextStringId++
        stringIds[value] = id
        stringDictionary[id] = value
        pendingStrings.add(id to value)
        return id
    }

    private fun dictionaryValue(id: Int): String {
        stringDictionary[id]?.let { return it }
        val value = dictionaryLocations.read(id)
            ?: throw EOFException("Missing Irminsul dictionary value $id")
        if (retainedStringIds[id]) {
            stringDictionary[id] = value
            stringIds[value] = id
        }
        return value
    }

    private fun clearMemory(clearStrings: Boolean = true) {
        clearResidentIndexes(clearRolledBack = true)
        knownSources.clear()
        playerIdsByName.clear()
        if (clearStrings) {
            stringDictionary.clear()
            stringIds.clear()
            pendingStrings.clear()
            stateStringAdmission.clear()
            retainedStringIds.clear()
            dictionaryLocations.clear()
            nextStringId = 1
        }
        startupRolledBackIds.clear()
        blockActionTypes.clear()
        if (::bitSetCache.isInitialized) bitSetCache.reset()
    }

    private fun clearResidentIndexes(clearRolledBack: Boolean) {
        actionsById.clear()
        orderedIds.clear()
        if (clearRolledBack) rolledBack.clear()
        idsByWorld.clear()
        idsByAction.clear()
        idsByObject.clear()
        idsByOldObject.clear()
        idsBySource.clear()
        idsByPlayer.clear()
        idsByChunk.clear()
        blockActionTypes.clear()
        if (::bitSetCache.isInitialized) bitSetCache.reset()
    }

    private fun trimResidentHotWindowIfNeeded() {
        val trimLimit = hotActionLimit + max(hotActionLimit / 20, MIN_HOT_TRIM_OVERFLOW)
        if (actionsById.size <= trimLimit) return

        val skip = (actionsById.size - hotActionLimit).coerceAtLeast(0)
        val retained = orderedIds.asSequence()
            .drop(skip)
            .mapNotNull { actionsById[it] }
            .toList()
        coldActionsOnDisk += skip
        clearResidentIndexes(clearRolledBack = false)
        retained.forEach(::addAction)
        logInfo(
            "Irminsul trimmed resident hot window to ${actionsById.size}; " +
                    "coldActions=$coldActionsOnDisk remain on disk"
        )
    }

    private fun matchingActions(params: ActionSearchParams, newestFirst: Boolean): List<StoredAction> {
        val hotActions = ArrayList<StoredAction>()
        forEachMatchingHotAction(params, newestFirst) { action ->
            hotActions.add(action)
            true
        }

        val coldActions = ArrayList<StoredAction>()
        forEachMatchingColdAction(params, newestFirst) { action ->
            coldActions.add(action)
            true
        }
        return if (newestFirst) {
            hotActions + coldActions
        } else {
            coldActions + hotActions
        }
    }

    private fun countMatchingActions(params: ActionSearchParams): Long {
        countMatchingActionsWithoutScan(params)?.let { return it }
        var count = 0L
        forEachMatchingHotAction(params, newestFirst = false) {
            count += 1
            true
        }
        forEachMatchingColdRecord(params, newestFirst = false) { _, _, _ ->
            count += 1
            true
        }
        return count
    }

    private fun countMatchingActionsWithoutScan(params: ActionSearchParams): Long? {
        if (params.hasOnlyRollbackFilter()) {
            val total = (nextId - 1).toLong()
            val rollbackCount = rolledBack.cardinality().toLong()
            return when (params.rolledBack) {
                true -> rollbackCount
                false -> total - rollbackCount
                null -> total
            }
        }
        if (params.bounds != null || params.before != null || params.after != null || params.rolledBack != null) {
            return null
        }

        var counter: ((SegmentSummary) -> Long)? = null
        fun selectCounter(values: Collection<Negatable<*>>?, candidate: (SegmentSummary) -> Long): Boolean {
            if (values.isNullOrEmpty()) return true
            if (counter != null || values.any { !it.allowed }) return false
            counter = candidate
            return true
        }

        val actionValues = params.actions.orEmpty().mapTo(HashSet()) { it.property }
        if (!selectCounter(params.actions) { it.countActions(actionValues) }) return null
        val sourceValues = params.sourceNames.orEmpty().mapTo(HashSet()) { it.property }
        if (!selectCounter(params.sourceNames) { it.countSources(sourceValues) }) return null
        val worldValues = params.worlds.orEmpty().mapTo(HashSet()) { it.property }
        if (!selectCounter(params.worlds) { it.countWorlds(worldValues) }) return null
        val playerValues = params.sourcePlayerIds.orEmpty().mapTo(HashSet()) { it.property }
        if (!selectCounter(params.sourcePlayerIds) { it.countPlayers(playerValues) }) return null
        val objectValues = params.objects.orEmpty().mapTo(HashSet()) { it.property }
        if (objectValues.size > 1) return null
        if (!selectCounter(params.objects) { it.countObject(objectValues.single()) }) return null

        val selectedCounter = counter ?: return null
        if (segmentSummaries.values.sumOf { it.actionCount.toLong() } != (nextId - 1).toLong()) return null
        return segmentSummaries.values.sumOf(selectedCounter)
    }

    private fun ActionSearchParams.hasOnlyRollbackFilter(): Boolean =
        bounds == null && before == null && after == null && actions.isNullOrEmpty() && objects.isNullOrEmpty() &&
            sourceNames.isNullOrEmpty() && sourcePlayerIds.isNullOrEmpty() && worlds.isNullOrEmpty()

    private fun selectBlockPlan(params: ActionSearchParams, newestFirst: Boolean): RollbackExecutor.Selection {
        if (!params.canDedupeBlockActions()) {
            return RollbackExecutor.Selection(
                actions = matchingActions(params, newestFirst).mapNotNull { it.toActionType() },
                dedupeBlockActions = false
            )
        }

        val unsafeKeys = HashSet<BlockKey>()
        val groups = LinkedHashMap<BlockKey, StoredActionGroup>()
        var requestedActions = 0

        forEachMatchingAction(params, newestFirst) { action ->
            requestedActions += 1
            val key = action.blockKey()
            if (action.isBlockAction()) {
                if (!unsafeKeys.contains(key)) {
                    groups.computeIfAbsent(key) { StoredActionGroup() }.add(action)
                }
            } else {
                unsafeKeys.add(key)
                groups.remove(key)
            }
            true
        }

        if (requestedActions == 0) return RollbackExecutor.Selection(emptyList())

        val idsByRepresentative = HashMap<Int, List<Int>>()
        val expectedActionByRepresentative = HashMap<Int, ActionType>()
        groups.values.forEach { group ->
            idsByRepresentative[group.representative.id] = group.actionIds.toList()
            group.expectedCurrent.toActionType()?.let {
                expectedActionByRepresentative[group.representative.id] = it
            }
        }

        val plannedActions = ArrayList<ActionType>()
        forEachMatchingAction(params, newestFirst) { action ->
            val ids = idsByRepresentative[action.id]
            val shouldApply = ids != null || !action.isBlockAction() || unsafeKeys.contains(action.blockKey())
            if (shouldApply) action.toActionType()?.let(plannedActions::add)
            true
        }

        if (plannedActions.size == requestedActions && idsByRepresentative.isEmpty()) {
            return RollbackExecutor.Selection(plannedActions)
        }
        return RollbackExecutor.Selection(
            actions = plannedActions,
            actionIdsByRepresentative = idsByRepresentative,
            expectedActionByRepresentative = expectedActionByRepresentative,
            requestedActions = requestedActions
        )
    }

    private fun pageMatchingActions(
        params: ActionSearchParams,
        newestFirst: Boolean,
        offset: Long,
        limit: Int
    ): List<StoredAction> {
        val page = ArrayList<StoredAction>(limit)
        var skipped = 0L
        fun accept(action: StoredAction): Boolean {
            if (skipped < offset) {
                skipped += 1
                return true
            }
            if (page.size < limit) page.add(action)
            return page.size < limit
        }

        fun acceptCold(recordOffset: Long, channel: FileChannel, input: DataInputStream): Boolean {
            if (skipped < offset) {
                skipped += 1
                return true
            }
            if (page.size < limit) page.add(readStoredActionAt(channel, input, recordOffset))
            return page.size < limit
        }

        if (newestFirst) {
            if (!forEachMatchingHotAction(params, newestFirst = true) { accept(it) }) return page
            forEachMatchingColdRecord(params, newestFirst = true) { recordOffset, channel, input ->
                acceptCold(recordOffset, channel, input)
            }
        } else {
            forEachMatchingColdRecord(params, newestFirst = false) { recordOffset, channel, input ->
                acceptCold(recordOffset, channel, input)
            }
            if (page.size < limit) {
                forEachMatchingHotAction(params, newestFirst = false) { accept(it) }
            }
        }
        return page
    }

    private fun scanMatchingPage(
        params: ActionSearchParams,
        newestFirst: Boolean,
        offset: Long,
        limit: Int
    ): PageScan {
        val page = ArrayList<StoredAction>(limit)
        var totalMatches = 0L

        fun accept(action: StoredAction): Boolean {
            if (totalMatches >= offset && page.size < limit) page.add(action)
            totalMatches += 1
            return true
        }

        fun acceptCold(recordOffset: Long, channel: FileChannel, input: DataInputStream): Boolean {
            if (totalMatches >= offset && page.size < limit) {
                page.add(readStoredActionAt(channel, input, recordOffset))
            }
            totalMatches += 1
            return true
        }

        if (newestFirst) {
            forEachMatchingHotAction(params, newestFirst = true, ::accept)
            forEachMatchingColdRecord(params, newestFirst = true, ::acceptCold)
        } else {
            forEachMatchingColdRecord(params, newestFirst = false, ::acceptCold)
            forEachMatchingHotAction(params, newestFirst = false, ::accept)
        }
        return PageScan(page, totalMatches)
    }

    private fun shouldScanCold(): Boolean = coldActionsOnDisk > 0

    private fun oldestResidentId(): Int = orderedIds.firstOrNull() ?: nextId

    private fun forEachMatchingAction(
        params: ActionSearchParams,
        newestFirst: Boolean,
        block: (StoredAction) -> Boolean
    ): Boolean {
        return if (newestFirst) {
            forEachMatchingHotAction(params, newestFirst = true, block) &&
                    forEachMatchingColdAction(params, newestFirst = true, block)
        } else {
            forEachMatchingColdAction(params, newestFirst = false, block) &&
                    forEachMatchingHotAction(params, newestFirst = false, block)
        }
    }

    private fun forEachMatchingHotAction(
        params: ActionSearchParams,
        newestFirst: Boolean,
        block: (StoredAction) -> Boolean
    ): Boolean {
        val iterator = candidateIdSequence(params, newestFirst).iterator()
        while (iterator.hasNext()) {
            val action = actionsById[iterator.next()]
            if (action != null && action.matches(params) && !block(action)) return false
        }
        return true
    }

    private fun forEachMatchingColdAction(
        params: ActionSearchParams,
        newestFirst: Boolean,
        block: (StoredAction) -> Boolean
    ): Boolean {
        if (!shouldScanCold()) return true
        return forEachDiskAction(newestFirst, oldestResidentId(), params) { action ->
            !action.matches(params) || block(action)
        }
    }

    private fun forEachMatchingColdRecord(
        params: ActionSearchParams,
        newestFirst: Boolean,
        block: (Long, FileChannel, DataInputStream) -> Boolean
    ): Boolean {
        if (!shouldScanCold()) return true
        val maxExclusiveId = oldestResidentId()
        val filter = DiskSearchFilter(params, ::dictionaryValue, rolledBack::get)
        val files = segmentFiles().filter { file ->
            segmentSummaries[parseSegmentNumber(file)]?.mightMatch(params, maxExclusiveId) != false
        }
        val iterable = if (newestFirst) files.asReversed() else files
        iterable.forEach { file ->
            val shouldContinue = if (newestFirst) {
                forEachSegmentMatchingRecordReverse(file, maxExclusiveId, filter, block)
            } else {
                forEachSegmentMatchingRecord(file, maxExclusiveId, filter, block)
            }
            if (!shouldContinue) return false
        }
        return true
    }

    private fun forEachDiskAction(
        newestFirst: Boolean,
        maxExclusiveId: Int,
        params: ActionSearchParams? = null,
        block: (StoredAction) -> Boolean
    ): Boolean {
        val files = segmentFiles().filter { file ->
            segmentSummaries[parseSegmentNumber(file)]?.mightMatch(params, maxExclusiveId) != false
        }
        val iterable = if (newestFirst) files.asReversed() else files
        iterable.forEach { file ->
            if (!newestFirst) {
                if (!forEachSegmentAction(file, maxExclusiveId, block)) return false
                return@forEach
            }
            if (!forEachSegmentActionReverse(file, maxExclusiveId, block)) return false
        }
        return true
    }

    internal fun forEachSegmentActionReverse(
        file: Path,
        maxExclusiveId: Int,
        block: (StoredAction) -> Boolean
    ): Boolean {
        fun dictionaryValue(id: Int): String = this@IrminsulLedgerStore.dictionaryValue(id)
        val segment = parseSegmentNumber(file)
        val reverseIndex = segmentReverseIndexes.computeIfAbsent(segment) { buildSegmentReverseIndex(file) }
        if (reverseIndex.blockCount == 0) return true

        val fileSize = file.fileSize()
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val seekableInput = SeekableFileInputStream(channel)
            DataInputStream(seekableInput).use { input ->
                for (blockIndex in reverseIndex.blockCount - 1 downTo 0) {
                    if (reverseIndex.firstActionId(blockIndex) >= maxExclusiveId) continue

                    val startOffset = reverseIndex.offset(blockIndex)
                    val endOffset = if (blockIndex + 1 < reverseIndex.blockCount) {
                        reverseIndex.offset(blockIndex + 1)
                    } else {
                        fileSize
                    }
                    var offsets = LongArray(REVERSE_CHECKPOINT_STRIDE)
                    var offsetCount = 0
                    seekableInput.seek(startOffset)
                    try {
                        while (seekableInput.position < endOffset) {
                            val recordOffset = seekableInput.position
                            val actionId = when (input.readUnsignedByte()) {
                                ACTION_RECORD -> {
                                    val size = input.readInt()
                                    if (size < Integer.BYTES || size > MAX_RECORD_BYTES) throw EOFException()
                                    val id = input.readInt()
                                    input.skipFully(size.toLong() - Integer.BYTES)
                                    id
                                }
                                STRING_DICTIONARY_RECORD -> {
                                    input.readInt()
                                    input.skipUtf8()
                                    null
                                }
                                ACTION_RECORD_V2 -> {
                                    input.readActionIdAndSkipV2()
                                }
                                ACTION_RECORD_V3 -> {
                                    input.readActionIdAndSkipV3()
                                }
                                ACTION_RECORD_V4 -> {
                                    input.readActionIdAndSkipV4()
                                }
                                else -> {
                                    throw EOFException("Invalid Irminsul reverse-scan record")
                                }
                            }
                            if (actionId != null && actionId < maxExclusiveId) {
                                if (offsetCount >= offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                                offsets[offsetCount] = recordOffset
                                offsetCount += 1
                            }
                        }
                    } catch (_: EOFException) {
                        // Startup truncates incomplete tails. Keep valid actions if a scan races external damage.
                    }

                    for (index in offsetCount - 1 downTo 0) {
                        seekableInput.seek(offsets[index])
                        val action = when (input.readUnsignedByte()) {
                            ACTION_RECORD -> {
                                val size = input.readInt()
                                val payload = ByteArray(size)
                                input.readFully(payload)
                                StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
                            }
                            ACTION_RECORD_V2 -> {
                                StoredAction.readV2(input, ::dictionaryValue)
                            }
                            ACTION_RECORD_V3 -> {
                                StoredAction.readV3(input, ::dictionaryValue)
                            }
                            ACTION_RECORD_V4 -> {
                                StoredAction.readV4(input, ::dictionaryValue)
                            }
                            else -> {
                                throw EOFException("Invalid Irminsul action checkpoint")
                            }
                        }
                        if (!block(action)) return false
                    }
                }
            }
        }
        return true
    }

    private fun buildSegmentReverseIndex(file: Path): SegmentReverseIndex {
        val reverseIndex = SegmentReverseIndex()
        val countingInput = CountingInputStream(BufferedInputStream(file.inputStream()))
        DataInputStream(countingInput).use { input ->
            if (input.readInt() != ACTION_MAGIC || input.readInt() != FORMAT_VERSION) return reverseIndex
            while (true) {
                val recordOffset = countingInput.bytesRead
                try {
                    val actionId = when (input.readUnsignedByte()) {
                        ACTION_RECORD -> {
                            val size = input.readInt()
                            if (size < Integer.BYTES || size > MAX_RECORD_BYTES) return reverseIndex
                            val id = input.readInt()
                            input.skipFully(size.toLong() - Integer.BYTES)
                            id
                        }
                        STRING_DICTIONARY_RECORD -> {
                            input.readInt()
                            input.skipUtf8()
                            null
                        }
                        ACTION_RECORD_V2 -> {
                            input.readActionIdAndSkipV2()
                        }
                        ACTION_RECORD_V3 -> {
                            input.readActionIdAndSkipV3()
                        }
                        ACTION_RECORD_V4 -> {
                            input.readActionIdAndSkipV4()
                        }
                        else -> {
                            return reverseIndex
                        }
                    }
                    if (actionId != null) reverseIndex.add(actionId, recordOffset)
                } catch (_: EOFException) {
                    return reverseIndex
                }
            }
        }
    }

    private fun forEachSegmentMatchingRecord(
        file: Path,
        maxExclusiveId: Int,
        filter: DiskSearchFilter,
        block: (Long, FileChannel, DataInputStream) -> Boolean
    ): Boolean {
        val segment = parseSegmentNumber(file)
        val reverseIndex = segmentReverseIndexes.computeIfAbsent(segment) { buildSegmentReverseIndex(file) }
        if (reverseIndex.blockCount == 0) return true

        val fileSize = file.fileSize()
        FileChannel.open(file, StandardOpenOption.READ).use { scanChannel ->
            val seekableInput = SeekableFileInputStream(scanChannel)
            DataInputStream(seekableInput).use { scanInput ->
                if (scanInput.readInt() != ACTION_MAGIC || scanInput.readInt() != FORMAT_VERSION) return true
                FileChannel.open(file, StandardOpenOption.READ).use { loadChannel ->
                    DataInputStream(Channels.newInputStream(loadChannel)).use { loadInput ->
                        for (blockIndex in 0 until reverseIndex.blockCount) {
                            if (reverseIndex.firstActionId(blockIndex) >= maxExclusiveId) break
                            if (!reverseIndex.mightMatch(blockIndex, filter.params, maxExclusiveId)) continue

                            val startOffset = reverseIndex.offset(blockIndex)
                            val endOffset = if (blockIndex + 1 < reverseIndex.blockCount) {
                                reverseIndex.offset(blockIndex + 1)
                            } else {
                                fileSize
                            }
                            seekableInput.seek(startOffset)
                            try {
                                while (seekableInput.position < endOffset) {
                                    val recordOffset = seekableInput.position
                                    when (val recordType = scanInput.readUnsignedByte()) {
                                        STRING_DICTIONARY_RECORD -> {
                                            scanInput.readInt()
                                            scanInput.skipUtf8()
                                        }
                                        ACTION_RECORD, ACTION_RECORD_V2, ACTION_RECORD_V3, ACTION_RECORD_V4 -> {
                                            val actionId = readActionIdIfMatches(scanInput, recordType, filter)
                                            if (actionId != null && actionId < maxExclusiveId &&
                                                !block(recordOffset, loadChannel, loadInput)
                                            ) {
                                                return false
                                            }
                                        }
                                        else -> {
                                            throw EOFException("Invalid Irminsul forward search record")
                                        }
                                    }
                                }
                            } catch (_: EOFException) {
                                // Startup truncates incomplete tails. Later checkpoints can still remain readable.
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun forEachSegmentMatchingRecordReverse(
        file: Path,
        maxExclusiveId: Int,
        filter: DiskSearchFilter,
        block: (Long, FileChannel, DataInputStream) -> Boolean
    ): Boolean {
        val segment = parseSegmentNumber(file)
        val reverseIndex = segmentReverseIndexes.computeIfAbsent(segment) { buildSegmentReverseIndex(file) }
        if (reverseIndex.blockCount == 0) return true

        val fileSize = file.fileSize()
        FileChannel.open(file, StandardOpenOption.READ).use { scanChannel ->
            val seekableInput = SeekableFileInputStream(scanChannel)
            DataInputStream(seekableInput).use { scanInput ->
                FileChannel.open(file, StandardOpenOption.READ).use { loadChannel ->
                    DataInputStream(Channels.newInputStream(loadChannel)).use { loadInput ->
                        var matchingOffsets = LongArray(REVERSE_CHECKPOINT_STRIDE)
                        for (blockIndex in reverseIndex.blockCount - 1 downTo 0) {
                            if (reverseIndex.firstActionId(blockIndex) >= maxExclusiveId) continue
                            if (!reverseIndex.mightMatch(blockIndex, filter.params, maxExclusiveId)) continue

                            val startOffset = reverseIndex.offset(blockIndex)
                            val endOffset = if (blockIndex + 1 < reverseIndex.blockCount) {
                                reverseIndex.offset(blockIndex + 1)
                            } else {
                                fileSize
                            }
                            var matchingCount = 0
                            seekableInput.seek(startOffset)
                            try {
                                while (seekableInput.position < endOffset) {
                                    val recordOffset = seekableInput.position
                                    when (val recordType = scanInput.readUnsignedByte()) {
                                        STRING_DICTIONARY_RECORD -> {
                                            scanInput.readInt()
                                            scanInput.skipUtf8()
                                        }
                                        ACTION_RECORD, ACTION_RECORD_V2, ACTION_RECORD_V3, ACTION_RECORD_V4 -> {
                                            val actionId = readActionIdIfMatches(scanInput, recordType, filter)
                                            if (actionId != null && actionId < maxExclusiveId) {
                                                if (matchingCount >= matchingOffsets.size) {
                                                    matchingOffsets = matchingOffsets.copyOf(matchingOffsets.size * 2)
                                                }
                                                matchingOffsets[matchingCount] = recordOffset
                                                matchingCount += 1
                                            }
                                        }
                                        else -> {
                                            throw EOFException("Invalid Irminsul reverse search record")
                                        }
                                    }
                                }
                            } catch (_: EOFException) {
                                // Startup truncates incomplete tails. Keep valid records if external damage races a scan.
                            }

                            for (index in matchingCount - 1 downTo 0) {
                                if (!block(matchingOffsets[index], loadChannel, loadInput)) return false
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun readActionIdIfMatches(
        input: DataInputStream,
        recordType: Int,
        filter: DiskSearchFilter
    ): Int? = when (recordType) {
        ACTION_RECORD -> {
            val size = input.readInt()
            if (size <= 0 || size > MAX_RECORD_BYTES) throw EOFException()
            readLegacyActionIdIfMatches(input, filter)
        }
        ACTION_RECORD_V2, ACTION_RECORD_V3, ACTION_RECORD_V4 -> {
            readDictionaryActionIdIfMatches(input, recordType, filter)
        }
        else -> {
            throw EOFException("Invalid Irminsul search record")
        }
    }

    internal fun readActionIdIfMatchesForRegression(
        input: DataInputStream,
        recordVersion: Int,
        params: ActionSearchParams,
        dictionaryValue: (Int) -> String,
        rolledBackIds: Set<Int> = emptySet()
    ): Int? {
        val recordType = when (recordVersion) {
            1 -> ACTION_RECORD
            2 -> ACTION_RECORD_V2
            3 -> ACTION_RECORD_V3
            4 -> ACTION_RECORD_V4
            else -> throw IllegalArgumentException("Unknown Irminsul action record version $recordVersion")
        }
        val filter = DiskSearchFilter(params, dictionaryValue, rolledBackIds::contains)
        return readActionIdIfMatches(input, recordType, filter)
    }

    private fun readLegacyActionIdIfMatches(input: DataInputStream, filter: DiskSearchFilter): Int? {
        val id = input.readInt()
        val action = input.readUtf8If(filter.actions != null)
        val epochSecond = input.readLong()
        val nano = input.readInt().also(::validateNano)
        val x = input.readInt()
        val y = input.readInt()
        val z = input.readInt()
        val world = input.readUtf8If(filter.worlds != null)
        val objectIdentifier = input.readUtf8If(filter.objects != null)
        val oldObjectIdentifier = input.readUtf8If(filter.objects != null)
        input.skipNullableUtf8()
        input.skipNullableUtf8()
        val source = input.readUtf8If(filter.sources != null)
        val playerId = input.readNullableUuidIf(filter.players != null)
        input.skipNullableUtf8()
        input.skipNullableUtf8()
        return if (filter.matches(
                id,
                action,
                epochSecond,
                nano,
                x,
                y,
                z,
                world,
                objectIdentifier,
                oldObjectIdentifier,
                source,
                playerId
            )
        ) {
            id
        } else {
            null
        }
    }

    private fun readDictionaryActionIdIfMatches(
        input: DataInputStream,
        recordType: Int,
        filter: DiskSearchFilter
    ): Int? {
        val id = input.readInt()
        val action = input.readDictionaryValueIf(filter.actions != null, filter.dictionaryValue)
        val epochSecond = input.readLong()
        val nano = input.readInt().also(::validateNano)
        val x = input.readInt()
        val y = input.readInt()
        val z = input.readInt()
        val world = input.readDictionaryValueIf(filter.worlds != null, filter.dictionaryValue)
        val objectIdentifier = input.readDictionaryValueIf(filter.objects != null, filter.dictionaryValue)
        val oldObjectIdentifier = input.readDictionaryValueIf(filter.objects != null, filter.dictionaryValue)
        when (recordType) {
            ACTION_RECORD_V2, ACTION_RECORD_V3 -> {
                input.skipFully(Integer.BYTES * 2L)
            }
            ACTION_RECORD_V4 -> {
                input.skipAdaptiveNullableUtf8()
                input.skipAdaptiveNullableUtf8()
            }
        }
        val source = input.readDictionaryValueIf(filter.sources != null, filter.dictionaryValue)
        val playerId = input.readNullableUuidIf(filter.players != null)
        input.skipFully(Integer.BYTES.toLong())
        if (recordType == ACTION_RECORD_V2) {
            input.skipFully(Integer.BYTES.toLong())
        } else {
            input.skipNullableUtf8()
        }
        return if (filter.matches(
                id,
                action,
                epochSecond,
                nano,
                x,
                y,
                z,
                world,
                objectIdentifier,
                oldObjectIdentifier,
                source,
                playerId
            )
        ) {
            id
        } else {
            null
        }
    }

    private fun readStoredActionAt(
        channel: FileChannel,
        input: DataInputStream,
        offset: Long
    ): StoredAction {
        channel.position(offset)
        return when (input.readUnsignedByte()) {
            ACTION_RECORD -> {
                val size = input.readInt()
                if (size <= 0 || size > MAX_RECORD_BYTES) throw EOFException()
                val payload = ByteArray(size)
                input.readFully(payload)
                StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
            }
            ACTION_RECORD_V2 -> {
                StoredAction.readV2(input, ::dictionaryValue)
            }
            ACTION_RECORD_V3 -> {
                StoredAction.readV3(input, ::dictionaryValue)
            }
            ACTION_RECORD_V4 -> {
                StoredAction.readV4(input, ::dictionaryValue)
            }
            else -> {
                throw EOFException("Invalid Irminsul action offset")
            }
        }
    }

    private fun validateNano(nano: Int) {
        if (nano !in 0..999_999_999) throw EOFException("Invalid Irminsul timestamp")
    }

    private fun forEachSegmentAction(
        file: Path,
        maxExclusiveId: Int,
        block: (StoredAction) -> Boolean
    ): Boolean {
        var shouldContinue = true
        fun dictionaryValue(id: Int): String = this@IrminsulLedgerStore.dictionaryValue(id)

        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            if (magic != ACTION_MAGIC || version != FORMAT_VERSION) return true

            while (shouldContinue) {
                try {
                    val action = when (input.readUnsignedByte()) {
                        ACTION_RECORD -> {
                            val size = input.readInt()
                            if (size <= 0 || size > MAX_RECORD_BYTES) return true
                            val payload = ByteArray(size)
                            input.readFully(payload)
                            StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
                        }
                        STRING_DICTIONARY_RECORD -> {
                            input.readInt()
                            input.skipUtf8()
                            null
                        }
                        ACTION_RECORD_V2 -> {
                            StoredAction.readV2(input, ::dictionaryValue)
                        }
                        ACTION_RECORD_V3 -> {
                            StoredAction.readV3(input, ::dictionaryValue)
                        }
                        ACTION_RECORD_V4 -> {
                            StoredAction.readV4(input, ::dictionaryValue)
                        }
                        else -> {
                            return true
                        }
                    }
                    if (action != null && action.id < maxExclusiveId) shouldContinue = block(action)
                } catch (_: EOFException) {
                    return shouldContinue
                }
            }
        }
        return shouldContinue
    }

    private fun StoredAction.matches(params: ActionSearchParams): Boolean {
        params.bounds?.let { bounds ->
            if (x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY ||
                z < bounds.minZ || z > bounds.maxZ
            ) {
                return false
            }
        }
        if (params.after != null && timestamp.isBefore(params.after)) return false
        if (params.before != null && timestamp.isAfter(params.before)) return false
        if (params.rolledBack != null && rolledBack[id] != params.rolledBack) return false
        if (!matchesNegatable(action, params.actions)) return false
        if (!matchesEitherNegatable(objectIdentifier, oldObjectIdentifier, params.objects)) return false
        if (!matchesNegatable(sourceName, params.sourceNames)) return false
        if (!matchesNegatable(world, params.worlds)) return false
        if (!matchesNullableNegatable(sourcePlayerId, params.sourcePlayerIds)) return false
        return true
    }

    private fun candidateIdSequence(params: ActionSearchParams, newestFirst: Boolean): Sequence<Int> {
        val candidates = candidateIds(params)
        return candidateIdSequence(candidates, newestFirst)
    }

    private fun candidateIdSequence(candidates: BitSet?, newestFirst: Boolean): Sequence<Int> {
        return when {
            candidates != null -> candidates.asIdSequence(newestFirst)
            newestFirst -> orderedIds.asReversedSequence()
            else -> orderedIds.asSequence()
        }
    }

    private fun candidateIds(params: ActionSearchParams): BitSet? {
        val sets = ArrayList<BitSet>()

        params.bounds?.let { bounds ->
            val byChunk = BitSet()
            val worlds = allowedValues(params.worlds)
            val worldCandidates = worlds ?: idsByWorld.keys
            for (world in worldCandidates) {
                for (chunkX in (bounds.minX shr 4)..(bounds.maxX shr 4)) {
                    for (chunkZ in (bounds.minZ shr 4)..(bounds.maxZ shr 4)) {
                        idsByChunk[LocationKey(world, chunkX, chunkZ)]?.setBits(byChunk, bitSetCache)
                    }
                }
            }
            sets.add(byChunk)
        }

        allowedValues(params.actions)?.let { values -> sets.add(unionBits(values, idsByAction)) }
        allowedValues(params.sourceNames)?.let { values -> sets.add(unionBits(values, idsBySource)) }
        allowedValues(params.worlds)?.let { values -> sets.add(unionBits(values, idsByWorld)) }
        allowedValues(params.sourcePlayerIds)?.let { values -> sets.add(unionBits(values, idsByPlayer)) }
        allowedValues(params.objects)?.let { values ->
            val objectIds = unionBits(values, idsByObject)
            objectIds.or(unionBits(values, idsByOldObject))
            sets.add(objectIds)
        }

        if (sets.isEmpty()) return null
        if (sets.any { it.nextSetBit(0) < 0 }) return BitSet()

        val smallest = sets.minBy { it.cardinality() }
        val result = smallest.clone() as BitSet
        sets.forEach { set ->
            if (set !== smallest) result.and(set)
        }
        return result
    }

    private fun <T> MutableMap<T, IntList>.add(key: T, id: Int) {
        computeIfAbsent(key) { IntList() }.add(id)
    }

    private fun <T> unionBits(values: Collection<T>, index: Map<T, IntList>): BitSet {
        val result = BitSet()
        values.forEach { value -> index[value]?.setBits(result, bitSetCache) }
        return result
    }

    private fun logMemoryProfile() {
        val runtime = Runtime.getRuntime()
        val hostMemoryMiB = physicalMemoryBytes()?.toMiB()
        val heapMaxMiB = runtime.maxMemory().toMiB()
        val heapCommittedMiB = runtime.totalMemory().toMiB()
        val hostMemory = hostMemoryMiB?.let { "${it}MiB" } ?: "unknown"
        val sparseBlockCount = segmentReverseIndexes.values.sumOf { it.blockCount.toLong() }
        val sparseIndexMiB = segmentReverseIndexes.values.sumOf { it.estimatedBytes() }.toMiB()
        logInfo(
            "Irminsul memory profile. hostMemory=$hostMemory, heapMax=${heapMaxMiB}MiB, " +
                    "heapCommitted=${heapCommittedMiB}MiB, residentIndexes=enabled, " +
                    "hotActionLimit=$hotActionLimit, " +
                    "sparseBlocks=$sparseBlockCount, sparseIndexEstimate=${sparseIndexMiB}MiB, " +
                    "bitSetCacheBudget=${bitSetCache.budgetBytes.toMiB()}MiB, " +
                    "bitSetCacheMinValues=${bitSetCache.minValues}"
        )

        val recommendedHeapMiB = hostMemoryMiB?.let { (it / 2).coerceIn(4096L, 16384L) }
        if (recommendedHeapMiB != null && heapMaxMiB < recommendedHeapMiB / 2) {
            logWarn(
                "Irminsul heap is much smaller than host memory. Consider raising the server -Xmx " +
                        "toward ${recommendedHeapMiB}MiB for large rollback/search workloads."
            )
        }
    }

    private fun physicalMemoryBytes(): Long? {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        for (methodName in listOf("getTotalMemorySize", "getTotalPhysicalMemorySize")) {
            val memory = runCatching {
                val method = bean.javaClass.getMethod(methodName)
                (method.invoke(bean) as? Number)?.toLong()
            }.getOrNull()
            if (memory != null && memory > 0L) return memory
        }
        return null
    }

    private fun segmentFiles(): List<Path> = segmentFiles(actionsDir)

    private fun segmentFiles(directory: Path): List<Path> {
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { stream ->
            stream
                .filter { it.name.startsWith(SEGMENT_PREFIX) && it.name.endsWith(SEGMENT_SUFFIX) }
                .sorted { left, right -> parseSegmentNumber(left).compareTo(parseSegmentNumber(right)) }
                .toList()
        }
    }

    private fun parseSegmentNumber(path: Path): Int =
        path.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toIntOrNull() ?: 0

    private fun maxSegmentBytes(): Long =
        config.irminsulSegmentSizeMiB().coerceAtLeast(8).toLong() * 1024L * 1024L

    private fun StoredAction.toActionType(): ActionType? {
        val typeSupplier = ActionRegistry.getType(action)
        if (typeSupplier == null) {
            logWarn("Unknown action type $action")
            return null
        }

        return typeSupplier.get().also {
            it.id = id
            it.timestamp = timestamp
            it.pos = BlockPos(x, y, z)
            it.world = world
            it.objectIdentifier = objectIdentifier
            it.oldObjectIdentifier = oldObjectIdentifier
            it.objectState = objectState
            it.oldObjectState = oldObjectState
            it.sourceName = sourceName
            it.sourceProfile = sourcePlayerId?.let { uuid -> GameProfile(uuid, sourcePlayerName ?: "") }
            it.extraData = extraData
            it.rolledBack = rolledBack[id]
        }
    }

    private fun StoredAction.isBlockAction(): Boolean {
        blockActionTypes[action]?.let { return it }
        val typeSupplier = ActionRegistry.getType(action) ?: return false
        return (typeSupplier.get() is BlockChangeActionType).also { blockActionTypes[action] = it }
    }

    private fun StoredAction.blockKey(): BlockKey = BlockKey(world, x, y, z)

    internal class SegmentSummary {
        var actionCount = 0
            private set
        private var minId = Int.MAX_VALUE
        private var minTimestamp = Instant.MAX
        private var maxTimestamp = Instant.MIN
        private var minX = Int.MAX_VALUE
        private var maxX = Int.MIN_VALUE
        private var minY = Int.MAX_VALUE
        private var maxY = Int.MIN_VALUE
        private var minZ = Int.MAX_VALUE
        private var maxZ = Int.MIN_VALUE
        private val actions = HashMap<String, Int>()
        private val worlds = HashMap<Identifier, Int>()
        private val objects = HashMap<Identifier, Int>()
        private val sources = HashMap<String, Int>()
        private val players = HashMap<UUID, Int>()
        private val chunkBloom = BitSet(CHUNK_BLOOM_BITS)

        fun add(action: StoredAction) {
            actionCount += 1
            minId = minOf(minId, action.id)
            minTimestamp = minOf(minTimestamp, action.timestamp)
            maxTimestamp = maxOf(maxTimestamp, action.timestamp)
            minX = minOf(minX, action.x)
            maxX = maxOf(maxX, action.x)
            minY = minOf(minY, action.y)
            maxY = maxOf(maxY, action.y)
            minZ = minOf(minZ, action.z)
            maxZ = maxOf(maxZ, action.z)
            actions.increment(action.action)
            worlds.increment(action.world)
            objects.increment(action.objectIdentifier)
            if (action.oldObjectIdentifier != action.objectIdentifier) objects.increment(action.oldObjectIdentifier)
            sources.increment(action.sourceName)
            action.sourcePlayerId?.let { players.increment(it) }
            addChunk(action.world, action.x shr 4, action.z shr 4)
        }

        fun countActions(values: Set<String>): Long = values.sumOf { actions[it]?.toLong() ?: 0L }

        fun countWorlds(values: Set<Identifier>): Long = values.sumOf { worlds[it]?.toLong() ?: 0L }

        fun countSources(values: Set<String>): Long = values.sumOf { sources[it]?.toLong() ?: 0L }

        fun countPlayers(values: Set<UUID>): Long = values.sumOf { players[it]?.toLong() ?: 0L }

        fun countObject(value: Identifier): Long = objects[value]?.toLong() ?: 0L

        fun mightMatch(params: ActionSearchParams?, maxExclusiveId: Int): Boolean {
            if (minId >= maxExclusiveId) return false
            if (params == null) return true
            if (params.after != null && maxTimestamp.isBefore(params.after)) return false
            if (params.before != null && minTimestamp.isAfter(params.before)) return false
            params.bounds?.let { bounds ->
                if (maxX < bounds.minX || minX > bounds.maxX ||
                    maxY < bounds.minY || minY > bounds.maxY ||
                    maxZ < bounds.minZ || minZ > bounds.maxZ
                ) {
                    return false
                }
                if (!mightContainAnyChunk(bounds, params.worlds)) return false
            }
            if (!actions.keys.containsAnyAllowed(params.actions)) return false
            if (!worlds.keys.containsAnyAllowed(params.worlds)) return false
            if (!objects.keys.containsAnyAllowed(params.objects)) return false
            if (!sources.keys.containsAnyAllowed(params.sourceNames)) return false
            if (!players.keys.containsAnyAllowed(params.sourcePlayerIds)) return false
            return true
        }

        private fun <T> MutableMap<T, Int>.increment(value: T) {
            this[value] = (this[value] ?: 0) + 1
        }

        private fun addChunk(world: Identifier, chunkX: Int, chunkZ: Int) {
            chunkBloom.set(chunkHash(world, chunkX, chunkZ, CHUNK_HASH_SALT_1))
            chunkBloom.set(chunkHash(world, chunkX, chunkZ, CHUNK_HASH_SALT_2))
        }

        private fun mightContainAnyChunk(
            bounds: BlockBox,
            worldFilters: Collection<Negatable<Identifier>>?
        ): Boolean {
            val candidateWorlds = allowedValues(worldFilters) ?: worlds.keys
            val minChunkX = bounds.minX shr 4
            val maxChunkX = bounds.maxX shr 4
            val minChunkZ = bounds.minZ shr 4
            val maxChunkZ = bounds.maxZ shr 4
            val chunkCount = (maxChunkX.toLong() - minChunkX.toLong() + 1L) *
                    (maxChunkZ.toLong() - minChunkZ.toLong() + 1L)
            if (chunkCount * candidateWorlds.size.toLong() > MAX_CHUNK_BLOOM_PROBES) return true

            candidateWorlds.forEach { world ->
                for (chunkX in minChunkX..maxChunkX) {
                    for (chunkZ in minChunkZ..maxChunkZ) {
                        val first = chunkHash(world, chunkX, chunkZ, CHUNK_HASH_SALT_1)
                        val second = chunkHash(world, chunkX, chunkZ, CHUNK_HASH_SALT_2)
                        if (chunkBloom[first] && chunkBloom[second]) return true
                    }
                }
            }
            return false
        }

        private fun chunkHash(world: Identifier, chunkX: Int, chunkZ: Int, salt: Int): Int {
            var hash = world.hashCode() xor salt
            hash = hash xor chunkX * -0x61c88647
            hash = hash xor chunkZ * -0x7a143595
            hash = hash xor (hash ushr 16)
            hash *= -0x7a143595
            hash = hash xor (hash ushr 15)
            return hash and CHUNK_BLOOM_BITS - 1
        }

        companion object {
            private const val CHUNK_HASH_SALT_1 = 0x13579BDF
            private const val CHUNK_HASH_SALT_2 = 0x2468ACE
        }
    }

    private data class LocationKey(val world: Identifier, val chunkX: Int, val chunkZ: Int)

    private data class BlockKey(val world: Identifier, val x: Int, val y: Int, val z: Int)

    private data class PageScan(val actions: List<StoredAction>, val totalMatches: Long)

    internal data class SegmentWriteResult(val segments: IntArray, val offsets: LongArray)

    private class DiskSearchFilter(
        val params: ActionSearchParams,
        val dictionaryValue: (Int) -> String,
        private val rollbackState: (Int) -> Boolean
    ) {
        val bounds = params.bounds
        val before = params.before
        val after = params.after
        private val rolledBack = params.rolledBack
        val actions = params.actions.takeUnless { it.isNullOrEmpty() }
        val objects = params.objects.takeUnless { it.isNullOrEmpty() }
            ?.map { Negatable(it.property.toString(), it.allowed) }
        val sources = params.sourceNames.takeUnless { it.isNullOrEmpty() }
        val players = params.sourcePlayerIds.takeUnless { it.isNullOrEmpty() }
        val worlds = params.worlds.takeUnless { it.isNullOrEmpty() }
            ?.map { Negatable(it.property.toString(), it.allowed) }

        fun matches(
            id: Int,
            action: String?,
            epochSecond: Long,
            nano: Int,
            x: Int,
            y: Int,
            z: Int,
            world: String?,
            objectIdentifier: String?,
            oldObjectIdentifier: String?,
            source: String?,
            playerId: UUID?
        ): Boolean {
            bounds?.let { box ->
                if (x < box.minX || x > box.maxX || y < box.minY || y > box.maxY ||
                    z < box.minZ || z > box.maxZ
                ) {
                    return false
                }
            }
            if (after != null && timestampBefore(epochSecond, nano, after)) return false
            if (before != null && timestampAfter(epochSecond, nano, before)) return false
            if (rolledBack != null && rollbackState(id) != rolledBack) return false
            if (actions != null && !matchesNegatable(checkNotNull(action), actions)) return false
            if (objects != null && !matchesEitherNegatable(
                    checkNotNull(objectIdentifier),
                    checkNotNull(oldObjectIdentifier),
                    objects
                )
            ) {
                return false
            }
            if (sources != null && !matchesNegatable(checkNotNull(source), sources)) return false
            if (worlds != null && !matchesNegatable(checkNotNull(world), worlds)) return false
            if (players != null && !matchesNullableNegatable(playerId, players)) return false
            return true
        }

        private fun timestampBefore(epochSecond: Long, nano: Int, instant: Instant): Boolean =
            epochSecond < instant.epochSecond || epochSecond == instant.epochSecond && nano < instant.nano

        private fun timestampAfter(epochSecond: Long, nano: Int, instant: Instant): Boolean =
            epochSecond > instant.epochSecond || epochSecond == instant.epochSecond && nano > instant.nano
    }

    private class StoredActionGroup {
        val actionIds = ArrayList<Int>()
        lateinit var representative: StoredAction
        lateinit var expectedCurrent: StoredAction

        fun add(action: StoredAction) {
            if (actionIds.isEmpty()) {
                expectedCurrent = action
            }
            actionIds.add(action.id)
            representative = action
        }
    }

    private class DenseActionTable {
        private var values = arrayOfNulls<StoredAction>(INITIAL_CAPACITY)
        private var baseId = 0

        var size = 0
            private set

        operator fun get(id: Int): StoredAction? {
            val index = id - baseId
            return if (index >= 0 && index < values.size) values[index] else null
        }

        operator fun set(id: Int, action: StoredAction) {
            ensureBase(id)
            val index = id - baseId
            ensureCapacity(index)
            if (values[index] == null) size += 1
            values[index] = action
        }

        fun containsKey(id: Int): Boolean = get(id) != null

        fun clear() {
            values = arrayOfNulls(INITIAL_CAPACITY)
            baseId = 0
            size = 0
        }

        private fun ensureBase(id: Int) {
            if (size == 0) baseId = id
        }

        private fun ensureCapacity(index: Int) {
            if (index < values.size) return
            var capacity = values.size
            while (capacity <= index) capacity *= 2
            values = values.copyOf(capacity)
        }

        companion object {
            private const val INITIAL_CAPACITY = 1024
        }
    }

    private class IntRingBuffer {
        private var values = IntArray(INITIAL_CAPACITY)
        private var head = 0
        var size = 0
            private set

        fun add(value: Int) {
            ensureCapacity(size + 1)
            values[(head + size) % values.size] = value
            size += 1
        }

        fun removeFirst(): Int {
            check(size > 0)
            val value = values[head]
            head = (head + 1) % values.size
            size -= 1
            return value
        }

        fun clear() {
            values = IntArray(INITIAL_CAPACITY)
            head = 0
            size = 0
        }

        fun firstOrNull(): Int? = if (size == 0) null else values[head]

        fun forEach(block: (Int) -> Unit) {
            for (index in 0 until size) block(values[(head + index) % values.size])
        }

        fun asSequence(): Sequence<Int> = sequence {
            for (index in 0 until size) yield(values[(head + index) % values.size])
        }

        fun asReversedSequence(): Sequence<Int> = sequence {
            for (index in size - 1 downTo 0) yield(values[(head + index) % values.size])
        }

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var capacity = values.size
            while (capacity < required) capacity *= 2
            val newValues = IntArray(capacity)
            for (index in 0 until size) newValues[index] = values[(head + index) % values.size]
            values = newValues
            head = 0
        }

        companion object {
            private const val INITIAL_CAPACITY = 1024
        }
    }

    private class IntList {
        private var values = IntArray(INITIAL_CAPACITY)
        private var size = 0
        private var maxValue = 0
        private var cachedBits: BitSet? = null

        fun add(value: Int) {
            ensureCapacity(size + 1)
            values[size] = value
            size += 1
            if (value > maxValue) maxValue = value
            cachedBits?.set(value)
        }

        fun setBits(target: BitSet, cachePolicy: BitSetCachePolicy) {
            val bits = cachedBits ?: buildCachedBits(cachePolicy)
            if (bits != null) {
                target.or(bits)
                return
            }

            for (index in 0 until size) {
                target.set(values[index])
            }
        }

        private fun buildCachedBits(cachePolicy: BitSetCachePolicy): BitSet? {
            if (size < cachePolicy.minValues || maxValue <= 0) return null

            val estimatedBytes = cachePolicy.estimateBitSetBytes(maxValue)
            if (!cachePolicy.tryReserve(estimatedBytes)) return null

            val bits = BitSet(maxValue + 1)
            for (index in 0 until size) {
                bits.set(values[index])
            }
            cachedBits = bits
            return bits
        }

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var capacity = values.size
            while (capacity < required) capacity *= 2
            values = values.copyOf(capacity)
        }

        companion object {
            private const val INITIAL_CAPACITY = 8
        }
    }

    internal class SegmentReverseIndex(private val stride: Int = REVERSE_CHECKPOINT_STRIDE) {
        private var offsets = LongArray(INITIAL_CAPACITY)
        private var firstActionIds = IntArray(INITIAL_CAPACITY)
        private var summaryCounts = IntArray(INITIAL_CAPACITY)
        private var minEpochSeconds = LongArray(INITIAL_CAPACITY)
        private var maxEpochSeconds = LongArray(INITIAL_CAPACITY)
        private var minNanos = IntArray(INITIAL_CAPACITY)
        private var maxNanos = IntArray(INITIAL_CAPACITY)
        private var minX = IntArray(INITIAL_CAPACITY)
        private var maxX = IntArray(INITIAL_CAPACITY)
        private var minY = IntArray(INITIAL_CAPACITY)
        private var maxY = IntArray(INITIAL_CAPACITY)
        private var minZ = IntArray(INITIAL_CAPACITY)
        private var maxZ = IntArray(INITIAL_CAPACITY)
        private var actionBlooms = LongArray(INITIAL_CAPACITY)
        private var worldBlooms = LongArray(INITIAL_CAPACITY)
        private var objectBlooms = LongArray(INITIAL_CAPACITY)
        private var sourceBlooms = LongArray(INITIAL_CAPACITY)
        private var playerBlooms = LongArray(INITIAL_CAPACITY)
        private var chunkBlooms = Array(CHUNK_BLOOM_WORDS) { LongArray(INITIAL_CAPACITY) }
        private var actionCount = 0
        var blockCount = 0
            private set

        init {
            require(stride > 0)
        }

        fun add(actionId: Int, offset: Long) {
            val blockIndex = beginAction(actionId, offset)
            summaryCounts[blockIndex] = UNKNOWN_SUMMARY
        }

        fun add(action: StoredAction, offset: Long) {
            val blockIndex = beginAction(action.id, offset)
            if (summaryCounts[blockIndex] < 0) return

            val timestamp = action.timestamp
            if (summaryCounts[blockIndex] == 0) {
                minEpochSeconds[blockIndex] = timestamp.epochSecond
                maxEpochSeconds[blockIndex] = timestamp.epochSecond
                minNanos[blockIndex] = timestamp.nano
                maxNanos[blockIndex] = timestamp.nano
                minX[blockIndex] = action.x
                maxX[blockIndex] = action.x
                minY[blockIndex] = action.y
                maxY[blockIndex] = action.y
                minZ[blockIndex] = action.z
                maxZ[blockIndex] = action.z
            } else {
                updateTimestampRange(blockIndex, timestamp)
                minX[blockIndex] = minOf(minX[blockIndex], action.x)
                maxX[blockIndex] = maxOf(maxX[blockIndex], action.x)
                minY[blockIndex] = minOf(minY[blockIndex], action.y)
                maxY[blockIndex] = maxOf(maxY[blockIndex], action.y)
                minZ[blockIndex] = minOf(minZ[blockIndex], action.z)
                maxZ[blockIndex] = maxOf(maxZ[blockIndex], action.z)
            }
            summaryCounts[blockIndex] += 1
            actionBlooms[blockIndex] = actionBlooms[blockIndex] or bloomMask(action.action.hashCode())
            worldBlooms[blockIndex] = worldBlooms[blockIndex] or bloomMask(action.world.hashCode())
            objectBlooms[blockIndex] = objectBlooms[blockIndex] or bloomMask(action.objectIdentifier.hashCode())
            objectBlooms[blockIndex] = objectBlooms[blockIndex] or bloomMask(action.oldObjectIdentifier.hashCode())
            sourceBlooms[blockIndex] = sourceBlooms[blockIndex] or bloomMask(action.sourceName.hashCode())
            action.sourcePlayerId?.let { playerId ->
                playerBlooms[blockIndex] = playerBlooms[blockIndex] or bloomMask(playerId.hashCode())
            }
            addChunk(blockIndex, action.x shr 4, action.z shr 4)
        }

        fun offset(blockIndex: Int): Long = offsets[blockIndex]

        fun firstActionId(blockIndex: Int): Int = firstActionIds[blockIndex]

        fun estimatedBytes(): Long = offsets.size.toLong() * ESTIMATED_BYTES_PER_BLOCK

        fun mightMatch(blockIndex: Int, params: ActionSearchParams, maxExclusiveId: Int): Boolean {
            if (firstActionIds[blockIndex] >= maxExclusiveId) return false
            if (summaryCounts[blockIndex] <= 0) return true
            params.bounds?.let { bounds ->
                if (maxX[blockIndex] < bounds.minX || minX[blockIndex] > bounds.maxX ||
                    maxY[blockIndex] < bounds.minY || minY[blockIndex] > bounds.maxY ||
                    maxZ[blockIndex] < bounds.minZ || minZ[blockIndex] > bounds.maxZ
                ) {
                    return false
                }
                if (!mightContainChunk(blockIndex, bounds)) return false
            }
            params.after?.let { after ->
                if (timestampBefore(maxEpochSeconds[blockIndex], maxNanos[blockIndex], after)) return false
            }
            params.before?.let { before ->
                if (timestampAfter(minEpochSeconds[blockIndex], minNanos[blockIndex], before)) return false
            }
            if (!bloomMightContainAllowed(actionBlooms[blockIndex], params.actions)) return false
            if (!bloomMightContainAllowed(worldBlooms[blockIndex], params.worlds)) return false
            if (!bloomMightContainAllowed(objectBlooms[blockIndex], params.objects)) return false
            if (!bloomMightContainAllowed(sourceBlooms[blockIndex], params.sourceNames)) return false
            if (!bloomMightContainAllowed(playerBlooms[blockIndex], params.sourcePlayerIds)) return false
            return true
        }

        private fun beginAction(actionId: Int, offset: Long): Int {
            if (actionCount % stride == 0) {
                ensureCapacity(blockCount + 1)
                offsets[blockCount] = offset
                firstActionIds[blockCount] = actionId
                blockCount += 1
            }
            actionCount += 1
            return blockCount - 1
        }

        private fun updateTimestampRange(blockIndex: Int, timestamp: Instant) {
            if (timestampBefore(
                    timestamp.epochSecond,
                    timestamp.nano,
                    minEpochSeconds[blockIndex],
                    minNanos[blockIndex]
                )
            ) {
                minEpochSeconds[blockIndex] = timestamp.epochSecond
                minNanos[blockIndex] = timestamp.nano
            }
            if (timestampAfter(
                    timestamp.epochSecond,
                    timestamp.nano,
                    maxEpochSeconds[blockIndex],
                    maxNanos[blockIndex]
                )
            ) {
                maxEpochSeconds[blockIndex] = timestamp.epochSecond
                maxNanos[blockIndex] = timestamp.nano
            }
        }

        private fun addChunk(blockIndex: Int, chunkX: Int, chunkZ: Int) {
            setChunkBit(blockIndex, chunkHash(chunkX, chunkZ, CHUNK_HASH_SALT_1))
            setChunkBit(blockIndex, chunkHash(chunkX, chunkZ, CHUNK_HASH_SALT_2))
        }

        private fun setChunkBit(blockIndex: Int, bit: Int) {
            val word = bit ushr 6
            chunkBlooms[word][blockIndex] = chunkBlooms[word][blockIndex] or (1L shl bit)
        }

        private fun mightContainChunk(blockIndex: Int, bounds: BlockBox): Boolean {
            val minChunkX = bounds.minX shr 4
            val maxChunkX = bounds.maxX shr 4
            val minChunkZ = bounds.minZ shr 4
            val maxChunkZ = bounds.maxZ shr 4
            val chunkCount = (maxChunkX.toLong() - minChunkX.toLong() + 1L) *
                    (maxChunkZ.toLong() - minChunkZ.toLong() + 1L)
            if (chunkCount > MAX_BLOCK_CHUNK_PROBES) return true

            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val first = chunkHash(chunkX, chunkZ, CHUNK_HASH_SALT_1)
                    val second = chunkHash(chunkX, chunkZ, CHUNK_HASH_SALT_2)
                    if (hasChunkBit(blockIndex, first) && hasChunkBit(blockIndex, second)) return true
                }
            }
            return false
        }

        private fun hasChunkBit(blockIndex: Int, bit: Int): Boolean =
            chunkBlooms[bit ushr 6][blockIndex] and (1L shl bit) != 0L

        private fun ensureCapacity(required: Int) {
            if (required <= offsets.size) return
            var capacity = offsets.size
            while (capacity < required) capacity *= 2
            offsets = offsets.copyOf(capacity)
            firstActionIds = firstActionIds.copyOf(capacity)
            summaryCounts = summaryCounts.copyOf(capacity)
            minEpochSeconds = minEpochSeconds.copyOf(capacity)
            maxEpochSeconds = maxEpochSeconds.copyOf(capacity)
            minNanos = minNanos.copyOf(capacity)
            maxNanos = maxNanos.copyOf(capacity)
            minX = minX.copyOf(capacity)
            maxX = maxX.copyOf(capacity)
            minY = minY.copyOf(capacity)
            maxY = maxY.copyOf(capacity)
            minZ = minZ.copyOf(capacity)
            maxZ = maxZ.copyOf(capacity)
            actionBlooms = actionBlooms.copyOf(capacity)
            worldBlooms = worldBlooms.copyOf(capacity)
            objectBlooms = objectBlooms.copyOf(capacity)
            sourceBlooms = sourceBlooms.copyOf(capacity)
            playerBlooms = playerBlooms.copyOf(capacity)
            for (word in chunkBlooms.indices) chunkBlooms[word] = chunkBlooms[word].copyOf(capacity)
        }

        companion object {
            private const val INITIAL_CAPACITY = 16
            private const val UNKNOWN_SUMMARY = -1
            private const val CHUNK_BLOOM_WORDS = 4
            private const val CHUNK_HASH_SALT_1 = 0x13579BDF
            private const val CHUNK_HASH_SALT_2 = 0x2468ACE
            private const val ESTIMATED_BYTES_PER_BLOCK = 136L

            private fun bloomMask(valueHash: Int): Long {
                val first = mixHash(valueHash)
                val second = mixHash(valueHash xor -0x61c88647)
                val firstBit = 1L shl first
                val secondBit = 1L shl second
                return firstBit or secondBit
            }

            private fun chunkHash(chunkX: Int, chunkZ: Int, salt: Int): Int {
                val mask = CHUNK_BLOOM_WORDS * Long.SIZE_BITS - 1
                return mixHash(chunkX * -0x61c88647 xor chunkZ * -0x7a143595 xor salt) and mask
            }

            private fun mixHash(value: Int): Int {
                var hash = value
                hash = hash xor (hash ushr 16)
                hash *= -0x7a143595
                hash = hash xor (hash ushr 15)
                return hash
            }

            private fun <T> bloomMightContainAllowed(
                bloom: Long,
                values: Collection<Negatable<T>>?
            ): Boolean {
                if (values.isNullOrEmpty()) return true
                var hasAllowed = false
                values.forEach { value ->
                    if (value.allowed) {
                        hasAllowed = true
                        val mask = bloomMask(value.property.hashCode())
                        if (bloom and mask == mask) return true
                    }
                }
                return !hasAllowed
            }

            private fun timestampBefore(epochSecond: Long, nano: Int, instant: Instant): Boolean =
                epochSecond < instant.epochSecond || epochSecond == instant.epochSecond && nano < instant.nano

            private fun timestampAfter(epochSecond: Long, nano: Int, instant: Instant): Boolean =
                epochSecond > instant.epochSecond || epochSecond == instant.epochSecond && nano > instant.nano

            private fun timestampBefore(
                epochSecond: Long,
                nano: Int,
                otherEpochSecond: Long,
                otherNano: Int
            ): Boolean = epochSecond < otherEpochSecond || epochSecond == otherEpochSecond && nano < otherNano

            private fun timestampAfter(
                epochSecond: Long,
                nano: Int,
                otherEpochSecond: Long,
                otherNano: Int
            ): Boolean = epochSecond > otherEpochSecond || epochSecond == otherEpochSecond && nano > otherNano
        }
    }

    private class BitSetCachePolicy(heapMaxBytes: Long, hostMemoryBytes: Long?) {
        val budgetBytes: Long = computeBudget(heapMaxBytes, hostMemoryBytes)
        val minValues: Int = when {
            budgetBytes >= 1024L * MIB_BYTES -> 2048
            budgetBytes >= 256L * MIB_BYTES -> 4096
            else -> 8192
        }
        private var reservedBytes = 0L

        fun estimateBitSetBytes(maxValue: Int): Long {
            val words = (maxValue.toLong() + Long.SIZE_BITS) / Long.SIZE_BITS
            return words * java.lang.Long.BYTES
        }

        @Synchronized
        fun tryReserve(bytes: Long): Boolean {
            if (bytes <= 0L) return true
            if (budgetBytes <= 0L || bytes > budgetBytes / 2) return false
            if (reservedBytes + bytes > budgetBytes) return false
            reservedBytes += bytes
            return true
        }

        @Synchronized
        fun reset() {
            reservedBytes = 0L
        }

        companion object {
            private fun computeBudget(heapMaxBytes: Long, hostMemoryBytes: Long?): Long {
                val configuredMiB = config.irminsulIndexCacheMiB()
                if (configuredMiB >= 0) return configuredMiB.toLong() * MIB_BYTES
                if (heapMaxBytes <= 0L) return 0L

                val heapBudget = heapMaxBytes / 8
                val hostBudget = hostMemoryBytes?.let { it / 16 } ?: heapBudget
                return minOf(heapBudget, hostBudget, 2048L * MIB_BYTES)
            }
        }
    }

    data class StoredAction(
        val id: Int,
        val action: String,
        val timestamp: Instant,
        val x: Int,
        val y: Int,
        val z: Int,
        val world: Identifier,
        val objectIdentifier: Identifier,
        val oldObjectIdentifier: Identifier,
        val objectState: String?,
        val oldObjectState: String?,
        val sourceName: String,
        val sourcePlayerId: UUID?,
        val sourcePlayerName: String?,
        val extraData: String?
    ) {
        fun withId(id: Int) = copy(id = id)

        fun write(output: DataOutputStream) {
            output.writeInt(id)
            output.writeUtf8(action)
            output.writeLong(timestamp.epochSecond)
            output.writeInt(timestamp.nano)
            output.writeInt(x)
            output.writeInt(y)
            output.writeInt(z)
            output.writeIdentifier(world)
            output.writeIdentifier(objectIdentifier)
            output.writeIdentifier(oldObjectIdentifier)
            output.writeNullableUtf8(objectState)
            output.writeNullableUtf8(oldObjectState)
            output.writeUtf8(sourceName)
            output.writeNullableUuid(sourcePlayerId)
            output.writeNullableUtf8(sourcePlayerName)
            output.writeNullableUtf8(extraData)
        }

        fun writeV2(output: DataOutputStream, dictionaryId: (String) -> Int) {
            output.writeInt(id)
            output.writeInt(dictionaryId(action))
            output.writeLong(timestamp.epochSecond)
            output.writeInt(timestamp.nano)
            output.writeInt(x)
            output.writeInt(y)
            output.writeInt(z)
            output.writeInt(dictionaryId(world.toString()))
            output.writeInt(dictionaryId(objectIdentifier.toString()))
            output.writeInt(dictionaryId(oldObjectIdentifier.toString()))
            output.writeNullableDictionaryId(objectState, dictionaryId)
            output.writeNullableDictionaryId(oldObjectState, dictionaryId)
            output.writeInt(dictionaryId(sourceName))
            output.writeNullableUuid(sourcePlayerId)
            output.writeNullableDictionaryId(sourcePlayerName, dictionaryId)
            output.writeNullableDictionaryId(extraData, dictionaryId)
        }

        fun writeV3(output: DataOutputStream, dictionaryId: (String) -> Int) {
            output.writeInt(id)
            output.writeInt(dictionaryId(action))
            output.writeLong(timestamp.epochSecond)
            output.writeInt(timestamp.nano)
            output.writeInt(x)
            output.writeInt(y)
            output.writeInt(z)
            output.writeInt(dictionaryId(world.toString()))
            output.writeInt(dictionaryId(objectIdentifier.toString()))
            output.writeInt(dictionaryId(oldObjectIdentifier.toString()))
            output.writeNullableDictionaryId(objectState, dictionaryId)
            output.writeNullableDictionaryId(oldObjectState, dictionaryId)
            output.writeInt(dictionaryId(sourceName))
            output.writeNullableUuid(sourcePlayerId)
            output.writeNullableDictionaryId(sourcePlayerName, dictionaryId)
            output.writeNullableUtf8(extraData)
        }

        fun writeV4(output: DataOutputStream, dictionaryId: (String) -> Int?) {
            output.writeInt(id)
            output.writeInt(checkNotNull(dictionaryId(action)))
            output.writeLong(timestamp.epochSecond)
            output.writeInt(timestamp.nano)
            output.writeInt(x)
            output.writeInt(y)
            output.writeInt(z)
            output.writeInt(checkNotNull(dictionaryId(world.toString())))
            output.writeInt(checkNotNull(dictionaryId(objectIdentifier.toString())))
            output.writeInt(checkNotNull(dictionaryId(oldObjectIdentifier.toString())))
            output.writeAdaptiveNullableUtf8(objectState, dictionaryId)
            output.writeAdaptiveNullableUtf8(oldObjectState, dictionaryId)
            output.writeInt(checkNotNull(dictionaryId(sourceName)))
            output.writeNullableUuid(sourcePlayerId)
            output.writeInt(sourcePlayerName?.let { checkNotNull(dictionaryId(it)) } ?: 0)
            output.writeNullableUtf8(extraData)
        }

        companion object {
            fun from(action: ActionType, id: Int): StoredAction = StoredAction(
                id = id,
                action = action.identifier,
                timestamp = action.timestamp,
                x = action.pos.x,
                y = action.pos.y,
                z = action.pos.z,
                world = action.world ?: Ledger.server.overworld.registryKey.value,
                objectIdentifier = action.objectIdentifier,
                oldObjectIdentifier = action.oldObjectIdentifier,
                objectState = action.objectState,
                oldObjectState = action.oldObjectState,
                sourceName = action.sourceName,
                sourcePlayerId = action.sourceProfile?.id,
                sourcePlayerName = action.sourceProfile?.name,
                extraData = action.extraData
            )

            fun read(input: DataInputStream): StoredAction = StoredAction(
                id = input.readInt(),
                action = input.readUtf8(),
                timestamp = input.readInstant(),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = input.readIdentifier(),
                objectIdentifier = input.readIdentifier(),
                oldObjectIdentifier = input.readIdentifier(),
                objectState = input.readNullableUtf8(),
                oldObjectState = input.readNullableUtf8(),
                sourceName = input.readUtf8(),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableUtf8(),
                extraData = input.readNullableUtf8()
            )

            fun readV2(
                input: DataInputStream,
                dictionaryValue: (Int) -> String,
                retain: (Int) -> Unit = {}
            ): StoredAction = StoredAction(
                id = input.readInt(),
                action = input.readRetainedDictionaryValue(dictionaryValue, retain),
                timestamp = input.readInstant(),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("overworld"),
                objectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                oldObjectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                objectState = input.readNullableDictionaryId(dictionaryValue),
                oldObjectState = input.readNullableDictionaryId(dictionaryValue),
                sourceName = input.readRetainedDictionaryValue(dictionaryValue, retain),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableRetainedDictionaryValue(dictionaryValue, retain),
                extraData = input.readNullableDictionaryId(dictionaryValue)
            )

            fun readV3(
                input: DataInputStream,
                dictionaryValue: (Int) -> String,
                retain: (Int) -> Unit = {}
            ): StoredAction = StoredAction(
                id = input.readInt(),
                action = input.readRetainedDictionaryValue(dictionaryValue, retain),
                timestamp = input.readInstant(),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("overworld"),
                objectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                oldObjectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                objectState = input.readNullableDictionaryId(dictionaryValue),
                oldObjectState = input.readNullableDictionaryId(dictionaryValue),
                sourceName = input.readRetainedDictionaryValue(dictionaryValue, retain),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableRetainedDictionaryValue(dictionaryValue, retain),
                extraData = input.readNullableUtf8()
            )

            fun readV4(
                input: DataInputStream,
                dictionaryValue: (Int) -> String,
                retain: (Int) -> Unit = {}
            ): StoredAction = StoredAction(
                id = input.readInt(),
                action = input.readRetainedDictionaryValue(dictionaryValue, retain),
                timestamp = input.readInstant(),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("overworld"),
                objectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                oldObjectIdentifier = Identifier.tryParse(input.readRetainedDictionaryValue(dictionaryValue, retain))
                    ?: Identifier.ofVanilla("air"),
                objectState = input.readAdaptiveNullableUtf8(dictionaryValue),
                oldObjectState = input.readAdaptiveNullableUtf8(dictionaryValue),
                sourceName = input.readRetainedDictionaryValue(dictionaryValue, retain),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableRetainedDictionaryValue(dictionaryValue, retain),
                extraData = input.readNullableUtf8()
            )
        }
    }

    internal class SegmentWriter(
        private val directory: Path,
        startSegment: Int,
        private val maxSegmentBytes: Long,
        private val fsyncOnBatch: Boolean
    ) : Closeable {
        var segmentNumber = startSegment
            private set

        private lateinit var channel: FileChannel
        private lateinit var stream: FileOutputStream
        private lateinit var output: DataOutputStream
        private var segmentBytes = 0L

        init {
            open(segmentNumber)
        }

        @Suppress("TooGenericExceptionCaught")
        fun write(
            strings: List<Pair<Int, String>>,
            actions: List<StoredAction>,
            dictionaryId: (String) -> Int?
        ): SegmentWriteResult {
            val startSegment = segmentNumber
            val startBytes = segmentBytes
            val actionSegments = IntArray(actions.size)
            val actionOffsets = LongArray(actions.size)
            try {
                strings.forEach { (id, value) ->
                    val recordBytes = 1L + Integer.BYTES + utf8RecordSize(value)
                    rotateIfNeeded(recordBytes)
                    output.writeByte(STRING_DICTIONARY_RECORD)
                    output.writeInt(id)
                    output.writeUtf8(value)
                    segmentBytes += recordBytes
                }
                actions.forEachIndexed { index, action ->
                    val recordBytes = 1L + actionV4RecordSize(action, dictionaryId)
                    rotateIfNeeded(recordBytes)
                    actionOffsets[index] = segmentBytes
                    output.writeByte(ACTION_RECORD_V4)
                    action.writeV4(output, dictionaryId)
                    segmentBytes += recordBytes
                    actionSegments[index] = segmentNumber
                }
                output.flush()
                if (fsyncOnBatch) channel.force(false)
            } catch (throwable: Throwable) {
                runCatching { rollbackBatch(startSegment, startBytes) }
                    .onFailure(throwable::addSuppressed)
                throw throwable
            }
            return SegmentWriteResult(actionSegments, actionOffsets)
        }

        private fun rotateIfNeeded(recordBytes: Long) {
            if (segmentBytes <= ACTION_FILE_HEADER_BYTES || segmentBytes + recordBytes <= maxSegmentBytes) return

            close()
            segmentNumber += 1
            open(segmentNumber)
        }

        private fun open(number: Int) {
            val file = segmentPath(number)
            val exists = file.exists()
            stream = FileOutputStream(file.toFile(), true)
            channel = stream.channel
            output = DataOutputStream(BufferedOutputStream(stream))
            segmentBytes = if (exists) file.fileSize() else 0L
            if (!exists || file.fileSize() == 0L) {
                output.writeInt(ACTION_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.flush()
                segmentBytes = ACTION_FILE_HEADER_BYTES
            }
        }

        private fun rollbackBatch(startSegment: Int, startBytes: Long) {
            runCatching(::closeCurrent)
            for (segment in segmentNumber downTo startSegment + 1) {
                Files.deleteIfExists(segmentPath(segment))
            }
            RandomAccessFile(segmentPath(startSegment).toFile(), "rw").use { file ->
                file.setLength(startBytes)
            }
            segmentNumber = startSegment
            open(startSegment)
        }

        private fun segmentPath(number: Int): Path =
            directory.resolve("$SEGMENT_PREFIX${number.toString().padStart(8, '0')}$SEGMENT_SUFFIX")

        private fun closeCurrent() {
            try {
                if (::output.isInitialized) output.close()
            } finally {
                if (::channel.isInitialized && channel.isOpen) channel.close()
            }
        }

        override fun close() {
            closeCurrent()
        }
    }

    internal class StateWriter(private val file: Path, private val fsyncOnBatch: Boolean) : Closeable {
        private lateinit var stream: FileOutputStream
        private lateinit var channel: FileChannel
        private lateinit var output: DataOutputStream

        init {
            open()
        }

        private fun open() {
            val exists = file.exists()
            stream = FileOutputStream(file.toFile(), true)
            channel = stream.channel
            output = DataOutputStream(BufferedOutputStream(stream))
            if (!exists || file.fileSize() == 0L) {
                output.writeInt(STATE_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.flush()
            }
        }

        fun writeRollbackStatesCompressed(ids: IntArray, count: Int, value: Boolean) {
            if (count <= 0) return

            Arrays.sort(ids, 0, count)
            writeAtomically {
                var start = ids[0]
                var runLength = 1
                for (index in 1 until count) {
                    val id = ids[index]
                    if (id == start + runLength) {
                        runLength += 1
                    } else {
                        writeRollbackRun(start, runLength, value)
                        start = id
                        runLength = 1
                    }
                }
                writeRollbackRun(start, runLength, value)
            }
        }

        private fun writeRollbackRun(start: Int, count: Int, value: Boolean) {
            output.writeByte(STATE_ROLLBACK_RANGE_RECORD)
            output.writeInt(start)
            output.writeInt(count)
            output.writeBoolean(value)
        }

        fun writePlayer(player: PlayerResult) {
            writeAtomically {
                output.writeByte(STATE_PLAYER_RECORD)
                writePlayer(output, player)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun writeAtomically(write: () -> Unit) {
            val startBytes = channel.size()
            try {
                write()
                output.flush()
                if (fsyncOnBatch) channel.force(false)
            } catch (throwable: Throwable) {
                runCatching { rollbackAppend(startBytes) }
                    .onFailure(throwable::addSuppressed)
                throw throwable
            }
        }

        private fun rollbackAppend(startBytes: Long) {
            runCatching(::closeCurrent)
            RandomAccessFile(file.toFile(), "rw").use { stateFile ->
                stateFile.setLength(startBytes)
            }
            open()
        }

        private fun closeCurrent() {
            try {
                if (::output.isInitialized) output.close()
            } finally {
                if (::channel.isInitialized && channel.isOpen) channel.close()
            }
        }

        override fun close() {
            closeCurrent()
        }
    }
}

private fun <T> allowedValues(values: Collection<Negatable<T>>?): Set<T>? {
    if (values.isNullOrEmpty() || values.any { !it.allowed }) return null
    return values.mapTo(HashSet()) { it.property }
}

private fun <T> Set<T>.containsAnyAllowed(values: Collection<Negatable<T>>?): Boolean {
    val allowed = values?.asSequence()?.filter { it.allowed }?.map { it.property } ?: return true
    var hasAllowed = false
    allowed.forEach { value ->
        hasAllowed = true
        if (contains(value)) return true
    }
    return !hasAllowed
}

private fun BitSet.forEachSetBit(block: (Int) -> Unit) {
    var id = nextSetBit(0)
    while (id >= 0) {
        block(id)
        id = nextSetBit(id + 1)
    }
}

private fun BitSet.asIdSequence(newestFirst: Boolean): Sequence<Int> = sequence {
    if (newestFirst) {
        var id = previousSetBit(length() - 1)
        while (id >= 0) {
            yield(id)
            id = previousSetBit(id - 1)
        }
    } else {
        var id = nextSetBit(0)
        while (id >= 0) {
            yield(id)
            id = nextSetBit(id + 1)
        }
    }
}

private fun Long.toMiB(): Long = this / (1024L * 1024L)

private fun <T> matchesNegatable(value: T, params: Collection<Negatable<T>>?): Boolean {
    if (params.isNullOrEmpty()) return true
    var hasAllowed = false
    var allowedMatch = false
    params.forEach { param ->
        if (param.allowed) {
            hasAllowed = true
            if (param.property == value) allowedMatch = true
        } else if (param.property == value) {
            return false
        }
    }
    return !hasAllowed || allowedMatch
}

private fun <T> matchesNullableNegatable(value: T?, params: Collection<Negatable<T>>?): Boolean {
    if (params.isNullOrEmpty()) return true
    var hasAllowed = false
    var allowedMatch = false
    params.forEach { param ->
        if (param.allowed) {
            hasAllowed = true
            if (param.property == value) allowedMatch = true
        } else if (param.property == value) {
            return false
        }
    }
    return !hasAllowed || allowedMatch
}

private fun <T> matchesEitherNegatable(first: T, second: T, params: Collection<Negatable<T>>?): Boolean {
    if (params.isNullOrEmpty()) return true
    var hasAllowed = false
    var allowedMatch = false
    params.forEach { param ->
        val matches = param.property == first || param.property == second
        if (param.allowed) {
            hasAllowed = true
            if (matches) allowedMatch = true
        } else if (matches) {
            return false
        }
    }
    return !hasAllowed || allowedMatch
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun utf8RecordSize(value: String): Long =
    Integer.BYTES.toLong() + value.toByteArray(Charsets.UTF_8).size

private fun DataInputStream.readUtf8(): String {
    val length = readInt()
    if (length < 0 || length > MAX_STRING_BYTES) throw EOFException()
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

private fun DataInputStream.readUtf8If(required: Boolean): String? {
    if (!required) {
        skipUtf8()
        return null
    }
    return readUtf8()
}

private fun DataInputStream.skipUtf8() {
    val length = readInt()
    if (length < 0 || length > MAX_STRING_BYTES) throw EOFException()
    skipFully(length.toLong())
}

private fun DataInputStream.skipFully(length: Long) {
    var remaining = length
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else if (read() < 0) {
            throw EOFException()
        } else {
            remaining -= 1L
        }
    }
}

private fun DataInputStream.skipNullableUtf8() {
    if (readBoolean()) skipUtf8()
}

private fun DataInputStream.skipNullableUuid() {
    if (readBoolean()) skipFully(Long.SIZE_BYTES * 2L)
}

private fun DataInputStream.skipAdaptiveNullableUtf8() {
    when (readUnsignedByte()) {
        ADAPTIVE_NULL -> Unit
        ADAPTIVE_DICTIONARY -> skipFully(Integer.BYTES.toLong())
        ADAPTIVE_INLINE -> skipUtf8()
        else -> throw EOFException("Unknown adaptive string encoding")
    }
}

internal fun DataInputStream.readActionIdAndSkipV2(): Int {
    val id = readInt()
    skipFully(52L)
    skipNullableUuid()
    skipFully(Integer.BYTES * 2L)
    return id
}

internal fun DataInputStream.readActionIdAndSkipV3(): Int {
    val id = readInt()
    skipFully(52L)
    skipNullableUuid()
    skipFully(Integer.BYTES.toLong())
    skipNullableUtf8()
    return id
}

internal fun DataInputStream.readActionIdAndSkipV4(): Int {
    val id = readInt()
    skipFully(40L)
    skipAdaptiveNullableUtf8()
    skipAdaptiveNullableUtf8()
    skipFully(Integer.BYTES.toLong())
    skipNullableUuid()
    skipFully(Integer.BYTES.toLong())
    skipNullableUtf8()
    return id
}

private fun DataInputStream.readInstant(): Instant {
    val epochSecond = readLong()
    val nano = readInt()
    return try {
        Instant.ofEpochSecond(epochSecond, nano.toLong())
    } catch (_: DateTimeException) {
        throw EOFException()
    }
}

private fun DataOutputStream.writeNullableUtf8(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUtf8(value)
}

private fun DataInputStream.readNullableUtf8(): String? =
    if (readBoolean()) readUtf8() else null

private fun DataOutputStream.writeNullableDictionaryId(value: String?, dictionaryId: (String) -> Int) {
    writeInt(value?.let(dictionaryId) ?: 0)
}

private fun DataInputStream.readNullableDictionaryId(dictionaryValue: (Int) -> String): String? {
    val id = readInt()
    return if (id == 0) null else dictionaryValue(id)
}

private fun DataInputStream.readDictionaryValueIf(
    required: Boolean,
    dictionaryValue: (Int) -> String
): String? {
    val id = readInt()
    return if (required) dictionaryValue(id) else null
}

private fun DataInputStream.readRetainedDictionaryValue(
    dictionaryValue: (Int) -> String,
    retain: (Int) -> Unit
): String {
    val id = readInt()
    retain(id)
    return dictionaryValue(id)
}

private fun DataInputStream.readNullableRetainedDictionaryValue(
    dictionaryValue: (Int) -> String,
    retain: (Int) -> Unit
): String? {
    val id = readInt()
    if (id == 0) return null
    retain(id)
    return dictionaryValue(id)
}

private fun DataOutputStream.writeAdaptiveNullableUtf8(value: String?, dictionaryId: (String) -> Int?) {
    if (value == null) {
        writeByte(ADAPTIVE_NULL)
        return
    }
    val id = dictionaryId(value)
    if (id != null) {
        writeByte(ADAPTIVE_DICTIONARY)
        writeInt(id)
    } else {
        writeByte(ADAPTIVE_INLINE)
        writeUtf8(value)
    }
}

private fun DataInputStream.readAdaptiveNullableUtf8(dictionaryValue: (Int) -> String): String? =
    when (readUnsignedByte()) {
        ADAPTIVE_NULL -> null
        ADAPTIVE_DICTIONARY -> dictionaryValue(readInt())
        ADAPTIVE_INLINE -> readUtf8()
        else -> throw EOFException("Unknown adaptive string encoding")
    }

private fun DataOutputStream.writeIdentifier(value: Identifier) = writeUtf8(value.toString())

private fun DataInputStream.readIdentifier(): Identifier =
    Identifier.tryParse(readUtf8()) ?: Identifier.ofVanilla("air")

private fun DataOutputStream.writeNullableUuid(value: UUID?) {
    writeBoolean(value != null)
    if (value != null) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }
}

private fun DataInputStream.readNullableUuid(): UUID? =
    if (readBoolean()) UUID(readLong(), readLong()) else null

private fun DataInputStream.readNullableUuidIf(required: Boolean): UUID? {
    if (!required) {
        skipNullableUuid()
        return null
    }
    return readNullableUuid()
}

private fun writePlayer(output: DataOutputStream, player: PlayerResult) {
    output.writeLong(player.uuid.mostSignificantBits)
    output.writeLong(player.uuid.leastSignificantBits)
    output.writeUtf8(player.name)
    output.writeLong(player.firstJoin.epochSecond)
    output.writeInt(player.firstJoin.nano)
    output.writeLong(player.lastJoin.epochSecond)
    output.writeInt(player.lastJoin.nano)
}

private fun readPlayer(input: DataInputStream): PlayerResult = PlayerResult(
    uuid = UUID(input.readLong(), input.readLong()),
    name = input.readUtf8(),
    firstJoin = input.readInstant(),
    lastJoin = input.readInstant()
)

private fun playerRecordSize(player: PlayerResult): Long =
    16L + Integer.BYTES + player.name.toByteArray(Charsets.UTF_8).size + 2L * (Long.SIZE_BYTES + Integer.BYTES)

private fun actionV4RecordSize(
    action: IrminsulLedgerStore.StoredAction,
    dictionaryId: (String) -> Int?
): Long {
    var bytes = 0L
    bytes += Integer.BYTES // id
    bytes += Integer.BYTES // action dictionary id
    bytes += Long.SIZE_BYTES + Integer.BYTES // timestamp
    bytes += Integer.BYTES * 3L // x/y/z
    bytes += Integer.BYTES * 3L // world/object/oldObject dictionary ids
    bytes += adaptiveNullableUtf8RecordSize(action.objectState, dictionaryId)
    bytes += adaptiveNullableUtf8RecordSize(action.oldObjectState, dictionaryId)
    bytes += Integer.BYTES // source dictionary id
    bytes += 1L + if (action.sourcePlayerId == null) 0L else Long.SIZE_BYTES * 2L
    bytes += Integer.BYTES // nullable source player name
    bytes += 1L + (action.extraData?.let { utf8RecordSize(it) } ?: 0L)
    return bytes
}

private fun adaptiveNullableUtf8RecordSize(value: String?, dictionaryId: (String) -> Int?): Long =
    when {
        value == null -> 1L
        dictionaryId(value) != null -> 1L + Integer.BYTES
        else -> 1L + utf8RecordSize(value)
    }

private class RewriteDictionary {
    private val stringIds = HashMap<String, Int>()
    private val stateAdmission = StringAdmissionPolicy()
    private var nextId = 1
    val pendingStrings = ArrayList<Pair<Int, String>>()

    fun register(action: IrminsulLedgerStore.StoredAction) {
        intern(action.action)
        intern(action.world.toString())
        intern(action.objectIdentifier.toString())
        intern(action.oldObjectIdentifier.toString())
        action.objectState?.let(::registerState)
        action.oldObjectState?.let(::registerState)
        intern(action.sourceName)
        action.sourcePlayerName?.let(::intern)
    }

    fun id(value: String): Int? = stringIds[value]

    private fun registerState(value: String) {
        if (stringIds.containsKey(value) || !stateAdmission.shouldIntern(value)) return
        intern(value)
    }

    private fun intern(value: String): Int {
        stringIds[value]?.let { return it }
        val id = nextId
        nextId += 1
        stringIds[value] = id
        pendingStrings.add(id to value)
        return id
    }
}

private class StringAdmissionPolicy {
    private val probation = object : LinkedHashMap<String, Unit>(STATE_DICTIONARY_PROBATION_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > STATE_DICTIONARY_PROBATION_SIZE
    }

    fun shouldIntern(value: String): Boolean {
        if (value.length > MAX_STATE_DICTIONARY_CHARS) return false
        if (probation.remove(value) != null) return true
        probation[value] = Unit
        return false
    }

    fun clear() = probation.clear()
}

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

private class SeekableFileInputStream(private val channel: FileChannel) : InputStream() {
    private val fileSize = channel.size()
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES).apply { limit(0) }
    private var bufferStart = 0L

    var position = 0L
        private set

    fun seek(target: Long) {
        require(target >= 0L)
        val bufferEnd = bufferStart + buffer.limit().toLong()
        if (target in bufferStart..bufferEnd) {
            buffer.position((target - bufferStart).toInt())
        } else {
            buffer.limit(0)
        }
        position = target
    }

    override fun read(): Int {
        if (!buffer.hasRemaining() && !refill()) return -1
        position += 1L
        return buffer.get().toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var total = 0
        while (total < length) {
            if (!buffer.hasRemaining() && !refill()) break
            val count = minOf(length - total, buffer.remaining())
            buffer.get(target, offset + total, count)
            total += count
            position += count.toLong()
        }
        return if (total == 0) -1 else total
    }

    override fun skip(length: Long): Long {
        if (length <= 0L || position >= fileSize) return 0L
        val skipped = minOf(length, fileSize - position)
        seek(position + skipped)
        return skipped
    }

    override fun available(): Int = minOf(fileSize - position, Int.MAX_VALUE.toLong()).toInt()

    override fun close() = channel.close()

    private fun refill(): Boolean {
        buffer.clear()
        bufferStart = position
        val count = channel.read(buffer, position)
        if (count <= 0) {
            buffer.limit(0)
            return false
        }
        buffer.flip()
        return true
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
    }
}

private class DictionaryLocations : Closeable {
    private var segments = IntArray(INITIAL_CAPACITY) { MISSING_SEGMENT }
    private var offsets = LongArray(INITIAL_CAPACITY)
    private val paths = HashMap<Int, Path>()
    private val channels = HashMap<Int, FileChannel>()

    fun put(id: Int, segment: Int, path: Path, offset: Long) {
        if (id <= 0 || offset < 0L) return
        ensureCapacity(id)
        segments[id] = segment
        offsets[id] = offset
        paths[segment] = path
    }

    fun read(id: Int): String? {
        if (id <= 0 || id >= segments.size) return null
        val segment = segments[id]
        if (segment == MISSING_SEGMENT) return null
        val path = paths[segment] ?: return null
        val channel = channels.computeIfAbsent(segment) {
            FileChannel.open(path, StandardOpenOption.READ)
        }
        val lengthBuffer = ByteBuffer.allocate(Integer.BYTES)
        channel.readFully(lengthBuffer, offsets[id])
        lengthBuffer.flip()
        val length = lengthBuffer.int
        if (length < 0 || length > MAX_STRING_BYTES) throw EOFException("Invalid Irminsul dictionary value")
        val valueBuffer = ByteBuffer.allocate(length)
        channel.readFully(valueBuffer, offsets[id] + Integer.BYTES)
        return valueBuffer.array().toString(Charsets.UTF_8)
    }

    fun clear() {
        close()
        segments = IntArray(INITIAL_CAPACITY) { MISSING_SEGMENT }
        offsets = LongArray(INITIAL_CAPACITY)
        paths.clear()
    }

    override fun close() {
        var failure: Throwable? = null
        channels.values.forEach { channel ->
            val current = runCatching {
                if (channel.isOpen) channel.close()
            }.exceptionOrNull()
            if (current != null) {
                if (failure == null) {
                    failure = current
                } else {
                    failure.addSuppressed(current)
                }
            }
        }
        channels.clear()
        failure?.let { throw it }
    }

    private fun ensureCapacity(id: Int) {
        if (id < segments.size) return
        var capacity = segments.size
        while (capacity <= id) capacity *= 2
        val newSegments = IntArray(capacity) { MISSING_SEGMENT }
        segments.copyInto(newSegments)
        segments = newSegments
        offsets = offsets.copyOf(capacity)
    }

    companion object {
        private const val INITIAL_CAPACITY = 1024
        private const val MISSING_SEGMENT = -1
    }
}

private fun FileChannel.readFully(buffer: ByteBuffer, startPosition: Long) {
    var position = startPosition
    while (buffer.hasRemaining()) {
        val count = read(buffer, position)
        if (count < 0) throw EOFException("Truncated Irminsul dictionary value")
        if (count == 0) continue
        position += count.toLong()
    }
}
