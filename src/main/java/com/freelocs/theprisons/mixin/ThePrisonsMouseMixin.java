package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.core.ThePrisonsCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class ThePrisonsMouseMixin {
    /**
     * Runs once per rendered frame, right where vanilla turns the view by mouse input and before the camera is set
     * up, so a macro's view motion is applied at frame rate instead of in 20 Hz tick steps.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void theprisons$frameRotation(CallbackInfo ci) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null) {
            core.control().frame(MinecraftClient.getInstance());
        }
    }
}
