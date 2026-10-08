package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.items.ArmorAttachments;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Room for the armour attachments on every living entity's render state. */
@Mixin(LivingEntityRenderState.class)
public abstract class ThePrisonsLivingEntityRenderStateMixin implements ArmorAttachments.Duck {
    @Unique
    private final ArmorAttachments.Holder theprisons$holder = new ArmorAttachments.Holder();

    @Override
    public ArmorAttachments.Holder theprisons$attachments() {
        return theprisons$holder;
    }
}
