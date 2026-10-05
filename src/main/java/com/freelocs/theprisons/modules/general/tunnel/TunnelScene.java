package com.freelocs.theprisons.modules.general.tunnel;

import com.freelocs.theprisons.modules.hud.CosmicStats;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Everything the tunnel shows (pure drawing, GUI coordinates): the backdrop, the rainbow road under the player that
 * follows what the macro does, the player's 3D model cut out of the game, sparks and shooting stars, notifications,
 * the little ticker and the iris that opens and closes the whole thing.
 */
final class TunnelScene {
    /** The settings the scene needs, read once per frame. */
    record Options(String background, boolean drift, int dim, boolean road, boolean roadGlow, int roadWidth, boolean player,
                   int playerScale, boolean sway, boolean sparks, boolean shooting, boolean notifications, boolean ticker) {
    }

    private record Spark(float x, float y, float vx, float vy, long born, int life, float hue) {
    }

    private record Note(TunnelScanner.Event event, long born) {
    }

    private static final long NOTE_MS = 3600L;
    private static final long TICKER_MS = 5000L;
    /** Body yaw that shows the player from behind (verified by screenshot). */
    private static final float BACK = 0.0F;

    private final Random random = new Random(11);
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final float[][] stars = new float[140][4];
    private final Supplier<CosmicStats.@Nullable Snapshot> stats;
    private double scroll;
    private long lastMs;
    private long shootMs;
    private float shootX;
    private float shootY;
    private double bend;
    private long startedMs;

    TunnelScene(Supplier<CosmicStats.@Nullable Snapshot> stats) {
        this.stats = stats;
        for (float[] s : stars) {
            s[0] = random.nextFloat();
            s[1] = random.nextFloat() * 0.55F;
            s[2] = 0.4F + random.nextFloat() * 1.3F;
            s[3] = random.nextFloat();
        }
    }

    void begin(long nowMs) {
        startedMs = nowMs;
        lastMs = nowMs;
        sparks.clear();
        notes.clear();
        shootMs = nowMs + 4000L;
        scroll = 0.0D;
        bend = 0.0D;
    }

    void notify(TunnelScanner.Event event, long nowMs) {
        notes.add(new Note(event, nowMs));
    }

    // ── The scene ────────────────────────────────────────────────────────────

    void draw(DrawContext c, MinecraftClient client, Options o, TunnelScanner scanner, long now) {
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        float dt = Math.min(0.1F, Math.max(0.0F, (now - lastMs) / 1000.0F));
        lastMs = now;
        double t = (now - startedMs) / 1000.0D;
        double speed = scanner.speed();
        scroll += dt * (1.6D + speed * 0.9D);
        bend += (scanner.steer() - bend) * Math.min(1.0D, dt * 3.0D);

        background(c, w, h, o, t);
        int horizon = (int) (h * 0.50F);
        horizonGlow(c, w, h, horizon, t);
        if (o.shooting()) {
            shootingStar(c, w, h, now);
        }
        if (o.road()) {
            road(c, w, h, horizon, o, t, speed);
        }
        if (speed > 1.2D) {
            speedLines(c, w, h, horizon, t, speed);
        }
        if (o.player()) {
            player(c, client, w, h, o, t, scanner);
        }
        if (o.sparks()) {
            sparks(c, w, h, now, dt, speed);
        }
        if (o.notifications()) {
            notes(c, client, w, h, scanner, now);
        }
        if (o.ticker()) {
            ticker(c, client, scanner, now);
        }
    }

    // ── Backdrop ─────────────────────────────────────────────────────────────

