package com.github.quiltservertools.ledger.commands.parameters

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.SearchResults
import com.github.quiltservertools.ledger.commands.subcommands.resolveDirectPlayerIds
import com.github.quiltservertools.ledger.database.DatabaseCacheService
import com.github.quiltservertools.ledger.database.SqlLedgerStore
import com.github.quiltservertools.ledger.database.Tables
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.util.Identifier
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

fun main() {
    rollbackStatusRejectsInvalidValues()
    timeParameterRejectsPartialAndInvalidValues()
    objectParameterRejectsUnknownTags()
    sourceParameterRejectsMissingValues()
    emptyFiltersCannotAuthorizePurge()
    playerNameIndexRetainsOfflineAliases()
    directPlayerLookupUsesLedgerHistoryAndUuids()
    sqlPlayerAliasSchemaMigratesExistingDatabase()
    searchResultCacheRetainsRecentWindowsAndIsolatesQueries()
}

private fun rollbackStatusRejectsInvalidValues() {
    val parameter = RollbackStatusParameter()
    check(parameter.parse(StringReader("true")))
    check(!parameter.parse(StringReader("false")))
    assertSyntaxFailure { parameter.parse(StringReader("not-a-boolean")) }
}

private fun timeParameterRejectsPartialAndInvalidValues() {
    val parameter = TimeParameter()
    val parsed = parameter.parse(StringReader("1h30m"))
    val age = Duration.between(parsed, Instant.now()).seconds
    check(age in 5_399L..5_401L) { "Unexpected parsed duration: $age seconds" }

    listOf("", "later", "1hgarbage", "h1", "999999999999999999999999999d").forEach { input ->
        assertSyntaxFailure { parameter.parse(StringReader(input)) }
    }
}

private fun objectParameterRejectsUnknownTags() {
    val parameter = ObjectParameter()
    check(parameter.parse(StringReader("minecraft:stone")) == listOf(Identifier.of("minecraft", "stone")))
    assertSyntaxFailure { parameter.parse(StringReader("")) }

    val tagId = Identifier.of("ledger", "this_tag_does_not_exist")
    assertSyntaxFailure { requireKnownObjectTag(StringReader("#ledger:this_tag_does_not_exist"), tagId, emptySet()) }

    val matches = setOf(Identifier.of("minecraft", "stone"), Identifier.of("minecraft", "stone"))
    check(requireKnownObjectTag(StringReader("#minecraft:stone"), tagId, matches) == matches.toList())
}

private fun sourceParameterRejectsMissingValues() {
    val parameter = SourceParameter()
    assertSyntaxFailure { parameter.parse(StringReader("")) }
    assertSyntaxFailure { parameter.parse(StringReader("@")) }
    check(parameter.parse(StringReader("OfflinePlayer")) == "OfflinePlayer")
    check(parameter.parse(StringReader("@lava")) == "@lava")
}

private fun emptyFiltersCannotAuthorizePurge() {
    val emptyFilters = ActionSearchParams.build {
        actions = mutableSetOf()
        objects = mutableSetOf()
        sourceNames = mutableSetOf()
        sourcePlayerIds = mutableSetOf()
        worlds = mutableSetOf()
    }
    assertSyntaxFailure(emptyFilters::ensurePurgeScoped)
    check(emptyFilters.canDedupeBlockActions())
}

private fun playerNameIndexRetainsOfflineAliases() {
    val playerId = UUID.fromString("12345678-1234-5678-9abc-def012345678")
    DatabaseCacheService.addPlayerName(playerId, "FormerName")
    DatabaseCacheService.addPlayerName(playerId, "CurrentName")

    check(DatabaseCacheService.getPlayerIdsByName("formername") == setOf(playerId))
    check(DatabaseCacheService.getPlayerIdsByName("CURRENTNAME") == setOf(playerId))
    check(DatabaseCacheService.getPlayerNames().containsAll(setOf("formername", "currentname")))
}

