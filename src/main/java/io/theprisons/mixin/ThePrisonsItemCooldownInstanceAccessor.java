package io.theprisons.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.entity.player.ItemCooldownManager$Entry")
public interface ThePrisonsItemCooldownInstanceAccessor {
    @Accessor("startTick")
    int theprisons$getStartTime();

    @Accessor("endTick")
    int theprisons$getEndTime();
}
