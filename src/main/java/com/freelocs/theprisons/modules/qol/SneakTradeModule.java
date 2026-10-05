package com.freelocs.theprisons.modules.qol;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import java.util.regex.Pattern;

/**
 * Sneak + right-click on another player sends {@code /trade <their name>}. The name comes from the player clicked;
 * NPCs (names with colours, spaces, ...) are no real player names and are left alone. The click itself is not sent to
 * the server then, only the command.
 */
public final class SneakTradeModule extends Module {
    /** A Minecraft account name: what /trade accepts. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Both hands fire, and holding the key repeats the click: one command per this many ms. */
    private static final long REPEAT_MS = 1_000L;

    private final Settings.TextSetting command;
    private long lastSentMs;

    public SneakTradeModule() {
        super("sneak_trade", "Sneak Trade", Category.QOL, "Players",
                "Sneak and right-click another player: sends /trade <name> for that player.",
                Settings.KeybindSetting.NONE);
        command = text("command", "Command", "trade", 32)
                .description("Sent with the clicked player's name, without the slash.").group("General");
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    /** Registers the click listener once (it checks {@link #enabled()} itself). */
    public void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient() || !enabled() || hand != Hand.MAIN_HAND || !player.isSneaking()
                    || !(entity instanceof PlayerEntity other) || other == player) {
                return ActionResult.PASS;
            }
            String name = other.getGameProfile().name();
            if (name == null || !NAME.matcher(name).matches()) {
                return ActionResult.PASS;
            }
            long now = System.currentTimeMillis();
            if (now - lastSentMs < REPEAT_MS) {
                return ActionResult.SUCCESS;
            }
            ClientPlayerEntity self = MinecraftClient.getInstance().player;
            if (self == null) {
                return ActionResult.PASS;
            }
            lastSentMs = now;
            String cmd = command.get().trim().replaceFirst("^/", "");
            self.networkHandler.sendChatCommand(cmd + " " + name);
            ThePrisonsClient.LOGGER.info("[sneak_trade] /{} {}", cmd, name);
            return ActionResult.SUCCESS;
        });
    }
}
