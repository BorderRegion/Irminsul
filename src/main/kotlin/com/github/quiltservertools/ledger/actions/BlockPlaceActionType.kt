package com.github.quiltservertools.ledger.actions

import com.github.quiltservertools.ledger.utility.NbtUtils
import com.github.quiltservertools.ledger.utility.TextColorPallet
import com.github.quiltservertools.ledger.utility.getWorld
import com.github.quiltservertools.ledger.utility.literal
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.util.Util

class BlockPlaceActionType : BlockChangeActionType() {
    override val identifier = "block-place"

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)
        if (world == null || shouldSkipConflict(world.getBlockState(pos), newBlockState())) return false

        world.setBlockState(pos, oldBlockState())

        return true
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)
        if (world == null || shouldSkipConflict(world.getBlockState(pos), oldBlockState())) return false

        val state = newBlockState()
        world.setBlockState(pos, state)
        if (state.hasBlockEntity() && extraData != null) {
            world.getBlockEntity(pos)?.let {
                NbtUtils.readBlockEntity(it, NbtUtils.readCompound(extraData), server.registryManager)
            }
        }

        return true
    }

    override fun getObjectMessage(source: ServerCommandSource): Text = Text.translatable(
        Util.createTranslationKey(
            this.getTranslationType(),
            objectIdentifier
        )
    ).setStyle(TextColorPallet.secondaryVariant).styled {
        it.withHoverEvent(HoverEvent.ShowText(objectIdentifier.toString().literal()))
    }
}