    private void background(DrawContext c, int w, int h, Options o, double t) {
        TunnelBackdrops.Picture p = TunnelBackdrops.picture(o.background());
        if (p != null) {
            double cover = Math.max(w / (double) p.width(), h / (double) p.height());
            double zoom = o.drift() ? 1.07D + 0.03D * Math.sin(t * 0.13D) : 1.0D;
            double sw = p.width() * cover * zoom;
            double sh = p.height() * cover * zoom;
            double ox = -(sw - w) / 2.0D + (o.drift() ? Math.sin(t * 0.09D) * (sw - w) / 2.2D : 0.0D);
            double oy = -(sh - h) / 2.0D + (o.drift() ? Math.cos(t * 0.07D) * (sh - h) / 2.2D : 0.0D);
            c.getMatrices().pushMatrix();
            c.getMatrices().translate((float) ox, (float) oy);
            c.getMatrices().scale((float) (sw / p.width()), (float) (sh / p.height()));
            c.drawTexture(RenderPipelines.GUI_TEXTURED, p.texture(), 0, 0, 0.0F, 0.0F, p.width(), p.height(), p.width(), p.height(),
                    p.width(), p.height());
            c.getMatrices().popMatrix();
        } else {
            nebula(c, w, h, t);
        }
        if (o.dim() > 0) {
            c.fill(0, 0, w, h, (Math.round(o.dim() * 2.55F) << 24));
        }
        // soft vignette so the road and the player stand out
        c.fillGradient(0, 0, w, h / 4, 0x66000000, 0x00000000);
        c.fillGradient(0, h * 3 / 4, w, h, 0x00000000, 0x77000000);
    }

    /** The animated built-in: a deep violet sky with moving aurora bands and twinkling stars. */
    private void nebula(DrawContext c, int w, int h, double t) {
        c.fillGradient(0, 0, w, h / 2, 0xFF0B0620, 0xFF241052);
        c.fillGradient(0, h / 2, w, h, 0xFF241052, 0xFF0A1636);
        for (int band = 0; band < 3; band++) {
            double phase = t * (0.18D + band * 0.07D) + band * 2.1D;
            int y0 = (int) (h * (0.12D + 0.12D * band + 0.05D * Math.sin(phase)));
            int hue = band == 0 ? 0x7A3CFF : band == 1 ? 0x2FB6FF : 0xFF4FB8;
            int a = 48 + (int) (26 * Math.sin(phase * 1.7D));
            c.fillGradient(0, y0, w, y0 + h / 6, (a << 24) | hue, 0x00000000 | hue);
            c.fillGradient(0, y0 - h / 8, w, y0, 0x00000000 | hue, (a << 24) | hue);
        }
        for (float[] s : stars) {
            double tw = 0.55D + 0.45D * Math.sin(t * s[2] * 2.0D + s[3] * 20.0D);
            int x = (int) (((s[0] + t * 0.002D * s[2]) % 1.0D) * w);
            int y = (int) (s[1] * h);
            int a = (int) (200 * tw);
            c.fill(x, y, x + 1, y + 1, (a << 24) | 0xFFFFFF);
            if (s[2] > 1.2F) {
                c.fill(x - 1, y, x + 2, y + 1, ((a / 3) << 24) | 0xBFD8FF);
                c.fill(x, y - 1, x + 1, y + 2, ((a / 3) << 24) | 0xBFD8FF);
            }
        }
    }

    private void horizonGlow(DrawContext c, int w, int h, int horizon, double t) {
        int pulse = 56 + (int) (22 * Math.sin(t * 1.4D));
        c.fillGradient(0, horizon - h / 7, w, horizon, 0x00FF9AF5, (pulse << 24) | 0xFF9AF5);
        c.fillGradient(0, horizon, w, horizon + h / 12, (pulse << 24) | 0xFF9AF5, 0x00FF9AF5);
    }

    // ── The rainbow road ─────────────────────────────────────────────────────

    private static int hsv(float hue, float sat, float val, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (Color.HSBtoRGB(hue - (float) Math.floor(hue), sat, MathHelper.clamp(val, 0.0F, 1.0F)) & 0xFFFFFF);
    }

