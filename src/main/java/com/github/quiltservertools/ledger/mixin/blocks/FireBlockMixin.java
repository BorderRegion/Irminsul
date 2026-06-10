package com.github.quiltservertools.ledger.mixin.blocks;

import com.github.quiltservertools.ledger.callbacks.BlockBreakCallback;
import com.github.quiltservertools.ledger.callbacks.BlockPlaceCallback;
import com.github.quiltservertools.ledger.utility.Sources;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(
            method = "trySpreadingFire",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"
            )
    )
    private void ledgerBlockBurnBreakInvoker(
            World world,
            BlockPos pos,
            int spreadFactor,
            Random random,
            int currentAge,
            CallbackInfo ci,
            @Local BlockState blockState
    ) {
        if (!blockState.isAir() && !blockState.isOf(Blocks.FIRE)) {
            BlockBreakCallback.EVENT.invoker().breakBlock(world, pos, blockState, world.getBlockEntity(pos), Sources.FIRE);
        }
    }

    @Inject(
            method = "trySpreadingFire",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"
            )
    )
    private void ledgerBlockBurnReplaceBreakInvoker(
            World world,
            BlockPos pos,
            int spreadFactor,
            Random random,
            int currentAge,
            CallbackInfo ci,
            @Local BlockState blockState
    ) {
        if (!blockState.isAir() && !blockState.isOf(Blocks.FIRE)) {
            BlockBreakCallback.EVENT.invoker().breakBlock(world, pos, blockState, world.getBlockEntity(pos), Sources.FIRE);
        }
    }

    @Inject(
            method = "trySpreadingFire",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z",
                    shift = At.Shift.AFTER
            )
    )
    private void ledgerBlockBurnReplacePlaceInvoker(
            World world,
            BlockPos pos,
            int spreadFactor,
            Random random,
            int currentAge,
            CallbackInfo ci,
            @Local BlockState blockState
    ) {
        if (blockState.isAir()) {
            BlockState newState = world.getBlockState(pos);
            if (newState.isOf(Blocks.FIRE)) {
                BlockPlaceCallback.EVENT.invoker().place(world, pos.toImmutable(), newState, null, Sources.FIRE);
            }
        }
    }

    @Inject(
            method = "scheduledTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void ledgerBlockFireSpreadPlaceInvoker(
            BlockState state,
            ServerWorld world,
            BlockPos pos,
            Random random,
            CallbackInfo ci,
            @Local BlockPos.Mutable mutable
    ) {
        BlockPos spreadPos = mutable.toImmutable();
        BlockState newState = world.getBlockState(spreadPos);
        if (newState.isOf(Blocks.FIRE)) {
            BlockPlaceCallback.EVENT.invoker().place(world, spreadPos, newState, null, Sources.FIRE);
        }
    }
}
