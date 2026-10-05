package com.freelocs.theprisons.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slot under the mouse (the market search lets digits through to the hotbar swap while one is hovered). */
@Mixin(HandledScreen.class)
public interface ThePrisonsHandledAccessor {
    @Accessor("focusedSlot")
    @Nullable Slot theprisons$focusedSlot();
}
