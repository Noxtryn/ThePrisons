package io.theprisons.modules.qol.bandit;

import io.theprisons.core.client.TextStrip;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.modules.qol.players.FriendList;
import io.theprisons.modules.qol.players.FriendsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bandit spear helpers.
 * <ul>
 *   <li><b>Aim assist (key L):</b> the mod looks at the direction where most ore bandits stand in a line (the spear
 *       pierces everything on its path) - with the ore macro's human view motion. While it aims, the mouse does not turn
 *       the view at all; movement stays entirely the player's.</li>
 *   <li><b>Recall signal:</b> after a throw the best moment to press F (within the deadline) is shown - the player presses.</li>
 *   <li>A static spear crosshair, a sight point for the nearest enemy player, a line bar and throw / return effects.</li>
 * </ul>
 */
public final class SpearHelperModule extends Module {
    private final ControlService control;

    private final Settings.BoolSetting crosshair;
    private final Settings.BoolSetting hideVanilla;
    private final Settings.EnumSetting<CrosshairPreset> preset;
    private final Settings.ColorSetting crosshairColor;
    private final Settings.IntSetting crosshairSize;
    private final Settings.IntSetting crosshairGap;
    private final Settings.IntSetting crosshairThick;
    private final Settings.BoolSetting crosshairOutline;
    private final Settings.BoolSetting crosshairDot;
    private final Settings.BoolSetting marker;
    private final Settings.EnumSetting<SightStyle> sightStyle;
    private final Settings.ColorSetting markerColor;
    private final Settings.BoolSetting sightLine;
    private final Settings.IntSetting range;
    private final Settings.IntSetting cone;
    private final Settings.BoolSetting includeFriends;
    private final Settings.BoolSetting fx;
    private final Settings.BoolSetting trail;
    private final Settings.EnumSetting<SpearEffects.Trail> trailStyle;
    private final Settings.BoolSetting returnBolt;
    private final Settings.BoolSetting throwHand;
    private final Settings.BoolSetting returnFlash;
    private final Settings.BoolSetting fxSound;
    private final Settings.DoubleSetting fxVolume;
    private final Settings.BoolSetting anyBandit;
    private final Settings.IntSetting aimWindow;
    private final Settings.DoubleSetting lineMargin;
    private final Settings.BoolSetting lineBar;
    private final Settings.DoubleSetting recallDeadline;
    private final Settings.IntSetting recallMin;
    private final Settings.IntSetting recallLead;
    private final Settings.BoolSetting recallHud;
    private final Settings.BoolSetting recallSound;
    private final Settings.DoubleSetting speed;
    private final Settings.DoubleSetting gravity;
    private final Settings.DoubleSetting drag;

    private final SpearEffects effects = new SpearEffects();
    private final Map<UUID, Vec3d> velocity = new HashMap<>();
    private final List<BanditLine.Body> bandits = new ArrayList<>();
    private final List<Vec3d> banditVel = new ArrayList<>();
    private final List<Double> banditChest = new ArrayList<>();
    private final Map<UUID, Vec3d> banditSmooth = new HashMap<>();
    private @Nullable PlayerEntity target;
    private SpearBallistics.@Nullable Solution solution;
    private BanditLine.@Nullable Best lineBest;
    private RecallPlanner planner = new RecallPlanner(3000L, 2, 250L);
    private long plannerThrowMs;
    private int recallNow;
    private long recallFiredMs;
    private boolean recallSignalled;
    /** The helper idles (no scans) this long after the spear left the hand. */
    private static final long IDLE_MS = 8_000L;
    private long lastSpearMs;

    /** The aim assist was started with L. */
    private boolean aimOn;
    /** The mod holds the view right now (the mouse does not turn it). */
    private static volatile boolean aimLocked;
    private float lockedYaw = Float.NaN;
    private int aimCount;
    private static @Nullable SpearHelperModule instance;

