package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.market.MarketSearch;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles the keyboard shortcut/search input that is actually declared by Screen.
 *
 * Minecraft 1.21.11 moved the default charTyped/mouseClicked implementations to
 * ParentElement, so those injections intentionally live in
 * ThePrisonsMarketParentElementMixin instead of targeting Screen.
 */
@Mixin(Screen.class)
public abstract class ThePrisonsMarketScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void theprisons$marketKey(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen && MarketSearch.keyPressed(input.key())) {
            cir.setReturnValue(true);
        }
    }
}
