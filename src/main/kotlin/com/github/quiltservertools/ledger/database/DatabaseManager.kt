package com.github.quiltservertools.ledger.database

import com.github.quiltservertools.ledger.actions.ActionType
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.Preview
import com.github.quiltservertools.ledger.actionutils.RollbackExecutor
import com.github.quiltservertools.ledger.actionutils.SearchResults
import com.github.quiltservertools.ledger.config.DatabaseSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.database.fast.FastLedgerStore
import com.github.quiltservertools.ledger.logInfo
import com.github.quiltservertools.ledger.utility.PlayerResult
import com.mojang.authlib.GameProfile
import net.minecraft.util.Identifier
import java.util.UUID
import javax.sql.DataSource

object DatabaseManager {
    private lateinit var store: LedgerStore

    val databaseType: String
        get() = store.databaseType

    fun setup(dataSource: DataSource?) {
        store = when (val engine = config[DatabaseSpec.engine].lowercase()) {
            "fastdb", "fast", "ledger-fastdb" -> FastLedgerStore()
            "sql", "sqlite", "jdbc", "" -> SqlLedgerStore(dataSource)
            else -> error("Unknown Ledger database engine '$engine'. Expected 'sql' or 'fastdb'.")
        }
        store.setup()
        logInfo("Using Ledger database engine: ${store.databaseType}")
    }

    fun ensureTables() = store.ensureTables()
    suspend fun setupCache() = store.setupCache()
    suspend fun autoPurge() = store.autoPurge()
    suspend fun searchActions(params: ActionSearchParams, page: Int): SearchResults =
        store.searchActions(params, page)

    suspend fun countActions(params: ActionSearchParams): Long =
        store.countActions(params)

    suspend fun rollbackActions(params: ActionSearchParams): List<ActionType> {
        val actions = store.selectRollback(params)
        store.rollbackActions(actions.mapTo(HashSet()) { it.id })
        return actions
    }

    suspend fun rollbackActions(actionIds: Set<Int>) =
        store.rollbackActions(actionIds)

    suspend fun restoreActions(params: ActionSearchParams): List<ActionType> {
        val actions = store.selectRestore(params)
        store.restoreActions(actions.mapTo(HashSet()) { it.id })
        return actions
    }

    suspend fun restoreActions(actionIds: Set<Int>) =
        store.restoreActions(actionIds)

    suspend fun selectRollback(params: ActionSearchParams): List<ActionType> =
        store.selectRollback(params)

    suspend fun selectRestore(params: ActionSearchParams): List<ActionType> =
        store.selectRestore(params)

    suspend fun selectRollbackPlan(params: ActionSearchParams): RollbackExecutor.Selection =
        store.selectRollbackPlan(params)

    suspend fun selectRestorePlan(params: ActionSearchParams): RollbackExecutor.Selection =
        store.selectRestorePlan(params)

    suspend fun previewActions(params: ActionSearchParams, type: Preview.Type): List<ActionType> =
        store.previewActions(params, type)

    suspend fun logActionBatch(actions: List<ActionType>) =
        store.logActionBatch(actions)

    suspend fun registerWorld(identifier: Identifier) =
        store.registerWorld(identifier)

    suspend fun registerActionType(id: String) =
        store.registerActionType(id)

    suspend fun logPlayer(uuid: UUID, name: String) =
        store.logPlayer(uuid, name)

    suspend fun insertIdentifiers(identifiers: Collection<Identifier>) =
        store.insertIdentifiers(identifiers)

    suspend fun purgeActions(params: ActionSearchParams) =
        store.purgeActions(params)

    suspend fun searchPlayers(players: Set<GameProfile>): List<PlayerResult> =
        store.searchPlayers(players)

    fun getKnownSources(): Set<String> =
        store.getKnownSources()

    fun close() {
        if (::store.isInitialized) store.close()
    }
}
