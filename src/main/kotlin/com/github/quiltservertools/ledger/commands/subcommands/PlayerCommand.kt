package com.github.quiltservertools.ledger.commands.subcommands

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.commands.BuildableCommand
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.database.DatabaseManager
import com.github.quiltservertools.ledger.utility.Context
import com.github.quiltservertools.ledger.utility.LiteralNode
import com.github.quiltservertools.ledger.utility.MessageUtils
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.GameProfileArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.util.UUID

internal fun resolveDirectPlayerIds(input: String, knownPlayerIds: Set<UUID>, onlinePlayerId: UUID?): Set<UUID> {
    runCatching { UUID.fromString(input) }.getOrNull()?.let { return setOf(it) }
    return buildSet {
        addAll(knownPlayerIds)
        onlinePlayerId?.let(::add)
    }
}

object PlayerCommand : BuildableCommand {
    private const val PLAYER_ARGUMENT = "player"

    override fun build(): LiteralNode {
        return literal("player")
            .requires(Permissions.require("ledger.commands.player", CommandConsts.PERMISSION_LEVEL))
            .then(
                argument(PLAYER_ARGUMENT, GameProfileArgumentType.gameProfile())
                .suggests { context, builder ->
                    if (builder.remaining.startsWith('@')) {
                        GameProfileArgumentType.gameProfile().listSuggestions(context, builder)
                    } else {
                        CommandSource.suggestMatching(
                            buildSet {
                                addAll(context.source.playerNames)
                                addAll(DatabaseManager.getKnownPlayerNames())
                            },
                            builder
                        )
                    }
                }
                .executes {
                    return@executes lookupPlayer(resolvePlayerIds(it), it.source)
                }
            )
            .build()
    }

    private fun resolvePlayerIds(context: Context): Set<UUID> {
        val input = context.nodes.last { it.node.name == PLAYER_ARGUMENT }.range.get(context.input)
        if (input.startsWith('@')) {
            return GameProfileArgumentType.getProfileArgument(context, PLAYER_ARGUMENT).mapTo(HashSet()) { it.id() }
        }

        return resolveDirectPlayerIds(
            input,
            DatabaseManager.getKnownPlayerIdsByName(input),
            context.source.server.playerManager.getPlayer(input)?.uuid
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun lookupPlayer(playerIds: Set<UUID>, source: ServerCommandSource): Int {
        Ledger.launch {
            try {
                val players = DatabaseManager.searchPlayers(playerIds)
                MessageUtils.sendPlayerMessage(source, players)
            } catch (throwable: Throwable) {
                Ledger.logger.warn("Ledger player lookup failed", throwable)
                source.sendError(Text.literal(throwable.message ?: "Ledger player lookup failed. Check server logs."))
            }
        }

        return 1
    }
}
