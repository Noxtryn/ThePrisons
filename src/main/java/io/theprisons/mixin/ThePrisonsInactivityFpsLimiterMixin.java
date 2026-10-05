package io.theprisons.mixin;

import io.theprisons.core.ThePrisonsCore;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InactivityFpsLimiter.class)
public abstract class ThePrisonsInactivityFpsLimiterMixin {
    /**
     * Vanilla drops to 30 FPS after a minute without keyboard/mouse input (10 FPS after ten minutes or when minimised).
     * A running macro is not idle: the view is turned per frame, so the normal FPS limit stays while it drives.
     */
    @Inject(method = "getLimitReason", at = @At("HEAD"), cancellable = true)
    private void theprisons$noLimitWhileAutomating(CallbackInfoReturnable<InactivityFpsLimiter.LimitReason> cir) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null && core.control().automating()) {
            cir.setReturnValue(InactivityFpsLimiter.LimitReason.NONE);
        }
    }
}
