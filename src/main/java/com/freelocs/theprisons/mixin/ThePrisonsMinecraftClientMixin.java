package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.core.ThePrisonsCore;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ThePrisonsMinecraftClientMixin {
    /**
     * Vanilla cancels block breaking every tick while the attack key is up, which would reset the progress of a
     * running macro. While a module holds the control lease it drives the interaction manager itself.
     */
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void theprisons$skipWhileAutomating(boolean breaking, CallbackInfo ci) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null && core.control().automating()) {
            ci.cancel();
        }
    }

    /**
     * A running macro keeps going when the player tabs out: vanilla opens the pause menu as soon as the window loses
     * focus, which would pause the macro like pressing Escape. Only that automatic menu is skipped - Escape pressed in
     * the focused window still opens it.
     */
    @Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
    private void theprisons$keepRunningWhenTabbedOut(boolean pauseOnly, CallbackInfo ci) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (core != null && core.control().automating() && !client.isWindowFocused()) {
            ci.cancel();
        }
    }
}