    public SpearHelperModule(ControlService control) {
        super("spear_helper", "Spear Helper", Category.BANDIT, "Spear",
                "Bandit spear helper: aim assist on L (the mod looks at the best line of ore bandits), recall timing for F, "
                        + "a static shooter crosshair, throw effects.",
                GLFW.GLFW_KEY_L);
        this.control = control;
        crosshair = bool("crosshair", "Spear crosshair", true).group("Crosshair");
        hideVanilla = bool("hide_vanilla", "Replace vanilla crosshair", true).group("Crosshair");
        preset = choice("preset", "Style", CrosshairPreset.SHOOTER, CrosshairPreset::label).group("Crosshair");
        crosshairColor = color("crosshair_color", "Colour", 0x00FFD0).group("Crosshair");
        crosshairSize = integer("crosshair_size", "Size", 5, 2, 14, 1).group("Crosshair");
        crosshairGap = integer("crosshair_gap", "Gap", 3, 0, 12, 1).group("Crosshair");
        crosshairThick = integer("crosshair_thick", "Thickness", 1, 1, 4, 1).group("Crosshair");
        crosshairOutline = bool("crosshair_outline", "Outline", true).group("Crosshair");
        crosshairDot = bool("crosshair_dot", "Centre dot", true).group("Crosshair");
        anyBandit = bool("any_bandit", "Include bosses & special bandits", false)
                .description("Bandits are the players named bandit_xx_xxxxxx. Bosses are left out unless this is on.").group("Aim assist (L)");
        aimWindow = integer("aim_window", "Search window", 90, 20, 180, 5).suffix("°")
                .description("The best line is searched this far to each side of where you look.").group("Aim assist (L)");
        lineMargin = decimal("line_margin", "Corridor width (each side)", 0.45D, 0.1D, 1.5D, 0.05D).suffix(" b")
                .description("How far beside the line a bandit may stand and still be pierced.").group("Aim assist (L)");
        lineBar = bool("line_bar", "Show the line bar", true)
                .description("A bar under the crosshair: bandits as dots, the best direction as a bracket, the count.").group("Aim assist (L)");
        range = integer("range", "Range", 40, 8, 120, 1).suffix(" blocks").group("Aim assist (L)");
        recallDeadline = decimal("recall_deadline", "Deadline", 3.0D, 0.8D, 6.0D, 0.1D).suffix(" s").group("Recall signal (F)");
        recallMin = integer("recall_min", "Early signal from", 2, 1, 8, 1).suffix(" bandits").group("Recall signal (F)");
        recallLead = integer("recall_lead", "Lead time (ping + reaction)", 150, 0, 500, 10).suffix(" ms").group("Recall signal (F)");
        recallHud = bool("recall_hud", "Show the recall timer", true).group("Recall signal (F)");
        recallSound = bool("recall_sound", "Signal sound", true).group("Recall signal (F)");
        marker = bool("marker", "Sight point on the nearest enemy player", true)
                .description("The visor: where to aim at a player - lead for a moving target and the drop of the throw - plus distance and flight time.")
                .group("Sight point");
        sightStyle = choice("sight_style", "Sight style", SightStyle.RETICLE, SightStyle::label).group("Sight point");
        markerColor = color("marker_color", "Colour", 0xFF4D4D).group("Sight point");
        sightLine = bool("sight_line", "Line from the crosshair", false).group("Sight point");
        cone = integer("cone", "Search cone", 45, 5, 120, 1).suffix("°")
                .description("Only players this far from your view direction count.").group("Sight point");
        includeFriends = bool("include_friends", "Include friends & gang", false).group("Sight point");
        fx = bool("fx", "Throw effects", true)
                .description("The spear is the weapon: sparks as the bullet when it flies, a lightning bolt when it returns.").group("Throw effects");
        trail = bool("trail", "Bullet trail", true).group("Throw effects");
        trailStyle = choice("trail_style", "Trail style", SpearEffects.Trail.LIGHTNING, SpearEffects.Trail::label).group("Throw effects");
        returnBolt = bool("return_bolt", "Lightning on return", true).group("Throw effects");
        throwHand = bool("throw_burst", "Muzzle burst at the throw", true).group("Throw effects");
        returnFlash = bool("return_flash", "Flash on return", true).group("Throw effects");
        fxSound = bool("fx_sound", "Effect sounds", true).group("Throw effects");
        fxVolume = decimal("fx_volume", "Effect volume", 1.0D, 0.0D, 2.0D, 0.1D).group("Throw effects");
        speed = decimal("speed", "Throw speed", 2.5D, 0.5D, 6.0D, 0.05D).suffix(" b/tick")
                .description("Spear speed (trident: 2.5). Adjust if throws land short / long.").group("Ballistics");
        gravity = decimal("gravity", "Gravity", 0.05D, 0.0D, 0.2D, 0.005D).group("Ballistics");
        drag = decimal("drag", "Air drag", 0.99D, 0.9D, 1.0D, 0.005D).group("Ballistics");
        instance = this;
    }

