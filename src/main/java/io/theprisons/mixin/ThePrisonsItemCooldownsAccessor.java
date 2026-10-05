package io.theprisons.mixin;

import net.minecraft.entity.player.ItemCooldownManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ItemCooldownManager.class)
public interface ThePrisonsItemCooldownsAccessor {
    @Accessor("entries")
    Map<?, ?> theprisons$getCooldowns();

    @Accessor("tick")
    int theprisons$getTickCount();
}
