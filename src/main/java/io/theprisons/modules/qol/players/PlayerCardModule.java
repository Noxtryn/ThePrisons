package io.theprisons.modules.qol.players;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.gui.hud.HudElement;
import io.theprisons.gui.kit.Ui;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Player cards: right-click another player (without sneaking - that is Sneak Trade) and their card shows on the HUD
 * for a few seconds (click them again to hide it); Shift + Tab opens the player list as a screen where a click on a
 * player shows their card. The click itself still goes to the server.
 */
public final class PlayerCardModule extends Module implements HudElement {
    /** How long a right-clicked player's card stays. */
    private static final long SHOW_MS = 12_000L;
    private static final long FADE_MS = 400L;

    private final Settings.IntSetting x;
    private final Settings.IntSetting y;
    private final Settings.DoubleSetting scale;
    private @Nullable UUID shown;
    private long shownAtMs;
    private long lastClickMs;
    private boolean shiftTabDown;

    public PlayerCardModule() {
        super("player_cards", "Player Cards", Category.QOL, "Players",
                "Right-click a player for their card (rank, gang, health, gear); Shift + Tab: the clickable player list.",
                Settings.KeybindSetting.NONE);
        x = integer("x", "X", -1, -1, 4000, 1).group("Position");
        y = integer("y", "Y", -1, -1, 4000, 1).group("Position");
        scale = decimal("scale", "Scale", 1.0D, 0.5D, 2.0D, 0.05D).group("Position");
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    /** The right-click listener and the Shift + Tab key (registered once; both check {@link #enabled()}). */
    public void register(EventBus bus) {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() && enabled() && hand == Hand.MAIN_HAND && !player.isSneaking()
                    && entity instanceof PlayerEntity other && other != player) {
                long now = Util.getMeasuringTimeMs();
                if (now - lastClickMs > 300L) { // both hands and held clicks fire repeatedly
                    lastClickMs = now;
                    toggle(other.getUuid(), now);
                }
            }
            return ActionResult.PASS;
        });
        bus.subscribe(CoreEvents.TickEnd.class, this, e -> tick(e.client()));
        PlayerStats.register();
    }

    /** Shows a player's card on the HUD (as a right-click on them does). */
    public void show(UUID uuid) {
        shown = uuid;
        shownAtMs = Util.getMeasuringTimeMs();
    }

    private void toggle(UUID uuid, long now) {
        if (uuid.equals(shown) && now - shownAtMs < SHOW_MS) {
            shown = null;
        } else {
            shown = uuid;
            shownAtMs = now;
            MinecraftClient client = MinecraftClient.getInstance();
            PlayerListEntry entry = client.getNetworkHandler() != null ? client.getNetworkHandler().getPlayerListEntry(uuid) : null;
            if (entry != null) {
                PlayerStats.get(entry.getProfile().name()); // Cosmic's /playerstats for the card
            }
        }
    }

    private void tick(MinecraftClient client) {
        if (!enabled() || client.player == null) {
            shiftTabDown = false;
            return;
        }
        boolean down = client.options.playerListKey.isPressed() && client.isShiftPressed();
        if (down && !shiftTabDown && client.currentScreen == null) {
            client.setScreen(new PlayerListScreen());
        }
        shiftTabDown = down;
    }

    private PlayerCard.@Nullable Subject subject(MinecraftClient client) {
        UUID uuid = shown;
        if (uuid == null || client.getNetworkHandler() == null) {
            return null;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(uuid);
        return PlayerCard.of(entry);
    }

    public void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || client.player == null || client.options.hudHidden || shown == null) {
            return;
        }
        long age = Util.getMeasuringTimeMs() - shownAtMs;
        if (age > SHOW_MS) {
            shown = null;
            return;
        }
        PlayerCard.Subject s = subject(client);
        if (s == null) {
            return;
        }
        float in = Ui.appear(shownAtMs, 0, 250.0F);
        float out = age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1.0F;
        draw(context, client, s, Math.min(in, out));
    }

    private void draw(DrawContext context, MinecraftClient client, PlayerCard.Subject s, float alpha) {
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int[] b = bounds(sw, sh, PlayerCard.height(s));
        float f = scale.get().floatValue();
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(b[0] + Math.round((1.0F - alpha) * 12.0F), b[1]);
        context.getMatrices().scale(f, f);
        PlayerCard.draw(context, client.textRenderer, 0, 0, s, alpha);
        context.getMatrices().popMatrix();
    }

    // ── HudElement ───────────────────────────────────────────────────────────

    private int[] bounds(int screenWidth, int screenHeight, int height) {
        double s = scale.get();
        int w = (int) Math.round(PlayerCard.WIDTH * s);
        int h = (int) Math.round(height * s);
        // default: right of the crosshair, next to the player just clicked
        int bx = x.get() < 0 ? Math.min(screenWidth / 2 + 24, Math.max(0, screenWidth - w - 4)) : Math.min(x.get(), Math.max(0, screenWidth - w));
        int by = y.get() < 0 ? Math.max(4, (screenHeight - h) / 2) : Math.min(y.get(), Math.max(0, screenHeight - h));
        return new int[]{bx, by, w, h};
    }

    @Override
    public int[] bounds(int screenWidth, int screenHeight) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerCard.Subject s = subject(client);
        if (s == null && client.player != null && client.getNetworkHandler() != null) {
            s = PlayerCard.of(client.getNetworkHandler().getPlayerListEntry(client.player.getUuid()));
        }
        return bounds(screenWidth, screenHeight, s != null ? PlayerCard.height(s) : 120);
    }

    @Override
    public void moveTo(int nx, int ny, int screenWidth, int screenHeight) {
        x.set(Math.max(0, nx));
        y.set(Math.max(0, ny));
    }

    @Override
    public double scale() {
        return scale.get();
    }

    @Override
    public void setScale(double value) {
        scale.set(Math.max(0.5D, Math.min(2.0D, value)));
    }

    @Override
    public void drawPreview(DrawContext context, int screenWidth, int screenHeight) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        PlayerCard.Subject self = PlayerCard.of(client.getNetworkHandler().getPlayerListEntry(client.player.getUuid()));
        if (self != null) {
            draw(context, client, self, 1.0F);
        }
    }

    @Override
    public void reset() {
        x.set(-1);
        y.set(-1);
        scale.set(1.0D);
    }
}