private fun directPlayerLookupUsesLedgerHistoryAndUuids() {
    val historicalId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val onlineId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    check(resolveDirectPlayerIds("FormerName", setOf(historicalId), onlineId) == setOf(historicalId, onlineId))
    check(resolveDirectPlayerIds(historicalId.toString(), emptySet(), onlineId) == setOf(historicalId))
}

private fun sqlPlayerAliasSchemaMigratesExistingDatabase() {
    val directory = Files.createTempDirectory("ledger-sql-migration")
    try {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${directory.resolve("ledger.sqlite")}"
        }
        val legacyDatabase = Database.connect(dataSource)
        val playerId = UUID.fromString("99999999-8888-7777-6666-555555555555")
        transaction(legacyDatabase) {
            SchemaUtils.create(Tables.Players)
            Tables.Players.insert {
                it[Tables.Players.playerId] = playerId
                it[Tables.Players.playerName] = "LegacyPlayer"
            }
        }

        SqlLedgerStore(dataSource).apply {
            setup()
            ensureTables()
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'player_aliases'"
                ).use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) { "player_aliases table was not migrated" }
                }
                statement.executeQuery("SELECT COUNT(*) FROM players").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) { "Legacy player row was not preserved" }
                }
                statement.executeUpdate(
                    "INSERT INTO player_aliases(player_id, player_name) " +
                        "SELECT id, 'LegacyPlayer' FROM players"
                )
                statement.executeUpdate(
                    "INSERT OR IGNORE INTO player_aliases(player_id, player_name) " +
                        "SELECT id, 'LegacyPlayer' FROM players"
                )
                statement.executeQuery("SELECT COUNT(*) FROM player_aliases").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) { "Player aliases are not unique per player" }
                }
            }
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun searchResultCacheRetainsRecentWindowsAndIsolatesQueries() {
    val sourceName = "cache-regression"
    val params = ActionSearchParams.build {}
    val pages = (1..30).associateWith { page -> SearchResults(emptyList(), params, page, 30) }

    Ledger.clearSearchResults(sourceName)
    Ledger.cacheSearchResults(sourceName, params, (1..10).map(pages::getValue))
    Ledger.cacheSearchResults(sourceName, params, (11..20).map(pages::getValue))
    check((1..20).all { Ledger.getCachedSearchResult(sourceName, params, it) === pages.getValue(it) })

    Ledger.cacheSearchResults(sourceName, params, (21..30).map(pages::getValue))
    check((1..10).all { Ledger.getCachedSearchResult(sourceName, params, it) == null })
    check((11..30).all { Ledger.getCachedSearchResult(sourceName, params, it) === pages.getValue(it) })

    Ledger.cacheSearchResults(sourceName, params, (1..10).map(pages::getValue))
    check((1..10).all { Ledger.getCachedSearchResult(sourceName, params, it) === pages.getValue(it) })
    check((11..20).all { Ledger.getCachedSearchResult(sourceName, params, it) == null })
    check((21..30).all { Ledger.getCachedSearchResult(sourceName, params, it) === pages.getValue(it) })
    check(Ledger.getCachedSearchTotalPages(sourceName, params) == 30)

    val nextParams = ActionSearchParams.build {}
    check(params == nextParams && params !== nextParams)
    val replacement = SearchResults(emptyList(), nextParams, 1, 1)
    Ledger.cacheSearchResults(sourceName, nextParams, listOf(replacement))
    check(Ledger.getCachedSearchResult(sourceName, params, 1) == null)
    check(Ledger.getCachedSearchResult(sourceName, nextParams, 1) === replacement)
    check(Ledger.getCachedSearchTotalPages(sourceName, nextParams) == 1)
    Ledger.clearSearchResults(sourceName)
}

private fun assertSyntaxFailure(block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    check(failure is CommandSyntaxException) { "Expected a command syntax error, got $failure" }
}
