@file:Suppress("MagicNumber")

package com.github.quiltservertools.ledger.database.fast

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
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Arrays
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.math.ceil
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
private const val SEGMENT_PREFIX = "actions-"
private const val SEGMENT_SUFFIX = ".lfda"
private const val STATE_FILE = "state.lfds"
private const val MIB_BYTES = 1024L * 1024L
private const val ACTION_RECORD_V3 = 4
private const val MIN_HOT_ACTION_LIMIT = 10_000
private const val MIN_HOT_TRIM_OVERFLOW = 10_000

class FastLedgerStore : LedgerStore {
    override val databaseType: String = "fastdb"

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
    private val knownSources = ConcurrentHashMap.newKeySet<String>()
    private val stringDictionary = HashMap<Int, String>()
    private val stringIds = HashMap<String, Int>()
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

    override fun setup() {
        synchronized(lock) {
            root = config.getDatabasePath().resolve("ledger-fastdb")
            actionsDir = root.resolve("actions")
        statePath = root.resolve(STATE_FILE)
        hotActionLimit = config[DatabaseSpec.fastHotActionLimit].coerceAtLeast(MIN_HOT_ACTION_LIMIT)
        actionsDir.createDirectories()
        loadActions()
        loadState()
        applyStartupRollbackState()
        bitSetCache = BitSetCachePolicy(Runtime.getRuntime().maxMemory(), physicalMemoryBytes())
        openWriters()
            logInfo(
                "FastDB ready. residentActions=${actionsById.size}, coldActions=$coldActionsOnDisk, " +
                        "nextId=$nextId, segments=${segmentFiles().size}, path=$root"
            )
            logMemoryProfile()
        }
    }

