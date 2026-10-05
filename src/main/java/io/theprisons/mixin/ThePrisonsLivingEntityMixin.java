package io.theprisons.mixin;

import io.theprisons.core.ThePrisonsCore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class ThePrisonsLivingEntityMixin {
    /** The client calls this for the server's damage packet: tells the core who hit the local player. */
    @Inject(method = "onDamaged", at = @At("HEAD"))
    private void theprisons$onDamaged(DamageSource source, CallbackInfo ci) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return;
        }
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null) {
            core.onPlayerHurt(source);
        }
    }
}
