package io.theprisons.mixin;

import io.theprisons.modules.qol.market.MarketOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The info card on the vanilla auction house menus (the ones the mod's own screen does not replace). */
@Mixin(HandledScreen.class)
public abstract class ThePrisonsMarketOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void theprisons$marketRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MarketOverlay.render(context, (HandledScreen<?>) (Object) this);
    }

    /** The mouse wheel turns the pages of the item list beside the inventory. */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void theprisons$listScroll(double mouseX, double mouseY, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen screen
                && io.theprisons.modules.qol.market.MarketSearch.scrolled(mouseX, mouseY, vertical, screen.width)) {
            cir.setReturnValue(true);
        }
    }
}
