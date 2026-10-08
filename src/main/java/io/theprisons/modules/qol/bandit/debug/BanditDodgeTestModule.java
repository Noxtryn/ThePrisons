package io.theprisons.modules.qol.bandit.debug;

import com.mojang.brigadier.Command;
import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.control.IntentPriority;
import io.theprisons.core.control.MovementIntent;
import io.theprisons.core.control.RotationIntent;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.parse.BanditClassifier;
import io.theprisons.core.cosmic.sense.InputSensor;
import io.theprisons.core.cosmic.state.CosmicStateService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.hud.HudLine;
import io.theprisons.core.module.AutomationModule;
import io.theprisons.core.module.Category;
import io.theprisons.core.render.Overlay;
import io.theprisons.core.world.LiveWorldView;
import io.theprisons.core.world.WorldCache;
import io.theprisons.modules.qol.bandit.WorldTerrain;
import io.theprisons.modules.qol.bandit.dodge.BanditDodgePlanner;
import io.theprisons.modules.qol.bandit.dodge.DodgeAction;
import io.theprisons.modules.qol.bandit.dodge.DodgeBandit;
import io.theprisons.modules.qol.bandit.dodge.DodgeCandidate;
import io.theprisons.modules.qol.bandit.dodge.DodgeConfig;
import io.theprisons.modules.qol.bandit.dodge.DodgeDrive;
import io.theprisons.modules.qol.bandit.dodge.DodgeDecision;
import io.theprisons.modules.qol.bandit.dodge.DodgeInputs;
import io.theprisons.modules.qol.bandit.dodge.SpearAreaEvaluator;
import io.theprisons.modules.qol.bandit.dodge.SpearAreaState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TEMPORARY developer-only test: only MOVES through a bandit area and keeps away from bandits. No spear, no aiming, no attack, no target. It senses
 * (state store + live entities), builds a {@link DodgeInputs}, asks the pure {@link BanditDodgePlanner}, and submits a {@link MovementIntent}.
 * The planner never touches keys; the keys come from the world direction through {@link Geo#keysFor} with the CURRENT view yaw. The view follows
 * the movement heading slowly, or stays forward - it never aims at a bandit. Manual input stops it at once.
 *
 * <p>Only registered and usable with {@code -Dtheprisons.dev=true} ({@code FeatureProfile.DEV}); not part of any shipped profile.
 */
public final class BanditDodgeTestModule extends AutomationModule {
    private static final int MAX_LIVE = 24;

    private final DodgeConfig cfg = new DodgeConfig();
    private final BanditDodgePlanner planner = new BanditDodgePlanner(cfg);
    private final Map<String, Vec3d> smooth = new HashMap<>();
    private volatile DodgeDecision last;
    private volatile List<double[]> bandits = List.of();   // x, z for the overlay
    private double px;
    private double py;
    private double pz;
    // log-on-change memory
    private DodgeAction lastAction;
    private boolean lastBreach;
    private boolean lastWindow;
    private boolean lastJump;
    private SpearAreaState lastArea;
    private int lastHeadingBucket = Integer.MIN_VALUE;
    private int lastOscillations;
    private boolean lastStuck;
    private long startedMs;

    public BanditDodgeTestModule(ControlService control, WorldCache world) {
        super("bandit_dodge_test", "Bandit Dodge Test", Category.BANDIT, "Dev",
                "DEVELOPER TEST: runs through the bandit area and keeps away from every bandit. No spear, no aim, no attack. "
                        + "Stops on any manual input.", GLFW.GLFW_KEY_UNKNOWN, control, world, 24, 8);
    }

    /** {@code /prisons banditdodgetest} toggles the test (developer mode only). */
    public void register(ThePrisonsCore core) {
        core.commands().contribute(root -> root.then(ClientCommandManager.literal("banditdodgetest").executes(ctx -> {
            core.modules().toggle(this);
            return Command.SINGLE_SUCCESS;
        })));
    }

    @Override
    protected void onStart(MinecraftClient client) {
        planner.reset();
        smooth.clear();
        last = null;
        lastAction = null;
        lastBreach = false;
        lastWindow = false;
        lastJump = false;
        lastArea = null;
        lastHeadingBucket = Integer.MIN_VALUE;
        lastOscillations = 0;
        lastStuck = false;
        startedMs = System.currentTimeMillis();
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        cosmic.interest(this, cfg.minDistance + cfg.warningBand + cfg.lookahead + 20.0D, 1);
        cosmic.captureExtra(this, this::captureExtras);
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
        on(CoreEvents.WorldRender.class, this::render);
        ThePrisonsClient.LOGGER.info("[BanditDodge] start: min distance {}, warning band {}, lookahead {}, {} directions (NO spear, NO aim)",
                cfg.minDistance, cfg.warningBand, cfg.lookahead, cfg.directions);
    }

    @Override
    protected void onStop(MinecraftClient client) {
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        cosmic.release(this);
        cosmic.removeCaptureExtra(this);
        last = null;
        bandits = List.of();
        ThePrisonsClient.LOGGER.info("[BanditDodge] stopped after {} s", (System.currentTimeMillis() - startedMs) / 1000L);
    }

    // ── Tick: sense → plan → intent ──────────────────────────────────────────

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !player.isAlive()) {
            return;
        }
        if (client.currentScreen != null) {
            control.submit(MovementIntent.none(IntentPriority.EMERGENCY, "screen open"));
            return;
        }
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        CosmicContextSnapshot snap = cosmic.latest();
        if (snap == null || !snap.player().present()) {
            control.submit(MovementIntent.none(IntentPriority.PATHFINDING, "waiting for the first sample"));
            return;
        }
        if (snap.player().manualInput() || InputSensor.anyMovementPhysical(client)) {
            ThePrisonsClient.LOGGER.info("[BanditDodge] manual input: stopping");
            disableSelf("Manual input.");
            return;
        }
        long now = System.currentTimeMillis();
        px = player.getX();
        py = player.getY();
        pz = player.getZ();

        List<DodgeBandit> list = new ArrayList<>();
        List<double[]> marks = new ArrayList<>();
        Map<String, Vec3d> next = new HashMap<>();
        int liveCount = 0;
        double range = cfg.minDistance + cfg.warningBand + cfg.lookahead + 12.0D;
        for (CosmicContextSnapshot.ClassifiedEntity ce : snap.combat().entities()) {
            Raw.Entity r = ce.entity();
            if (r.player()) {
                continue;
            }
            BanditClassifier.Classification c = BanditClassifier.classify(r.name(), (r.name() + " " + r.shown()).trim());
            if (!BanditClassifier.isBandit(c, true)) {
                continue;
            }
            double x = r.x();
            double z = r.z();
            if (Math.hypot(x - px, z - pz) > range) {
                continue;
            }
            String id = c.named() ? r.name() : r.name() + "#" + r.id();
            double vx = 0.0D;
            double vz = 0.0D;
            Entity entity = liveCount < MAX_LIVE ? client.world.getEntityById(r.id()) : null;
            if (entity instanceof LivingEntity le && le.isAlive()) {
                liveCount++;
                x = le.getX();
                z = le.getZ();
                Vec3d moved = new Vec3d((le.getX() - le.lastX) * 20.0D, 0.0D, (le.getZ() - le.lastZ) * 20.0D); // blocks per second
                Vec3d old = smooth.get(id);
                Vec3d s = old == null ? moved : old.multiply(0.6D).add(moved.multiply(0.4D));
                next.put(id, s);
                vx = s.x;
                vz = s.z;
            } else if (entity != null) {
                continue; // dead / removed
            }
            list.add(new DodgeBandit(id, x, z, vx, vz));
            marks.add(new double[]{x, z});
        }
        smooth.clear();
        smooth.putAll(next);
        bandits = marks;

        SpearAreaState area = SpearAreaEvaluator.evaluate(ThePrisonsCore.get().cosmic().model(), cosmic.zone());
        DodgeInputs in = new DodgeInputs(now, px, py, pz, (px - player.lastX) * 20.0D, (pz - player.lastZ) * 20.0D, player.getYaw(),
                player.isOnGround(), list, new WorldTerrain(new LiveWorldView(client.world, world.classifier())), area);
        DodgeDecision d = planner.plan(in);
        last = d;
        logChanges(d);

        var keys = DodgeDrive.keys(d.dirX(), d.dirZ(), player.getYaw(), d.jump() && player.isOnGround());
        IntentPriority priority = d.breach() || d.stuck() ? IntentPriority.EMERGENCY : IntentPriority.PATHFINDING;
        // One stable source name: a changing name looked like a new owner every tick in the control log.
        control.submit(new MovementIntent(priority, "dodge", keys));
        // The view follows the movement heading (never a bandit) with a dead zone; it is not rotated while the heading is steady.
        float yaw = DodgeDrive.nextYaw(player.getYaw(), d.dirX(), d.dirZ());
        if (yaw != player.getYaw()) {
            control.submit(RotationIntent.following(IntentPriority.PATHFINDING, "dodge", MathHelper.wrapDegrees(yaw), 8.0F, 12.0F, 12.0F));
        }
    }

    private void logChanges(DodgeDecision d) {
        int bucket = (int) Math.round(d.headingDegrees() / 22.5D);
        boolean headingChanged = lastHeadingBucket != Integer.MIN_VALUE && bucket != lastHeadingBucket;
        boolean changed = headingChanged || d.action() != lastAction || d.breach() != lastBreach || d.aimWindowOpen() != lastWindow
                || d.jump() && !lastJump || d.area() != lastArea || d.oscillations() != lastOscillations || d.stuck() && !lastStuck;
        if (changed) {
            long now = System.currentTimeMillis();
            ThePrisonsClient.LOGGER.info("[BanditDodge] t={}ms {} heading {}° (was {}°, held {}ms) score kept {} best {} nearest {} projected {} threat {} free {} "
                            + "near {} jump {} sprint {} window {} ({}) area {} owners keys {} view {} | {}",
                    now - startedMs, d.action(), Math.round(d.headingDegrees()), fmt(planner.previousHeadingDegrees()), planner.headingAgeMs(now),
                    fmt(planner.lastCurrentScore()), fmt(planner.lastBestScore()), fmt(d.nearestBandit()), fmt(d.projectedNearestBandit()),
                    fmt(d.threatScore()), fmt(d.freeDistance()), d.nearbyCount(), d.jump(), d.sprint(), d.aimWindowOpen() ? "OPEN" : "CLOSED",
                    d.aimWindowTicks(), d.area(), control.input().winnerSource().isEmpty() ? "-" : control.input().winnerSource(),
                    control.rotationWinner(), d.reason());
        }
        lastHeadingBucket = bucket;
        lastAction = d.action();
        lastBreach = d.breach();
        lastWindow = d.aimWindowOpen();
        lastJump = d.jump();
        lastArea = d.area();
        lastOscillations = d.oscillations();
        lastStuck = d.stuck();
    }

    // ── HUD / capture / debug overlay ───────────────────────────────────────

    @Override
    public void collectHud(List<HudLine> out) {
        DodgeDecision d = last;
        if (!enabled() || d == null) {
            return;
        }
        out.add(new HudLine("DODGE TEST", "dev · no spear · no aim", 0xFFFFC14D));
        out.add(new HudLine("Nearest", fmt(d.nearestBandit()) + " · near " + d.nearbyCount() + " · min " + fmt(cfg.minDistance), d.breach() ? 0xFFFF6B6B : 0xFF65F59B));
        out.add(new HudLine("Threat", fmt(d.threatScore()) + " · projected " + fmt(d.projectedNearestBandit()), 0xFF9AA3B8));
        out.add(new HudLine("Move", Math.round(d.headingDegrees()) + "° · free " + fmt(d.freeDistance()) + " · " + hudAction(d), 0xFF4DD8FF));
        out.add(new HudLine("Aim window", (d.aimWindowOpen() ? "OPEN " : "CLOSED ") + d.aimWindowTicks() + " ticks", d.aimWindowOpen() ? 0xFF65F59B : 0xFFFF6B6B));
        out.add(new HudLine("Spear area", d.area().name(), 0xFF9AA3B8));
        out.add(new HudLine("Why", d.reason(), 0xFF9AA3B8));
    }

    private static String hudAction(DodgeDecision d) {
        return switch (d.action()) {
            case CONTINUE, RUN -> "RUN";
            case DIAGONAL, STRAFE_LEFT, STRAFE_RIGHT -> "GAP";
            case JUMP_FORWARD, JUMP_LEFT, JUMP_RIGHT -> "JUMP";
            case HARD_EVADE -> "EVADE";
            case RECOVER -> "RECOVER";
        };
    }

    private Map<String, String> captureExtras() {
        Map<String, String> m = new HashMap<>();
        DodgeDecision d = last;
        m.put("banditDodge.enabled", Boolean.toString(enabled()));
        if (d == null) {
            return m;
        }
        m.put("banditDodge.heading", fmt(d.headingDegrees()));
        m.put("banditDodge.score", fmt(d.score()));
        m.put("banditDodge.freeDistance", fmt(d.freeDistance()));
        m.put("banditDodge.nearestBandit", fmt(d.nearestBandit()));
        m.put("banditDodge.projectedNearest", fmt(d.projectedNearestBandit()));
        m.put("banditDodge.threatScore", fmt(d.threatScore()));
        m.put("banditDodge.nearbyCount", Integer.toString(d.nearbyCount()));
        m.put("banditDodge.minDistance", fmt(cfg.minDistance));
        m.put("banditDodge.aimWindow", d.aimWindowOpen() ? "OPEN" : "CLOSED");
        m.put("banditDodge.aimWindowTicks", Integer.toString(d.aimWindowTicks()));
        m.put("banditDodge.jump", Boolean.toString(d.jump()));
        m.put("banditDodge.reason", d.reason());
        m.put("banditDodge.spearArea", d.area().name());
        for (DodgeCandidate c : d.candidates()) {
            m.put("banditDodge.candidate." + c.index(), String.format(Locale.ROOT, "heading=%.0f free=%.1f nearestProjected=%.1f threat=%.1f score=%.1f blocked=%s",
                    c.headingDegrees(), c.free(), c.nearest(), c.threat(), c.score(), c.blocked().isEmpty() ? "-" : c.blocked()));
        }
        return m;
    }

    /** Developer-only: minimum-distance circles, the chosen vector, every candidate (red = unsafe / blocked, green = safe). */
    private void render(CoreEvents.WorldRender event) {
        DodgeDecision d = last;
        if (!enabled() || d == null) {
            return;
        }
        Overlay o = Overlay.begin(event.context());
        if (o == null) {
            return;
        }
        double y = py + 0.2D;
        for (double[] b : bandits) {
            circle(o, b[0], y, b[1], cfg.minDistance, 0xFFFF4040);
            circle(o, b[0], y, b[1], cfg.minDistance + cfg.warningBand, 0x66FFC14D);
        }
        for (DodgeCandidate c : d.candidates()) {
            if (c.index() < 0) {
                continue;
            }
            double len = Math.min(c.free(), cfg.lookahead);
            o.line(px, y, pz, px + c.dirX() * len, y, pz + c.dirZ() * len, c.safe() ? 0x8865F59B : 0x88FF4040, 1.0F);
        }
        o.line(px, y + 0.3D, pz, px + d.dirX() * 6.0D, y + 0.3D, pz + d.dirZ() * 6.0D, 0xFF4DD8FF, 3.0F);
        o.label(px + d.dirX() * 6.0D, y + 0.8D, pz + d.dirZ() * 6.0D, d.action().name(), 0xFF4DD8FF);
    }

    private static void circle(Overlay o, double cx, double y, double cz, double radius, int color) {
        int steps = 32;
        double lx = cx + radius;
        double lz = cz;
        for (int i = 1; i <= steps; i++) {
            double a = i * Math.PI * 2.0D / steps;
            double nx = cx + Math.cos(a) * radius;
            double nz = cz + Math.sin(a) * radius;
            o.line(lx, y, lz, nx, y, nz, color, 1.0F);
            lx = nx;
            lz = nz;
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }
}
