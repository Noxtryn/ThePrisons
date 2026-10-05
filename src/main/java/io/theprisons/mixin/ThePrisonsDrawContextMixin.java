package io.theprisons.mixin;

import io.theprisons.modules.qol.items.ItemLookModule;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DrawContext.class)
public abstract class ThePrisonsDrawContextMixin {
    /** Tier frame behind Cosmic Prisons items (every GUI item goes through this method). */
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V",
            at = @At("HEAD"))
    private void theprisons$tierFrame(LivingEntity entity, World world, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        ItemLookModule look = ItemLookModule.get();
        if (look != null && !stack.isEmpty()) {
            look.beforeItem((DrawContext) (Object) this, stack, x, y);
        }
    }

    /** Badge (orb %, prestige / enchant level) after count and durability bar. */
    @Inject(method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("TAIL"))
    private void theprisons$badge(TextRenderer textRenderer, ItemStack stack, int x, int y, String countOverride, CallbackInfo ci) {
        ItemLookModule look = ItemLookModule.get();
        if (look != null && !stack.isEmpty()) {
            look.afterOverlay((DrawContext) (Object) this, textRenderer, stack, x, y);
        }
    }
}
