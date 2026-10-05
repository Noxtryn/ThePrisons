package com.freelocs.theprisons.modules.general.tunnel;

import com.freelocs.theprisons.core.event.CoreEvents;
import com.freelocs.theprisons.core.event.EventBus;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.modules.hud.SessionHudModule;
import com.freelocs.theprisons.modules.mining.ore.OreMacroModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunnel Vision (F5 + V): the game view is replaced - the world is not drawn at all - by a backdrop of your choice with
 * only the player cut out of the game as a 3D model, standing on a rainbow road that follows what the macro does.
 * HUDs and overlays stay. An iris closes over the game and opens on the tunnel (and back).
 */
public final class TunnelVisionModule extends Module {
    private enum Phase { OFF, ENTERING, ON, EXITING }

    private static final long ENTER_MS = 1700L;
    private static final long EXIT_MS = 1400L;
    private static final double ENTER_SPLIT = 0.45D;
    private static final double EXIT_SPLIT = 0.5D;

    private final @Nullable OreMacroModule macro;
    private final Settings.ChoiceSetting background;
    private final Settings.BoolSetting drift;
    private final Settings.IntSetting dim;
    private final Settings.BoolSetting road;
    private final Settings.BoolSetting roadGlow;
    private final Settings.IntSetting roadWidth;
    private final Settings.BoolSetting player;
    private final Settings.IntSetting playerScale;
    private final Settings.BoolSetting sway;
    private final Settings.BoolSetting sparks;
    private final Settings.BoolSetting shooting;
    private final Settings.BoolSetting notifications;
    private final Settings.BoolSetting ticker;

    private final TunnelScanner scanner = new TunnelScanner();
    private final TunnelScene scene;
    private Phase phase = Phase.OFF;
    private long phaseMs;
    private boolean chordDown;
    private boolean revealed;
    private @Nullable Perspective perspectiveBefore;
    private static @Nullable TunnelVisionModule instance;

    public TunnelVisionModule(@Nullable OreMacroModule macro, @Nullable SessionHudModule sessionHud) {
        super("tunnel_vision", "Tunnel Vision", Category.GENERAL, "Tunnel",
                "F5 + V: the game view turns into a show - a backdrop of your choice, only your player in 3D on a rainbow road "
                        + "that follows what the macro does, with small notifications. HUDs stay.",
                Settings.KeybindSetting.NONE);
        this.macro = macro;
        this.scene = new TunnelScene(() -> sessionHud == null ? null : sessionHud.latest());
        background = add(new Settings.ChoiceSetting("background", "Background", "Default (map)", TunnelVisionModule::backgrounds))
                .description("Put png / jpg pictures into config/theprisons/tunnel/ to choose them here.").group("Background");
        drift = bool("drift", "Slow camera drift", true).group("Background");
        dim = integer("dim", "Dim the backdrop", 12, 0, 60, 2).suffix(" %").group("Background");
        road = bool("road", "Rainbow road", true).group("Road");
        roadGlow = bool("road_glow", "Road glow", true).group("Road");
        roadWidth = integer("road_width", "Road width", 100, 60, 140, 5).suffix(" %").group("Road");
        player = bool("player", "Show my player", true).group("Player");
        playerScale = integer("player_scale", "Player size", 100, 60, 140, 5).suffix(" %").group("Player");
        sway = bool("sway", "Idle sway", true).group("Player");
        sparks = bool("sparks", "Sparks", true).group("Show");
        shooting = bool("shooting", "Shooting stars", true).group("Show");
        notifications = bool("notifications", "Macro notifications", true)
                .description("A small card when the macro changes what it does (mining, sorting, break, escaping ...).").group("Show");
        ticker = bool("ticker", "Stats ticker", true).group("Show");
        action("toggle", "Tunnel Vision", "Start / stop (F5 + V)", () -> set(phase == Phase.OFF || phase == Phase.EXITING)).group("Show");
        instance = this;
    }

    private static List<Settings.Option> backgrounds() {
        List<Settings.Option> out = new ArrayList<>();
        out.add(new Settings.Option(TunnelBackdrops.NEBULA, "Nebula (animated)", "", 0xFFA66CFF));
        for (String file : TunnelBackdrops.files()) {
            int dot = file.lastIndexOf('.');
            out.add(new Settings.Option(file, dot > 0 ? file.substring(0, dot) : file, "", 0xFFFFFFFF));
        }
        return out;
    }

