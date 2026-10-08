package io.theprisons.mixin;

import io.theprisons.modules.qol.items.HdPackSync;
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
     * <p>Before it - and only when "Texture pack: HD V2" is chosen - comes the optional HD V2 item pack: its PNGs sit at the same paths as the classic
     * ones and replace exactly the textures it has; every other item keeps its classic texture.
     */
    @Inject(method = "createResourcePacks", at = @At("RETURN"), cancellable = true)
    private void theprisons$lookOnTop(CallbackInfoReturnable<List<ResourcePack>> cir) {
        boolean hdWanted = HdPackSync.hdV2Chosen();
        ResourcePack hd = hdWanted ? builtIn(HdPackSync.PACK_PATH, HdPackSync.PACK_ID, HdPackSync.PACK_NAME) : null;
        ResourcePack look = builtIn("resourcepacks/theprisons_look", "theprisons_look", "ThePrisons Look");
        if (hd != null || look != null) {
            cir.setReturnValue(HdPackSync.assemble(cir.getReturnValue(), hdWanted, hd, look));
        }
        HdPackSync.shared().packsComputed(hdWanted, hd != null);
    }

    private static ResourcePack builtIn(String path, String id, String name) {
        return FabricLoader.getInstance().getModContainer("theprisons")
                .flatMap(mod -> mod.findPath(path))
                .filter(Files::isDirectory)
                .<ResourcePack>map(root -> new DirectoryResourcePack(new ResourcePackInfo(id, Text.literal(name), ResourcePackSource.BUILTIN, Optional.empty()), root))
                .orElse(null);
    }
}
