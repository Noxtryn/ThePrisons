package io.theprisons.modules.general.tunnel;

import io.theprisons.modules.hud.CosmicStats;
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
 * Everything the tunnel shows (pure drawing, GUI coordinates): the backdrop, the surface under the player - the rainbow
 * road that follows what the macro does, or the magic carpet - the player's 3D model cut out of the game, the crystals
 * and asteroids that are shot and burst, particles, notifications, the little ticker and the iris that opens and closes
 * the whole thing. Built to be cheap: pooled particles, no allocations per frame, quality steps for the road.
 */
final class TunnelScene {
    /** The settings the scene needs, read once per frame. */
    record Options(String background, boolean drift, int dim, boolean road, boolean roadGlow, int roadWidth, boolean player,
                   int playerScale, boolean sway, boolean sparks, boolean shooting, boolean notifications, boolean ticker,
                   TunnelVisionModule.Surface surface, int autoSeconds, TunnelVisionModule.Quality quality, boolean targets) {
    }

    private enum Mode { ROAD, CARPET }

    private static final class Target {
        boolean asteroid;
        boolean shot;
        float lane;
        float side;
        float baseY;
        float x0;
        float drift;
        int seed;
        long bornMs;
        long shootMs;
        long hitMs;
        float hx;
        float hy;
        float tx;
        float ty;
        float r;
    }

    private record Note(TunnelScanner.Event event, long born) {
    }

    private static final long NOTE_MS = 3600L;
    private static final long TICKER_MS = 5000L;
    private static final long TRANS_MS = 2600L;
    private static final long TARGET_LIFE_MS = 1700L;
    private static final long SHOT_AT_MS = 1250L;
    private static final long BOLT_MS = 170L;
    /** Body yaw that shows the player from behind (verified by screenshot). */
    private static final float BACK = 0.0F;

    private final Random random = new Random(11);
    private final TunnelFx fx = new TunnelFx();
    private final List<Note> notes = new ArrayList<>();
    private final Target[] targets = {new Target(), new Target()};
    private final boolean[] live = new boolean[2];
    private final float[][] stars = new float[140][4];
    private final int[] laneColour = new int[6];
    private final int[] railTable = new int[64];
    private final Supplier<CosmicStats.@Nullable Snapshot> stats;
    private double scroll;
    private long lastMs;
    private long shootMs;
    private float shootX;
    private float shootY;
    private long starBorn = -10_000L;
    private double bend;
    private long startedMs;
    private Mode shown = Mode.ROAD;
    private Mode pending = Mode.ROAD;
    private boolean transitioning;
    private long transStart;
    private long nextTargetMs;
    private long flashUntilMs;

    TunnelScene(Supplier<CosmicStats.@Nullable Snapshot> stats) {
        this.stats = stats;
        for (float[] s : stars) {
            s[0] = random.nextFloat();
            s[1] = random.nextFloat() * 0.55F;
            s[2] = 0.4F + random.nextFloat() * 1.3F;
            s[3] = random.nextFloat();
        }
        for (int i = 0; i < railTable.length; i++) {
            railTable[i] = Color.HSBtoRGB(i / (float) railTable.length, 0.45F, 1.0F) & 0xFFFFFF;
        }
    }

    void begin(long nowMs, boolean startOnCarpet) {
        startedMs = nowMs;
        lastMs = nowMs;
        fx.clear();
        notes.clear();
        live[0] = false;
        live[1] = false;
        shootMs = nowMs + 4000L;
        nextTargetMs = nowMs + 1800L;
        scroll = 0.0D;
        bend = 0.0D;
        transitioning = false;
        shown = startOnCarpet ? Mode.CARPET : Mode.ROAD;
    }

