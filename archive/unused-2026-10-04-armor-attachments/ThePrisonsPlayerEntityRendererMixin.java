package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.items.ArmorAttachmentFeature;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class ThePrisonsPlayerEntityRendererMixin {
    /** Adds the 3D armour attachments to every player renderer. */
    @Inject(method = "<init>", at = @At("TAIL"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void theprisons$armorAttachments(EntityRendererFactory.Context context, boolean slim, CallbackInfo ci) {
        ((ThePrisonsLivingEntityRendererInvoker) this).theprisons$addFeature(
                new ArmorAttachmentFeature((FeatureRendererContext) (Object) this));
    }
}
