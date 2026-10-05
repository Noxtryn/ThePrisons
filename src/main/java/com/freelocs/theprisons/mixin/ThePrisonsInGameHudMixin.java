package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.modules.qol.storage.StorageOverlayScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class ThePrisonsInGameHudMixin {
    /** Every action bar text, however the server sends it (game message or the overlay packet). */
    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void theprisons$onOverlay(Text message, boolean tinted, CallbackInfo ci) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core != null && message != null) {
            core.onActionBar(message);
        }
    }

    /**
     * Nothing but the game behind the storage overlay: the whole HUD (hotbar, hearts, chat, scoreboard and every HUD
     * widget, ours included) stays hidden while it is open; the overlay draws its own hotbar.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void theprisons$hideUnderStorageOverlay(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen instanceof com.freelocs.theprisons.gui.kit.HidesHud) {
            ci.cancel();
        }
    }

    /** The custom scoreboard replaces the server's sidebar. */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true)
    private void theprisons$customScoreboard(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (com.freelocs.theprisons.modules.hud.scoreboard.ScoreboardModule.replacesSidebar()) {
            ci.cancel();
        }
    }
}
