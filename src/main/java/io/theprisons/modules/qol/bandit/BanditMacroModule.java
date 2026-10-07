package io.theprisons.modules.qol.bandit;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.control.InputController;
import io.theprisons.core.control.RotationMath;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.hud.HudLine;
import io.theprisons.core.module.AutomationModule;
import io.theprisons.core.module.Category;
import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.PathStraightener;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.setting.Settings;
import io.theprisons.core.world.WorldCache;
import io.theprisons.core.world.WorldSnapshot;
import io.theprisons.modules.mining.ore.LaneDriver;
import io.theprisons.modules.mining.ore.route.Route;
import io.theprisons.modules.mining.ore.route.RouteStore;
import io.theprisons.modules.qol.players.FriendList;
import io.theprisons.modules.qol.players.FriendsModule;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The bandit macro (spear mode): one state machine ({@link BanditFsm}, states in {@link BanditState}) that hunts Cosmic's
 * bandits with the spear. It builds on what is already there instead of a second system:
 * <ul>
 *     <li>bandit detection {@link BanditScan}, the spear item / projectile detection of the spear helper,</li>
 *     <li>the throw maths {@link SpearBallistics} (lead + drop) with the helper's ballistics settings,</li>
 *     <li>the recall timing {@link RecallPlanner}, now with the F input sent by the macro ({@code SWAP_ITEM_WITH_OFFHAND}),</li>
 *     <li>the ore macro's human view motion (control lease, {@code RotationController}) and its path finder + driver
 *     ({@link PathSearch}, {@link LaneDriver}) for the positioning,</li>
 *     <li>friends / gang from {@link FriendsModule}.</li>
 * </ul>
 * Safety comes first: another real player coming near ends an attack (retreat), a crowd or critical health ends the run
 * (after the spear is recalled); no state may last longer than its timeout.
 */
public final class BanditMacroModule extends AutomationModule {
    /** The only mode so far (the setting is there for the dashboard and for later modes). */
    private enum Mode { SPEAR }

    private static final int PATH_NODES = 6000;
    private static final int PATH_RADIUS = 48;
    /** Retreats within {@value #RETREAT_WINDOW_MS} ms that end the run. */
    private static final int MAX_RETREATS = 3;
    private static final long RETREAT_WINDOW_MS = 90_000L;
    private static final double CRITICAL_HEALTH = 6.0D;
    /** One attack (positioning + aiming + waiting for a clear line) may take this long, then the bandit is skipped. */
    private static final long ATTEMPT_MS = 30_000L;

    private final Settings.EnumSetting<Mode> mode;
    private final Settings.BoolSetting anyBandit;
    private final Settings.IntSetting targetRange;
    private final Settings.IntSetting minSafe;
    private final Settings.IntSetting maxCombat;
    private final Settings.ChoiceSetting patrolRoute;
    private final Settings.BoolSetting positioning;
    private final Settings.IntSetting stickiness;
    private final Settings.IntSetting aimSpeed;
    private final Settings.BoolSetting pierce;
    private final Settings.IntSetting chargeTicks;
    private final Settings.DoubleSetting lineMargin;
    private final Settings.BoolSetting autoRecall;
    private final Settings.IntSetting recallThreshold;
    private final Settings.DoubleSetting recallDeadline;
    private final Settings.IntSetting recallLead;
    private final Settings.BoolSetting stopNearPlayer;
    private final Settings.IntSetting safetyRadius;
    private final Settings.BoolSetting includeFriends;
    private final Settings.IntSetting searchTimeout;
    private final Settings.IntSetting movementTimeout;
    private final Settings.IntSetting throwTimeout;
    private final Settings.IntSetting returnTimeout;
    private final Settings.BoolSetting debug;

    private final RouteStore routes;
    private final Random random = new Random();
    private final LaneDriver driver = new LaneDriver(3.0D);
    private BanditFsm fsm = newFsm();

    private final Map<String, LivingEntity> bandits = new HashMap<>();
    private final List<BanditTargeting.Candidate> candidates = new ArrayList<>();
    private final Map<String, Vec3d> smooth = new HashMap<>();
    private final Map<String, Long> blacklist = new HashMap<>();
    private final Map<UUID, Double> lastDistance = new HashMap<>();
    private final Map<UUID, Double> closing = new HashMap<>();
    private final List<PlayerEntity> players = new ArrayList<>();
    private BanditDanger.Verdict verdict = new BanditDanger.Verdict(BanditDanger.Level.NONE, "", "");

    private @Nullable String targetId;
    private long targetSinceMs;
    private SpearState spearState = SpearState.MISSING;
    private RecallPlanner planner = new RecallPlanner(3000L, 2, 250L);
    private int recallsSent;
    private int throwsDone;
    private int kills;
    /** When the attack on the current target began: moves between aiming / positioning / ready must not go on forever. */
    private long attemptStartMs;
    private int failedThrows;
    private long missingSinceMs;
    private int aimedTicks;
    /** The one click of a "hold 0" throw was sent. */
    private boolean clicked;
    private int reactionTicks;
    private int cooldownTicks;
    private int tickCount;
    private int stalledTicks;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private @Nullable Vec3d lastSpear;
    private int stuckTicks;
    private String pendingStop = "";
    private final List<Long> retreats = new ArrayList<>();
    /** What the running path search is for. */
    private enum PathFor { ATTACK, PATROL }

    private PathFor pathFor = PathFor.ATTACK;
    private List<int[]> patrol = List.of();
    private int patrolIndex;
    private int patrolFailures;
    private boolean pathBusy;
    private int pathFailures;
    private long pathAtMs;
    private double pathTargetX = Double.NaN;
    private double pathTargetZ = Double.NaN;
    private SpearBallistics.@Nullable Solution solution;
    private String lastDetail = "";
    private @Nullable String hudRecall = "NOT NEEDED";

