package com.github.quiltservertools.ledger.database

import com.github.quiltservertools.ledger.actions.ActionType
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.Preview
import com.github.quiltservertools.ledger.actionutils.RollbackExecutor
import com.github.quiltservertools.ledger.actionutils.SearchResults
import com.github.quiltservertools.ledger.utility.PlayerResult
import com.mojang.authlib.GameProfile
import net.minecraft.util.Identifier
import java.util.UUID

interface LedgerStore {
    val databaseType: String

    fun setup()
    fun ensureTables()
    suspend fun setupCache()
    suspend fun autoPurge()
    suspend fun searchActions(params: ActionSearchParams, page: Int): SearchResults
    suspend fun countActions(params: ActionSearchParams): Long
    suspend fun selectRollback(params: ActionSearchParams): List<ActionType>
    suspend fun selectRestore(params: ActionSearchParams): List<ActionType>
    suspend fun selectRollbackPlan(params: ActionSearchParams): RollbackExecutor.Selection =
        RollbackExecutor.Selection(selectRollback(params))

    suspend fun selectRestorePlan(params: ActionSearchParams): RollbackExecutor.Selection =
        RollbackExecutor.Selection(selectRestore(params))

    suspend fun previewActions(params: ActionSearchParams, type: Preview.Type): List<ActionType>
    suspend fun logActionBatch(actions: List<ActionType>)
    suspend fun registerWorld(identifier: Identifier)
    suspend fun registerActionType(id: String)
    suspend fun logPlayer(uuid: UUID, name: String)
    suspend fun insertIdentifiers(identifiers: Collection<Identifier>)
    suspend fun rollbackActions(actionIds: Set<Int>)
    suspend fun restoreActions(actionIds: Set<Int>)
    suspend fun purgeActions(params: ActionSearchParams)
    suspend fun searchPlayers(players: Set<GameProfile>): List<PlayerResult>
    fun getKnownSources(): Set<String>
    fun close()
}
