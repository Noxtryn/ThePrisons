package io.theprisons.mixin;

import io.theprisons.modules.qol.items.ItemLookModule;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(HandledScreen.class)
public abstract class ThePrisonsHandledScreenTooltipMixin {
    @Shadow
    protected @Nullable Slot focusedSlot;

    /** Item tooltips get the MMORPG frame in the item's rarity colour. */
    @ModifyArg(method = "drawMouseoverTooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/util/Identifier;)V"),
            index = 5)
    private @Nullable Identifier theprisons$tierTooltip(@Nullable Identifier style) {
        Slot slot = focusedSlot;
        return slot == null ? style : ItemLookModule.tooltipStyle(slot.getStack(), style);
    }
}
