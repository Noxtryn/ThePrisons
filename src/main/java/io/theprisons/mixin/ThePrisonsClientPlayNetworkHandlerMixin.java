package io.theprisons.mixin;

import io.theprisons.modules.qol.storage.StorageOverlayModule;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ThePrisonsClientPlayNetworkHandlerMixin {
    /**
     * The server closes the vault when another page is opened (or on its own); the storage overlay then stays open
     * with the vault cards instead of dropping back into the game.
     */
    @Inject(method = "onCloseScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;closeScreen()V"), cancellable = true)
    private void theprisons$keepStorageOverlay(CloseScreenS2CPacket packet, CallbackInfo ci) {
        StorageOverlayModule storage = StorageOverlayModule.get();
        if (storage != null && storage.onServerClose()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendChatCommand", at = @At("HEAD"))
    private void theprisons$playerCommand(String command, CallbackInfo ci) {
        io.theprisons.modules.qol.market.MarketModule.onPlayerCommand();
    }

    /** The market scan runs in the background: the sounds the server plays for its menu clicks are not played. */
    @Inject(method = "onPlaySound", at = @At("HEAD"), cancellable = true)
    private void theprisons$quietScan(PlaySoundS2CPacket packet, CallbackInfo ci) {
        if (io.theprisons.modules.qol.market.MarketModule.silent()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlaySoundFromEntity", at = @At("HEAD"), cancellable = true)
    private void theprisons$quietScanEntity(PlaySoundFromEntityS2CPacket packet, CallbackInfo ci) {
        if (io.theprisons.modules.qol.market.MarketModule.silent()) {
            ci.cancel();
        }
    }
}
