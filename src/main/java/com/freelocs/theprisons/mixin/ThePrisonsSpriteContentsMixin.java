package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.general.look.ComicTextures;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.resource.metadata.TextureResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.resource.metadata.ResourceMetadataSerializer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import java.util.Optional;

/** Comic style for every atlas sprite: the image is styled / upscaled, the frame size scaled to match. */
@Mixin(SpriteContents.class)
public abstract class ThePrisonsSpriteContentsMixin {
    private static final String CTOR = "<init>(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/SpriteDimensions;"
            + "Lnet/minecraft/client/texture/NativeImage;Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)V";

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true)
    private static NativeImage theprisons$comicImage(NativeImage image, Identifier id, SpriteDimensions dims, NativeImage original,
                                                    Optional<AnimationResourceMetadata> animation,
                                                    List<ResourceMetadataSerializer.Value<?>> extra,
                                                    Optional<TextureResourceMetadata> texture) {
        return ComicTextures.sprite(id, image, animation);
    }

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true)
    private static SpriteDimensions theprisons$comicDimensions(SpriteDimensions dims, Identifier id, SpriteDimensions original,
                                                              NativeImage image, Optional<AnimationResourceMetadata> animation,
                                                              List<ResourceMetadataSerializer.Value<?>> extra,
                                                              Optional<TextureResourceMetadata> texture) {
        return ComicTextures.dimensions(id, dims, animation);
    }
}