    public static @Nullable SpearHelperModule get() {
        return instance;
    }

    /** The ballistics the player set: throw speed (blocks/tick), gravity, air drag. */
    static double[] ballistics() {
        SpearHelperModule m = instance;
        return m == null ? new double[]{2.5D, 0.05D, 0.99D} : new double[]{m.speed.get(), m.gravity.get(), m.drag.get()};
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public void register(EventBus bus) {
        bus.subscribe(CoreEvents.TickEnd.class, this, e -> tick(e.client()));
    }

    // ── Key L, lifecycle ─────────────────────────────────────────────────────

    /** L: starts / stops the aim assist. */
    @Override
    public boolean onKeybind() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || client.player == null || client.currentScreen != null) {
            return true;
        }
        aimOn = !aimOn;
        if (!aimOn) {
            releaseAim(client);
        }
        client.player.sendMessage(Text.literal("Spear aim assist " + (aimOn ? "on" : "off")), true);
        return true;
    }

    @Override
    protected void onDisable() {
        aimOn = false;
        releaseAim(MinecraftClient.getInstance());
        effects.clear();
        planner.reset();
    }

    /** True while the mod holds the view: the mouse must not turn it (mixin). */
    public static boolean aimLocked() {
        return aimLocked;
    }

    private void releaseAim(MinecraftClient client) {
        aimLocked = false;
        lockedYaw = Float.NaN;
        aimCount = 0;
        if (control.holds(this)) {
            control.release(this, client);
        }
    }

    // ── Held item ────────────────────────────────────────────────────────────

    /** True while the vanilla crosshair is replaced (mixin). */
    public static boolean replacesCrosshair() {
        SpearHelperModule m = instance;
        MinecraftClient client = MinecraftClient.getInstance();
        return m != null && m.enabled() && m.crosshair.on() && m.hideVanilla.on() && client.player != null
                && client.currentScreen == null && holdsSpear(client.player);
    }

    /** Dashboard preview of the chosen crosshair. */
    public static void preview(DrawContext c, int cx, int cy) {
        SpearHelperModule m = instance;
        if (m != null) {
            m.preset.get().draw(c, cx, cy, m.crosshairSize.value(), m.crosshairGap.value(), m.crosshairThick.value(),
                    0xFF000000 | m.crosshairColor.get(), m.crosshairOutline.on(), m.crosshairDot.on() || m.preset.get() == CrosshairPreset.DOT);
        }
    }

    /** Dashboard preview of the chosen sight point. */
    public static void previewSight(DrawContext c, int cx, int cy) {
        SpearHelperModule m = instance;
        if (m != null) {
            m.sightStyle.get().draw(c, cx, cy, 0xFF000000 | m.markerColor.get(), Util.getMeasuringTimeMs() / 1000.0D, 0.0F);
        }
    }

    static boolean holdsSpear(ClientPlayerEntity player) {
        return isSpear(player.getMainHandStack()) || isSpear(player.getOffHandStack());
    }

    static boolean isSpear(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return io.theprisons.core.cosmic.parse.SpearRule.isSpearPath(path);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!enabled() || player == null || client.world == null) {
            effects.clear();
            planner.reset();
            bandits.clear();
            lineBest = null;
            target = null;
            solution = null;
            velocity.clear();
            if (aimLocked || control.holds(this)) {
                releaseAim(client);
            }
            return;
        }
        // Everything below costs per entity and tick (hundreds of fake players at a bandit event): only while a spear was
        // in the hand lately, the aim assist is on or a throw is in the air.
        long nowMs = System.currentTimeMillis();
        if (holdsSpear(player)) {
            lastSpearMs = nowMs;
        }
        if (nowMs - lastSpearMs > IDLE_MS && !aimOn && !effects.active()) {
            bandits.clear();
            banditVel.clear();
            banditChest.clear();
            lineBest = null;
            target = null;
            solution = null;
            planner.reset();
            recallNow = 0;
            return;
        }
        boolean fxOn = fx.on();
        effects.tick(client, player, new SpearEffects.Options(fxOn && trail.on(), trailStyle.get(), fxOn && returnBolt.on(),
                fxOn && fxSound.on(), fxVolume.get().floatValue(), fxOn && throwHand.on()));
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player) {
                continue;
            }
            Vec3d moved = new Vec3d(other.getX() - other.lastX, other.getY() - other.lastY, other.getZ() - other.lastZ);
            velocity.merge(other.getUuid(), moved, (old, now) -> old.multiply(0.6D).add(now.multiply(0.4D)));
        }
        if (client.world.getTime() % 100 == 0) {
            velocity.keySet().removeIf(id -> client.world.getPlayerByUuid(id) == null);
        }
        collectBandits(client, player);
        updateRecall(client, player);
        boolean spear = holdsSpear(player);
        lineBest = spear && !bandits.isEmpty()
                ? BanditLine.best(player.getX(), player.getZ(), player.getYaw(), bandits, range.value(), lineMargin.get(), Math.min(60, aimWindow.value()))
                : null;
        if (spear && client.currentScreen == null) {
            pickTarget(client, player);
        } else {
            target = null;
            solution = null;
        }
        aim(client, player, spear);
    }

    private boolean isBandit(LivingEntity e) {
        return BanditScan.isBandit(e, anyBandit.on());
    }

    private void collectBandits(MinecraftClient client, ClientPlayerEntity player) {
        bandits.clear();
        banditVel.clear();
        banditChest.clear();
        double max = range.value() + 10.0D;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()
                    || living.squaredDistanceTo(player) > max * max || !isBandit(living)) {
                continue;
            }
            Vec3d moved = new Vec3d(living.getX() - living.lastX, 0.0D, living.getZ() - living.lastZ);
            Vec3d smooth = banditSmooth.merge(living.getUuid(), moved, (old, now) -> old.multiply(0.6D).add(now.multiply(0.4D)));
            bandits.add(new BanditLine.Body(living.getX(), living.getZ(), living.getWidth() / 2.0D));
            banditVel.add(smooth);
            banditChest.add(living.getY() + living.getHeight() * 0.65D);
        }
        if (client.world.getTime() % 200 == 0) {
            banditSmooth.clear();
        }
    }

    // ── Aim assist: the mod looks, the player moves ──────────────────────────

    private void aim(MinecraftClient client, ClientPlayerEntity player, boolean spear) {
        boolean want = aimOn && (spear || effects.active()) && client.currentScreen == null && player.isAlive() && !bandits.isEmpty();
        if (!want) {
            if (aimLocked || control.holds(this)) {
                releaseAim(client);
            }
            return;
        }
        if (!control.holds(this) && !control.acquire(this, "Spear Aim")) {
            aimOn = false;
            player.sendMessage(Text.literal("Spear aim assist off: another automation is running"), true);
            return;
        }
        double px = player.getX();
        double pz = player.getZ();
        double r = range.value();
        double margin = lineMargin.get();
        BanditLine.Best global = BanditLine.best(px, pz, player.getYaw(), bandits, r, margin, aimWindow.value());
        BanditLine.Best chosen = global;
        if (!Float.isNaN(lockedYaw)) {
            // Stay on the line already aimed at while it is as good as any other (no flicker between equal lines).
            BanditLine.Best local = BanditLine.best(px, pz, lockedYaw, bandits, r, margin, 6.0D);
            if (local.count() > 0 && local.count() >= global.count()) {
                chosen = local;
            }
        }
        if (chosen.count() == 0) {
            lockedYaw = Float.NaN;
            aimCount = 0;
            aimLocked = false;
            return;
        }
        lockedYaw = chosen.yaw();
        aimCount = chosen.count();
        int nearest = BanditLine.nearestOnRay(px, pz, chosen.yaw(), bandits, r, margin);
        float pitch = player.getPitch();
        if (nearest >= 0) {
            BanditLine.Body b = bandits.get(nearest);
            Vec3d eye = player.getEntityPos().add(0.0D, player.getStandingEyeHeight(), 0.0D);
            SpearBallistics.Solution s = SpearBallistics.solve(eye.x, eye.y, eye.z, b.x(), banditChest.get(nearest), b.z(), 0.0D, 0.0D, 0.0D,
                    speed.get(), gravity.get(), drag.get());
            if (s != null) {
                pitch = s.pitch();
            } else {
                double flat = Math.hypot(b.x() - eye.x, b.z() - eye.z);
                pitch = (float) -Math.toDegrees(Math.atan2(banditChest.get(nearest) - eye.y, flat));
            }
        }
        // The ore macro's human view motion (minimum-jerk movements, written at frame rate); the mouse is blocked meanwhile.
        control.rotation().request(RotationMode.TURNING, MathHelper.wrapDegrees(chosen.yaw()), MathHelper.clamp(pitch, -89.0F, 89.0F), 8.0F);
        aimLocked = true;
    }

    // ── Sight point (nearest enemy player) ───────────────────────────────────

    private void pickTarget(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d eye = eye(player, 1.0F);
        Vec3d look = Vec3d.fromPolar(player.getPitch(), player.getYaw());
        double bestCos = Math.cos(Math.toRadians(cone.value()));
        PlayerEntity best = null;
        double max = range.value();
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player || other.isSpectator() || !other.isAlive()) {
                continue;
            }
            if (!includeFriends.on() && friendly(other)) {
                continue;
            }
            Vec3d to = aimPoint(other, 1.0F).subtract(eye);
            double dist = to.length();
            if (dist < 1.0D || dist > max) {
                continue;
            }
            double cos = to.dotProduct(look) / dist;
            if (cos > bestCos) {
                bestCos = cos;
                best = other;
            }
        }
        target = best;
        solution = null;
        if (best != null) {
            Vec3d p = aimPoint(best, 1.0F);
            Vec3d v = velocity.getOrDefault(best.getUuid(), Vec3d.ZERO);
            solution = SpearBallistics.solve(eye.x, eye.y, eye.z, p.x, p.y, p.z, v.x, best.isOnGround() ? 0.0D : v.y, v.z,
                    speed.get(), gravity.get(), drag.get());
        }
    }

    private static boolean friendly(PlayerEntity other) {
        FriendsModule friends = FriendsModule.get();
        if (friends == null) {
            return false;
        }
        String name = other.getGameProfile().name();
        return name != null && friends.relation(name, null) != FriendList.Relation.NONE;
    }

    private static Vec3d eye(ClientPlayerEntity player, float tp) {
        return player.getLerpedPos(tp).add(0.0D, player.getStandingEyeHeight(), 0.0D);
    }

    private static Vec3d aimPoint(PlayerEntity other, float tp) {
        return other.getLerpedPos(tp).add(0.0D, other.getHeight() * 0.65D, 0.0D);
    }

    // ── Recall signal: the best moment for F (the player presses it) ─────────

    private void updateRecall(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d sp = effects.spearPos();
        if (sp == null) {
            planner.reset();
            plannerThrowMs = 0L;
            recallNow = 0;
            return;
        }
        long now = Util.getMeasuringTimeMs();
        if (effects.throwStartMs() != plannerThrowMs) {
            plannerThrowMs = effects.throwStartMs();
            planner = new RecallPlanner(Math.round(recallDeadline.get() * 1000.0D), recallMin.value(), 250L);
            planner.start(now);
            recallSignalled = false;
        }
        double margin = lineMargin.get();
        double px = player.getX();
        double pz = player.getZ();
        recallNow = BanditLine.countSegment(sp.x, sp.z, px, pz, bandits, margin);
        double k = recallLead.value() / 50.0D;
        Vec3d pv = player.getVelocity();
        Vec3d sv = effects.spearVel();
        List<BanditLine.Body> ahead = new ArrayList<>(bandits.size());
        for (int i = 0; i < bandits.size(); i++) {
            BanditLine.Body b = bandits.get(i);
            Vec3d v = banditVel.get(i);
            ahead.add(new BanditLine.Body(b.x() + v.x * k, b.z() + v.z * k, b.radius()));
        }
        int predicted = BanditLine.countSegment(sp.x + sv.x * k, sp.z + sv.z * k, px + pv.x * k, pz + pv.z * k, ahead, margin);
        double reach = Math.hypot(sp.x - px, sp.z - pz) + 2.0D;
        int candidates = 0;
        for (BanditLine.Body b : bandits) {
            if (Math.hypot(b.x() - px, b.z() - pz) <= reach) {
                candidates++;
            }
        }
        if (planner.update(now, recallNow, predicted, candidates)) {
            recallFiredMs = now;
            if (recallSound.on() && !recallSignalled) {
                recallSignalled = true;
                client.world.playSoundClient(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 0.7F, 1.6F);
            }
        }
    }

    // ── Frame: drawing only (the view is never touched here) ─────────────────

    public void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (!enabled() || player == null || client.currentScreen != null || client.options.hudHidden) {
            return;
        }
        int cx = client.getWindow().getScaledWidth() / 2;
        int cy = client.getWindow().getScaledHeight() / 2;
        if (fx.on() && returnFlash.on()) {
            float z = effects.zap();
            if (z > 0.01F) {
                int w = client.getWindow().getScaledWidth();
                int h = client.getWindow().getScaledHeight();
                int a = (int) (z * 70);
                context.fillGradient(0, 0, w, h, (a << 24) | 0x66CCFF, (a / 3 << 24) | 0xFFFFFF);
            }
        }
        if (!holdsSpear(player) && !effects.active()) {
            return;
        }
        float tp = counter.getTickProgress(true);
        if (holdsSpear(player) && crosshair.on()) {
            // Static: always exactly the screen centre, never changed by movement, charging or throws.
            preset.get().draw(context, cx, cy, crosshairSize.value(), crosshairGap.value(), crosshairThick.value(),
                    0xFF000000 | crosshairColor.get(), crosshairOutline.on(), crosshairDot.on() || preset.get() == CrosshairPreset.DOT);
        }
        if (holdsSpear(player) && marker.on() && target != null && solution != null) {
            drawMarker(context, client, player, cx, cy, tp);
        }
        drawLineBar(context, client, player, cx, cy);
    }

    private void drawMarker(DrawContext c, MinecraftClient client, ClientPlayerEntity player, int cx, int cy, float tp) {
        SpearBallistics.Solution s = solution;
        Vec3d dir = Vec3d.fromPolar(s.pitch(), s.yaw());
        Vec3d look = Vec3d.fromPolar(player.getPitch(), player.getYaw());
        Vec3d right = look.crossProduct(new Vec3d(0.0D, 1.0D, 0.0D)).normalize();
        Vec3d up = right.crossProduct(look).normalize();
        double fwd = dir.dotProduct(look);
        if (fwd <= 0.05D) {
            return;
        }
        double tanHalf = Math.tan(Math.toRadians(client.options.getFov().getValue() / 2.0D));
        int h = client.getWindow().getScaledHeight();
        int w = client.getWindow().getScaledWidth();
        double aspect = (double) w / h;
        int mx = cx + (int) Math.round(dir.dotProduct(right) / fwd / (tanHalf * aspect) * cx);
        int my = cy - (int) Math.round(dir.dotProduct(up) / fwd / tanHalf * cy);
        mx = MathHelper.clamp(mx, 8, w - 8);
        my = MathHelper.clamp(my, 8, h - 8);
        if (Math.hypot(mx - cx, my - cy) < 14.0D) {
            return; // on the crosshair already: nothing to draw around it
        }
        int col = 0xFF000000 | markerColor.get();
        if (sightLine.on()) {
            int steps = (int) Math.hypot(mx - cx, my - cy) / 4;
            for (int i = 1; i < steps; i++) {
                int lx = cx + (mx - cx) * i / steps;
                int ly = cy + (my - cy) * i / steps;
                c.fill(lx, ly, lx + 1, ly + 1, (0x66 << 24) | (markerColor.get() & 0xFFFFFF));
            }
        }
        sightStyle.get().draw(c, mx, my, col, Util.getMeasuringTimeMs() / 1000.0D, 0.0F);
        double dist = eye(player, tp).distanceTo(aimPoint(target, tp));
        String text = target.getGameProfile().name() + "  " + String.format(Locale.ROOT, "%.1fm  %.2fs", dist, s.ticks() / 20.0D);
        c.drawTextWithShadow(client.textRenderer, text, mx - client.textRenderer.getWidth(text) / 2, my + 11, col);
    }

    private void drawLineBar(DrawContext c, MinecraftClient client, ClientPlayerEntity player, int cx, int cy) {
        int y = cy + 26;
        int half = 60;
        double window = 45.0D;
        if (aimOn) {
            String text = aimLocked ? "AIM  x" + aimCount : "AIM  (no bandit)";
            c.drawTextWithShadow(client.textRenderer, text, cx - client.textRenderer.getWidth(text) / 2, cy - 38, aimLocked ? 0xFF4DE0FF : 0xAAFFFFFF);
        }
        if (lineBar.on() && !bandits.isEmpty()) {
            c.fill(cx - half, y, cx + half + 1, y + 1, 0x66FFFFFF);
            c.fill(cx, y - 2, cx + 1, y + 3, 0xAAFFFFFF);
            for (BanditLine.Body b : bandits) {
                double bearing = Math.toDegrees(Math.atan2(-(b.x() - player.getX()), b.z() - player.getZ()));
                double off = MathHelper.wrapDegrees(bearing - player.getYaw());
                if (Math.abs(off) <= window && Math.hypot(b.x() - player.getX(), b.z() - player.getZ()) <= range.value()) {
                    int dx = (int) Math.round(off / window * half);
                    c.fill(cx + dx - 1, y - 2, cx + dx + 2, y + 3, 0xFFFF4D4D);
                }
            }
            BanditLine.Best best = aimLocked && !Float.isNaN(lockedYaw)
                    ? new BanditLine.Best(lockedYaw, aimCount, 0) : lineBest;
            if (best != null && best.count() >= 2) {
                double off = MathHelper.wrapDegrees(best.yaw() - player.getYaw());
                int dx = (int) Math.round(MathHelper.clamp(off / window, -1.0D, 1.0D) * half);
                int col = 0xFFFFD34D;
                c.fill(cx + dx - 3, y - 5, cx + dx + 4, y - 4, col);
                c.fill(cx + dx - 3, y + 5, cx + dx + 4, y + 6, col);
                c.fill(cx + dx - 3, y - 5, cx + dx - 2, y + 6, col);
                c.fill(cx + dx + 3, y - 5, cx + dx + 4, y + 6, col);
                String text = "x" + best.count();
                c.drawTextWithShadow(client.textRenderer, text, cx + dx - client.textRenderer.getWidth(text) / 2, y + 8, col);
            }
        }
        if (recallHud.on() && effects.active()) {
            long now = Util.getMeasuringTimeMs();
            double total = Math.max(1.0D, recallDeadline.get() * 1000.0D);
            double left = Math.max(0.0D, total - planner.elapsed(now));
            int w = 80;
            int ry = y + 22;
            boolean signal = recallFiredMs >= plannerThrowMs && recallFiredMs != 0L && now - recallFiredMs < 1500L;
            c.fill(cx - w / 2, ry, cx + w / 2, ry + 3, 0x55FFFFFF);
            int fill = (int) Math.round(w * Math.min(1.0D, planner.elapsed(now) / total));
            c.fill(cx - w / 2, ry, cx - w / 2 + fill, ry + 3, signal ? 0xFFFFD34D : 0xFF4DB8FF);
            String text = signal ? "PRESS F NOW" : String.format(Locale.ROOT, "recall %.1fs  on the way back: %d", left / 1000.0D, recallNow);
            c.drawTextWithShadow(client.textRenderer, text, cx - client.textRenderer.getWidth(text) / 2, ry + 6, signal ? 0xFFFFD34D : 0xFFFFFFFF);
        }
    }
}
