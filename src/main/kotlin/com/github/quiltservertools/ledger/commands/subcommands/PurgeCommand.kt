package com.github.quiltservertools.ledger.commands.subcommands

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.RollbackOperations
import com.github.quiltservertools.ledger.commands.BuildableCommand
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.commands.arguments.SearchParamArgument
import com.github.quiltservertools.ledger.config.SearchSpec
import com.github.quiltservertools.ledger.config.config
import com.github.quiltservertools.ledger.utility.Context
import com.github.quiltservertools.ledger.utility.LiteralNode
import com.github.quiltservertools.ledger.utility.TextColorPallet
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text

object PurgeCommand : BuildableCommand {
    override fun build(): LiteralNode {
        return literal("purge")
            .requires(Permissions.require("ledger.commands.purge", config[SearchSpec.purgePermissionLevel]))
            .then(
                SearchParamArgument.argument(CommandConsts.PARAMS).executes {
                runPurge(it, SearchParamArgument.get(it, CommandConsts.PARAMS))
            }
            )
            .build()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runPurge(ctx: Context, params: ActionSearchParams): Int {
        val source = ctx.source
        params.ensurePurgeScoped()
        source.sendFeedback(
            { Text.translatable("text.ledger.purge.starting").setStyle(TextColorPallet.secondary) },
            true
        )
        Ledger.launch {
            try {
                RollbackOperations.purge(params)
                source.sendFeedback(
                    { Text.translatable("text.ledger.purge.complete").setStyle(TextColorPallet.secondary) },
                    true
                )
            } catch (throwable: Throwable) {
                Ledger.logger.warn("Ledger purge failed", throwable)
                source.sendError(Text.literal(throwable.message ?: "Ledger purge failed. Check server logs."))
            }
        }
        return 1
    }
}
