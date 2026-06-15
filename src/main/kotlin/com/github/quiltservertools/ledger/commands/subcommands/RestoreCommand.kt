package com.github.quiltservertools.ledger.commands.subcommands

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actionutils.ActionSearchParams
import com.github.quiltservertools.ledger.actionutils.RollbackOperations
import com.github.quiltservertools.ledger.commands.BuildableCommand
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.commands.arguments.SearchParamArgument
import com.github.quiltservertools.ledger.utility.Context
import com.github.quiltservertools.ledger.utility.LiteralNode
import com.github.quiltservertools.ledger.utility.MessageUtils
import com.github.quiltservertools.ledger.utility.TextColorPallet
import com.github.quiltservertools.ledger.utility.literal
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text

object RestoreCommand : BuildableCommand {
    override fun build(): LiteralNode {
        return CommandManager.literal("restore")
            .requires(Permissions.require("ledger.commands.rollback", CommandConsts.PERMISSION_LEVEL))
            .then(
                SearchParamArgument.argument("params")
                    .executes { restore(it, SearchParamArgument.get(it, "params")) }
            )
            .build()
    }

    @Suppress("TooGenericExceptionCaught")
    fun restore(context: Context, params: ActionSearchParams): Int {
        val source = context.source
        params.ensureSpecific()
        Ledger.launch {
            MessageUtils.warnBusy(source)
            try {
                val result = RollbackOperations.execute(
                    context.source.server,
                    params,
                    RollbackOperations.Mode.RESTORE
                ) { selection ->
                    source.sendFeedback(
                        {
                            Text.translatable(
                                "text.ledger.restore.start",
                                selection.requestedActions.toString().literal().setStyle(TextColorPallet.secondary)
                            ).setStyle(TextColorPallet.primary)
                        },
                        true
                    )
                }

                if (result.requestedActions == 0) {
                    source.sendError(Text.translatable("error.ledger.command.no_results"))
                    return@launch
                }

                Ledger.logger.info(
                    "Restore executor applied ${result.appliedActions}/${result.attemptedActions} world operations; " +
                        "${result.successfulActionIds.size}/${result.requestedActions} selected action ids succeeded"
                )

                for (entry in result.failures.entries) {
                    source.sendFeedback(
                        {
                            Text.translatable("text.ledger.restore.fail", entry.key, entry.value).setStyle(
                                TextColorPallet.secondary
                            )
                        },
                        true
                    )
                }

                source.sendFeedback(
                    {
                        Text.translatable(
                            if (result.successfulActionIds.size == result.requestedActions) {
                                "text.ledger.restore.finish"
                            } else {
                                "text.ledger.restore.partial"
                            }
                        )
                            .append(" ")
                            .append(
                                "${result.successfulActionIds.size}/${result.requestedActions}"
                                    .literal()
                                    .setStyle(TextColorPallet.secondary)
                            )
                            .setStyle(TextColorPallet.primary)
                    },
                    true
                )
            } catch (throwable: Throwable) {
                Ledger.logger.warn("Ledger restore failed", throwable)
                source.sendError(Text.literal(throwable.message ?: "Ledger restore failed. Check server logs."))
            }
        }
        return 1
    }
}