    void notify(TunnelScanner.Event event, long nowMs) {
        notes.add(new Note(event, nowMs));
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Overshoots a little, then settles: the carpet "pops" in. */
    private static float back(float x) {
        x = MathHelper.clamp(x, 0.0F, 1.0F);
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float u = x - 1.0F;
        return 1.0F + c3 * u * u * u + c1 * u * u;
    }

    private Mode wanted(Options o, long now) {
        return switch (o.surface()) {
            case ROAD -> Mode.ROAD;
            case CARPET -> Mode.CARPET;
            case AUTO -> ((now - startedMs) / (Math.max(10, o.autoSeconds()) * 1000L)) % 2L == 0L ? Mode.ROAD : Mode.CARPET;
        };
    }

    // ── The scene ────────────────────────────────────────────────────────────

    void draw(DrawContext c, MinecraftClient client, Options o, TunnelScanner scanner, long now) {
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        float dt = Math.min(0.1F, Math.max(0.0F, (now - lastMs) / 1000.0F));
        lastMs = now;
        double t = (now - startedMs) / 1000.0D;
        double speed = scanner.speed();
        fx.density = o.quality().density();
        scroll += dt * (1.6D + speed * 0.9D);
        bend += (scanner.steer() - bend) * Math.min(1.0D, dt * 3.0D);

        // which surface, and the transition between them
        Mode want = wanted(o, now);
        if (!transitioning && want != shown) {
            transitioning = true;
            pending = want;
            transStart = now;
            flashUntilMs = 0L;
        }
        float p = 0.0F;
        if (transitioning) {
            p = (now - transStart) / (float) TRANS_MS;
            if (p >= 1.0F) {
                shown = pending;
                transitioning = false;
                p = 0.0F;
            }
        }
        float dissolve = transitioning ? ease(p / 0.42F) : 0.0F;
        float build = transitioning ? ease((p - 0.38F) / 0.62F) : 1.0F;
        // 0 = no carpet, 1 = the carpet carries the player
        float carpetAmount;
        if (!transitioning) {
            carpetAmount = shown == Mode.CARPET ? 1.0F : 0.0F;
        } else {
            carpetAmount = shown == Mode.CARPET ? 1.0F - dissolve : build;
        }
        if (transitioning && now - transStart > TRANS_MS * 0.4D && flashUntilMs == 0L) {
            flashUntilMs = now + 180L; // the cut
        }

        float punch = transitioning ? (float) Math.sin(Math.PI * MathHelper.clamp(p / 0.6F, 0.0F, 1.0F)) * 0.035F : 0.0F;
        background(c, w, h, o, t, punch);
        int horizon = (int) (h * 0.50F);
        if (carpetAmount > 0.01F) {
            clouds(c, w, h, horizon, carpetAmount, t);
        }
        horizonGlow(c, w, h, horizon, t, 1.0F - 0.55F * carpetAmount);
        if (o.shooting()) {
            shootingStar(c, w, h, now);
        }

        // the road: whole, dissolving (from the horizon down) or being laid (from the player outwards)
        boolean roadWhole = !transitioning && shown == Mode.ROAD;
        if (o.road() && (roadWhole || transitioning)) {
            float sMin = 0.0F;
            if (transitioning && shown == Mode.ROAD) {
                sMin = dissolve;
                if (dissolve < 0.995F) {
                    frontParticles(w, h, horizon, o, sMin, false);
                }
            } else if (transitioning) {
                sMin = 1.0F - build;
                if (build > 0.02F && build < 0.995F) {
                    frontParticles(w, h, horizon, o, sMin, true);
                }
            }
            if (sMin < 0.999F) {
                road(c, w, h, horizon, o, t, sMin);
            }
        }
        if (speed > 1.2D && roadWhole && o.quality() != TunnelVisionModule.Quality.FAST) {
            speedLines(c, w, h, horizon, t, speed);
        }

        // the player, standing on the road or floating on the carpet
        int ph = Math.round(h * 0.5F * (o.playerScale() / 100.0F));
        int pw = Math.round(ph * 0.72F);
        double bob = Math.sin(t * 1.5D) * h * 0.016D + Math.sin(t * 0.73D + 1.0D) * h * 0.008D;
        int lift = Math.round(carpetAmount * (h * 0.055F + (float) bob));
        int cx = w / 2 + (int) Math.round(bend * w * 0.035D);
        int y2 = Math.round(h * 0.845F) - lift;
        int footY = y2 - Math.round(ph * 0.04F);

        boolean carpetShown = (!transitioning && shown == Mode.CARPET) || transitioning;
        if (carpetShown && carpetAmount > 0.01F) {
            float rowsGone = transitioning && shown == Mode.CARPET ? dissolve : 0.0F;
            float scale = transitioning && shown == Mode.ROAD ? back(build) : (transitioning ? 1.0F : 1.0F);
            TunnelCarpet.draw(c, cx, footY, pw, h, t, rowsGone, scale, scanner.steer());
            if (transitioning && shown == Mode.CARPET && dissolve < 0.995F) {
                carpetParticles(cx, footY, pw, h, dissolve, false);
            } else if (transitioning && build < 0.995F) {
                carpetParticles(cx, footY, pw, h, build, true);
            }
            if (carpetAmount > 0.8F && o.sparks()) {
                trailSparks(cx, footY, pw, h);
            }
        }
        if (o.player()) {
            player(c, client, cx, y2, pw, ph, o, t, scanner);
        }
        if (o.targets() && !transitioning) {
            targets(c, w, h, horizon, o, now, dt, cx, y2, pw, ph);
        }
        if (o.sparks() && roadWhole) {
            ambientSparks(w, h, speed);
        }
        fx.update(dt);
        fx.draw(c);
        if (o.notifications()) {
            notes(c, client, w, h, scanner, now);
        }
        if (o.ticker()) {
            ticker(c, client, scanner, now);
        }
        if (now < flashUntilMs) {
            int a = Math.round(150 * (flashUntilMs - now) / 180.0F);
            c.fill(0, 0, w, h, (a << 24) | 0xFFE8FF);
        }
    }

    // ── Backdrop ─────────────────────────────────────────────────────────────

    private void background(DrawContext c, int w, int h, Options o, double t, float punch) {
        TunnelBackdrops.Picture p = TunnelBackdrops.picture(o.background());
        if (p != null) {
            double cover = Math.max(w / (double) p.width(), h / (double) p.height());
            double zoom = (o.drift() ? 1.07D + 0.03D * Math.sin(t * 0.13D) : 1.0D) + punch;
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
            nebula(c, w, h, t, o.quality());
        }
        if (o.dim() > 0) {
            c.fill(0, 0, w, h, (Math.round(o.dim() * 2.55F) << 24));
        }
        c.fillGradient(0, 0, w, h / 4, 0x66000000, 0x00000000);
        c.fillGradient(0, h * 3 / 4, w, h, 0x00000000, 0x77000000);
    }

    /** The animated built-in: a deep violet sky with moving aurora bands and twinkling stars. */
    private void nebula(DrawContext c, int w, int h, double t, TunnelVisionModule.Quality quality) {
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
        int step = quality == TunnelVisionModule.Quality.FAST ? 2 : 1;
        for (int si = 0; si < stars.length; si += step) {
            float[] s = stars[si];
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

    private void horizonGlow(DrawContext c, int w, int h, int horizon, double t, float strength) {
        int pulse = Math.round((56 + 22 * (float) Math.sin(t * 1.4D)) * strength);
        c.fillGradient(0, horizon - h / 7, w, horizon, 0x00FF9AF5, (pulse << 24) | 0xFF9AF5);
        c.fillGradient(0, horizon, w, horizon + h / 12, (pulse << 24) | 0xFF9AF5, 0x00FF9AF5);
    }

    /** Soft cloud banks drifting by below the carpet. */
    private void clouds(DrawContext c, int w, int h, int horizon, float amount, double t) {
        for (int i = 0; i < 7; i++) {
            double speed = 6.0D + i * 3.5D;
            double x = ((t * speed + i * 137.0D) % (w + 160.0D)) - 80.0D;
            int y = horizon + Math.round(h * (0.10F + 0.07F * i));
            int a = Math.round((14 + 3 * i) * amount);
            int len = 46 + i * 9;
            c.fill((int) x, y, (int) x + len, y + 3 + i / 2, (a << 24) | 0xD8E4FF);
            c.fill((int) x + len / 6, y - 2, (int) x + len * 5 / 6, y, (a << 24) | 0xD8E4FF);
            c.fill((int) x + len / 3, y + 3 + i / 2, (int) x + len * 2 / 3, y + 5 + i / 2, (a / 2 << 24) | 0xD8E4FF);
        }
    }

    // ── The rainbow road ─────────────────────────────────────────────────────

    private static int hsv(float hue, float sat, float val, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (Color.HSBtoRGB(hue - (float) Math.floor(hue), sat, MathHelper.clamp(val, 0.0F, 1.0F)) & 0xFFFFFF);
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * A flat road in perspective: one scanline per row (every {@code step}-th at lower quality), bending with the macro's
     * steering, scrolling with its speed. Only the rows from {@code sMin} (0 = horizon, 1 = at the player) on are drawn.
     */
    private void road(DrawContext c, int w, int h, int horizon, Options o, double t, float sMin) {
        int rows = h - horizon;
        double nearHalf = w * 0.31D * (o.roadWidth() / 100.0D);
        int lanes = 6;
        int step = o.quality().rowStep();
        boolean glow = o.roadGlow() && o.quality() == TunnelVisionModule.Quality.HIGH;
        for (int i = 0; i < lanes; i++) {
            laneColour[i] = Color.HSBtoRGB((float) ((i / (double) lanes + t * 0.08D) % 1.0D), 0.70F, 1.0F) & 0xFFFFFF;
        }
        int yStart = Math.max(horizon + 1, horizon + Math.round(sMin * rows));
        for (int y = yStart; y < h; y += step) {
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
                c.fill(lx0, y, lx1, y + step, (alpha << 24) | shade(laneColour[lane], bright));
            }
            if (stripe > 0.93D) {
                c.fill(x0, y, x1, y + step, (Math.round(alpha * 0.35F) << 24) | 0xFFFFFF);
            }
            int rail = Math.max(1, Math.round(1 + 3 * s));
            int railColour = (alpha << 24) | railTable[(int) (((t * 0.25D + z * 0.04D) % 1.0D) * railTable.length) & 63];
            c.fill(x0 - rail, y, x0, y + step, railColour);
            c.fill(x1, y, x1 + rail, y + step, railColour);
            if (glow) {
                for (int g = 1; g <= 3; g++) {
                    int a = Math.round(alpha * (0.22F / g));
                    int colour = (a << 24) | (railColour & 0xFFFFFF);
                    c.fill(x0 - rail - g * 3, y, x0 - rail - (g - 1) * 3, y + step, colour);
                    c.fill(x1 + rail + (g - 1) * 3, y, x1 + rail + g * 3, y + step, colour);
                }
            }
        }
    }

    private void speedLines(DrawContext c, int w, int h, int horizon, double t, double speed) {
        int n = Math.min(14, 5 + (int) speed * 2);
        for (int i = 0; i < n; i++) {
            double a = (i * 0.6180339887D + t * 0.05D) % 1.0D;
            double d0 = 0.25D + ((t * 0.9D + i * 0.37D) % 1.0D) * 0.6D;
            int x = (int) (w / 2.0D + (a - 0.5D) * 2.0D * w * d0 * 0.9D);
            int y = (int) (horizon + (h - horizon) * d0 * 0.9D);
            int len = 2 + (int) (6 * d0);
            c.fill(x, y, x + 1, y + len, ((int) (60 * (1.0D - d0)) << 24) | 0xFFFFFF);
        }
    }

    /** Particles along the front where the road dissolves (rising, rainbow) or is laid (cyan sparks). */
    private void frontParticles(int w, int h, int horizon, Options o, float sMin, boolean laying) {
        int rows = h - horizon;
        float s = Math.max(0.03F, sMin);
        float y = horizon + s * rows;
        double centre = w / 2.0D + bend * (1.0F - s) * (1.0F - s) * w * 0.5D;
        double half = w * 0.31D * (o.roadWidth() / 100.0D) * s;
        int count = Math.round((laying ? 4 : 7) * fx.density);
        for (int i = 0; i < count; i++) {
            float x = (float) (centre + (random.nextDouble() * 2 - 1) * half);
            int colour = laying ? (random.nextBoolean() ? 0x8AF5FF : 0xFFFFFF)
                    : Color.HSBtoRGB(random.nextFloat(), 0.55F, 1.0F) & 0xFFFFFF;
            fx.add(x, y, (random.nextFloat() - 0.5F) * 26.0F, laying ? -10.0F - random.nextFloat() * 22.0F : -28.0F - random.nextFloat() * 56.0F,
                    0.55F + random.nextFloat() * 0.5F, random.nextInt(4) == 0 ? 3 : 2, colour, TunnelFx.KIND_SPARK);
        }
    }

    private void carpetParticles(int cx, int footY, int pw, int h, float amount, boolean appearing) {
        int count = Math.round((appearing ? 5 : 6) * fx.density);
        for (int i = 0; i < count; i++) {
            double ang = random.nextDouble() * Math.PI * 2;
            float rad = pw * (0.5F + random.nextFloat() * 0.7F);
            float x = cx + (float) Math.cos(ang) * rad;
            float y = footY + (float) Math.sin(ang) * h * 0.05F + h * 0.02F;
            int colour = switch (random.nextInt(3)) { case 0 -> 0xFFC93C; case 1 -> 0xFF7AC8; default -> 0x8AF5FF; };
            float vx = appearing ? -(x - cx) * 0.8F + (float) -Math.sin(ang) * 30.0F : (random.nextFloat() - 0.5F) * 30.0F;
            float vy = appearing ? -20.0F - random.nextFloat() * 24.0F : -24.0F - random.nextFloat() * 50.0F;
            fx.add(x, y, vx, vy, 0.6F + random.nextFloat() * 0.6F, 2, colour, TunnelFx.KIND_SPARK);
        }
    }

    private void trailSparks(int cx, int footY, int pw, int h) {
        if (random.nextFloat() < 0.35F * fx.density) {
            float x = cx + (random.nextFloat() - 0.5F) * pw * 1.6F;
            fx.add(x, footY + h * 0.07F, (random.nextFloat() - 0.5F) * 10.0F, 14.0F + random.nextFloat() * 18.0F, 0.9F, 2,
                    random.nextBoolean() ? 0xFFC93C : 0xFF9AF5, TunnelFx.KIND_SPARK);
        }
    }

    private void ambientSparks(int w, int h, double speed) {
        int spawn = Math.round((1 + (int) (speed * 0.6D)) * fx.density);
        for (int i = 0; i < spawn; i++) {
            if (random.nextFloat() < 0.5F) {
                float x = w / 2.0F + (random.nextFloat() - 0.5F) * w * 0.5F;
                float y = h * 0.62F + random.nextFloat() * h * 0.34F;
                fx.add(x, y, (random.nextFloat() - 0.5F) * 14.0F, -14.0F - random.nextFloat() * 30.0F, 0.9F + random.nextFloat() * 0.9F, 2,
                        Color.HSBtoRGB(random.nextFloat(), 0.5F, 1.0F) & 0xFFFFFF, TunnelFx.KIND_SPARK);
            }
        }
    }

    // ── The player ───────────────────────────────────────────────────────────

    private void player(DrawContext c, MinecraftClient client, int cx, int y2, int pw, int ph, Options o, double t, TunnelScanner scanner) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        int y1 = y2 - ph;
        int x1 = cx - pw / 2;
        int x2 = cx + pw / 2;
        int footY = y2 - Math.round(ph * 0.04F);
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
        // the glow under the feet (road only; the carpet has its own)
        if (shown == Mode.ROAD && !transitioning) {
            for (int i = 0; i < 6; i++) {
                int rx = pw / 2 + 10 - i * 3;
                c.fill(cx - rx, footY + i, cx + rx, footY + i + 1, (Math.round(70 - i * 8) << 24) | 0x66E8FF);
            }
        }
    }

    // ── Targets: crystals (pink) and asteroids (light blue) that are shot and burst ──

    private void targets(DrawContext c, int w, int h, int horizon, Options o, long now, float dt, int cx, int y2, int pw, int ph) {
        float unit = h / 300.0F;
        float handX = cx + pw * 0.28F;
        float handY = y2 - ph * 0.48F;
        if (now >= nextTargetMs) {
            for (int i = 0; i < targets.length; i++) {
                if (!live[i]) {
                    spawn(targets[i], i, w, h, horizon, now);
                    nextTargetMs = now + 1500L + random.nextInt(1300);
                    break;
                }
            }
        }
        for (int i = 0; i < targets.length; i++) {
            if (!live[i]) {
                continue;
            }
            Target t = targets[i];
            long age = now - t.bornMs;
            float life = MathHelper.clamp(age / (float) TARGET_LIFE_MS, 0.0F, 1.0F);
            float pop = back(MathHelper.clamp(age / 320.0F, 0.0F, 1.0F));
            float sNow = 0.10F + 0.22F * life;
            float x;
            float y;
            float size;
            if (t.asteroid) {
                x = t.x0 + t.drift * (age / 1000.0F);
                y = t.baseY + 6.0F * (float) Math.sin(age / 420.0D + t.seed);
                size = (9.0F + 15.0F * life) * unit * pop;
            } else {
                double centre = w / 2.0D + bend * (1.0F - sNow) * (1.0F - sNow) * w * 0.5D;
                x = (float) (centre + t.side * w);
                y = horizon + sNow * (h - horizon);
                size = (10.0F + 54.0F * sNow) * unit * pop;
            }
            t.tx = x;
            t.ty = y;
            if (!t.shot) {
                if (t.asteroid) {
                    TunnelFx.asteroid(c, x, y, size, age / 900.0D * (t.seed % 2 == 0 ? 1 : -1), t.seed, Math.min(1.0F, pop));
                } else {
                    TunnelFx.crystal(c, x, y, size, Math.min(1.0F, pop), age / 1000.0D);
                }
                if (age >= SHOT_AT_MS) {
                    t.shot = true;
                    t.shootMs = now;
                    t.hx = handX;
                    t.hy = handY;
                    fx.add(handX, handY, 0, 0, 0.16F, 4, t.asteroid ? 0x8FD8FF : 0xFF7AC8, TunnelFx.KIND_FLASH);
                }
                continue;
            }
            // the bolt flies, then the target bursts
            float f = MathHelper.clamp((now - t.shootMs) / (float) BOLT_MS, 0.0F, 1.0F);
            if (f < 1.0F) {
                if (t.asteroid) {
                    TunnelFx.asteroid(c, x, y, size, age / 900.0D * (t.seed % 2 == 0 ? 1 : -1), t.seed, 1.0F);
                } else {
                    TunnelFx.crystal(c, x, y, size, 1.0F, age / 1000.0D);
                }
                float bx = t.hx + (x - t.hx) * f;
                float by = t.hy + (y - t.hy) * f;
                float dx = x - t.hx;
                float dy = y - t.hy;
                float len = Math.max(1.0F, (float) Math.hypot(dx, dy));
                TunnelFx.bolt(c, bx, by, dx / len, dy / len, 16, t.asteroid ? 0x8FD8FF : 0xFF7AC8);
            } else {
                burst(t, x, y, size);
                live[i] = false;
            }
        }
    }

    private void spawn(Target t, int index, int w, int h, int horizon, long now) {
        live[index] = true;
        t.shot = false;
        t.bornMs = now;
        t.seed = random.nextInt(1000);
        t.lane = (random.nextFloat() * 2 - 1) * 0.6F;
        // beside the player, never behind the model: 12 % to 20 % of the width to the left or right
        t.side = (random.nextBoolean() ? 1.0F : -1.0F) * (0.12F + random.nextFloat() * 0.08F);
        boolean carpet = shown == Mode.CARPET;
        t.asteroid = carpet && random.nextInt(4) != 0;
        if (carpet) {
            t.x0 = w / 2.0F + t.side * w;
            t.baseY = h * (0.22F + random.nextFloat() * 0.22F);
            t.drift = (random.nextFloat() - 0.5F) * 10.0F;
            if (!t.asteroid) {
                // a crystal floating in the sky: stand it on a small invisible plane near the horizon
                t.asteroid = false;
            }
        }
    }

    /** A small burst, not a firework: shards, a few sparks, a short flash. */
    private void burst(Target t, float x, float y, float size) {
        int shards = Math.round((t.asteroid ? 11 : 9) * fx.density);
        for (int i = 0; i < shards; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            float sp = (t.asteroid ? 28.0F : 36.0F) + random.nextFloat() * 70.0F;
            int colour = t.asteroid
                    ? (random.nextInt(3) == 0 ? 0xC6EEFF : random.nextBoolean() ? 0x74C4EE : 0x3F86C4)
                    : (random.nextInt(3) == 0 ? 0xFFD6F0 : random.nextBoolean() ? 0xFF6FC9 : 0xC4208A);
            fx.add(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - 18.0F, 0.45F + random.nextFloat() * 0.4F,
                    t.asteroid ? 3 : 2, colour, TunnelFx.KIND_SHARD);
        }
        int sparks = Math.round(5 * fx.density);
        for (int i = 0; i < sparks; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            float sp = 20.0F + random.nextFloat() * 60.0F;
            fx.add(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 0.3F + random.nextFloat() * 0.3F, 2,
                    t.asteroid ? 0xE8F8FF : 0xFFE6F6, TunnelFx.KIND_SPARK);
        }
        if (t.asteroid) {
            for (int i = 0; i < Math.round(5 * fx.density); i++) {
                double a = random.nextDouble() * Math.PI * 2;
                fx.add(x, y, (float) Math.cos(a) * 22.0F, (float) Math.sin(a) * 22.0F, 0.7F, 3, 0x9FB8D0, TunnelFx.KIND_DUST);
            }
        }
        fx.add(x, y, 0, 0, 0.2F, Math.max(4.0F, size * 0.7F), t.asteroid ? 0x9FDFFF : 0xFF9AE0, TunnelFx.KIND_FLASH);
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