    /** A flat road in perspective: one scanline per row, bending with the macro's steering, scrolling with its speed. */
    private void road(DrawContext c, int w, int h, int horizon, Options o, double t, double speed) {
        int rows = h - horizon;
        double nearHalf = w * 0.31D * (o.roadWidth() / 100.0D);
        int lanes = 6;
        for (int y = horizon + 1; y < h; y++) {
            float s = (y - horizon) / (float) rows;
            double z = 1.0D / (s + 0.035D);
            double half = nearHalf * s;
            double centre = w / 2.0D + bend * (1.0F - s) * (1.0F - s) * w * 0.5D;
            int alpha = Math.round(255 * MathHelper.clamp(s * 3.4F, 0.0F, 1.0F));
            int x0 = (int) Math.round(centre - half);
            int x1 = (int) Math.round(centre + half);
            if (x1 - x0 < 2) {
                continue;
            }
            double stripe = 0.5D + 0.5D * Math.sin(z * 2.6D - scroll * 5.0D);
            float bright = (float) (0.62D + 0.34D * stripe);
            for (int lane = 0; lane < lanes; lane++) {
                int lx0 = x0 + (x1 - x0) * lane / lanes;
                int lx1 = x0 + (x1 - x0) * (lane + 1) / lanes;
                float hue = (float) (lane / (double) lanes + t * 0.08D);
                c.fill(lx0, y, lx1, y + 1, hsv(hue, 0.70F, bright, alpha));
            }
            // checker sparkle: a bright cross-stripe now and then
            if (stripe > 0.93D) {
                c.fill(x0, y, x1, y + 1, (Math.round(alpha * 0.35F) << 24) | 0xFFFFFF);
            }
            int rail = Math.max(1, Math.round(1 + 3 * s));
            float railHue = (float) (t * 0.25D + z * 0.04D);
            int railColour = hsv(railHue, 0.45F, 1.0F, alpha);
            c.fill(x0 - rail, y, x0, y + 1, railColour);
            c.fill(x1, y, x1 + rail, y + 1, railColour);
            if (o.roadGlow()) {
                for (int g = 1; g <= 3; g++) {
                    int a = Math.round(alpha * (0.22F / g));
                    int colour = (a << 24) | (railColour & 0xFFFFFF);
                    c.fill(x0 - rail - g * 3, y, x0 - rail - (g - 1) * 3, y + 1, colour);
                    c.fill(x1 + rail + (g - 1) * 3, y, x1 + rail + g * 3, y + 1, colour);
                }
            }
        }
    }

    private void speedLines(DrawContext c, int w, int h, int horizon, double t, double speed) {
        int n = Math.min(18, 6 + (int) speed * 2);
        for (int i = 0; i < n; i++) {
            double a = (i * 0.6180339887D + t * 0.05D) % 1.0D;
            double d0 = 0.25D + ((t * 0.9D + i * 0.37D) % 1.0D) * 0.6D;
            int x = (int) (w / 2.0D + (a - 0.5D) * 2.0D * w * d0 * 0.9D);
            int y = (int) (horizon + (h - horizon) * d0 * 0.9D);
            int len = 2 + (int) (6 * d0);
            c.fill(x, y, x + 1, y + len, ((int) (60 * (1.0D - d0)) << 24) | 0xFFFFFF);
        }
    }

    // ── The player ───────────────────────────────────────────────────────────

