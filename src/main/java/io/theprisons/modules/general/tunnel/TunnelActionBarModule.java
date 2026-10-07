package io.theprisons.modules.general.tunnel;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.gui.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The mining session's action bar (XP / energy per minute, boosts) while Tunnel Vision hides the world: the server's
 * own bar is not drawn then, this one is - on a spot the user chooses in the HUD editor.
 */
public final class TunnelActionBarModule extends Module implements HudElement {
    private static final long FRESH_MS = 4_000L;
    private static final String SAMPLE = "+63.8 XP (67.5k/min)  +124.6 CE (114.7k/min)  |  1.5x Comp Shard (49s)";

    private final Settings.IntSetting x;
    private final Settings.IntSetting y;
    private final Settings.DoubleSetting scale;
    private @Nullable String text;
    private long textMs;

    public TunnelActionBarModule() {
        super("tunnel_actionbar", "Tunnel Action Bar", Category.HUD, "Tunnel",
                "Shows the action bar (XP, energy, boosts of the mining session) during Tunnel Vision; move it in the HUD editor.",
                Settings.KeybindSetting.NONE);
        x = integer("x", "X", -1, -1, 4000, 1).group("Position");
        y = integer("y", "Y", -1, -1, 4000, 1).group("Position");
        scale = decimal("scale", "Scale", 1.0D, 0.5D, 2.0D, 0.05D).group("Position");
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public void register(EventBus bus) {
        bus.subscribe(CoreEvents.ChatReceived.class, this, event -> {
            if (event.overlay() && !event.fromPlayer()) {
                String s = io.theprisons.core.client.TextStrip.strip(event.message().getString()).trim();
                if (!s.isEmpty()) {
                    text = s;
                    textMs = Util.getMeasuringTimeMs();
                }
            }
        });
    }

    /** Drawn after the vanilla HUD, only while the tunnel scene covers the world. */
    public void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        String s = text;
        if (!enabled() || client.player == null || client.options.hudHidden || s == null
                || Util.getMeasuringTimeMs() - textMs > FRESH_MS || !TunnelVisionModule.hidesWorld()) {
            return;
        }
        draw(context, client, s);
    }

    private int[] bounds(int sw, int sh, String s) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        double f = scale.get();
        int w = (int) Math.round((font.getWidth(s) + 12) * f);
        int h = (int) Math.round(16 * f);
        int bx = x.get() < 0 ? (sw - w) / 2 : Math.min(x.get(), Math.max(0, sw - w));
        int by = y.get() < 0 ? sh - 59 - h : Math.min(y.get(), Math.max(0, sh - h));
        return new int[]{bx, by, w, h};
    }

    private void draw(DrawContext c, MinecraftClient client, String s) {
        int[] b = bounds(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), s);
        float f = scale.get().floatValue();
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(b[0], b[1]);
        c.getMatrices().scale(f, f);
        int w = Math.round(b[2] / f);
        c.fill(0, 0, w, 16, 0xB00E0C1A);
        c.fill(0, 0, 2, 16, 0xFF7B3FFF);
        c.drawTextWithShadow(client.textRenderer, s, 8, 4, 0xFFFFFFFF);
        c.getMatrices().popMatrix();
    }

    @Override
    public int[] bounds(int sw, int sh) {
        return bounds(sw, sh, text != null ? text : SAMPLE);
    }

    @Override
    public void moveTo(int nx, int ny, int sw, int sh) {
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
    public void drawPreview(DrawContext context, int sw, int sh) {
        MinecraftClient client = MinecraftClient.getInstance();
        draw(context, client, text != null ? text : SAMPLE);
    }

    @Override
    public void reset() {
        x.set(-1);
        y.set(-1);
        scale.set(1.0D);
    }
}
