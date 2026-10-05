package io.theprisons.mixin;

import io.theprisons.modules.general.tunnel.TunnelVisionModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class ThePrisonsGameRendererMixin {
    /** Tunnel Vision: the game view is not drawn at all (the tunnel scene replaces it in the HUD pass). */
    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
    private void theprisons$tunnelVision(RenderTickCounter tickCounter, CallbackInfo ci) {
        if (TunnelVisionModule.hidesWorld()) {
            ci.cancel();
        }
    }
}
