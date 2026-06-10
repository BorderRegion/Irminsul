package com.github.quiltservertools.ledger.mixin;

import com.github.quiltservertools.ledger.callbacks.BlockPlaceCallback;
import com.github.quiltservertools.ledger.utility.Sources;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.dimension.NetherPortal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortal.class)
public abstract class NetherPortalMixin {
    @Shadow
    @Final
    private Direction.Axis axis;

    @Shadow
    @Final
    private Direction negativeDir;

    @Shadow
    @Final
    private BlockPos lowerCorner;

    @Shadow
    @Final
    private int height;

    @Shadow
    @Final
    private int width;

    @Inject(method = "createPortal", at = @At("RETURN"))
    public void logPortalPlacement(WorldAccess worldAccess, CallbackInfo ci) {
        if (worldAccess instanceof ServerWorld world) {
            BlockState state = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, this.axis);
            BlockPos upperCorner = this.lowerCorner
                    .offset(Direction.UP, this.height - 1)
                    .offset(this.negativeDir, this.width - 1);

            for (BlockPos pos : BlockPos.iterate(this.lowerCorner, upperCorner)) {
                BlockPlaceCallback.EVENT.invoker().place(world, pos.toImmutable(), state, null, Sources.PORTAL);
            }
        }
    }
}