    public BanditMacroModule(ControlService control, WorldCache world, RouteStore routes) {
        super("bandit_macro", "Bandit Macro", Category.BANDIT, "Macro",
                "Hunts bandits with the spear on its own: picks a target, walks into range, aims with the human view motion "
                        + "(lead and drop), throws, recalls at the best moment and starts again. Backs off or stops when another "
                        + "player comes near.",
                GLFW.GLFW_KEY_J, control, world, 48, 16);
        mode = choice("mode", "Mode", Mode.SPEAR, m -> "Spear").group("Macro");
        anyBandit = bool("any_bandit", "Include bosses & special bandits", false).group("Macro");
        targetRange = integer("target_range", "Target range", 60, 10, 120, 1).suffix(" blocks")
                .description("Bandits farther away than this are ignored.").group("Targeting");
        minSafe = integer("min_safe", "Minimum safe distance", 6, 2, 20, 1).suffix(" blocks")
                .description("Closer than this the macro steps back before it throws.").group("Targeting");
        maxCombat = integer("max_combat", "Maximum combat distance", 34, 10, 80, 1).suffix(" blocks")
                .description("Farther than this the macro walks closer first.").group("Targeting");
        patrolRoute = add(new Settings.ChoiceSetting("patrol_route", "Patrol route", "Off (stay here)",
                () -> routes.options(MinecraftClient.getInstance())))
                .description("A recorded route (Controls: record route) walked from waypoint to waypoint while no bandit is in "
                        + "reach. Off: the macro waits where you stand.").group("Targeting");
        this.routes = routes;
        positioning = bool("positioning", "Walk into range", true)
                .description("Off: it only throws from where you stand.").group("Targeting");
        stickiness = integer("stickiness", "Target stickiness", 25, 0, 80, 1)
                .description("How strongly it stays with its bandit instead of switching to a slightly better one.").group("Targeting");
        aimSpeed = integer("aim_speed", "Human aim speed", 5, 1, 10, 1)
                .description("1 = slow and careful, 10 = quick. The view always moves like a hand, never snaps.").group("Combat");
        pierce = bool("pierce", "Aim through more bandits", true)
                .description("Turns the throw a few degrees so the spear also goes through other bandits behind or beside "
                        + "the target (it still hits the target).").group("Combat");
        chargeTicks = integer("charge_ticks", "Hold the throw", 12, 0, 40, 1).suffix(" ticks")
                .description("How long the use key is held (a trident-like spear needs about 10). 0 = one click. It lets go "
                        + "as soon as the spear is away.").group("Combat");
        lineMargin = decimal("line_margin", "Corridor width (each side)", 0.45D, 0.1D, 1.5D, 0.05D).suffix(" b")
                .description("How far beside the spear's way back a bandit may stand and still be hit.").group("Combat");
        throwTimeout = integer("throw_timeout", "Throw timeout", 3, 1, 10, 1).suffix(" s").group("Combat");
        returnTimeout = integer("return_timeout", "Return timeout", 9, 3, 30, 1).suffix(" s").group("Combat");
        autoRecall = bool("auto_recall", "Auto recall", true)
                .description("The macro presses F (swap hands) itself at the moment the recall timer picks.").group("Recall");
        recallThreshold = integer("recall_threshold", "Recall threshold", 2, 1, 8, 1).suffix(" bandits")
                .description("Recall early when this many bandits are on the way back.").group("Recall");
        recallDeadline = decimal("recall_deadline", "Recall at the latest after", 3.0D, 0.8D, 6.0D, 0.1D).suffix(" s").group("Recall");
        recallLead = integer("recall_lead", "Lead time (ping + reaction)", 150, 0, 500, 10).suffix(" ms").group("Recall");
        stopNearPlayer = bool("player_detection", "Player detection", true)
                .description("Other real players (not friends / gang) are a PvP danger: the macro backs off and stops if they stay.")
                .group("Safety");
        safetyRadius = integer("safety_radius", "Player safety radius", 30, 8, 80, 1).suffix(" blocks").group("Safety");
        includeFriends = bool("stop_for_friends", "React to friends & gang too", false).group("Safety");
        searchTimeout = integer("search_timeout", "Search timeout", 120, 20, 600, 5).suffix(" s")
                .description("No bandit to fight for this long: the macro stops.").group("Safety");
        movementTimeout = integer("movement_timeout", "Movement timeout", 14, 4, 60, 1).suffix(" s").group("Safety");
        debug = bool("debug", "Debug mode", false)
                .description("More lines in the log ([BanditMacro]) and the reason in the HUD.").group("Debug");
    }

    private BanditFsm newFsm() {
        BanditFsm.Timeouts t = new BanditFsm.Timeouts(
                searchTimeout == null || patrolRoute != null && !patrolRoute.off() ? (searchTimeout == null ? 120_000L : 0L)
                        : searchTimeout.value() * 1000L,
                movementTimeout == null ? 14_000L : movementTimeout.value() * 1000L,
                8_000L,
                throwTimeout == null ? 3_000L : throwTimeout.value() * 1000L,
                returnTimeout == null ? 9_000L : returnTimeout.value() * 1000L,
                3_000L,
                10_000L,
                5_000L,
                4_000L);
        return new BanditFsm(t, line -> ThePrisonsClient.LOGGER.info("[BanditMacro] {}", line));
    }

    @Override
    public boolean toleratesDamage() {
        return true;
    }

    @Override
    protected @Nullable String canStart(MinecraftClient client) {
        if (client.player != null && spearSlot(client.player) < 0 && ownSpear(client, client.player) == null) {
            return "No spear in the hotbar.";
        }
        return null;
    }

    @Override
    protected void onStart(MinecraftClient client) {
        fsm = newFsm();
        long now = System.currentTimeMillis();
        tickCount = 0;
        throwsDone = 0;
        kills = 0;
        failedThrows = 0;
        recallsSent = 0;
        missingSinceMs = 0L;
        targetId = null;
        pendingStop = "";
        retreats.clear();
        blacklist.clear();
        smooth.clear();
        lastDistance.clear();
        closing.clear();
        driver.stop();
        pathBusy = false;
        pathFailures = 0;
        patrol = List.of();
        patrolIndex = 0;
        patrolFailures = 0;
        if (!patrolRoute.off()) {
            Route route = routes.find(client, patrolRoute.get());
            if (route != null && route.waypoints().size() >= 2) {
                patrol = route.waypoints();
                ThePrisonsClient.LOGGER.info("[BanditMacro] patrol route {} ({} waypoints)", route.name(), patrol.size());
            }
        }
        solution = null;
        hudRecall = "NOT NEEDED";
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
        fsm.to(BanditState.SEARCHING, "START", now);
    }

