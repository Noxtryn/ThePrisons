package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.market.MarketSearch;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.Click;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.21.11 provides the default charTyped/mouseClicked handlers through
 * ParentElement rather than Screen. Inject here so InventoryScreen receives the
 * Market search input without targeting a method that Screen does not declare.
 */
@Mixin(ParentElement.class)
public interface ThePrisonsMarketParentElementMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void theprisons$marketChar(CharInput input, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen
                && input.isValidChar()
                && MarketSearch.charTyped(input.asString().charAt(0))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void theprisons$marketClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>
                && !((Object) this instanceof InventoryScreen)
                && com.freelocs.theprisons.modules.qol.market.MarketOverlay.click(click.x(), click.y())) {
            cir.setReturnValue(true);
            return;
        }
        if ((Object) this instanceof InventoryScreen) {
            InventoryScreen screen = (InventoryScreen) (Object) this;
            if (MarketSearch.click(click.x(), click.y(), screen.width, screen.height)) {
                cir.setReturnValue(true);
            }
        }
    }
}
