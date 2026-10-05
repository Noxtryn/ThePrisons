package io.theprisons.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Slot positions are final in vanilla; the storage overlay moves the slots of the open vault into its card and the
 * hotbar to the bottom, so the vanilla click / drag / shift logic of {@code HandledScreen} keeps working unchanged.
 */
@Mixin(Slot.class)
public interface ThePrisonsSlotAccessor {
    @Mutable
    @Accessor("x")
    void theprisons$setX(int x);

    @Mutable
    @Accessor("y")
    void theprisons$setY(int y);
}
