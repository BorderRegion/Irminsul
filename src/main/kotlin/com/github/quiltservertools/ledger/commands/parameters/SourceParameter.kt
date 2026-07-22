package com.github.quiltservertools.ledger.commands.parameters

import com.github.quiltservertools.ledger.database.DatabaseManager
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.command.CommandSource
import net.minecraft.server.command.ServerCommandSource
import java.util.concurrent.CompletableFuture

private val MISSING_SOURCE = SimpleCommandExceptionType(LiteralMessage("Expected a player name or source."))

class SourceParameter : SimpleParameter<String>() {
    override fun parse(stringReader: StringReader): String {
        val i: Int = stringReader.cursor

        while (stringReader.canRead() && stringReader.peek() != ' ') {
            stringReader.skip()
        }

        val source = stringReader.string.substring(i, stringReader.cursor)
        if (source.isEmpty() || source == "@") throw MISSING_SOURCE.createWithContext(stringReader)
        return source
    }

    override fun getSuggestions(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val sources = buildSet {
            addAll(context.source.playerNames)
            addAll(DatabaseManager.getKnownPlayerNames())
            DatabaseManager.getKnownSources().forEach {
                add("@$it")
            }
        }
        return CommandSource.suggestMatching(
            sources,
            builder
        )
    }
}
