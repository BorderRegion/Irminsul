package com.github.quiltservertools.ledger.utility

import com.mojang.serialization.Dynamic
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.datafixer.Schemas
import net.minecraft.datafixer.TypeReferences
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringNbtReader
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryOps
import net.minecraft.registry.RegistryWrapper
import net.minecraft.storage.NbtReadView
import net.minecraft.storage.NbtWriteView
import net.minecraft.storage.ReadView
import net.minecraft.util.ErrorReporter
import net.minecraft.util.Identifier
import net.minecraft.util.Uuids
import net.minecraft.world.World
import java.util.UUID as JavaUuid

const val ITEM_NBT_DATA_VERSION = 3817
const val ITEM_COMPONENTS_DATA_VERSION = 3825

const val PROPERTIES = "Properties" // BlockState
const val COUNT_PRE_1_20_5 = "Count" // ItemStack
const val COUNT = "count" // ItemStack
const val UUID = "UUID" // Entity

object NbtUtils {
    fun readCompound(tag: String?): NbtCompound = StringNbtReader.readCompound(tag ?: "{}")

    fun toString(tag: NbtCompound?): String? = tag?.toString()

    fun blockStateToProperties(state: BlockState): NbtCompound? {
        val stateTag = NbtHelper.fromBlockState(state)
        if (state.block.defaultState == state) return null // Don't store default block state
        return stateTag.getCompound(PROPERTIES).orElse(null)
    }

    fun blockStateFromProperties(tag: NbtCompound, name: Identifier): BlockState {
        val stateTag = NbtCompound()
        stateTag.putString("Name", name.toString())
        stateTag.put(PROPERTIES, tag)
        return NbtHelper.toBlockState(Registries.createEntryLookup(Registries.BLOCK), stateTag)
    }

    fun itemFromProperties(tag: String?, name: Identifier, registries: RegistryWrapper.WrapperLookup): ItemStack {
        val extraDataTag = readCompound(tag)
        var itemTag = extraDataTag
        if (!extraDataTag.contains(COUNT)) {
            // 1.20.4 and lower (need data fixing)
            itemTag.putString("id", name.toString())
            if (!itemTag.contains(COUNT_PRE_1_20_5)) {
                // Ledger ItemStack in 1.20.4 and earlier had "Count" omitted if it was 1
                itemTag.putByte(COUNT_PRE_1_20_5, 1)
            }
            itemTag = Schemas.getFixer().update(
                TypeReferences.ITEM_STACK,
                Dynamic(NbtOps.INSTANCE, itemTag), ITEM_NBT_DATA_VERSION, ITEM_COMPONENTS_DATA_VERSION
            ).cast(NbtOps.INSTANCE) as NbtCompound
        }

        return itemFromNbt(itemTag, registries)
    }

    fun itemFromNbt(tag: NbtCompound?, registries: RegistryWrapper.WrapperLookup): ItemStack {
        if (tag == null) return ItemStack.EMPTY
        val ops = RegistryOps.of(NbtOps.INSTANCE, registries)
        return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY)
    }

    fun itemToNbt(stack: ItemStack, registries: RegistryWrapper.WrapperLookup): NbtCompound? {
        if (stack.isEmpty) return null
        val ops = RegistryOps.of(NbtOps.INSTANCE, registries)
        val encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null) ?: return null
        return encoded.asCompound().orElse(null)
    }

    fun readView(tag: NbtCompound, registries: RegistryWrapper.WrapperLookup): ReadView =
        NbtReadView.create(ErrorReporter.EMPTY, registries, tag)

    fun readBlockEntity(blockEntity: BlockEntity, tag: NbtCompound, registries: RegistryWrapper.WrapperLookup) {
        blockEntity.read(readView(tag, registries))
    }

    fun entityToNbt(entity: Entity, registries: RegistryWrapper.WrapperLookup): NbtCompound? {
        val view = NbtWriteView.create(ErrorReporter.EMPTY, registries)
        return if (entity.saveSelfData(view)) view.nbt else null
    }

    @JvmStatic
    fun entityToNbt(entity: Entity): NbtCompound =
        entityToNbt(entity, entity.entityWorld.registryManager) ?: NbtCompound()

    @JvmStatic
    fun copyItemEntity(entity: ItemEntity): ItemEntity {
        val world = entity.entityWorld
        val copy = entityToNbt(entity, world.registryManager)?.let { entityFromNbt(it, world) } as? ItemEntity
        return copy ?: ItemEntity(world, entity.x, entity.y, entity.z, entity.stack.copy())
    }

    fun readEntity(entity: Entity, tag: NbtCompound, registries: RegistryWrapper.WrapperLookup) {
        entity.readData(readView(tag, registries))
    }

    fun entityFromNbt(tag: NbtCompound, world: World): Entity? =
        EntityType.loadEntityWithPassengers(tag, world, SpawnReason.LOAD) { it }

    fun getUuid(tag: NbtCompound, key: String = UUID): JavaUuid? =
        tag.get(key, Uuids.INT_STREAM_CODEC).orElse(null)
}
