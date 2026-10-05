package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.players.FriendsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Friends and gang mates glow (the vanilla outline) - see {@link FriendsModule}. */
@Mixin(MinecraftClient.class)
public abstract class ThePrisonsFriendGlowMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void theprisons$friendGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        FriendsModule friends = FriendsModule.get();
        if (friends != null && friends.glowColour(entity) >= 0) {
            cir.setReturnValue(true);
        }
    }
}
