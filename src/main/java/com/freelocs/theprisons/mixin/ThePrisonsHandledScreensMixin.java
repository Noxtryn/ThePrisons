package com.freelocs.theprisons.mixin;

import com.freelocs.theprisons.modules.qol.storage.StorageOverlayModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreens.class)
public abstract class ThePrisonsHandledScreensMixin {
    /** A private vault the server opens is shown inside the storage overlay instead of the vanilla chest screen. */
    @Inject(method = "open", at = @At("HEAD"), cancellable = true)
    private static void theprisons$storageOverlay(ScreenHandlerType<?> type, MinecraftClient client, int id, Text title,
                                                  CallbackInfo ci) {
        StorageOverlayModule storage = StorageOverlayModule.get();
        if (storage != null && storage.interceptOpen(type, client, id, title)) {
            ci.cancel();
        }
    }
}
