package io.theprisons.mixin;

import io.theprisons.modules.qol.market.MarketSearch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.RecipeBookScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Cosmic item list of the player inventory. In 1.21.11 the inventory is a {@link RecipeBookScreen}: it draws and
 * handles input itself and never reaches {@code HandledScreen.render} / {@code Screen.keyPressed}, so the hooks sit here.
 */
@Mixin(RecipeBookScreen.class)
public abstract class ThePrisonsMarketInventoryMixin {
    private boolean theprisons$overSlot() {
        return ((ThePrisonsHandledAccessor) this).theprisons$focusedSlot() != null;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void theprisons$searchRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof InventoryScreen screen) {
            MarketSearch.render(context, MinecraftClient.getInstance().textRenderer, screen.width, screen.height, mouseX, mouseY);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void theprisons$searchKey(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen && MarketSearch.keyPressed(input.key(), input.modifiers(), theprisons$overSlot())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void theprisons$searchChar(CharInput input, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen && input.isValidChar()) {
            char ch = input.asString().charAt(0);
            if (Character.isDigit(ch) && theprisons$overSlot()) {
                return;
            }
            if (MarketSearch.charTyped(ch)) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void theprisons$searchClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen screen
                && MarketSearch.click(click.x(), click.y(), screen.width, screen.height)) {
            cir.setReturnValue(true);
        }
    }
}
