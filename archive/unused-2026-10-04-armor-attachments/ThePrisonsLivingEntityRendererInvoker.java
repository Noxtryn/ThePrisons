package com.freelocs.theprisons.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface ThePrisonsLivingEntityRendererInvoker {
    @Invoker("addFeature")
    @SuppressWarnings("rawtypes")
    boolean theprisons$addFeature(FeatureRenderer feature);
}
