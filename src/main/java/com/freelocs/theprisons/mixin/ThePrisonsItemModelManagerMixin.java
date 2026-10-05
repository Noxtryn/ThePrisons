package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.items.ItemLookModule;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemModelManager.class)
public abstract class ThePrisonsItemModelManagerMixin {
    /**
     * Swaps the item model of recognised Cosmic Prisons items. Modifies the stored model id instead of redirecting
     * {@code stack.get(ITEM_MODEL)}: Cosmic Textures redirects that call, and two redirects of one call clash. This
     * way both mods apply and {@link ItemLookModule#model} decides which texture wins.
     */
    @ModifyVariable(method = "update", at = @At("STORE"), ordinal = 0)
    private Identifier theprisons$prisonsModel(Identifier model, ItemRenderState renderState, ItemStack stack,
                                               ItemDisplayContext displayContext, World world,
                                               HeldItemContext heldItemContext, int seed) {
        ItemLookModule look = ItemLookModule.get();
        return look != null && model != null ? look.model(stack, model) : model;
    }
}
