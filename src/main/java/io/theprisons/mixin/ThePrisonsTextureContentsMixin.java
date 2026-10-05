package io.theprisons.mixin;

import io.theprisons.modules.general.look.ComicTextures;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.TextureContents;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Comic style for single textures (mobs, GUI backgrounds ...). */
@Mixin(TextureContents.class)
public abstract class ThePrisonsTextureContentsMixin {
    @ModifyVariable(method = "load", at = @At("STORE"), ordinal = 0)
    private static NativeImage theprisons$comicTexture(NativeImage image, ResourceManager manager, Identifier id) {
        return ComicTextures.texture(id, image);
    }
}
