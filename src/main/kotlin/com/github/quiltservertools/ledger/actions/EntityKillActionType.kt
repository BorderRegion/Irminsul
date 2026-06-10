package com.github.quiltservertools.ledger.actions

import com.github.quiltservertools.ledger.actionutils.Preview
import com.github.quiltservertools.ledger.utility.NbtUtils
import com.github.quiltservertools.ledger.utility.UUID
import com.github.quiltservertools.ledger.utility.getWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.network.listener.ClientPlayPacketListener
import net.minecraft.network.packet.Packet
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.EntityTrackerEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d

class EntityKillActionType : AbstractActionType() {
    override val identifier = "entity-kill"

    override fun getTranslationType() = "entity"

    override fun previewRollback(preview: Preview, player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val world = server.getWorld(world) ?: return

        val entityType = Registries.ENTITY_TYPE.getOptionalValue(objectIdentifier)
        if (entityType.isEmpty) return

        val entity = entityType.get().create(world, SpawnReason.LOAD) as LivingEntity? ?: return
        NbtUtils.readEntity(entity, NbtUtils.readCompound(extraData), world.registryManager)
        entity.health = entity.maxHealth
        entity.velocity = Vec3d.ZERO
        entity.fireTicks = 0
        val entityTrackerEntry = EntityTrackerEntry(world, entity, 1, false, NoopTrackerPacketSender)
        entityTrackerEntry.startTracking(player)
        preview.spawnedEntityTrackers.add(entityTrackerEntry)
    }

    override fun previewRestore(preview: Preview, player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val world = server.getWorld(world) ?: return

        val tag = NbtUtils.readCompound(extraData)
        val uuid = NbtUtils.getUuid(tag, UUID) ?: return
        val entity = world.getEntity(uuid)
        entity?.let {
            val entityTrackerEntry = EntityTrackerEntry(world, entity, 1, false, NoopTrackerPacketSender)
            entityTrackerEntry.stopTracking(player)
            preview.removedEntityTrackers.add(entityTrackerEntry)
        }
    }

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false

        val entityType = Registries.ENTITY_TYPE.getOptionalValue(objectIdentifier)
        if (entityType.isPresent) {
            val entity = entityType.get().create(world, SpawnReason.LOAD) ?: return false
            NbtUtils.readEntity(entity, NbtUtils.readCompound(extraData), world.registryManager)
            entity.velocity = Vec3d.ZERO
            entity.fireTicks = 0
            if (entity is LivingEntity) entity.health = entity.maxHealth

            world.spawnEntity(entity)

            return true
        }

        return false
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)

        val uuid = NbtUtils.getUuid(NbtUtils.readCompound(extraData), UUID) ?: return false
        val entity = world?.getEntity(uuid)

        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED)
            return true
        }

        return false
    }

    private object NoopTrackerPacketSender : EntityTrackerEntry.TrackerPacketSender {
        override fun sendToListeners(packet: Packet<in ClientPlayPacketListener>) = Unit
        override fun sendToSelfAndListeners(packet: Packet<in ClientPlayPacketListener>) = Unit
        override fun sendToListenersIf(
            packet: Packet<in ClientPlayPacketListener>,
            predicate: java.util.function.Predicate<ServerPlayerEntity>
        ) = Unit
    }
}
