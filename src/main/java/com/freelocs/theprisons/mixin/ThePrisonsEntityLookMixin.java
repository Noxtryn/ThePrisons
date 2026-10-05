package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.bandit.SpearHelperModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class ThePrisonsEntityLookMixin {
    /** While the spear aim assist holds the view, the mouse does not turn the player at all. */
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void theprisons$lockLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (SpearHelperModule.aimLocked() && (Object) this == MinecraftClient.getInstance().player) {
            ci.cancel();
        }
    }
}
