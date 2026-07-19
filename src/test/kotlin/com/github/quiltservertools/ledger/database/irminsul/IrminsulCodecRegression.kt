package com.github.quiltservertools.ledger.database.irminsul

import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.utility.Negatable
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.createDirectories

private val SAMPLE = IrminsulLedgerStore.StoredAction(
    id = 42,
    action = "block-break",
    timestamp = Instant.ofEpochSecond(1_725_000_000L, 123_456_789),
    x = -12,
    y = 64,
    z = 987,
    world = Identifier.tryParse("minecraft:overworld")!!,
    objectIdentifier = Identifier.tryParse("minecraft:chest")!!,
    oldObjectIdentifier = Identifier.tryParse("minecraft:air")!!,
    objectState = "{facing:north,waterlogged:false}",
    oldObjectState = null,
    sourceName = "Player",
    sourcePlayerId = UUID.fromString("12345678-1234-5678-9abc-def012345678"),
    sourcePlayerName = "CodecTester",
    extraData = "{Items:[]}"
)

fun main() {
    legacyV1RoundTrip()
    dictionaryV2RoundTrip()
    dictionaryV3RoundTrip()
    adaptiveV4InlineRoundTrip()
    adaptiveV4DictionaryRoundTrip()
    metadataReadersConsumeExactRecordsAndApplyFilters()
    largeInlineV4MetadataIsSkippedWithoutResolution()
    reversePreScanSkipsV2Record()
    reversePreScanSkipsV3Record()
    reversePreScanSkipsV4Record()
    sparseReverseIndexUsesBoundedBlocks()
    sparseBlockSummarySkipsImpossibleFilters()
    sparseReverseScanHonorsBoundariesAndPartialTail()
    segmentSummarySkipsImpossibleFilters()
    failedSegmentBatchIsRolledBack()
    truncatedV4IsRejected()
}

private fun legacyV1RoundTrip() {
    val bytes = encode(SAMPLE::write)
    check(decode(bytes, IrminsulLedgerStore.StoredAction::read) == SAMPLE)
}

private fun dictionaryV2RoundTrip() {
    val dictionary = dictionary(includeState = true, includeExtra = true)
    val retained = HashSet<Int>()
    val bytes = encode { output -> SAMPLE.writeV2(output, dictionary::getValue) }
    val decoded = decode(bytes) { input ->
        IrminsulLedgerStore.StoredAction.readV2(input, dictionary.entries.associate { it.value to it.key }::getValue) {
            retained.add(it)
        }
    }
    check(decoded == SAMPLE)
    check(retained == retainedDictionaryIds(dictionary))
}

private fun dictionaryV3RoundTrip() {
    val dictionary = dictionary(includeState = true, includeExtra = false)
    val bytes = encode { output -> SAMPLE.writeV3(output, dictionary::getValue) }
    val decoded = decode(bytes) { input ->
        IrminsulLedgerStore.StoredAction.readV3(input, dictionary.entries.associate { it.value to it.key }::getValue)
    }
    check(decoded == SAMPLE)
}

private fun adaptiveV4InlineRoundTrip() {
    val dictionary = dictionary(includeState = false, includeExtra = false)
    val bytes = encode { output -> SAMPLE.writeV4(output, dictionary::get) }
    val decoded = decode(bytes) { input ->
        IrminsulLedgerStore.StoredAction.readV4(input, dictionary.entries.associate { it.value to it.key }::getValue)
    }
    check(decoded == SAMPLE)
}

private fun adaptiveV4DictionaryRoundTrip() {
    val dictionary = dictionary(includeState = true, includeExtra = false)
    val retained = HashSet<Int>()
    val bytes = encode { output -> SAMPLE.writeV4(output, dictionary::get) }
    val decoded = decode(bytes) { input ->
        IrminsulLedgerStore.StoredAction.readV4(input, dictionary.entries.associate { it.value to it.key }::getValue) {
            retained.add(it)
        }
    }
    check(decoded == SAMPLE)
    check(retained == retainedDictionaryIds(dictionary))
}

