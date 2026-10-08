package io.theprisons.mixin;

import io.theprisons.modules.qol.items.ItemTexturePacks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

@Mixin(ResourcePackManager.class)
public abstract class ThePrisonsResourcePackManagerMixin {
    /**
     * The mod's look (blocks, worn armour) is a built-in pack that always comes last = on top: it wins over every
     * texture pack the player has loaded. The comic filter still styles the textures of all other packs.
     *
     * <p>Before it comes the one official item-design overlay. It replaces only its reviewed PNG paths; every
     * other item keeps the Classic/Cosmic texture that would otherwise have won.
     */
    @Inject(method = "createResourcePacks", at = @At("RETURN"), cancellable = true)
    private void theprisons$lookOnTop(CallbackInfoReturnable<List<ResourcePack>> cir) {
        ResourcePack standard = builtIn(ItemTexturePacks.STANDARD_PATH, ItemTexturePacks.STANDARD_ID, ItemTexturePacks.STANDARD_NAME);
        ResourcePack look = builtIn("resourcepacks/theprisons_look", "theprisons_look", "ThePrisons Look");
        if (standard != null || look != null) {
            cir.setReturnValue(ItemTexturePacks.assemble(cir.getReturnValue(), standard, look));
        }
    }

    private static ResourcePack builtIn(String path, String id, String name) {
        return FabricLoader.getInstance().getModContainer("theprisons")
                .flatMap(mod -> mod.findPath(path))
                .filter(Files::isDirectory)
                .<ResourcePack>map(root -> new DirectoryResourcePack(new ResourcePackInfo(id, Text.literal(name), ResourcePackSource.BUILTIN, Optional.empty()), root))
                .orElse(null);
    }
}