    @Override
    protected void onStop(MinecraftClient client) {
        releaseUse(client);
        driver.stop();
        long now = System.currentTimeMillis();
        if (!fsm.state().inactive()) {
            fsm.to(BanditState.STOPPED, "MODULE_DISABLED", now);
        }
        ThePrisonsClient.LOGGER.info("[BanditMacro] stopped after {} throws", throwsDone);
    }

    @Override
    public Status status() {
        if (!enabled()) {
            String reason = lastStopReason();
            return reason == null ? Status.OFF : new Status(StatusLevel.ERROR, "Stopped: " + reason);
        }
        return new Status(StatusLevel.ACTIVE, fsm.state().label() + " · " + throwsDone + " throws");
    }

    @Override
    public void collectHud(List<HudLine> out) {
        if (!enabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        out.add(new HudLine("Bandit Macro", "ON · SPEAR", 0xFF65F59B));
        out.add(new HudLine("State", fsm.state().label(), 0xFF4DD8FF));
        LivingEntity target = targetEntity();
        if (target != null && client.player != null) {
            String hp = String.format(Locale.ROOT, "%.0f/%.0f", target.getHealth(), target.getMaxHealth());
            out.add(new HudLine("Target", String.format(Locale.ROOT, "%s · %.0fm · %s", targetId, Math.sqrt(target.squaredDistanceTo(client.player)), hp),
                    0xFFFFC14D));
        } else {
            out.add(new HudLine("Target", "none", 0xFF9AA3B8));
        }
        out.add(new HudLine("Spear", spearState.name(), spearState == SpearState.READY ? 0xFF65F59B
                : spearState == SpearState.MISSING ? 0xFFFF5C5C : 0xFFFFC14D));
        out.add(new HudLine("Throws", throwsDone + " · " + kills + " gone", 0xFF65F59B));
        out.add(new HudLine("Recall", hudRecall, fsm.state() == BanditState.RECALL_REQUIRED ? 0xFFFFC14D : 0xFF9AA3B8));
        if (debug.on()) {
            out.add(new HudLine("Debug", fsm.reason() + (lastDetail.isEmpty() ? "" : " · " + lastDetail), 0xFF9AA3B8));
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !player.isAlive()) {
            return;
        }
        if (client.currentScreen != null) {
            control.input().clear();
            releaseUse(client);
            return;
        }
        long now = System.currentTimeMillis();
        tickCount++;
        collectPlayers(client, player);
        collectBandits(client, player);
        ProjectileEntity spear = ownSpear(client, player);
        spearState = SpearState.classify(SpearHelperModule.holdsSpear(player), spearSlot(player) >= 0, spear != null,
                recallsSent > 0 && fsm.state().spearOut());
        if (fsm.state().inactive()) {
            return;
        }
        if (!safety(client, player, spear, now)) {
            return;
        }
        if (fsm.timedOut(now)) {
            onTimeout(client, player, spear, now);
            if (fsm.state().inactive()) {
                return;
            }
        }
        BanditState now0 = fsm.state();
        if ((now0 == BanditState.AIMING || now0 == BanditState.READY_TO_THROW || now0 == BanditState.POSITIONING)
                && attemptStartMs != 0L && now - attemptStartMs > ATTEMPT_MS) {
            driver.stop();
            control.input().clear();
            blacklistTarget(now, "ATTEMPT_TIMEOUT");
            go(BanditState.RECOVERING, "ATTEMPT_TIMEOUT");
            return;
        }
        switch (fsm.state()) {
            case SEARCHING -> searching(client, player, spear, now);
            case TARGET_ACQUIRED -> targetAcquired(client, player, now);
            case POSITIONING -> positioningState(client, player, now);
            case AIMING -> aiming(client, player, now);
            case READY_TO_THROW -> readyToThrow(client, player, now);
            case THROWING -> throwing(client, player, spear, now);
            case WAITING_FOR_RETURN -> waitingForReturn(client, player, spear, now);
            case RECALL_REQUIRED -> recallRequired(client, player, spear, now);
            case RECALLING -> recalling(client, player, spear, now);
            case TARGET_RECHECK -> targetRecheck(client, player, spear, now);
            case RETREATING -> retreating(client, player, spear, now);
            case RECOVERING -> recovering(client, player, spear, now);
            default -> {
            }
        }
    }

    private void go(BanditState next, String why) {
        long now = System.currentTimeMillis();
        if (fsm.to(next, why, now) && next == BanditState.TARGET_ACQUIRED) {
            attemptStartMs = now;
        }
    }

    // ── Safety first ─────────────────────────────────────────────────────────

    /** @return false when this tick was used up by a safety move */
    private boolean safety(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        if (!pendingStop.isEmpty()) {
            if (spear == null) {
                fail(client, pendingStop);
                return false;
            }
            return true;
        }
        retreats.removeIf(t -> now - t > RETREAT_WINDOW_MS);
        verdict = BanditDanger.evaluate(contacts(), safetyRadius.value(), player.getHealth(), CRITICAL_HEALTH);
        if (!stopNearPlayer.on() && verdict.level() != BanditDanger.Level.NONE && verdict.why().startsWith("PLAYER")) {
            verdict = new BanditDanger.Verdict(BanditDanger.Level.NONE, "", "");
        }
        BanditState state = fsm.state();
        if (verdict.level() == BanditDanger.Level.CRITICAL) {
            pendingStop = verdict.why() + (verdict.name().isEmpty() ? "" : " (" + verdict.name() + ")");
            if (spear != null) {
                if (state != BanditState.RECALL_REQUIRED && state != BanditState.RECALLING) {
                    go(BanditState.RECALL_REQUIRED, "CRITICAL_" + verdict.why());
                }
                return true;
            }
            fail(client, pendingStop);
            return false;
        }
        if (verdict.level() == BanditDanger.Level.CAUTION && state != BanditState.RETREATING && !state.spearOut()
                && state != BanditState.RECOVERING && state != BanditState.TARGET_RECHECK) {
            retreats.add(now);
            if (retreats.size() >= MAX_RETREATS) {
                pendingStop = "Players keep coming near (" + verdict.name() + ")";
                if (spear == null) {
                    fail(client, pendingStop);
                    return false;
                }
                return true;
            }
            releaseUse(client);
            driver.stop();
            go(BanditState.RETREATING, verdict.why());
            return true;
        }
        if (verdict.level() == BanditDanger.Level.CAUTION && state == BanditState.WAITING_FOR_RETURN && spear != null) {
            // Players near while the spear is out: bring it back now.
            go(BanditState.RECALL_REQUIRED, verdict.why());
        }
        return true;
    }

    private List<BanditDanger.Contact> contacts() {
        List<BanditDanger.Contact> out = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        for (PlayerEntity other : players) {
            double d = Math.sqrt(other.squaredDistanceTo(client.player));
            out.add(new BanditDanger.Contact(TextStrip.strip(other.getName().getString()), d, closing.getOrDefault(other.getUuid(), 0.0D)));
        }
        return out;
    }

    private void onTimeout(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        BanditState state = fsm.state();
        String why = "TIMEOUT_" + state;
        releaseUse(client);
        driver.stop();
        control.input().clear();
        switch (state) {
            case SEARCHING -> fail(client, "No bandit to fight for " + searchTimeout.value() + " s.");
            case WAITING_FOR_RETURN -> go(BanditState.RECALL_REQUIRED, "RETURN_TIMEOUT");
            case RECALL_REQUIRED, RECALLING -> go(BanditState.RECOVERING, "RECALL_TIMEOUT");
            case RECOVERING -> {
                if (spearState == SpearState.MISSING) {
                    fail(client, "No spear and no way to get it back.");
                } else {
                    go(BanditState.SEARCHING, "RECOVERED");
                }
            }
            case THROWING -> {
                failedThrows++;
                if (failedThrows >= 5) {
                    fail(client, "The throws do not work (no spear flies) - check \"Hold the throw\".");
                } else {
                    go(BanditState.RECOVERING, why);
                }
            }
            case POSITIONING -> {
                blacklistTarget(now, "POSITIONING_TIMEOUT");
                go(BanditState.RECOVERING, why);
            }
            case AIMING, READY_TO_THROW -> {
                blacklistTarget(now, "AIM_TIMEOUT");
                go(BanditState.RECOVERING, why);
            }
            case RETREATING -> go(BanditState.RECOVERING, why);
            default -> go(BanditState.SEARCHING, why);
        }
    }

    private void blacklistTarget(long now, String why) {
        if (targetId != null) {
            blacklist.put(targetId, now + 20_000L);
            ThePrisonsClient.LOGGER.info("[BanditMacro] {} skipped for 20 s ({})", targetId, why);
            targetId = null;
        }
        solution = null;
    }

    private void fail(MinecraftClient client, String reason) {
        long now = System.currentTimeMillis();
        releaseUse(client);
        driver.stop();
        fsm.to(BanditState.FAILSAFE, reason, now);
        alertStop(client, reason);
    }

    // ── Searching and target choice ──────────────────────────────────────────

    private void searching(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        if (spear != null) {
            go(BanditState.RECALL_REQUIRED, "SPEAR_STILL_OUT");
            return;
        }
        if (spearState == SpearState.MISSING) {
            if (missingSinceMs == 0L) {
                missingSinceMs = now;
            }
            if (now - missingSinceMs > 10_000L) {
                fail(client, "No spear in the hotbar.");
            }
            return;
        }
        missingSinceMs = 0L;
        if (spearState == SpearState.AVAILABLE) {
            player.getInventory().setSelectedSlot(spearSlot(player));
        }
        BanditTargeting.Candidate pick = pickTarget(now);
        if (pick != null) {
            driver.stop();
            setTarget(pick, now, "FOUND");
            go(BanditState.TARGET_ACQUIRED, "TARGET " + pick.id() + String.format(Locale.ROOT, " %.0fm", pick.distance()));
            return;
        }
        if (!patrol.isEmpty()) {
            patrolStep(client, player, now);
        }
    }

    /** No bandit in reach: walk the patrol route, waypoint after waypoint (the ore macro's path finder and driver). */
    private void patrolStep(MinecraftClient client, ClientPlayerEntity player, long now) {
        int[] wp = patrol.get(patrolIndex % patrol.size());
        double dist = Math.hypot(wp[0] + 0.5D - player.getX(), wp[2] + 0.5D - player.getZ());
        if (dist < 3.0D && Math.abs(wp[1] - player.getY()) < 4.0D) {
            driver.stop();
            patrolIndex = (patrolIndex + 1) % patrol.size();
            return;
        }
        if (driver.path() == null && !pathBusy && now - pathAtMs > 800L) {
            LongOpenHashSet goals = new LongOpenHashSet();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        goals.add(Pos.pack(wp[0] + dx, wp[1] + dy, wp[2] + dz));
                    }
                }
            }
            requestPath(client, player, goals, PathFor.PATROL, wp[0], wp[2], now);
        }
        if (driver.path() == null) {
            control.input().clear();
            status("Patrol: planning to waypoint " + (patrolIndex % patrol.size() + 1));
            return;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), false);
        control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), false, false));
        control.rotation().follow(drive.yaw(), 0.0F, 4.0F, 4.0F);
        status(String.format(Locale.ROOT, "Patrol: waypoint %d/%d (%.0fm)", patrolIndex % patrol.size() + 1, patrol.size(), dist));
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            driver.stop();
        }
    }

    private void status(String text) {
        lastDetail = text;
    }

    private BanditTargeting.Params params() {
        return new BanditTargeting.Params(minSafe.value(), maxCombat.value(), targetRange.value(), stickiness.value());
    }

    private BanditTargeting.@Nullable Candidate pickTarget(long now) {
        blacklist.values().removeIf(until -> until < now);
        List<BanditTargeting.Candidate> usable = new ArrayList<>();
        for (BanditTargeting.Candidate c : candidates) {
            if (!blacklist.containsKey(c.id())) {
                usable.add(c);
            }
        }
        return BanditTargeting.select(usable, targetId, now - targetSinceMs, 6_000L, params());
    }

    private void setTarget(BanditTargeting.Candidate pick, long now, String why) {
        if (!pick.id().equals(targetId)) {
            targetId = pick.id();
            targetSinceMs = now;
            pathFailures = 0;
            if (debug.on()) {
                ThePrisonsClient.LOGGER.info("[BanditMacro] target {} ({}) {}", pick.id(), why, String.format(Locale.ROOT, "%.1fm sight=%s", pick.distance(), pick.sight()));
            }
        }
    }

    private @Nullable LivingEntity targetEntity() {
        return targetId == null ? null : bandits.get(targetId);
    }

    private void targetAcquired(MinecraftClient client, ClientPlayerEntity player, long now) {
        control.input().clear();
        LivingEntity target = targetEntity();
        BanditTargeting.Candidate current = candidateFor(targetId);
        if (target == null || current == null || !BanditTargeting.valid(current, params())) {
            targetId = null;
            go(BanditState.SEARCHING, "TARGET_LOST");
            return;
        }
        BanditTargeting.Candidate better = pickTarget(now);
        if (better != null && !better.id().equals(targetId)) {
            setTarget(better, now, "BETTER");
            target = targetEntity();
            current = better;
        }
        if (spearState == SpearState.AVAILABLE) {
            player.getInventory().setSelectedSlot(spearSlot(player));
        }
        boolean inRange = current.distance() <= maxCombat.value() && current.distance() >= minSafe.value();
        if (inRange && current.sight()) {
            go(BanditState.AIMING, String.format(Locale.ROOT, "IN_RANGE %.0fm", current.distance()));
        } else if (positioning.on()) {
            go(BanditState.POSITIONING, current.distance() > maxCombat.value() ? "TOO_FAR" : current.distance() < minSafe.value() ? "TOO_CLOSE" : "NO_SIGHT");
        } else {
            targetId = null;
            go(BanditState.SEARCHING, "OUT_OF_RANGE");
        }
    }

    private BanditTargeting.@Nullable Candidate candidateFor(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (BanditTargeting.Candidate c : candidates) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    // ── Positioning (the ore macro's path finder and driver) ─────────────────

    /** The world as the path finder sees it - but every block carries the player (the ore macro only walks on stone). */
    private record Open(VoxelView base) implements VoxelView {
        @Override
        public int cell(int x, int y, int z) {
            return base.cell(x, y, z);
        }

        @Override
        public int ore(int x, int y, int z) {
            return base.ore(x, y, z);
        }

        @Override
        public boolean mineFloor(int x, int y, int z) {
            return true;
        }
    }

    private void positioningState(MinecraftClient client, ClientPlayerEntity player, long now) {
        LivingEntity target = targetEntity();
        BanditTargeting.Candidate current = candidateFor(targetId);
        if (target == null || current == null || !BanditTargeting.valid(current, params())) {
            driver.stop();
            control.input().clear();
            targetId = null;
            go(BanditState.SEARCHING, "TARGET_LOST");
            return;
        }
        boolean inRange = current.distance() <= maxCombat.value() && current.distance() >= minSafe.value();
        if (inRange && current.sight()) {
            driver.stop();
            control.input().clear();
            go(BanditState.AIMING, String.format(Locale.ROOT, "ARRIVED %.0fm", current.distance()));
            return;
        }
        boolean targetMoved = !Double.isNaN(pathTargetX) && Math.hypot(target.getX() - pathTargetX, target.getZ() - pathTargetZ) > 6.0D;
        if (driver.path() == null && !pathBusy && now - pathAtMs > 600L || targetMoved && !pathBusy && now - pathAtMs > 2500L) {
            requestAttackPath(client, player, target, current.distance(), now);
        }
        if (driver.path() == null) {
            control.input().clear();
            lastDetail = pathBusy ? "planning" : "no path yet";
            return;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), false);
        control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), false, false));
        control.rotation().follow(drive.yaw(), 0.0F, 4.0F, 4.0F);
        lastDetail = String.format(Locale.ROOT, "%.0fm to go", driver.remaining());
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            driver.stop();
        }
    }

    private void requestAttackPath(MinecraftClient client, ClientPlayerEntity player, LivingEntity target, double distance, long now) {
        double ideal = (minSafe.value() + maxCombat.value()) / 2.0D;
        double px = player.getX();
        double pz = player.getZ();
        double py = player.getY();
        double tx = target.getX();
        double tz = target.getZ();
        double away = Math.hypot(tx - px, tz - pz);
        LongOpenHashSet goals = new LongOpenHashSet();
        for (int a = 0; a < 360; a += 15) {
            for (double r = ideal - 3.0D; r <= ideal + 3.0D; r += 3.0D) {
                double gx = tx + Math.cos(Math.toRadians(a)) * r;
                double gz = tz + Math.sin(Math.toRadians(a)) * r;
                if (Math.hypot(gx - px, gz - pz) > away + 4.0D && distance > maxCombat.value()) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    goals.add(Pos.pack(MathHelper.floor(gx), MathHelper.floor(py) + dy, MathHelper.floor(gz)));
                }
            }
        }
        requestPath(client, player, goals, PathFor.ATTACK, tx, tz, now);
    }

    private void requestPath(MinecraftClient client, ClientPlayerEntity player, LongOpenHashSet goals, PathFor purpose,
                             double goalX, double goalZ, long now) {
        pathAtMs = now;
        pathTargetX = goalX;
        pathTargetZ = goalZ;
        pathFor = purpose;
        WorldSnapshot snapshot = world.snapshot(player, PATH_RADIUS, 16);
        VoxelView view = new Open(snapshot);
        long version = snapshot.version();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        pathBusy = true;
        submit("path", cancel -> {
            Walkability walk = new Walkability(view, 3);
            long start = walk.settle(px, py, pz);
            if (start == Long.MIN_VALUE) {
                return null;
            }
            PathSearch.Result found = PathSearch.findPath(walk, start, goals, PATH_NODES, PATH_RADIUS, version, cancel::cancelled);
            return found.path() == null ? null : PathStraightener.apply(found.path(), walk);
        }, path -> {
            pathBusy = false;
            BanditState state = fsm.state();
            if (purpose == PathFor.PATROL) {
                if (state != BanditState.SEARCHING) {
                    return;
                }
                if (path != null && path.size() > 1) {
                    patrolFailures = 0;
                    driver.start(path);
                } else if (++patrolFailures >= 2) {
                    // Not reachable from here: on to the next waypoint.
                    patrolFailures = 0;
                    patrolIndex = (patrolIndex + 1) % Math.max(1, patrol.size());
                }
                return;
            }
            if (state != BanditState.POSITIONING) {
                return;
            }
            if (path != null && path.size() > 1) {
                pathFailures = 0;
                driver.start(path);
            } else if (++pathFailures >= 3) {
                blacklistTarget(System.currentTimeMillis(), "NO_PATH");
                driver.stop();
                go(BanditState.RECOVERING, "NO_PATH");
            }
        });
    }

    // ── Aiming and throwing ──────────────────────────────────────────────────

    private float aimWidth() {
        return 3.0F + aimSpeed.value() * 1.2F;
    }

    /** Lead + drop for the target now; null when it cannot be reached. */
    private SpearBallistics.@Nullable Solution solve(ClientPlayerEntity player, LivingEntity target) {
        double[] ball = SpearHelperModule.ballistics();
        Vec3d eye = player.getEntityPos().add(0.0D, player.getStandingEyeHeight(), 0.0D);
        Vec3d v = smooth.getOrDefault(targetId, Vec3d.ZERO);
        double aimY = target.getY() + target.getHeight() * 0.65D;
        SpearBallistics.Solution base = SpearBallistics.solve(eye.x, eye.y, eye.z, target.getX(), aimY, target.getZ(), v.x, 0.0D, v.z,
                ball[0], ball[1], ball[2]);
        if (base == null || !pierce.on()) {
            return base;
        }
        // The same throw, turned a few degrees to pierce more bandits (every bandit led for its own walk).
        List<BanditLine.Body> bodies = new ArrayList<>();
        int targetIndex = -1;
        for (Map.Entry<String, LivingEntity> e : bandits.entrySet()) {
            LivingEntity b = e.getValue();
            Vec3d bv = smooth.getOrDefault(e.getKey(), Vec3d.ZERO);
            double ticks = Math.hypot(b.getX() - eye.x, b.getZ() - eye.z) / Math.max(0.5D, ball[0]);
            if (e.getKey().equals(targetId)) {
                targetIndex = bodies.size();
            }
            bodies.add(new BanditLine.Body(b.getX() + bv.x * ticks, b.getZ() + bv.z * ticks, b.getWidth() / 2.0D));
        }
        float yaw = BanditLine.pierceYaw(eye.x, eye.z, base.yaw(), bodies, targetIndex, targetRange.value(), lineMargin.get(), 6.0D);
        return new SpearBallistics.Solution(yaw, base.pitch(), base.ticks(), base.aimX(), base.aimY(), base.aimZ());
    }

    private boolean sight(MinecraftClient client, ClientPlayerEntity player, LivingEntity target) {
        Vec3d eye = player.getEntityPos().add(0.0D, player.getStandingEyeHeight(), 0.0D);
        Vec3d aim = new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.65D, target.getZ());
        return client.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                player)).getType() == HitResult.Type.MISS;
    }

    private void aiming(MinecraftClient client, ClientPlayerEntity player, long now) {
        control.input().clear();
        LivingEntity target = targetEntity();
        BanditTargeting.Candidate current = candidateFor(targetId);
        if (target == null || current == null || !BanditTargeting.valid(current, params())) {
            targetId = null;
            go(BanditState.SEARCHING, "TARGET_LOST");
            return;
        }
        if (current.distance() > maxCombat.value() + 4.0D || current.distance() < minSafe.value() - 1.0D || !current.sight()) {
            aimedTicks = 0;
            go(positioning.on() ? BanditState.POSITIONING : BanditState.SEARCHING, !current.sight() ? "SIGHT_LOST" : "RANGE_CHANGED");
            return;
        }
        if (spearState != SpearState.READY) {
            if (spearState == SpearState.AVAILABLE) {
                player.getInventory().setSelectedSlot(spearSlot(player));
            }
            return;
        }
        solution = solve(player, target);
        if (solution == null) {
            go(positioning.on() ? BanditState.POSITIONING : BanditState.SEARCHING, "OUT_OF_BALLISTIC_REACH");
            return;
        }
        control.rotation().request(RotationMode.TURNING, MathHelper.wrapDegrees(solution.yaw()), MathHelper.clamp(solution.pitch(), -89.0F, 89.0F), aimWidth());
        float yawError = Math.abs(RotationMath.wrap(solution.yaw() - player.getYaw()));
        float pitchError = Math.abs(solution.pitch() - player.getPitch());
        lastDetail = String.format(Locale.ROOT, "err %.1f/%.1f", yawError, pitchError);
        aimedTicks = yawError < 1.5F && pitchError < 2.5F ? aimedTicks + 1 : 0;
        if (aimedTicks >= 3) {
            reactionTicks = 3 + random.nextInt(6);
            go(BanditState.READY_TO_THROW, "AIMED");
        }
    }

    private void readyToThrow(MinecraftClient client, ClientPlayerEntity player, long now) {
        control.input().clear();
        LivingEntity target = targetEntity();
        if (target == null || spearState != SpearState.READY) {
            go(BanditState.AIMING, target == null ? "TARGET_LOST" : "SPEAR_NOT_READY");
            return;
        }
        solution = solve(player, target);
        if (solution == null || !sight(client, player, target)) {
            go(BanditState.AIMING, "SOLUTION_LOST");
            return;
        }
        control.rotation().request(RotationMode.TURNING, MathHelper.wrapDegrees(solution.yaw()), MathHelper.clamp(solution.pitch(), -89.0F, 89.0F), aimWidth());
        float yawError = Math.abs(RotationMath.wrap(solution.yaw() - player.getYaw()));
        float pitchError = Math.abs(solution.pitch() - player.getPitch());
        if (yawError > 2.5F || pitchError > 4.0F) {
            aimedTicks = 0;
            go(BanditState.AIMING, "DRIFTED");
            return;
        }
        String bystander = playerInLine(client, player, solution.yaw(), current(target, player));
        if (bystander != null) {
            // The spear pierces everything on its path - friends and strangers too.
            lastDetail = "player in line: " + bystander;
            aimedTicks = 0;
            go(BanditState.AIMING, "PLAYER_IN_LINE " + bystander);
            return;
        }
        if (reactionTicks-- > 0) {
            return;
        }
        clicked = false;
        go(BanditState.THROWING, "FIRE");
    }

    private static double current(LivingEntity target, ClientPlayerEntity player) {
        return Math.sqrt(target.squaredDistanceTo(player));
    }

    /** The name of a player (anybody, friends too) standing on the throw's line up to just behind the target; null = clear. */
    private @Nullable String playerInLine(MinecraftClient client, ClientPlayerEntity player, float yaw, double targetDistance) {
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player || other.isSpectator() || !other.isAlive()) {
                continue;
            }
            String name = TextStrip.strip(other.getName().getString());
            if (BanditScan.isBanditName(name)) {
                continue;
            }
            BanditLine.Body body = new BanditLine.Body(other.getX(), other.getZ(), other.getWidth() / 2.0D);
            if (BanditLine.hits(player.getX(), player.getZ(), yaw, body, targetDistance + 8.0D, 0.6D)) {
                return name;
            }
        }
        return null;
    }

    private void throwing(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        if (solution != null) {
            control.rotation().request(RotationMode.TURNING, MathHelper.wrapDegrees(solution.yaw()), MathHelper.clamp(solution.pitch(), -89.0F, 89.0F), aimWidth());
        }
        if (spear != null) {
            // Away (an instant-throw spear lets go before the charge time): let go of the key.
            releaseUse(client);
            failedThrows = 0;
            beginFlight();
            go(BanditState.WAITING_FOR_RETURN, "SPEAR_AWAY");
            return;
        }
        long held = fsm.elapsed(now);
        if (chargeTicks.value() == 0) {
            if (!clicked && client.interactionManager != null) {
                clicked = true;
                client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            }
            return;
        }
        if (held < chargeTicks.value() * 50L) {
            client.options.useKey.setPressed(true);
        } else {
            releaseUse(client);
        }
    }

    private void beginFlight() {
        planner = new RecallPlanner(Math.round(recallDeadline.get() * 1000.0D), recallThreshold.value(), 250L);
        planner.start(Util.getMeasuringTimeMs());
        recallsSent = 0;
        stuckTicks = 0;
        lastSpear = null;
        hudRecall = autoRecall.on() ? "READY" : "MANUAL";
    }

    // ── Spear in the air, recall ─────────────────────────────────────────────

    private void waitingForReturn(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        if (spear == null) {
            if (fsm.elapsed(now) < 500L) {
                return;
            }
            throwsDone++;
            go(BanditState.TARGET_RECHECK, "SPEAR_BACK");
            cooldownTicks = 6 + random.nextInt(10);
            return;
        }
        Vec3d pos = spear.getEntityPos();
        boolean stuck = lastSpear != null && pos.squaredDistanceTo(lastSpear) < 0.0004D;
        stuckTicks = stuck ? stuckTicks + 1 : 0;
        Vec3d velocity = lastSpear == null ? Vec3d.ZERO : pos.subtract(lastSpear);
        lastSpear = pos;
        double margin = lineMargin.get();
        double px = player.getX();
        double pz = player.getZ();
        List<BanditLine.Body> now0 = new ArrayList<>();
        List<BanditLine.Body> ahead = new ArrayList<>();
        double k = recallLead.value() / 50.0D;
        for (LivingEntity b : bandits.values()) {
            Vec3d v = smooth.getOrDefault(TextStrip.strip(b.getName().getString()), Vec3d.ZERO);
            now0.add(new BanditLine.Body(b.getX(), b.getZ(), b.getWidth() / 2.0D));
            ahead.add(new BanditLine.Body(b.getX() + v.x * k, b.getZ() + v.z * k, b.getWidth() / 2.0D));
        }
        int onWay = BanditLine.countSegment(pos.x, pos.z, px, pz, now0, margin);
        Vec3d pv = player.getVelocity();
        int predicted = BanditLine.countSegment(pos.x + velocity.x * k, pos.z + velocity.z * k, px + pv.x * k, pz + pv.z * k, ahead, margin);
        double reach = Math.hypot(pos.x - px, pos.z - pz) + 2.0D;
        int near = 0;
        for (BanditLine.Body b : now0) {
            if (Math.hypot(b.x() - px, b.z() - pz) <= reach) {
                near++;
            }
        }
        boolean fire = planner.update(Util.getMeasuringTimeMs(), onWay, predicted, near);
        if (!fire && stuckTicks > 6) {
            fire = true;
        }
        lastDetail = "on the way back: " + onWay;
        if (fire) {
            go(BanditState.RECALL_REQUIRED, stuckTicks > 6 ? "SPEAR_STUCK" : "PLANNER " + onWay);
        }
    }

    private void recallRequired(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        hudRecall = "REQUIRED";
        if (spear == null) {
            throwsDone++;
            go(BanditState.TARGET_RECHECK, "SPEAR_BACK");
            return;
        }
        if (!autoRecall.on() && pendingStop.isEmpty()) {
            // The player recalls by hand: just wait for the spear (the return timeout is the limit).
            return;
        }
        sendRecall(player);
        go(BanditState.RECALLING, "F_SENT");
    }

    private void recalling(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        hudRecall = "SENT x" + recallsSent;
        if (spear == null) {
            throwsDone++;
            cooldownTicks = 6 + random.nextInt(10);
            go(BanditState.TARGET_RECHECK, "SPEAR_BACK");
            return;
        }
        if (fsm.elapsed(now) > 1500L * recallsSent && recallsSent < 4) {
            sendRecall(player);
        }
    }

    private void sendRecall(ClientPlayerEntity player) {
        if (player.networkHandler != null) {
            // F: the swap-hands action. Cosmic turns it into the spear's recall.
            player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ORIGIN, Direction.DOWN));
            recallsSent++;
        }
    }

    private void targetRecheck(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        hudRecall = "NOT NEEDED";
        if (--cooldownTicks > 0 || spear != null) {
            return;
        }
        if (!pendingStop.isEmpty()) {
            fail(client, pendingStop);
            return;
        }
        BanditTargeting.Candidate current = candidateFor(targetId);
        if (current != null && BanditTargeting.valid(current, params())) {
            go(BanditState.TARGET_ACQUIRED, "STILL_ALIVE");
        } else {
            if (targetId != null && candidateFor(targetId) == null) {
                kills++;
            }
            targetId = null;
            go(BanditState.SEARCHING, "TARGET_GONE");
        }
    }

    // ── Retreat, recover ─────────────────────────────────────────────────────

    private void retreating(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        driver.stop();
        if (spear != null) {
            control.input().clear();
            go(BanditState.RECALL_REQUIRED, "RETREAT_SPEAR_OUT");
            return;
        }
        PlayerEntity worst = nearestPlayer(player);
        if (worst == null || verdict.level() == BanditDanger.Level.NONE
                && Math.sqrt(worst.squaredDistanceTo(player)) >= safetyRadius.value() * 1.2D) {
            control.input().clear();
            targetId = null;
            go(BanditState.SEARCHING, "SAFE_AGAIN");
            return;
        }
        // Straight away from the closest player, one calm view motion, never over an edge.
        float yaw = RotationMath.yawOf(player.getX() - worst.getX(), player.getZ() - worst.getZ());
        control.rotation().follow(yaw, 0.0F, 5.0F, 5.0F);
        boolean facing = Math.abs(RotationMath.wrap(yaw - player.getYaw())) < 30.0F;
        if (!facing || !safeAhead(client, player)) {
            control.input().clear();
            lastDetail = facing ? "edge ahead" : "turning";
            return;
        }
        double moved = Double.isNaN(lastX) ? 1.0D : Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        stalledTicks = moved < 0.03D ? stalledTicks + 1 : 0;
        lastX = player.getX();
        lastZ = player.getZ();
        control.input().set(new InputController.Keys(true, false, false, false, player.isOnGround() && stalledTicks > 5, true, false));
    }

    private void recovering(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.input().clear();
        releaseUse(client);
        driver.stop();
        if (spear != null) {
            go(BanditState.RECALL_REQUIRED, "SPEAR_STILL_OUT");
            return;
        }
        if (fsm.elapsed(now) < 1000L) {
            return;
        }
        if (spearState == SpearState.MISSING) {
            return;
        }
        go(BanditState.SEARCHING, "RECOVERED");
    }

    // ── World reading ────────────────────────────────────────────────────────

    private void collectBandits(MinecraftClient client, ClientPlayerEntity player) {
        bandits.clear();
        candidates.clear();
        double max = targetRange.value() + 10.0D;
        Map<String, Vec3d> next = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()
                    || living.squaredDistanceTo(player) > max * max || !BanditScan.isBandit(living, anyBandit.on())) {
                continue;
            }
            String id = TextStrip.strip(living.getName().getString());
            bandits.put(id, living);
            Vec3d moved = new Vec3d(living.getX() - living.lastX, 0.0D, living.getZ() - living.lastZ);
            Vec3d old = smooth.get(id);
            next.put(id, old == null ? moved : old.multiply(0.6D).add(moved.multiply(0.4D)));
        }
        smooth.clear();
        smooth.putAll(next);
        for (Map.Entry<String, LivingEntity> e : bandits.entrySet()) {
            LivingEntity b = e.getValue();
            double dist = Math.sqrt(b.squaredDistanceTo(player));
            int crowd = 0;
            for (PlayerEntity p : players) {
                if (p.squaredDistanceTo(b) <= 36.0D) {
                    crowd++;
                }
            }
            int neighbours = 0;
            for (LivingEntity o : bandits.values()) {
                if (o != b && o.squaredDistanceTo(b) <= 64.0D) {
                    neighbours++;
                }
            }
            boolean sight = dist <= targetRange.value() && sight(client, player, b);
            candidates.add(new BanditTargeting.Candidate(e.getKey(), b.getX(), b.getY(), b.getZ(), dist, sight, crowd, neighbours));
        }
    }

    private void collectPlayers(MinecraftClient client, ClientPlayerEntity player) {
        players.clear();
        FriendsModule friends = FriendsModule.get();
        Map<UUID, Double> seen = new HashMap<>();
        double max = safetyRadius.value() * 2.0D;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player || other.isSpectator() || !other.isAlive() || other.squaredDistanceTo(player) > max * max) {
                continue;
            }
            String name = TextStrip.strip(other.getName().getString());
            if (BanditScan.isBanditName(name) || other.getUuid().version() == 2 || other.getCustomName() != null
                    || name.toLowerCase(Locale.ROOT).startsWith("guard_") || client.getNetworkHandler() == null
                    || client.getNetworkHandler().getPlayerListEntry(other.getUuid()) == null) {
                continue;
            }
            if (!includeFriends.on() && friends != null && friends.relation(name, null) != FriendList.Relation.NONE) {
                continue;
            }
            players.add(other);
            double d = Math.sqrt(other.squaredDistanceTo(player));
            Double before = lastDistance.get(other.getUuid());
            double speed = before == null ? 0.0D : before - d;
            closing.merge(other.getUuid(), speed, (old, now) -> old * 0.7D + now * 0.3D);
            seen.put(other.getUuid(), d);
        }
        lastDistance.clear();
        lastDistance.putAll(seen);
        closing.keySet().retainAll(seen.keySet());
    }

    private @Nullable PlayerEntity nearestPlayer(ClientPlayerEntity player) {
        PlayerEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerEntity p : players) {
            double d = p.squaredDistanceTo(player);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** No drop of more than 2 blocks, no water / lava on the next steps ahead. */
    private boolean safeAhead(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d dir = Vec3d.fromPolar(0.0F, player.getYaw());
        for (int step = 1; step <= 2; step++) {
            int x = MathHelper.floor(player.getX() + dir.x * step);
            int z = MathHelper.floor(player.getZ() + dir.z * step);
            int y = MathHelper.floor(player.getY());
            boolean ground = false;
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(x, y - dy, z);
                var state = client.world.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) {
                    return false;
                }
                if (!state.getCollisionShape(client.world, pos).isEmpty()) {
                    ground = true;
                    break;
                }
            }
            if (!ground) {
                return false;
            }
        }
        return true;
    }

    // ── The spear ────────────────────────────────────────────────────────────

    private static int spearSlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (SpearHelperModule.isSpear(player.getInventory().getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** The spear this player threw and that is in the world now ({@code null} = none). */
    private static @Nullable ProjectileEntity ownSpear(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return null;
        }
        for (ProjectileEntity e : client.world.getEntitiesByClass(ProjectileEntity.class, player.getBoundingBox().expand(160.0D),
                p -> p.getOwner() == player && spearLike(p))) {
            return e;
        }
        return null;
    }

    private static boolean spearLike(ProjectileEntity p) {
        String path = Registries.ENTITY_TYPE.getId(p.getType()).getPath();
        return path.contains("spear") || path.contains("trident");
    }

    private static void releaseUse(MinecraftClient client) {
        client.options.useKey.setPressed(false);
    }

    private void alertStop(MinecraftClient client, String reason) {
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND, 1.0F, 1.0F));
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.7F, 1.0F));
        io.theprisons.core.setup.ModChat.show(io.theprisons.core.setup.ModChat.header("Bandit Macro stopped"));
        io.theprisons.core.setup.ModChat.show(Text.literal("  • " + reason).formatted(Formatting.RED));
        ThePrisonsClient.LOGGER.warn("[BanditMacro] {}", reason);
        disableSelf(reason);
    }
}
