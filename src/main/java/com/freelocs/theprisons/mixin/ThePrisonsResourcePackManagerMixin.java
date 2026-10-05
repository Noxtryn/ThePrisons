package com.freelocs.theprisons.mixin;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(ResourcePackManager.class)
public abstract class ThePrisonsResourcePackManagerMixin {
    /**
     * The mod's look (blocks, worn armour) is a built-in pack that always comes last = on top: it wins over every
     * texture pack the player has loaded. The comic filter still styles the textures of all other packs.
     */
    @Inject(method = "createResourcePacks", at = @At("RETURN"), cancellable = true)
    private void theprisons$lookOnTop(CallbackInfoReturnable<List<ResourcePack>> cir) {
        FabricLoader.getInstance().getModContainer("theprisons")
                .flatMap(mod -> mod.findPath("resourcepacks/theprisons_look"))
                .filter(Files::isDirectory)
                .ifPresent(root -> {
                    List<ResourcePack> packs = new ArrayList<>(cir.getReturnValue());
                    packs.add(new DirectoryResourcePack(new ResourcePackInfo("theprisons_look",
                            Text.literal("ThePrisons Look"), ResourcePackSource.BUILTIN, Optional.empty()), root));
                    cir.setReturnValue(packs);
                });
    }
}
