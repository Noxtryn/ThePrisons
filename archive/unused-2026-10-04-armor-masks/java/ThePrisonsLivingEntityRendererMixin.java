package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.items.ItemLookModule;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class ThePrisonsLivingEntityRendererMixin {
    /** Worn Cosmic masks are drawn as their 3D model on the head (see {@link ItemLookModule#wornHead}). */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void theprisons$wornMask(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
        ItemLookModule look = ItemLookModule.get();
        if (look != null) {
            look.wornHead(entity, state);
        }
    }
}
