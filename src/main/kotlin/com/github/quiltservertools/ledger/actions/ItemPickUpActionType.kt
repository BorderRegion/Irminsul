package com.github.quiltservertools.ledger.actions

import com.github.quiltservertools.ledger.utility.NbtUtils
import com.github.quiltservertools.ledger.utility.TextColorPallet
import com.github.quiltservertools.ledger.utility.UUID
import com.github.quiltservertools.ledger.utility.getWorld
import com.github.quiltservertools.ledger.utility.literal
import net.minecraft.entity.Entity
import net.minecraft.item.BlockItem
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.util.Util

open class ItemPickUpActionType : AbstractActionType() {
    override val identifier = "item-pick-up"

    override fun getTranslationType(): String {
        val item = Registries.ITEM.get(objectIdentifier)
        return if (item is BlockItem) {
            "block"
        } else {
            "item"
        }
    }

    private fun getStack(server: MinecraftServer) = NbtUtils.itemFromProperties(
        extraData,
        objectIdentifier,
        server.registryManager
    )

    override fun getObjectMessage(source: ServerCommandSource): Text {
        val stack = getStack(source.server)

        return "${stack.count} ".literal().append(
            Text.translatable(
                Util.createTranslationKey(
                    getTranslationType(),
                    objectIdentifier
                )
            )
        ).setStyle(TextColorPallet.secondaryVariant).styled {
            it.withHoverEvent(HoverEvent.ShowItem(stack))
        }
    }

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false

        val oldEntity = NbtUtils.readCompound(oldObjectState)
        val uuid = NbtUtils.getUuid(oldEntity, UUID) ?: return false
        val entity = world.getEntity(uuid)

        if (entity == null) {
            return NbtUtils.entityFromNbt(oldEntity, world)?.let { world.spawnEntity(it) } ?: false
        }
        return true
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)

        val oldEntity = NbtUtils.readCompound(oldObjectState)
        val uuid = NbtUtils.getUuid(oldEntity, UUID) ?: return false
        val entity = world?.getEntity(uuid)

        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED)
            return true
        }
        return false
    }
}
