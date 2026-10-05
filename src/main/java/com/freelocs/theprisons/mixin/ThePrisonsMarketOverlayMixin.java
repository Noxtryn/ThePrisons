package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.market.MarketOverlay;
import com.freelocs.theprisons.modules.qol.market.MarketSearch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class ThePrisonsMarketOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void theprisons$marketRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof InventoryScreen inventory) {
            MarketSearch.render(context, MinecraftClient.getInstance().textRenderer, inventory.width, inventory.height);
        }
        MarketOverlay.render(context, (HandledScreen<?>) (Object) this);
    }
}