    override fun ensureTables() = Unit
    override suspend fun setupCache() = Unit

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
        logInfo("FastDB purged $deleted actions older than $days days")
    }

    override suspend fun searchActions(params: ActionSearchParams, page: Int): SearchResults = synchronized(lock) {
        val pageSize = config[SearchSpec.pageSize]
        val totalMatches = countMatchingActions(params)
        if (totalMatches == 0) {
            SearchResults(emptyList(), params, page, 0)
        } else {
            val offset = pageSize * max(page - 1, 0)
            val actions = if (offset >= totalMatches) {
                emptyList()
            } else {
                pageMatchingActions(params, newestFirst = true, offset, pageSize).mapNotNull { it.toActionType() }
            }
            SearchResults(actions, params, page, ceil(totalMatches.toDouble() / pageSize.toDouble()).toInt())
        }
    }

    override suspend fun countActions(params: ActionSearchParams): Long = synchronized(lock) {
        countMatchingActions(params).toLong()
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
            val records = actions.map { StoredAction.from(it, nextId++) }
            records.forEach(::registerStrings)
            writer.write(records, stringIds::getValue)
            records.forEach(::addAction)
            trimResidentHotWindowIfNeeded()
        }
    }

    override suspend fun registerWorld(identifier: Identifier) = Unit
    override suspend fun registerActionType(id: String) = Unit
    override suspend fun insertIdentifiers(identifiers: Collection<Identifier>) = Unit

    override suspend fun logPlayer(uuid: UUID, name: String) = synchronized(lock) {
        val now = Instant.now()
        val existing = players[uuid]
        players[uuid] = if (existing == null) {
            PlayerResult(uuid, name, now, now)
        } else {
            existing.copy(name = name, lastJoin = now)
        }
        stateWriter.writePlayer(players.getValue(uuid))
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
        val purgeIds = matchingActions(params, newestFirst = false).mapTo(HashSet()) { it.id }
        if (purgeIds.isEmpty()) return 0

        val retained = ArrayList<StoredAction>()
        forEachDiskAction(newestFirst = false, maxExclusiveId = Int.MAX_VALUE) { action ->
            if (!purgeIds.contains(action.id)) retained.add(action)
            true
        }

        val retainedRolledBack = retained.asSequence()
            .filter { rolledBack[it.id] }
            .mapTo(HashSet()) { it.id }
        closeWriters()
        clearMemory()
        resetFiles()
        nextId = 1
        currentSegment = 0
        coldActionsOnDisk = 0
        openWriters()
        val remappedRolledBack = HashSet<Int>()
        retained.forEach { oldAction ->
            val newAction = oldAction.withId(nextId++)
            registerStrings(newAction)
            writer.write(listOf(newAction), stringIds::getValue)
            addAction(newAction)
            if (retainedRolledBack.contains(oldAction.id)) remappedRolledBack.add(newAction.id)
        }
        remappedRolledBack.forEach { rolledBack.set(it) }
        rewriteState()
        trimResidentHotWindowIfNeeded()
        return purgeIds.size
    }

    override suspend fun searchPlayers(players: Set<GameProfile>): List<PlayerResult> = synchronized(lock) {
        players.mapNotNull { player -> this.players[player.id] }
    }

    override fun getKnownSources(): Set<String> = knownSources

    override fun close() {
        synchronized(lock) {
            closeWriters()
        }
    }

    private fun loadActions() {
        val retainedActions = ArrayDeque<StoredAction>()
        val loadedSources = HashSet<String>()
        coldActionsOnDisk = 0
        try {
            segmentFiles().forEach { file ->
                val segment = parseSegmentNumber(file)
                if (segment >= currentSegment) currentSegment = segment

                var validBytes = 0L
                DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                    val magic = input.readInt()
                    val version = input.readInt()
                    if (magic != ACTION_MAGIC || version != FORMAT_VERSION) {
                        logWarn("Skipping unknown fastdb segment $file")
                        return@use
                    }
                    validBytes = 8L

                    while (true) {
                        try {
                            when (val record = input.readUnsignedByte()) {
                                ACTION_RECORD -> {
                                    val size = input.readInt()
                                    if (size <= 0 || size > MAX_RECORD_BYTES) {
                                        logWarn("Stopping at invalid fastdb record in $file")
                                        return@use
                                    }
                                    val payload = ByteArray(size)
                                    input.readFully(payload)
                                    val action = StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
                                    loadedSources.add(action.sourceName)
                                    retainedActions.retainLoadedAction(action)
                                    nextId = max(nextId, action.id + 1)
                                    validBytes += 1L + Integer.BYTES + size.toLong()
                                }
                                STRING_DICTIONARY_RECORD -> {
                                    val id = input.readInt()
                                    val value = input.readUtf8()
                                    stringDictionary[id] = value
                                    stringIds[value] = id
                                    nextStringId = max(nextStringId, id + 1)
                                    validBytes += 1L + Integer.BYTES.toLong() + utf8RecordSize(value)
                                }
                                ACTION_RECORD_V2 -> {
                                    val action = StoredAction.readV2(input, ::dictionaryValue)
                                    loadedSources.add(action.sourceName)
                                    retainedActions.retainLoadedAction(action)
                                    nextId = max(nextId, action.id + 1)
                                    validBytes += 1L + actionV2RecordSize(action)
                                }
                                ACTION_RECORD_V3 -> {
                                    val action = StoredAction.readV3(input, ::dictionaryValue)
                                    loadedSources.add(action.sourceName)
                                    retainedActions.retainLoadedAction(action)
                                    nextId = max(nextId, action.id + 1)
                                    validBytes += 1L + actionV3RecordSize(action)
                                }
                                else -> {
                                    logWarn("Stopping at unknown fastdb record $record in $file")
                                    return@use
                                }
                            }
                        } catch (_: EOFException) {
                            return@use
                        }
                    }
                }
                truncateToValidBytes(file, validBytes)
            }
        } finally {
            clearMemory(clearStrings = false)
            knownSources.addAll(loadedSources)
            retainedActions.forEach { action ->
                registerStrings(action)
                addAction(action)
            }
            if (coldActionsOnDisk > 0) {
                logInfo(
                    "FastDB loaded newest $hotActionLimit actions into memory; " +
                            "$coldActionsOnDisk older records remain on disk for cold scans"
                )
            }
        }
    }

    private fun ArrayDeque<StoredAction>.retainLoadedAction(action: StoredAction) {
        if (size >= hotActionLimit) {
            removeFirst()
            coldActionsOnDisk += 1
        }
        addLast(action)
    }

    private fun loadState() {
        if (!statePath.exists()) return

        var validBytes = 0L
        DataInputStream(BufferedInputStream(statePath.inputStream())).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            if (magic != STATE_MAGIC || version != FORMAT_VERSION) {
                logWarn("Skipping unknown fastdb state log $statePath")
                return
            }
            validBytes = 8L

            while (true) {
                try {
                    when (val type = input.readUnsignedByte()) {
                        STATE_ROLLBACK_RECORD -> {
                            val id = input.readInt()
                            val value = input.readBoolean()
                            if (value) startupRolledBackIds.add(id) else startupRolledBackIds.remove(id)
                            validBytes += 1L + Integer.BYTES + 1L
                        }
                        STATE_PLAYER_RECORD -> {
                            val player = readPlayer(input)
                            players[player.uuid] = player
                            validBytes += 1L + playerRecordSize(player)
                        }
                        STATE_ROLLBACK_RANGE_RECORD -> {
                            val startId = input.readInt()
                            val count = input.readInt()
                            val value = input.readBoolean()
                            repeat(count.coerceAtLeast(0)) { offset ->
                                val id = startId + offset
                                if (value) startupRolledBackIds.add(id) else startupRolledBackIds.remove(id)
                            }
                            validBytes += 1L + Integer.BYTES + Integer.BYTES + 1L
                        }
                        else -> {
                            logWarn("Stopping at unknown fastdb state record $type")
                            return
                        }
                    }
                } catch (_: EOFException) {
                    return
                }
            }
        }
        truncateToValidBytes(statePath, validBytes)
    }

    private fun truncateToValidBytes(file: Path, validBytes: Long) {
        if (validBytes <= 0L || file.fileSize() == validBytes) return
        RandomAccessFile(file.toFile(), "rw").use { it.setLength(validBytes) }
    }

    private fun openWriters() {
        writer = SegmentWriter(actionsDir, currentSegment, maxSegmentBytes())
        currentSegment = writer.segmentNumber
        stateWriter = StateWriter(statePath)
    }

    private fun closeWriters() {
        if (::writer.isInitialized) writer.close()
        if (::stateWriter.isInitialized) stateWriter.close()
    }

    private fun resetFiles() {
        if (actionsDir.exists()) {
            Files.list(actionsDir).use { stream ->
                stream.forEach { Files.deleteIfExists(it) }
            }
        }
        Files.deleteIfExists(statePath)
    }

    private fun rewriteState() {
        val states = orderedIds.asSequence()
            .filter { rolledBack[it] }
            .map { it to true }
            .toList()
        stateWriter.writeRollbackStates(states)
        players.values.forEach(stateWriter::writePlayer)
    }

    private fun applyStartupRollbackState() {
        if (startupRolledBackIds.isEmpty()) return

        startupRolledBackIds.forEach { id -> rolledBack.set(id) }
    }

    private fun updateRollbackState(actionIds: Set<Int>, value: Boolean) {
        if (actionIds.isEmpty()) return

        val updates = IntArray(actionIds.size)
        var count = 0
        actionIds.forEach { id ->
            if (id > 0 && id < nextId) {
                setRolledBack(id, value)
                updates[count] = id
                count += 1
            }
        }
        stateWriter.writeRollbackStatesCompressed(updates, count, value)
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
        idsByChunk.add(LocationKey(action.world, action.x shr 4, action.z shr 4), action.id)
    }

    private fun registerStrings(action: StoredAction) {
        // Dictionary ids are assigned to the full registry string at runtime. This is intentionally
        // namespace-agnostic, so modded ids like "modid:custom_block" are stored exactly as seen.
        internString(action.action)
        internString(action.world.toString())
        internString(action.objectIdentifier.toString())
        internString(action.oldObjectIdentifier.toString())
        action.objectState?.let(::internString)
        action.oldObjectState?.let(::internString)
        internString(action.sourceName)
        action.sourcePlayerName?.let(::internString)
    }

    private fun internString(value: String): Int {
        stringIds[value]?.let { return it }
        val id = nextStringId++
        stringIds[value] = id
        stringDictionary[id] = value
        if (::writer.isInitialized) writer.writeString(id, value)
        return id
    }

    private fun dictionaryValue(id: Int): String =
        stringDictionary[id] ?: error("Missing fastdb dictionary value $id")

    private fun clearMemory(clearStrings: Boolean = true) {
        clearResidentIndexes(clearRolledBack = true)
        knownSources.clear()
        if (clearStrings) {
            stringDictionary.clear()
            stringIds.clear()
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
            "FastDB trimmed resident hot window to ${actionsById.size}; " +
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

    private fun countMatchingActions(params: ActionSearchParams): Int {
        var count = 0
        forEachMatchingHotAction(params, newestFirst = false) {
            count += 1
            true
        }
        forEachMatchingColdAction(params, newestFirst = false) {
            count += 1
            true
        }
        return count
    }

    private fun selectBlockPlan(params: ActionSearchParams, newestFirst: Boolean): RollbackExecutor.Selection {
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
        offset: Int,
        limit: Int
    ): List<StoredAction> {
        val page = ArrayList<StoredAction>(limit)
        var skipped = 0
        fun accept(action: StoredAction): Boolean {
            if (skipped < offset) {
                skipped += 1
                return true
            }
            if (page.size < limit) page.add(action)
            return page.size < limit
        }

        if (newestFirst) {
            if (!forEachMatchingHotAction(params, newestFirst = true) { accept(it) }) return page
            forEachMatchingColdAction(params, newestFirst = true) { accept(it) }
        } else {
            forEachMatchingColdAction(params, newestFirst = false) { accept(it) }
            if (page.size < limit) {
                forEachMatchingHotAction(params, newestFirst = false) { accept(it) }
            }
        }
        return page
    }

    private fun shouldScanCold(params: ActionSearchParams): Boolean {
        if (coldActionsOnDisk <= 0) return false
        val oldestHot = orderedIds.firstOrNull()?.let { actionsById[it]?.timestamp } ?: return true
        return params.after == null || params.after.isBefore(oldestHot)
    }

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
        if (!shouldScanCold(params)) return true
        return forEachDiskAction(newestFirst, oldestResidentId()) { action ->
            !action.matches(params) || block(action)
        }
    }

    private fun forEachDiskAction(
        newestFirst: Boolean,
        maxExclusiveId: Int,
        block: (StoredAction) -> Boolean
    ): Boolean {
        val files = segmentFiles()
        val iterable = if (newestFirst) files.asReversed() else files
        iterable.forEach { file ->
            val segmentActions = readSegmentActions(file, maxExclusiveId)
            val actions = if (newestFirst) segmentActions.asReversed() else segmentActions
            actions.forEach { action ->
                if (!block(action)) return false
            }
        }
        return true
    }

    private fun readSegmentActions(file: Path, maxExclusiveId: Int): List<StoredAction> {
        val actions = ArrayList<StoredAction>()
        val dictionary = HashMap<Int, String>()
        fun dictionaryValue(id: Int): String =
            dictionary[id] ?: stringDictionary[id] ?: error("Missing fastdb dictionary value $id")

        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val magic = input.readInt()
            val version = input.readInt()
            if (magic != ACTION_MAGIC || version != FORMAT_VERSION) return emptyList()

            while (true) {
                try {
                    when (input.readUnsignedByte()) {
                        ACTION_RECORD -> {
                            val size = input.readInt()
                            if (size <= 0 || size > MAX_RECORD_BYTES) return actions
                            val payload = ByteArray(size)
                            input.readFully(payload)
                            val action = StoredAction.read(DataInputStream(ByteArrayInputStream(payload)))
                            if (action.id < maxExclusiveId) actions.add(action)
                        }
                        STRING_DICTIONARY_RECORD -> {
                            val id = input.readInt()
                            dictionary[id] = input.readUtf8()
                        }
                        ACTION_RECORD_V2 -> {
                            val action = StoredAction.readV2(input, ::dictionaryValue)
                            if (action.id < maxExclusiveId) actions.add(action)
                        }
                        ACTION_RECORD_V3 -> {
                            val action = StoredAction.readV3(input, ::dictionaryValue)
                            if (action.id < maxExclusiveId) actions.add(action)
                        }
                        else -> return actions
                    }
                } catch (_: EOFException) {
                    return actions
                }
            }
        }
    }

    private fun StoredAction.matches(params: ActionSearchParams): Boolean {
        if (params.bounds != null && !params.bounds.contains(BlockPos(x, y, z))) return false
        if (params.after != null && timestamp.isBefore(params.after)) return false
        if (params.before != null && timestamp.isAfter(params.before)) return false
        if (params.rolledBack != null && rolledBack[id] != params.rolledBack) return false
        if (!matchesNegatable(action, params.actions)) return false
        if (!matchesAnyNegatable(listOf(objectIdentifier, oldObjectIdentifier), params.objects)) return false
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
        logInfo(
            "FastDB memory profile. hostMemory=$hostMemory, heapMax=${heapMaxMiB}MiB, " +
                    "heapCommitted=${heapCommittedMiB}MiB, residentIndexes=enabled, " +
                    "hotActionLimit=$hotActionLimit, " +
                    "bitSetCacheBudget=${bitSetCache.budgetBytes.toMiB()}MiB, " +
                    "bitSetCacheMinValues=${bitSetCache.minValues}"
        )

        val recommendedHeapMiB = hostMemoryMiB?.let { (it / 2).coerceIn(4096L, 16384L) }
        if (recommendedHeapMiB != null && heapMaxMiB < recommendedHeapMiB / 2) {
            logWarn(
                "FastDB heap is much smaller than host memory. Consider raising the server -Xmx " +
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

    private fun segmentFiles(): List<Path> {
        if (!actionsDir.exists()) return emptyList()
        return Files.list(actionsDir).use { stream ->
            stream
                .filter { it.name.startsWith(SEGMENT_PREFIX) && it.name.endsWith(SEGMENT_SUFFIX) }
                .sorted { left, right -> parseSegmentNumber(left).compareTo(parseSegmentNumber(right)) }
                .toList()
        }
    }

    private fun parseSegmentNumber(path: Path): Int =
        path.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toIntOrNull() ?: 0

    private fun maxSegmentBytes(): Long =
        config[DatabaseSpec.fastSegmentSizeMiB].coerceAtLeast(8).toLong() * 1024L * 1024L

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

    private data class LocationKey(val world: Identifier, val chunkX: Int, val chunkZ: Int)

    private data class BlockKey(val world: Identifier, val x: Int, val y: Int, val z: Int)

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
                val configuredMiB = config[DatabaseSpec.fastIndexCacheMiB]
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
                timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
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

            fun readV2(input: DataInputStream, dictionaryValue: (Int) -> String): StoredAction = StoredAction(
                id = input.readInt(),
                action = dictionaryValue(input.readInt()),
                timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = Identifier.tryParse(dictionaryValue(input.readInt())) ?: Identifier.ofVanilla("overworld"),
                objectIdentifier = Identifier.tryParse(dictionaryValue(input.readInt()))
                    ?: Identifier.ofVanilla("air"),
                oldObjectIdentifier = Identifier.tryParse(dictionaryValue(input.readInt()))
                    ?: Identifier.ofVanilla("air"),
                objectState = input.readNullableDictionaryId(dictionaryValue),
                oldObjectState = input.readNullableDictionaryId(dictionaryValue),
                sourceName = dictionaryValue(input.readInt()),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableDictionaryId(dictionaryValue),
                extraData = input.readNullableDictionaryId(dictionaryValue)
            )

            fun readV3(input: DataInputStream, dictionaryValue: (Int) -> String): StoredAction = StoredAction(
                id = input.readInt(),
                action = dictionaryValue(input.readInt()),
                timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
                x = input.readInt(),
                y = input.readInt(),
                z = input.readInt(),
                world = Identifier.tryParse(dictionaryValue(input.readInt())) ?: Identifier.ofVanilla("overworld"),
                objectIdentifier = Identifier.tryParse(dictionaryValue(input.readInt()))
                    ?: Identifier.ofVanilla("air"),
                oldObjectIdentifier = Identifier.tryParse(dictionaryValue(input.readInt()))
                    ?: Identifier.ofVanilla("air"),
                objectState = input.readNullableDictionaryId(dictionaryValue),
                oldObjectState = input.readNullableDictionaryId(dictionaryValue),
                sourceName = dictionaryValue(input.readInt()),
                sourcePlayerId = input.readNullableUuid(),
                sourcePlayerName = input.readNullableDictionaryId(dictionaryValue),
                extraData = input.readNullableUtf8()
            )
        }
    }

    private class SegmentWriter(
        private val directory: Path,
        startSegment: Int,
        private val maxSegmentBytes: Long
    ) : Closeable {
        var segmentNumber = startSegment
            private set

        private lateinit var channel: FileChannel
        private lateinit var stream: FileOutputStream
        private lateinit var output: DataOutputStream

        init {
            open(segmentNumber)
        }

        fun writeString(id: Int, value: String) {
            rotateIfNeeded()
            output.writeByte(STRING_DICTIONARY_RECORD)
            output.writeInt(id)
            output.writeUtf8(value)
            output.flush()
            if (config[DatabaseSpec.fastFsyncOnBatch]) channel.force(false)
        }

        fun write(actions: List<StoredAction>, dictionaryId: (String) -> Int) {
            actions.forEach { action ->
                rotateIfNeeded()
                output.writeByte(ACTION_RECORD_V3)
                action.writeV3(output, dictionaryId)
            }
            output.flush()
            if (config[DatabaseSpec.fastFsyncOnBatch]) channel.force(false)
        }

        private fun rotateIfNeeded() {
            output.flush()
            if (channel.size() < maxSegmentBytes) return

            close()
            segmentNumber += 1
            open(segmentNumber)
        }

        private fun open(number: Int) {
            val file = directory.resolve("$SEGMENT_PREFIX${number.toString().padStart(8, '0')}$SEGMENT_SUFFIX")
            val exists = file.exists()
            stream = FileOutputStream(file.toFile(), true)
            channel = stream.channel
            output = DataOutputStream(BufferedOutputStream(stream))
            if (!exists || file.fileSize() == 0L) {
                output.writeInt(ACTION_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.flush()
            }
        }

        override fun close() {
            if (::output.isInitialized) output.close()
            if (::channel.isInitialized && channel.isOpen) channel.close()
        }
    }

    private class StateWriter(private val file: Path) : Closeable {
        private val stream: FileOutputStream
        private val channel: FileChannel
        private val output: DataOutputStream

        init {
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

        fun writeRollbackStates(states: List<Pair<Int, Boolean>>) {
            states.forEach { (id, value) ->
                output.writeByte(STATE_ROLLBACK_RECORD)
                output.writeInt(id)
                output.writeBoolean(value)
            }
            output.flush()
            if (config[DatabaseSpec.fastFsyncOnBatch]) channel.force(false)
        }

        fun writeRollbackStatesCompressed(ids: IntArray, count: Int, value: Boolean) {
            if (count <= 0) return

            Arrays.sort(ids, 0, count)
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
            output.flush()
            if (config[DatabaseSpec.fastFsyncOnBatch]) channel.force(false)
        }

        private fun writeRollbackRun(start: Int, count: Int, value: Boolean) {
            output.writeByte(STATE_ROLLBACK_RANGE_RECORD)
            output.writeInt(start)
            output.writeInt(count)
            output.writeBoolean(value)
        }

        fun writePlayer(player: PlayerResult) {
            output.writeByte(STATE_PLAYER_RECORD)
            writePlayer(output, player)
            output.flush()
            if (config[DatabaseSpec.fastFsyncOnBatch]) channel.force(false)
        }

        override fun close() {
            output.close()
            if (channel.isOpen) channel.close()
        }
    }
}

private fun <T> allowedValues(values: Collection<Negatable<T>>?): Set<T>? {
    if (values == null || values.any { !it.allowed }) return null
    return values.mapTo(HashSet()) { it.property }
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
    val allowed = params.filter { it.allowed }
    val denied = params.filterNot { it.allowed }
    if (denied.any { it.property == value }) return false
    return allowed.isEmpty() || allowed.any { it.property == value }
}

private fun <T> matchesNullableNegatable(value: T?, params: Collection<Negatable<T>>?): Boolean {
    if (params.isNullOrEmpty()) return true
    val allowed = params.filter { it.allowed }
    val denied = params.filterNot { it.allowed }
    if (value != null && denied.any { it.property == value }) return false
    return allowed.isEmpty() || allowed.any { it.property == value }
}

private fun <T> matchesAnyNegatable(values: Collection<T>, params: Collection<Negatable<T>>?): Boolean {
    if (params.isNullOrEmpty()) return true
    val allowed = params.filter { it.allowed }
    val denied = params.filterNot { it.allowed }
    if (denied.any { deniedValue -> values.any { it == deniedValue.property } }) return false
    return allowed.isEmpty() || allowed.any { allowedValue -> values.any { it == allowedValue.property } }
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun utf8RecordSize(value: String): Long =
    Integer.BYTES.toLong() + value.toByteArray(Charsets.UTF_8).size

private fun DataInputStream.readUtf8(): String {
    val bytes = ByteArray(readInt())
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
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
    firstJoin = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
    lastJoin = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
)

private fun playerRecordSize(player: PlayerResult): Long =
    16L + Integer.BYTES + player.name.toByteArray(Charsets.UTF_8).size + 2L * (Long.SIZE_BYTES + Integer.BYTES)

private fun actionV2RecordSize(action: FastLedgerStore.StoredAction): Long {
    var bytes = 0L
    bytes += Integer.BYTES // id
    bytes += Integer.BYTES // action dictionary id
    bytes += Long.SIZE_BYTES + Integer.BYTES // timestamp
    bytes += Integer.BYTES * 3L // x/y/z
    bytes += Integer.BYTES * 3L // world/object/oldObject dictionary ids
    bytes += Integer.BYTES * 2L // nullable block states
    bytes += Integer.BYTES // source dictionary id
    bytes += 1L + if (action.sourcePlayerId == null) 0L else Long.SIZE_BYTES * 2L
    bytes += Integer.BYTES * 2L // nullable source player name / extra data
    return bytes
}

private fun actionV3RecordSize(action: FastLedgerStore.StoredAction): Long {
    var bytes = 0L
    bytes += Integer.BYTES // id
    bytes += Integer.BYTES // action dictionary id
    bytes += Long.SIZE_BYTES + Integer.BYTES // timestamp
    bytes += Integer.BYTES * 3L // x/y/z
    bytes += Integer.BYTES * 3L // world/object/oldObject dictionary ids
    bytes += Integer.BYTES * 2L // nullable block states
    bytes += Integer.BYTES // source dictionary id
    bytes += 1L + if (action.sourcePlayerId == null) 0L else Long.SIZE_BYTES * 2L
    bytes += Integer.BYTES // nullable source player name
    bytes += 1L + (action.extraData?.let { utf8RecordSize(it) } ?: 0L)
    return bytes
}
