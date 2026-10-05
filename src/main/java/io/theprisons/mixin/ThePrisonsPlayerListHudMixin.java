package io.theprisons.mixin;

import io.theprisons.modules.hud.tab.BetterTabModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerListHud.class)
public abstract class ThePrisonsPlayerListHudMixin {
    /** The Better Tab replaces the vanilla tab list while it is on. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void theprisons$betterTab(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard,
                                     ScoreboardObjective objective, CallbackInfo ci) {
        BetterTabModule tab = BetterTabModule.get();
        if (tab != null && tab.enabled()) {
            tab.render(context, scaledWindowWidth, (PlayerListHud) (Object) this);
            ci.cancel();
        }
    }
}
