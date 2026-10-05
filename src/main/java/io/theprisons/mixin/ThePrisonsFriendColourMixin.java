package io.theprisons.mixin;

import io.theprisons.modules.qol.players.FriendsModule;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The glow of friends is blue, of gang mates pink (instead of the team colour) - see {@link FriendsModule}. */
@Mixin(Entity.class)
public abstract class ThePrisonsFriendColourMixin {
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void theprisons$friendColour(CallbackInfoReturnable<Integer> cir) {
        FriendsModule friends = FriendsModule.get();
        if (friends != null) {
            int colour = friends.glowColour((Entity) (Object) this);
            if (colour >= 0) {
                cir.setReturnValue(colour);
            }
        }
    }
}
