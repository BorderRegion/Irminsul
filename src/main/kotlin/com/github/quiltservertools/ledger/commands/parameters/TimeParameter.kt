package com.github.quiltservertools.ledger.commands.parameters

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.server.command.ServerCommandSource
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

private const val MAX_SIZE = 9
private val TIME_PATTERN = Regex("(?:[0-9]+[smhdw])+")
private val TIME_PART_PATTERN = Regex("([0-9]+)([smhdw])")
private val INVALID_TIME = SimpleCommandExceptionType(
    LiteralMessage("Invalid time. Use one or more values such as 30m, 2h, or 1d12h.")
)

class TimeParameter : SimpleParameter<Instant>() {
    private val units = listOf('s', 'm', 'h', 'd', 'w')

    @Suppress("MagicNumber")
    override fun parse(stringReader: StringReader): Instant {
        val i: Int = stringReader.cursor

        while (stringReader.canRead() && isCharValid(stringReader.peek())) {
            stringReader.skip()
        }

        val input = stringReader.string.substring(i, stringReader.cursor).lowercase()
        if (!TIME_PATTERN.matches(input)) throw INVALID_TIME.createWithContext(stringReader)

        return try {
            var duration = Duration.ZERO
            for (time in TIME_PART_PATTERN.findAll(input)) {
                val timeValue = time.groupValues[1].toLong()
                val timeUnit = time.groupValues[2]

                when (timeUnit) {
                    "s" -> duration = duration.plusSeconds(timeValue)
                    "m" -> duration = duration.plusMinutes(timeValue)
                    "h" -> duration = duration.plusHours(timeValue)
                    "d" -> duration = duration.plusDays(timeValue)
                    "w" -> duration = duration.plusDays(Math.multiplyExact(timeValue, 7L))
                }
            }
            Instant.now().minus(duration)
        } catch (_: NumberFormatException) {
            throw INVALID_TIME.createWithContext(stringReader)
        } catch (_: ArithmeticException) {
            throw INVALID_TIME.createWithContext(stringReader)
        } catch (_: DateTimeException) {
            throw INVALID_TIME.createWithContext(stringReader)
        }
    }

    private fun isCharValid(c: Char) = c in '0'..'9' || c.lowercaseChar() in 'a'..'z'

    override fun getSuggestions(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remaining.lowercase()
        for (unit in units) {
            if (remaining.isEmpty()) {
                for (i in 1..MAX_SIZE) builder.suggest(i.toString() + unit)
            } else {
                val end = remaining.last()
                if (end in '1'..'9') {
                    builder.suggest(remaining + unit)
                }
            }
        }
        return builder.buildFuture()
    }
}