    public static @Nullable TunnelVisionModule get() {
        return instance;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public void register(EventBus bus) {
        bus.subscribe(CoreEvents.TickEnd.class, this, e -> tick(e.client()));
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Starts (true) or stops the tunnel with the iris animation. */
    public void set(boolean on) {
        long now = Util.getMeasuringTimeMs();
        if (on && (phase == Phase.OFF || phase == Phase.EXITING)) {
            phase = Phase.ENTERING;
            phaseMs = now;
            revealed = false;
            scanner.reset();
            scene.begin(now);
        } else if (!on && (phase == Phase.ON || phase == Phase.ENTERING)) {
            phase = Phase.EXITING;
            phaseMs = now;
        }
    }

    public boolean active() {
        return phase != Phase.OFF;
    }

    private double progress(long now) {
        long length = phase == Phase.ENTERING ? ENTER_MS : EXIT_MS;
        return MathHelper.clamp((now - phaseMs) / (double) length, 0.0D, 1.0D);
    }

    /** The tunnel replaces the game right now (the world is not drawn). */
    private boolean sceneVisible(long now) {
        return switch (phase) {
            case ON -> true;
            case ENTERING -> progress(now) >= ENTER_SPLIT;
            case EXITING -> progress(now) < EXIT_SPLIT;
            case OFF -> false;
        };
    }

    /** For the renderer mixin: the world must not be drawn this frame. */
    public static boolean hidesWorld() {
        TunnelVisionModule m = instance;
        return m != null && m.enabled() && m.sceneVisible(Util.getMeasuringTimeMs());
    }

    private static double ease(double x) {
        x = MathHelper.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    // ── Tick: the chord, the phases, the scanner ─────────────────────────────

    private void tick(MinecraftClient client) {
        if (!enabled() || client.player == null) {
            if (phase != Phase.OFF) {
                phase = Phase.OFF;
            }
            return;
        }
        boolean f5 = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_F5);
        boolean v = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_V);
        boolean both = f5 && v && client.currentScreen == null;
        if (!f5) {
            perspectiveBefore = client.options.getPerspective();
        }
        if (both && !chordDown) {
            // F5 also cycled the perspective: give it back.
            if (perspectiveBefore != null) {
                client.options.setPerspective(perspectiveBefore);
            }
            set(phase == Phase.OFF || phase == Phase.EXITING);
        }
        chordDown = both;
        long now = Util.getMeasuringTimeMs();
        if (phase == Phase.ENTERING && progress(now) >= 1.0D) {
            phase = Phase.ON;
            phaseMs = now;
        } else if (phase == Phase.EXITING && progress(now) >= 1.0D) {
            phase = Phase.OFF;
        }
        if (phase != Phase.OFF) {
            scanner.tick(client, macro);
            if (!revealed && (phase == Phase.ON || (phase == Phase.ENTERING && progress(now) > 0.8D))) {
                revealed = true;
                scanner.push(new TunnelScanner.Event("Tunnel Vision", "F5 + V to leave", 0xFF4FE8E0));
            }
        }
    }

    // ── Drawing (from the HUD, before the vanilla HUD) ───────────────────────

    public static void renderBackdrop(DrawContext context, RenderTickCounter counter) {
        TunnelVisionModule m = instance;
        if (m != null && m.enabled() && m.phase != Phase.OFF) {
            m.draw(context);
        }
    }

    private void draw(DrawContext c) {
        MinecraftClient client = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        double full = Math.hypot(w, h) / 2.0D;
        if (sceneVisible(now)) {
            scene.draw(c, client, options(), scanner, now);
        }
        double p = progress(now);
        double radius = full;
        switch (phase) {
            case ENTERING -> radius = p < ENTER_SPLIT ? full * (1.0D - ease(p / ENTER_SPLIT))
                    : full * ease((p - ENTER_SPLIT) / (1.0D - ENTER_SPLIT));
            case EXITING -> radius = p < EXIT_SPLIT ? full * (1.0D - ease(p / EXIT_SPLIT))
                    : full * ease((p - EXIT_SPLIT) / (1.0D - EXIT_SPLIT));
            default -> {
            }
        }
        scene.iris(c, w, h, radius, now / 1000.0D);
    }

    private TunnelScene.Options options() {
        return new TunnelScene.Options(background.get(), drift.on(), dim.value(), road.on(), roadGlow.on(), roadWidth.value(),
                player.on(), playerScale.value(), sway.on(), sparks.on(), shooting.on(), notifications.on(), ticker.on());
    }
}