    private void player(DrawContext c, MinecraftClient client, int w, int h, Options o, double t, TunnelScanner scanner) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        int ph = Math.round(h * 0.5F * (o.playerScale() / 100.0F));
        int pw = Math.round(ph * 0.72F);
        int cx = w / 2 + (int) Math.round(bend * w * 0.035D);
        int y2 = Math.round(h * 0.845F);
        int y1 = y2 - ph;
        int x1 = cx - pw / 2;
        int x2 = cx + pw / 2;
        // glow + shadow on the road under the feet
        int footY = y2 - Math.round(ph * 0.04F);
        for (int i = 0; i < 6; i++) {
            int rx = pw / 2 + 10 - i * 3;
            c.fill(cx - rx, footY + i, cx + rx, footY + i + 1, (Math.round(70 - i * 8) << 24) | 0x66E8FF);
        }
        EntityRenderer<? super ClientPlayerEntity, ?> renderer = client.getEntityRenderDispatcher().getRenderer(player);
        EntityRenderState state = renderer.getAndUpdateRenderState(player, 1.0F);
        state.displayName = null;
        state.nameLabelPos = null;
        state.light = 0xF000F0;
        state.shadowPieces.clear();
        if (state instanceof LivingEntityRenderState living) {
            float sway = o.sway() ? (float) (Math.sin(t * 1.3D) * 7.0D) : 0.0F;
            living.bodyYaw = BACK + sway + (float) (scanner.steer() * 32.0D);
            living.relativeHeadYaw = (float) (-scanner.steer() * 24.0D) + (o.sway() ? (float) (Math.sin(t * 0.8D) * 8.0D) : 0.0F);
            living.pitch = 4.0F;
        }
        float scale = ph / 1.95F * 0.92F;
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX(-0.12F);
        c.addEntity(state, scale, new Vector3f(0.0F, player.getHeight() / 2.0F + 0.0625F, 0.0F), rotation, camera, x1, y1, x2, y2);
    }

    // ── Sparks, shooting stars ───────────────────────────────────────────────

    private void sparks(DrawContext c, int w, int h, long now, float dt, double speed) {
        int spawn = 1 + (int) (speed * 0.8D);
        for (int i = 0; i < spawn && sparks.size() < 140; i++) {
            if (random.nextFloat() < 0.55F) {
                float x = w / 2.0F + (random.nextFloat() - 0.5F) * w * 0.5F;
                float y = h * 0.62F + random.nextFloat() * h * 0.34F;
                sparks.add(new Spark(x, y, (random.nextFloat() - 0.5F) * 14.0F, -14.0F - random.nextFloat() * 30.0F, now,
                        900 + random.nextInt(900), random.nextFloat()));
            }
        }
        sparks.removeIf(s -> now - s.born() > s.life());
        for (Spark s : sparks) {
            float age = (now - s.born()) / 1000.0F;
            float life = s.life() / 1000.0F;
            float f = age / life;
            int x = Math.round(s.x() + s.vx() * age);
            int y = Math.round(s.y() + s.vy() * age);
            int a = Math.round(220 * (1.0F - f) * Math.min(1.0F, f * 8.0F));
            int colour = hsv(s.hue() + age * 0.2F, 0.5F, 1.0F, a);
            c.fill(x, y, x + 2, y + 2, colour);
            c.fill(x - 1, y, x + 3, y + 1, (a / 3 << 24) | (colour & 0xFFFFFF));
        }
    }

    private void shootingStar(DrawContext c, int w, int h, long now) {
        if (now >= shootMs) {
            shootX = random.nextFloat() * w * 0.8F;
            shootY = random.nextFloat() * h * 0.25F;
            shootMs = now + 6000L + random.nextInt(7000);
            starBorn = now;
        }
        float f = (now - starBorn) / 900.0F;
        if (f < 0.0F || f > 1.0F) {
            return;
        }
        for (int i = 0; i < 18; i++) {
            float g = f - i * 0.012F;
            if (g < 0.0F) {
                continue;
            }
            int x = Math.round(shootX + g * w * 0.28F);
            int y = Math.round(shootY + g * h * 0.16F);
            int a = Math.round(230 * (1.0F - i / 18.0F) * (1.0F - f * 0.6F));
            c.fill(x, y, x + 2, y + 1, (a << 24) | 0xFFFFFF);
        }
    }

    private long starBorn = -10_000L;

    // ── Notifications and the ticker ─────────────────────────────────────────

    private void notes(DrawContext c, MinecraftClient client, int w, int h, TunnelScanner scanner, long now) {
        TunnelScanner.Event event;
        while (notes.size() < 3 && (event = scanner.poll()) != null) {
            notes.add(new Note(event, now));
        }
        notes.removeIf(n -> now - n.born() > NOTE_MS);
        TextRenderer font = client.textRenderer;
        int y = Math.round(h * 0.10F);
        for (Note n : notes) {
            long age = now - n.born();
            float in = MathHelper.clamp(age / 350.0F, 0.0F, 1.0F);
            float out = MathHelper.clamp((NOTE_MS - age) / 400.0F, 0.0F, 1.0F);
            float a = Math.min(in, out);
            float ease = 1.0F - (1.0F - in) * (1.0F - in) * (1.0F - in);
            int cw = Math.max(Math.max(font.getWidth(n.event().title()) + 34, font.getWidth(n.event().detail()) + 34), 110);
            int cx = w / 2 - cw / 2;
            int cy = y - Math.round((1.0F - ease) * 26.0F);
            int alpha = Math.round(235 * a);
            c.fill(cx, cy, cx + cw, cy + 26, (Math.round(alpha * 0.8F) << 24) | 0x0E0C1A);
            c.fill(cx, cy, cx + 3, cy + 26, (alpha << 24) | (n.event().color() & 0xFFFFFF));
            c.fill(cx, cy + 25, cx + Math.round(cw * (1.0F - age / (float) NOTE_MS)), cy + 26, ((alpha / 2) << 24) | (n.event().color() & 0xFFFFFF));
            c.drawTextWithShadow(font, n.event().title(), cx + 10, cy + 4, (alpha << 24) | 0xFFFFFF);
            if (!n.event().detail().isEmpty()) {
                c.drawTextWithShadow(font, n.event().detail(), cx + 10, cy + 15, (alpha << 24) | 0xB8BED0);
            }
            y += 30;
        }
    }

    private void ticker(DrawContext c, MinecraftClient client, TunnelScanner scanner, long now) {
        CosmicStats.Snapshot s = stats.get();
        List<String> items = new ArrayList<>();
        items.add(scanner.label());
        if (s != null) {
            items.add("Energy/h  " + compact(s.energyPerHour()));
            items.add("XP/h  " + compact(s.xpPerHour()));
            items.add("Ores  " + compact(s.ores()));
            items.add("Up  " + clock(s.uptimeMs()));
        }
        int index = (int) (((now - startedMs) / TICKER_MS) % items.size());
        float phase = ((now - startedMs) % TICKER_MS) / (float) TICKER_MS;
        float a = Math.min(Math.min(phase * 8.0F, (1.0F - phase) * 8.0F), 1.0F);
        String text = items.get(index);
        TextRenderer font = client.textRenderer;
        int tw = font.getWidth(text) + 20;
        int alpha = Math.round(210 * a);
        int x0 = MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - tw / 2;
        c.fill(x0, 4, x0 + tw, 20, (Math.round(alpha * 0.75F) << 24) | 0x0E0C1A);
        c.fill(x0, 4, x0 + 2, 20, (alpha << 24) | hsv((float) ((now - startedMs) / 6000.0D), 0.6F, 1.0F, 255) & 0xFFFFFF);
        c.drawTextWithShadow(font, text, x0 + 10, 8, (alpha << 24) | 0xFFFFFF);
        // dots of the rotation
        int dots = items.size() * 6;
        int dx = x0 + tw / 2 - dots / 2;
        for (int i = 0; i < items.size(); i++) {
            c.fill(dx + i * 6, 21, dx + i * 6 + 4, 23, ((i == index ? 200 : 70) << 24) | 0xFFFFFF);
        }
    }

    private static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000.0D) {
            return String.format(Locale.ROOT, "%.2fB", v / 1_000_000_000.0D);
        }
        if (a >= 1_000_000.0D) {
            return String.format(Locale.ROOT, "%.2fM", v / 1_000_000.0D);
        }
        if (a >= 1_000.0D) {
            return String.format(Locale.ROOT, "%.1fk", v / 1_000.0D);
        }
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String clock(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    // ── The iris (the transition) ────────────────────────────────────────────

    /**
     * Black outside a circle of {@code radius} (in GUI pixels) around the centre, with a glowing rainbow rim.
     * radius >= the screen diagonal / 2 draws nothing.
     */
    void iris(DrawContext c, int w, int h, double radius, double timeSeconds) {
        double full = Math.hypot(w, h) / 2.0D;
        if (radius >= full) {
            return;
        }
        double cx = w / 2.0D;
        double cy = h / 2.0D;
        double r2 = radius * radius;
        for (int y = 0; y < h; y++) {
            double dy = y + 0.5D - cy;
            double d2 = r2 - dy * dy;
            if (d2 <= 0.0D) {
                c.fill(0, y, w, y + 1, 0xFF000000);
                continue;
            }
            double dx = Math.sqrt(d2);
            int xl = (int) Math.floor(cx - dx);
            int xr = (int) Math.ceil(cx + dx);
            if (xl > 0) {
                c.fill(0, y, xl, y + 1, 0xFF000000);
            }
            if (xr < w) {
                c.fill(xr, y, w, y + 1, 0xFF000000);
            }
            // rim: 3 px glowing, hue by angle
            float hue = (float) ((Math.atan2(dy, dx) / (2 * Math.PI)) + timeSeconds * 0.5D);
            int rim = hsv(hue, 0.55F, 1.0F, 235);
            int rimSoft = hsv(hue, 0.55F, 1.0F, 90);
            if (radius - Math.abs(dy) < 4.0D) {
                c.fill(Math.max(0, xl), y, Math.min(w, xr), y + 1, rim); // top and bottom of the circle: the whole chord is rim
            }
            c.fill(Math.max(0, xl - 3), y, Math.max(0, xl), y + 1, rim);
            c.fill(Math.min(w, xr), y, Math.min(w, xr + 3), y + 1, rim);
            c.fill(Math.max(0, xl), y, Math.min(w, xl + 6), y + 1, rimSoft);
            c.fill(Math.max(0, xr - 6), y, Math.min(w, xr), y + 1, rimSoft);
        }
    }
}
