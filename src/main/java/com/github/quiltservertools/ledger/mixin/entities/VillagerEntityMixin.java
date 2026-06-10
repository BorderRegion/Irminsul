package com.github.quiltservertools.ledger.mixin.entities;

import com.github.quiltservertools.ledger.callbacks.EntityKillCallback;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.conversion.EntityConversionContext;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "onStruckByLightning", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/passive/VillagerEntity;convertTo(Lnet/minecraft/entity/EntityType;Lnet/minecraft/entity/conversion/EntityConversionContext;Lnet/minecraft/entity/conversion/EntityConversionContext$Finalizer;)Lnet/minecraft/entity/mob/MobEntity;"))
    private MobEntity ledgerVillagerToWitch(VillagerEntity instance, EntityType entityType, EntityConversionContext context, EntityConversionContext.Finalizer finalizer, Operation<MobEntity> original, ServerWorld world, LightningEntity lightning) {
        MobEntity witch = original.call(instance, entityType, context, finalizer);
        if (witch == null) {
            return null;
        }

        LivingEntity entity = (LivingEntity) (Object) this;
        EntityKillCallback.EVENT.invoker().kill(entity.getEntityWorld(), entity.getBlockPos(), entity, world.getDamageSources().lightningBolt());
        return witch;
    }
}
