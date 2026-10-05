package io.theprisons.mixin;

import io.theprisons.core.ThePrisonsCore;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ThePrisonsClientWorldMixin {
    /**
     * Server block updates (single and chunk-delta) invalidate the world cache section they touch, so the cache is
     * refreshed on change instead of by blind periodic rescans.
     */
    @Unique
    private BlockState theprisons$previous;

    /** The block before the update (an ore turning into stone is a mined ore). */
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void theprisons$beforeBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        theprisons$previous = ((ClientWorld) (Object) this).getBlockState(pos);
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void theprisons$onBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null) {
            BlockState previous = theprisons$previous;
            core.onBlockUpdate((ClientWorld) (Object) this, pos, state, previous == null ? state : previous);
        }
        theprisons$previous = null;
    }
}
