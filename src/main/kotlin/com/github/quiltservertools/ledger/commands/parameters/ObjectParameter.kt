package com.github.quiltservertools.ledger.commands.parameters

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.IdentifierArgumentType
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.util.Identifier
import java.util.concurrent.CompletableFuture

private val UNKNOWN_OBJECT_TAG = DynamicCommandExceptionType {
    LiteralMessage("Unknown or empty block, item, or entity tag: #$it")
}
private val MISSING_OBJECT = SimpleCommandExceptionType(LiteralMessage("Expected a block, item, entity, or tag."))

internal fun requireKnownObjectTag(
    stringReader: StringReader,
    tagId: Identifier,
    matches: Set<Identifier>
): List<Identifier> {
    if (matches.isEmpty()) throw UNKNOWN_OBJECT_TAG.createWithContext(stringReader, tagId)
    return matches.toList()
}

class ObjectParameter : SimpleParameter<List<Identifier>>() {
    private val identifiers by lazy {
        mutableListOf<Identifier>().apply {
            addAll(Registries.ITEM.ids)
            addAll(Registries.BLOCK.ids)
            addAll(Registries.ENTITY_TYPE.ids)
        }
    }

    override fun parse(stringReader: StringReader): List<Identifier> {
        if (!stringReader.canRead()) throw MISSING_OBJECT.createWithContext(stringReader)
        if (stringReader.peek() == '#') {
            stringReader.skip()
            val tagId = IdentifierArgumentType.identifier().parse(stringReader)
            val matches = buildSet {
                addAll(
                    Registries.BLOCK.iterateEntries(TagKey.of(RegistryKeys.BLOCK, tagId))
                        .map { Registries.BLOCK.getId(it.value()) }
                )
                addAll(
                    Registries.ITEM.iterateEntries(TagKey.of(RegistryKeys.ITEM, tagId))
                        .map { Registries.ITEM.getId(it.value()) }
                )
                addAll(
                    Registries.ENTITY_TYPE.iterateEntries(TagKey.of(RegistryKeys.ENTITY_TYPE, tagId))
                        .map { Registries.ENTITY_TYPE.getId(it.value()) }
                )
            }
            return requireKnownObjectTag(stringReader, tagId, matches)
        }
        return listOf(IdentifierArgumentType.identifier().parse(stringReader))
    }

    override fun getSuggestions(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        return if (builder.remaining.startsWith("#")) {
            CommandSource.suggestIdentifiers(
                mutableListOf<Identifier>().apply {
                    addAll(Registries.BLOCK.streamTags().map { it.tag.id() }.toList())
                    addAll(Registries.ITEM.streamTags().map { it.tag.id() }.toList())
                    addAll(Registries.ENTITY_TYPE.streamTags().map { it.tag.id() }.toList())
                },
                builder.createOffset(builder.start + 1)
            )
        } else {
            CommandSource.suggestIdentifiers(
                identifiers,
                builder
            )
        }
    }
}