private fun metadataReadersConsumeExactRecordsAndApplyFilters() {
    val store = IrminsulLedgerStore()
    val matching = ActionSearchParams.build {
        bounds = BlockBox(SAMPLE.x, SAMPLE.y, SAMPLE.z, SAMPLE.x, SAMPLE.y, SAMPLE.z)
        before = SAMPLE.timestamp
        after = SAMPLE.timestamp
        rolledBack = true
        actions = mutableSetOf(Negatable.allow(SAMPLE.action))
        objects = mutableSetOf(Negatable.allow(SAMPLE.oldObjectIdentifier))
        sourceNames = mutableSetOf(Negatable.allow(SAMPLE.sourceName))
        sourcePlayerIds = mutableSetOf(Negatable.allow(SAMPLE.sourcePlayerId!!))
        worlds = mutableSetOf(Negatable.allow(SAMPLE.world))
    }
    val rejected = listOf(
        ActionSearchParams.build {
            bounds = BlockBox(SAMPLE.x + 1, SAMPLE.y, SAMPLE.z, SAMPLE.x + 1, SAMPLE.y, SAMPLE.z)
        },
        ActionSearchParams.build { after = SAMPLE.timestamp.plusNanos(1) },
        ActionSearchParams.build { before = SAMPLE.timestamp.minusNanos(1) },
        ActionSearchParams.build { actions = mutableSetOf(Negatable.allow("item-drop")) },
        ActionSearchParams.build {
            objects = mutableSetOf(Negatable.allow(Identifier.tryParse("minecraft:furnace")!!))
        },
        ActionSearchParams.build { sourceNames = mutableSetOf(Negatable.allow("Server")) },
        ActionSearchParams.build {
            sourcePlayerIds = mutableSetOf(Negatable.allow(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
        },
        ActionSearchParams.build {
            worlds = mutableSetOf(Negatable.allow(Identifier.tryParse("minecraft:the_nether")!!))
        },
        ActionSearchParams.build { rolledBack = false },
        ActionSearchParams.build { actions = mutableSetOf(Negatable.deny(SAMPLE.action)) }
    )

    for (version in 1..4) {
        assertMetadataRead(store, version, matching, expectedId = SAMPLE.id, rolledBackIds = setOf(SAMPLE.id))
        rejected.forEach { params ->
            assertMetadataRead(store, version, params, expectedId = null, rolledBackIds = setOf(SAMPLE.id))
        }
    }
}

private fun largeInlineV4MetadataIsSkippedWithoutResolution() {
    val largeAction = SAMPLE.copy(
        objectState = "x".repeat(1024 * 1024),
        oldObjectState = "y".repeat(1024 * 1024),
        extraData = "z".repeat(1024 * 1024)
    )
    val dictionary = dictionary(largeAction, includeState = false, includeExtra = false)
    val encoded = encodeMetadataRecord(4, largeAction, dictionary)
    var dictionaryResolutions = 0
    DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        val id = IrminsulLedgerStore().readActionIdIfMatchesForRegression(
            input,
            recordVersion = 4,
            params = ActionSearchParams.build {},
            dictionaryValue = {
                dictionaryResolutions += 1
                dictionary.entries.first { entry -> entry.value == it }.key
            }
        )
        check(id == largeAction.id)
        check(dictionaryResolutions == 0) { "Unfiltered metadata scan resolved dictionary strings" }
        check(input.readInt() == METADATA_MARKER) { "V4 metadata scan did not skip large inline fields" }
    }
}

private fun reversePreScanSkipsV2Record() {
    val dictionary = dictionary(includeState = true, includeExtra = true)
    assertRecordSkip(
        write = { output -> SAMPLE.writeV2(output, dictionary::getValue) },
        skip = DataInputStream::readActionIdAndSkipV2
    )
}

private fun reversePreScanSkipsV3Record() {
    val dictionary = dictionary(includeState = true, includeExtra = false)
    assertRecordSkip(
        write = { output -> SAMPLE.writeV3(output, dictionary::getValue) },
        skip = DataInputStream::readActionIdAndSkipV3
    )
}

private fun reversePreScanSkipsV4Record() {
    val dictionary = dictionary(includeState = false, includeExtra = false)
    assertRecordSkip(
        write = { output -> SAMPLE.writeV4(output, dictionary::get) },
        skip = DataInputStream::readActionIdAndSkipV4
    )
}

private fun sparseReverseIndexUsesBoundedBlocks() {
    val index = IrminsulLedgerStore.SegmentReverseIndex(stride = 3)
    for (id in 1..8) index.add(id, id * 10L)
    check(index.blockCount == 3)
    check(index.firstActionId(0) == 1 && index.offset(0) == 10L)
    check(index.firstActionId(1) == 4 && index.offset(1) == 40L)
    check(index.firstActionId(2) == 7 && index.offset(2) == 70L)
}

private fun sparseBlockSummarySkipsImpossibleFilters() {
    val index = IrminsulLedgerStore.SegmentReverseIndex(stride = 3)
    index.add(SAMPLE.withId(1).copy(x = 0, z = 0), 10L)
    index.add(SAMPLE.withId(2).copy(x = 160, z = 160), 20L)
    index.add(SAMPLE.withId(3).copy(x = 0, z = 0), 30L)
    val secondBlock = SAMPLE.withId(4).copy(action = "item-drop", sourceName = "Server")
    index.add(secondBlock, 40L)

    val exact = ActionSearchParams.build {
        bounds = BlockBox(SAMPLE.x, SAMPLE.y, SAMPLE.z, SAMPLE.x, SAMPLE.y, SAMPLE.z)
        before = SAMPLE.timestamp
        after = SAMPLE.timestamp
        actions = mutableSetOf(Negatable.allow(SAMPLE.action))
        objects = mutableSetOf(Negatable.allow(SAMPLE.oldObjectIdentifier))
        sourceNames = mutableSetOf(Negatable.allow(SAMPLE.sourceName))
        sourcePlayerIds = mutableSetOf(Negatable.allow(SAMPLE.sourcePlayerId!!))
        worlds = mutableSetOf(Negatable.allow(SAMPLE.world))
    }
    check(index.mightMatch(0, exact.copy(bounds = BlockBox(0, SAMPLE.y, 0, 0, SAMPLE.y, 0)), Int.MAX_VALUE))
    check(
        !index.mightMatch(
            0,
            ActionSearchParams.build { actions = mutableSetOf(Negatable.allow("missing")) },
            Int.MAX_VALUE
        )
    )
    check(
        !index.mightMatch(
            0,
            ActionSearchParams.build { sourceNames = mutableSetOf(Negatable.allow("missing")) },
            Int.MAX_VALUE
        )
    )
    check(!index.mightMatch(0, ActionSearchParams.build { after = SAMPLE.timestamp.plusNanos(1) }, Int.MAX_VALUE))
    check(
        !index.mightMatch(
            0,
            ActionSearchParams.build { bounds = BlockBox(80, SAMPLE.y, 80, 95, SAMPLE.y, 95) },
            Int.MAX_VALUE
        )
    )
    check(
        index.mightMatch(
            0,
            ActionSearchParams.build { actions = mutableSetOf(Negatable.deny("missing")) },
            Int.MAX_VALUE
        )
    )
    check(
        !index.mightMatch(
            1,
            ActionSearchParams.build { actions = mutableSetOf(Negatable.allow(SAMPLE.action)) },
            Int.MAX_VALUE
        )
    )
    check(!index.mightMatch(1, ActionSearchParams.build {}, maxExclusiveId = secondBlock.id))

    val unknownSummary = IrminsulLedgerStore.SegmentReverseIndex(stride = 3).apply { add(1, 10L) }
    check(
        unknownSummary.mightMatch(0, ActionSearchParams.build { actions = mutableSetOf(Negatable.allow("missing")) }, 2)
    )
}

private fun sparseReverseScanHonorsBoundariesAndPartialTail() {
    val root = Files.createTempDirectory("irminsul-reverse-regression")
    try {
        val file = root.resolve("actions-00000000.lfda")
        DataOutputStream(Files.newOutputStream(file)).use { output ->
            output.writeInt(0x4C464441)
            output.writeInt(1)
            for (id in 1..700) {
                val payload = encode { actionOutput -> SAMPLE.withId(id).write(actionOutput) }
                output.writeByte(1)
                output.writeInt(payload.size)
                output.write(payload)
            }
        }
        Files.write(file, byteArrayOf(1), StandardOpenOption.APPEND)

        val store = IrminsulLedgerStore()
        val newestColdIds = ArrayList<Int>()
        check(
            !store.forEachSegmentActionReverse(file, maxExclusiveId = 650) { action ->
            newestColdIds.add(action.id)
            newestColdIds.size < 10
        }
        )
        check(newestColdIds == (649 downTo 640).toList())

        val boundaryIds = ArrayList<Int>()
        check(
            !store.forEachSegmentActionReverse(file, maxExclusiveId = 513) { action ->
            boundaryIds.add(action.id)
            boundaryIds.size < 5
        }
        )
        check(boundaryIds == (512 downTo 508).toList())
    } finally {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

private fun segmentSummarySkipsImpossibleFilters() {
    val summary = IrminsulLedgerStore.SegmentSummary().apply { add(SAMPLE) }
    val missingAction = ActionSearchParams.build {
        actions = mutableSetOf(Negatable.allow("item-drop"))
    }
    val deniedAction = ActionSearchParams.build {
        actions = mutableSetOf(Negatable.deny(SAMPLE.action))
    }
    check(summary.countActions(setOf(SAMPLE.action)) == 1L)
    check(summary.countActions(setOf("item-drop")) == 0L)
    check(summary.countSources(setOf(SAMPLE.sourceName)) == 1L)
    check(summary.countWorlds(setOf(SAMPLE.world)) == 1L)
    check(summary.countPlayers(setOf(SAMPLE.sourcePlayerId!!)) == 1L)
    check(summary.countObject(SAMPLE.objectIdentifier) == 1L)
    check(summary.countObject(SAMPLE.oldObjectIdentifier) == 1L)
    val selfTransitionSummary = IrminsulLedgerStore.SegmentSummary().apply {
        add(SAMPLE.copy(oldObjectIdentifier = SAMPLE.objectIdentifier))
    }
    check(selfTransitionSummary.countObject(SAMPLE.objectIdentifier) == 1L)
    val spatialSummary = IrminsulLedgerStore.SegmentSummary().apply {
        add(SAMPLE.copy(x = 0, z = 0))
        add(SAMPLE.copy(x = 160, z = 160))
    }
    check(
        !spatialSummary.mightMatch(
            ActionSearchParams.build { bounds = BlockBox(80, SAMPLE.y, 80, 95, SAMPLE.y, 95) },
            Int.MAX_VALUE
        )
    )
    check(
        spatialSummary.mightMatch(
            ActionSearchParams.build { bounds = BlockBox(0, SAMPLE.y, 0, 15, SAMPLE.y, 15) },
            Int.MAX_VALUE
        )
    )
    check(summary.mightMatch(ActionSearchParams.build {}, Int.MAX_VALUE))
    check(!summary.mightMatch(ActionSearchParams.build { after = SAMPLE.timestamp.plusSeconds(1) }, Int.MAX_VALUE))
    check(!summary.mightMatch(ActionSearchParams.build { before = SAMPLE.timestamp.minusSeconds(1) }, Int.MAX_VALUE))
    check(!summary.mightMatch(missingAction, Int.MAX_VALUE))
    check(summary.mightMatch(deniedAction, Int.MAX_VALUE))
    check(summary.mightMatch(ActionSearchParams.build { actions = mutableSetOf() }, Int.MAX_VALUE))
    check(!summary.mightMatch(null, SAMPLE.id))
}

private fun failedSegmentBatchIsRolledBack() {
    val root = Files.createTempDirectory("irminsul-codec-regression")
    try {
        val actions = root.resolve("actions").also { it.createDirectories() }
        val state = root.resolve("state.lfds")
        val dictionary = dictionary(includeState = false, includeExtra = false)
        val definitions = dictionary.entries.map { (value, id) -> id to value }
        val rewritten = SAMPLE.withId(1)
        IrminsulLedgerStore.SegmentWriter(actions, 0, 96L, fsyncOnBatch = false).use { writer ->
            val failure = runCatching {
                writer.write(definitions, listOf(rewritten)) { value ->
                    if (value == SAMPLE.sourceName) error("injected codec failure")
                    dictionary[value]
                }
            }.exceptionOrNull()
            check(failure != null)
            writer.write(definitions, listOf(rewritten), dictionary::get)
        }
        IrminsulLedgerStore.StateWriter(state, fsyncOnBatch = false).use { writer ->
            writer.writeRollbackStatesCompressed(intArrayOf(rewritten.id), 1, true)
        }

        val store = IrminsulLedgerStore()
        check(store.validateRewrite(actions, state, expectedActions = 1))
        Files.writeString(root.resolve(".purge-ready"), "1")
        check(store.validatePurgeRoot(root))
        Files.writeString(root.resolve(".purge-ready"), "invalid")
        check(!store.validatePurgeRoot(root))
        Files.writeString(root.resolve(".purge-ready"), "1")
        val lastSegment = Files.list(actions).use { stream -> stream.max(Comparator.naturalOrder()).orElseThrow() }
        Files.write(lastSegment, byteArrayOf(0x7F), StandardOpenOption.APPEND)
        check(!store.validateRewrite(actions, state, expectedActions = 1))
        check(!store.validatePurgeRoot(root))
    } finally {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

private fun truncatedV4IsRejected() {
    val dictionary = dictionary(includeState = false, includeExtra = false)
    val bytes = encode { output -> SAMPLE.writeV4(output, dictionary::get) }
    val truncated = bytes.copyOf(bytes.size - 1)
    val failure = runCatching {
        decode(truncated) { input ->
            IrminsulLedgerStore.StoredAction.readV4(
                input,
                dictionary.entries.associate { it.value to it.key }::getValue
            )
        }
    }.exceptionOrNull()
    check(failure is EOFException) { "Expected EOFException for a truncated V4 record, got $failure" }
}

private fun dictionary(
    action: IrminsulLedgerStore.StoredAction = SAMPLE,
    includeState: Boolean,
    includeExtra: Boolean
): Map<String, Int> {
    val values = linkedSetOf(
        action.action,
        action.world.toString(),
        action.objectIdentifier.toString(),
        action.oldObjectIdentifier.toString(),
        action.sourceName,
        action.sourcePlayerName!!
    )
    if (includeState) {
        action.objectState?.let(values::add)
        action.oldObjectState?.let(values::add)
    }
    if (includeExtra) action.extraData?.let(values::add)
    return values.withIndex().associate { (index, value) -> value to index + 1 }
}

private fun assertMetadataRead(
    store: IrminsulLedgerStore,
    version: Int,
    params: ActionSearchParams,
    expectedId: Int?,
    rolledBackIds: Set<Int>
) {
    val dictionary = when (version) {
        2 -> dictionary(includeState = true, includeExtra = true)
        3 -> dictionary(includeState = true, includeExtra = false)
        else -> dictionary(includeState = false, includeExtra = false)
    }
    val valuesById = dictionary.entries.associate { it.value to it.key }
    DataInputStream(ByteArrayInputStream(encodeMetadataRecord(version, SAMPLE, dictionary))).use { input ->
        val id = store.readActionIdIfMatchesForRegression(
            input,
            version,
            params,
            valuesById::getValue,
            rolledBackIds
        )
        check(id == expectedId) { "Unexpected V$version metadata filter result: $id" }
        check(input.readInt() == METADATA_MARKER) { "V$version metadata reader crossed a record boundary" }
    }
}

private fun encodeMetadataRecord(
    version: Int,
    action: IrminsulLedgerStore.StoredAction,
    dictionary: Map<String, Int>
): ByteArray = encode { output ->
    when (version) {
        1 -> {
            val payload = encode(action::write)
            output.writeInt(payload.size)
            output.write(payload)
        }
        2 -> {
            action.writeV2(output, dictionary::getValue)
        }
        3 -> {
            action.writeV3(output, dictionary::getValue)
        }
        4 -> {
            action.writeV4(output, dictionary::get)
        }
        else -> {
            error("Unsupported metadata record version $version")
        }
    }
    output.writeInt(METADATA_MARKER)
}

private fun retainedDictionaryIds(dictionary: Map<String, Int>): Set<Int> = setOf(
    dictionary.getValue(SAMPLE.action),
    dictionary.getValue(SAMPLE.world.toString()),
    dictionary.getValue(SAMPLE.objectIdentifier.toString()),
    dictionary.getValue(SAMPLE.oldObjectIdentifier.toString()),
    dictionary.getValue(SAMPLE.sourceName),
    dictionary.getValue(SAMPLE.sourcePlayerName!!)
)

private fun assertRecordSkip(
    write: (DataOutputStream) -> Unit,
    skip: DataInputStream.() -> Int
) {
    val marker = 0x13579BDF
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        write(output)
        output.writeInt(marker)
    }
    DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
        check(input.skip() == SAMPLE.id)
        check(input.readInt() == marker) { "Reverse pre-scan did not stop at the record boundary" }
    }
}

private fun encode(write: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use(write)
    return bytes.toByteArray()
}

private fun <T> decode(bytes: ByteArray, read: (DataInputStream) -> T): T =
    DataInputStream(ByteArrayInputStream(bytes)).use(read)

private const val METADATA_MARKER = 0x2468ACE
