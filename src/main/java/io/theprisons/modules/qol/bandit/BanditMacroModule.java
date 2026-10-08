package io.theprisons.modules.qol.bandit;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.control.InputController;
import io.theprisons.core.control.IntentPriority;
import io.theprisons.core.control.MovementIntent;
import io.theprisons.core.control.RotationIntent;
import io.theprisons.core.control.RotationMath;
import io.theprisons.core.control.RotationMode;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.parse.BanditClassifier;
import io.theprisons.core.cosmic.sense.InputSensor;
import io.theprisons.core.cosmic.state.CosmicStateService;
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
import io.theprisons.core.world.LiveWorldView;
import io.theprisons.core.world.WorldCache;
import io.theprisons.core.world.WorldSnapshot;
import io.theprisons.modules.FeatureProfile;
import io.theprisons.modules.mining.ore.LaneDriver;
import io.theprisons.modules.mining.ore.route.Route;
import io.theprisons.modules.mining.ore.route.RouteStore;
import io.theprisons.modules.qol.bandit.combat.AttackPhase;
import io.theprisons.modules.qol.bandit.combat.CombatBrain;
import io.theprisons.modules.qol.bandit.combat.CombatConfig;
import io.theprisons.modules.qol.bandit.combat.CombatInputs;
import io.theprisons.modules.qol.bandit.combat.CombatState;
import io.theprisons.modules.qol.bandit.combat.Decision;
import io.theprisons.modules.qol.bandit.combat.Foe;
import io.theprisons.modules.qol.bandit.combat.Geo;
import io.theprisons.modules.qol.bandit.combat.SpearStatus;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * The bandit macro (spear mode): a combat controller, not a mob killer.
 *
 * <p><b>Who decides what.</b> {@link CombatBrain} (pure logic, tested without a game) decides the situation: which bandit is the target
 * (score model with lock and hysteresis), whether to approach, orbit, evade, reposition, recover or retreat, and whether the attack may run.
 * The entities come from the Cosmic state store (one scan for the whole mod); the live world is only asked about the target and a few
 * candidates (line of sight, speed). This class turns the brain's wishes into {@code MovementIntent} / {@code RotationIntent}; only the control
 * layer writes keys and view, one winner per tick, and manual input always wins.
 *
 * <p><b>The attack</b> is the spear cycle this macro always had (aim with lead and drop, throw, wait, recall with F), as a small state
 * machine of its own ({@link Attack}). It no longer stands still: the brain keeps orbiting while the macro aims and while the spear
 * is out; only the throw itself (a fraction of a second) holds still. Nothing about the spear's cooldown is assumed: when the client cannot read
 * it the status is UNKNOWN and the macro paces its throws itself ({@link #THROW_GAP_MS}).
 */
public final class BanditMacroModule extends AutomationModule {
    /** The only mode so far (the setting is there for the dashboard and for later modes). */
    private enum Mode { SPEAR }

    /** Where the spear attack is. */
    private enum Attack {
        NONE, AIMING, READY, THROWING, FLIGHT, RECALL_DUE, RECALLING, SETTLING;

        boolean spearMayBeOut() {
            return this == FLIGHT || this == RECALL_DUE || this == RECALLING;
        }

        AttackPhase phase() {
            return switch (this) {
                case NONE -> AttackPhase.NONE;
                case AIMING -> AttackPhase.AIMING;
                case READY, THROWING -> AttackPhase.COMMIT;
                default -> AttackPhase.IN_FLIGHT;
            };
        }
    }

    private static final int PATH_NODES = 6000;
    private static final int PATH_RADIUS = 48;
    /** Our own pacing between two throws (the spear's real cooldown is not known). */
    private static final long THROW_GAP_MS = 500L;
    /** The view must be this close to the throw solution (degrees) for this many ticks before the throw. */
    private static final int AIMED_TICKS = 3;
    /** After "aimed" the throw waits this many ticks (a fixed beat, no randomness). */
    private static final int REACTION_TICKS = 4;
    /** One aim (turning onto the solution) may take this long, then the attack is dropped. */
    private static final long AIM_TIMEOUT_MS = 10_000L;
    /** The stop after a safety verdict waits this long for the spear to come back. */
    private static final long STOP_RECALL_MS = 6_000L;
    private static final int MAX_LIVE = 8;

    private final Settings.EnumSetting<Mode> mode;
    private final Settings.BoolSetting anyBandit;
    private final Settings.IntSetting targetRange;
    private final Settings.IntSetting minSafe;
    private final Settings.IntSetting maxCombat;
    private final Settings.IntSetting preferredDistance;
    private final Settings.IntSetting maxThreats;
    private final Settings.IntSetting retreatHealth;
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
    private final LaneDriver driver = new LaneDriver(3.0D);
    private CombatConfig cfg = new CombatConfig();
    private CombatBrain brain = new CombatBrain(cfg, line -> {
    }, line -> {
    });

    /** The live entities of this tick that the brain looks at (by foe id): bounded, filled from the state store's list. */
    private final Map<String, LivingEntity> live = new HashMap<>();
    private final Map<String, Vec3d> smooth = new HashMap<>();
    private final Map<UUID, Double> lastPlayerDistance = new HashMap<>();
    private Decision lastDecision;
    private CombatState lastCombatState = CombatState.IDLE;
    private @Nullable String lastTargetId;
    private Decision.Move lastMove = Decision.Move.NONE;

    private Attack attack = Attack.NONE;
    private long attackSinceMs;
    private long nextAttackMs;
    private SpearState spearState = SpearState.MISSING;
    private SpearStatus spearStatus = SpearStatus.UNKNOWN;
    private RecallPlanner planner = new RecallPlanner(3000L, 2, 250L);
    private int recallsSent;
    private int throwsDone;
    private int failedThrows;
    private long missingSinceMs;
    private int aimedTicks;
    /** The one click of a "hold 0" throw was sent. */
    private boolean clicked;
    private int reactionTicks;
    private int tickCount;
    private int stuckTicks;
    private @Nullable Vec3d lastSpear;
    private SpearBallistics.@Nullable Solution solution;
    private @Nullable ProjectileEntity spearEntity;
    private @Nullable String pendingStop;
    private boolean pendingStopQuiet;
    private long pendingStopSinceMs;
    private long scanSinceMs;

    /** What the running path search is for. */
    private enum PathFor { ATTACK, PATROL }

    private PathFor pathFor = PathFor.ATTACK;
    private List<int[]> patrol = List.of();
    private int patrolIndex;
    private int patrolFailures;
    private boolean pathBusy;
    private int pathFailures;
    private long pathAtMs;
    private double pathGoalX = Double.NaN;
    private double pathGoalZ = Double.NaN;
    private CombatInputs.PathStatus pathStatus = CombatInputs.PathStatus.NONE;
    private String lastDetail = "";
    private @Nullable String hudRecall = "NOT NEEDED";

    public BanditMacroModule(ControlService control, WorldCache world, RouteStore routes) {
        super("bandit_macro", "Bandit Macro", Category.BANDIT, "Macro",
                "Hunts bandits with the spear on its own: picks a target and keeps it, walks into range, circles it at a distance, aims with "
                        + "the human view motion (lead and drop), throws, recalls at the best moment and starts again. Backs off when "
                        + "danger grows or another player comes near.",
                GLFW.GLFW_KEY_J, control, world, 48, 16);
        io.theprisons.hud.BanditHudInfo.source(this::hudInfo);
        mode = choice("mode", "Mode", Mode.SPEAR, m -> "Spear").group("Macro");
        anyBandit = bool("any_bandit", "Include bosses & special bandits", false).group("Macro");
        targetRange = integer("target_range", "Target range", 60, 10, 120, 1).suffix(" blocks")
                .description("Bandits farther away than this are ignored.").group("Targeting");
        minSafe = integer("min_safe", "Minimum safe distance", 6, 2, 20, 1).suffix(" blocks")
                .description("Closer than this the macro steps back.").group("Targeting");
        maxCombat = integer("max_combat", "Maximum combat distance", 34, 10, 80, 1).suffix(" blocks")
                .description("Farther than this the macro walks closer first.").group("Targeting");
        preferredDistance = integer("preferred_distance", "Combat distance (0 = auto)", 0, 0, 60, 1).suffix(" blocks")
                .description("The distance it keeps while circling the bandit. 0 = auto: 40 % into the band between the minimum and maximum "
                        + "distance, until verified game values exist.").group("Targeting");
        maxThreats = integer("max_threats", "Max nearby bandits", 3, 1, 8, 1)
                .description("More bandits than this around you and the macro breaks off the fight.").group("Targeting");
        patrolRoute = add(new Settings.ChoiceSetting("patrol_route", "Patrol route", "Off (stay here)",
                () -> routes.options(MinecraftClient.getInstance())))
                .description("A recorded route (Controls: record route) walked from waypoint to waypoint while no bandit is in "
                        + "reach. Off: the macro waits where you stand.").group("Targeting");
        this.routes = routes;
        positioning = bool("positioning", "Walk into range", true)
                .description("Off: it only fights bandits that are already in combat range.").group("Targeting");
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
        retreatHealth = integer("retreat_health", "Retreat below health", 8, 3, 19, 1).suffix(" hp")
                .description("Below this the macro breaks off the fight and waits for your health (4 hp more) before it goes on. "
                        + "Your own choice, not a game value.").group("Safety");
        includeFriends = bool("stop_for_friends", "React to friends & gang too", false).group("Safety");
        searchTimeout = integer("search_timeout", "Search timeout", 120, 20, 600, 5).suffix(" s")
                .description("No bandit to fight for this long: the macro stops.").group("Safety");
        movementTimeout = integer("movement_timeout", "Movement timeout", 14, 4, 60, 1).suffix(" s").group("Safety");
        debug = bool("debug", "Debug mode", false)
                .description("More lines in the log ([BanditCombat]) and a debug view in the HUD: state, target, distance, threats, "
                        + "who owns keys and view, path, orbit side.").group("Debug");
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

    /**
     * What the session dashboard shows of the fight, in plain words (internal planner terms stay in the debug lines, which only developer / debug mode adds).
     * Read on the client thread; cheap.
     */
    io.theprisons.hud.@Nullable BanditHudInfo hudInfo() {
        if (!enabled()) {
            return null;
        }
        String target = brain.targetId() == null ? "" : brain.targetId();
        double distance = Double.isNaN(brain.targetDistance()) ? -1.0D : brain.targetDistance();
        int threats = Math.max(0, brain.threats().count() - (target.isEmpty() ? 0 : 1));
        String spear = switch (spearState) {
            case READY -> "Ready";
            case AVAILABLE -> "In hotbar";
            case THROWN -> "In flight";
            case RETURNING -> "Returning";
            case MISSING -> "Missing";
        };
        double charge = -1.0D;
        if (attack == Attack.THROWING && chargeTicks.value() > 0) {
            charge = Math.max(0.0D, Math.min(1.0D, (System.currentTimeMillis() - attackSinceMs) / (chargeTicks.value() * 50.0D)));
        }
        String recall = switch (attack) {
            case RECALL_DUE -> "Due";
            case RECALLING -> "Recalling";
            case FLIGHT -> autoRecall.on() ? "Waiting" : "Manual";
            default -> "";
        };
        String route = patrolRoute.off() ? "" : patrolRoute.get();
        java.util.List<String> debug = tracing() ? java.util.List.of("state " + brain.state() + " (" + brain.reason() + ")", "band " + brain.bandState() + ", ring "
                + String.format(Locale.ROOT, "%.1f", brain.ring()), lastDetail) : java.util.List.of();
        return new io.theprisons.hud.BanditHudInfo(true, brain.state().label(), target, distance, threats, spear, charge, recall, "", route, false, brain.kills(), 0L, debug);
    }

    private boolean tracing() {
        return debug.on() || FeatureProfile.DEV;
    }

    private CombatConfig buildConfig() {
        CombatConfig c = new CombatConfig();
        c.minSafe = minSafe.value();
        c.maxCombat = Math.max(maxCombat.value(), minSafe.value() + 4);
        c.targetRange = positioning.on() ? targetRange.value() : c.maxCombat;
        c.preferredDistance = preferredDistance.value();
        c.maxNearbyThreats = maxThreats.value();
        c.retreatHealth = retreatHealth.value();
        c.criticalHealth = Math.min(6.0D, retreatHealth.value() - 1.0D);
        c.playerSafetyRadius = safetyRadius.value();
        c.lockBonus = stickiness.value();
        c.approachTimeoutMs = movementTimeout.value() * 1000L;
        // Verified game values would go here (CosmicGameModel: bandits.aggroRange, bandits.preferredCombatDistance). Today they are UNKNOWN.
        var model = ThePrisonsCore.get().cosmic().model();
        model.banditsArea().value("aggroRange", Double.class).atLeast(io.theprisons.core.cosmic.value.Confidence.OBSERVED)
                .ifPresent(v -> c.knownAggroRange = v);
        model.banditsArea().value("preferredCombatDistance", Double.class).atLeast(io.theprisons.core.cosmic.value.Confidence.OBSERVED)
                .ifPresent(v -> c.knownPreferredDistance = v);
        return c;
    }

    @Override
    protected void onStart(MinecraftClient client) {
        long now = System.currentTimeMillis();
        cfg = buildConfig();
        brain = new CombatBrain(cfg, line -> ThePrisonsClient.LOGGER.info("[BanditCombat] {}", line), line -> {
            if (tracing()) {
                ThePrisonsClient.LOGGER.info("[BanditCombat] {} | {}", line, control.telemetry().summary());
            }
        });
        tickCount = 0;
        throwsDone = 0;
        failedThrows = 0;
        recallsSent = 0;
        missingSinceMs = 0L;
        pendingStop = null;
        pendingStopQuiet = false;
        attack = Attack.NONE;
        nextAttackMs = 0L;
        solution = null;
        spearEntity = null;
        live.clear();
        smooth.clear();
        lastPlayerDistance.clear();
        driver.stop();
        pathBusy = false;
        pathFailures = 0;
        pathStatus = CombatInputs.PathStatus.NONE;
        patrol = List.of();
        patrolIndex = 0;
        patrolFailures = 0;
        scanSinceMs = now;
        hudRecall = "NOT NEEDED";
        lastDecision = null;
        lastCombatState = CombatState.IDLE;
        lastTargetId = null;
        if (!patrolRoute.off()) {
            Route route = routes.find(client, patrolRoute.get());
            if (route != null && route.waypoints().size() >= 2) {
                patrol = route.waypoints();
                ThePrisonsClient.LOGGER.info("[BanditMacro] patrol route {} ({} waypoints)", route.name(), patrol.size());
            }
        }
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        cosmic.interest(this, targetRange.value() + 10.0D, 1);
        cosmic.captureExtra(this, this::captureContext);
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
        brain.start(now);
        ThePrisonsClient.LOGGER.info("[BanditCombat] start: band {}-{} blocks, ring {} ({}), target range {}, retreat below {} hp, max {} threats",
                cfg.minSafe, cfg.maxCombat, String.format(Locale.ROOT, "%.1f", brain.ring()), cfg.ringSource(), cfg.targetRange, cfg.retreatHealth,
                cfg.maxNearbyThreats);
    }

    @Override
    protected void onStop(MinecraftClient client) {
        releaseUse(client);
        driver.stop();
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        cosmic.release(this);
        cosmic.removeCaptureExtra(this);
        long now = System.currentTimeMillis();
        brain.stop(now, "MODULE_DISABLED");
        brain.idle(now);
        live.clear();
        smooth.clear();
        attack = Attack.NONE;
        ThePrisonsClient.LOGGER.info("[BanditMacro] stopped after {} throws, {} bandits gone", throwsDone, brain.kills());
    }

    /** The control layer saw the view turn and turn without getting anywhere: drop the target and the path, scan again. */
    @Override
    public void onSpinLoop() {
        driver.stop();
        pathBusy = false;
        releaseUse(MinecraftClient.getInstance());
        brain.invalidate(System.currentTimeMillis(), "SPIN_GUARD");
        ThePrisonsClient.LOGGER.warn("[BanditCombat] SPIN_GUARD: target and path dropped");
    }

    @Override
    public Status status() {
        if (!enabled()) {
            String reason = lastStopReason();
            return reason == null ? Status.OFF : new Status(StatusLevel.ERROR, "Stopped: " + reason);
        }
        return new Status(StatusLevel.ACTIVE, brain.state().label() + " · " + throwsDone + " throws");
    }

    @Override
    public void collectHud(List<HudLine> out) {
        if (!enabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        out.add(new HudLine("Bandit Macro", "ON · SPEAR", 0xFF65F59B));
        out.add(new HudLine("State", brain.state().label(), 0xFF4DD8FF));
        String id = brain.targetId();
        LivingEntity target = id == null ? null : live.get(id);
        if (id != null && client.player != null && !Double.isNaN(brain.targetDistance())) {
            String hp = target == null ? "" : String.format(Locale.ROOT, " · %.0f/%.0f", target.getHealth(), target.getMaxHealth());
            out.add(new HudLine("Target", String.format(Locale.ROOT, "%s · %.0fm%s", id, brain.targetDistance(), hp), 0xFFFFC14D));
        } else {
            out.add(new HudLine("Target", "none", 0xFF9AA3B8));
        }
        out.add(new HudLine("Spear", spearState.name() + (spearStatus == SpearStatus.UNKNOWN ? "" : " · " + spearStatus.name()),
                spearState == SpearState.READY ? 0xFF65F59B : spearState == SpearState.MISSING ? 0xFFFF5C5C : 0xFFFFC14D));
        out.add(new HudLine("Throws", throwsDone + " · " + brain.kills() + " gone", 0xFF65F59B));
        out.add(new HudLine("Recall", hudRecall, attack == Attack.RECALL_DUE ? 0xFFFFC14D : 0xFF9AA3B8));
        if (tracing()) {
            out.add(new HudLine("Combat", String.format(Locale.ROOT, "%s · %s · threats %d", brain.state(), brain.bandState(), brain.threats().count()),
                    0xFF9AA3B8));
            out.add(new HudLine("Orbit", (brain.orbitDir() == null ? "-" : brain.orbitDir().name()) + String.format(Locale.ROOT, " · ring %.0f · loops %d · recoveries %d",
                    brain.ring(), brain.loops(), brain.recoveryCount()), 0xFF9AA3B8));
            var rot = control.rotationWinner();
            out.add(new HudLine("Owners", "keys " + (control.input().winnerSource().isEmpty() ? "-" : control.input().winnerSource()) + " · view "
                    + (rot == null ? "-" : rot.source()) + " · path " + pathStatus, 0xFF9AA3B8));
            out.add(new HudLine("Why", brain.reason() + (lastDetail.isEmpty() ? "" : " · " + lastDetail), 0xFF9AA3B8));
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !player.isAlive()) {
            return;
        }
        if (client.currentScreen != null) {
            control.submit(MovementIntent.none(IntentPriority.EMERGENCY, "screen open"));
            releaseUse(client);
            return;
        }
        if (brain.state().inactive() && pendingStop == null) {
            return;
        }
        long now = System.currentTimeMillis();
        tickCount++;
        CosmicStateService cosmic = ThePrisonsCore.get().cosmic();
        CosmicContextSnapshot snap = cosmic.latest();
        if (snap == null || !snap.player().present()) {
            control.submit(MovementIntent.none(IntentPriority.PATHFINDING, "waiting for the first sample"));
            return;
        }
        if (attack != Attack.NONE || tickCount % 4 == 0) {
            spearEntity = ownSpear(client, player);
        }
        ProjectileEntity spear = spearEntity;
        spearState = SpearState.classify(SpearHelperModule.holdsSpear(player), spearSlot(player) >= 0, spear != null,
                recallsSent > 0 && attack.spearMayBeOut());
        spearStatus = SpearStatus.of(snap.combat().spear().recognised(), snap.combat().spear().cooldown());

        if (pendingStop != null) {
            stopStep(client, player, spear, now);
            return;
        }

        CombatInputs inputs = buildInputs(client, player, snap, now);
        Decision d = brain.tick(inputs);
        lastDecision = d;
        if (d.stopping()) {
            beginStop(client, player, spear, d, now);
            return;
        }
        applyMovementAndView(client, player, d, now);
        attackStep(client, player, spear, d, now);
        scanStep(client, player, d, now);
    }

    // ── Reading the situation (from the Cosmic state store) ─────────────────

    private CombatInputs buildInputs(MinecraftClient client, ClientPlayerEntity player, CosmicContextSnapshot snap, long now) {
        double px = player.getX();
        double pz = player.getZ();
        live.clear();
        List<Foe> bandits = new ArrayList<>();
        List<Foe> players = new ArrayList<>();
        String locked = brain.targetId();
        int liveCount = 0;
        double banditRange = targetRange.value() + 10.0D;
        double playerRange = safetyRadius.value() * 2.0D;
        FriendsModule friends = FriendsModule.get();
        Map<UUID, Double> seen = new HashMap<>();
        Map<String, Vec3d> nextSmooth = new HashMap<>();
        for (CosmicContextSnapshot.ClassifiedEntity ce : snap.combat().entities()) {
            Raw.Entity r = ce.entity();
            boolean isPlayerEntity = r.player();
            double dist = Math.hypot(r.x() - px, r.z() - pz);
            BanditClassifier.Classification c = BanditClassifier.classify(r.name(), (r.name() + " " + r.shown()).trim());
            if (BanditClassifier.isBandit(c, anyBandit.on())) {
                if (dist > banditRange) {
                    continue;
                }
                String id = c.named() ? r.name() : r.name() + "#" + r.id();
                Entity entity = liveCount < MAX_LIVE || id.equals(locked) ? client.world.getEntityById(r.id()) : null;
                double x = r.x();
                double y = r.y();
                double z = r.z();
                double health = r.health();
                double maxHealth = Double.NaN;
                double vx = 0.0D;
                double vz = 0.0D;
                Foe.Sight sight = Foe.Sight.UNKNOWN;
                if (entity instanceof LivingEntity le && le.isAlive()) {
                    liveCount++;
                    live.put(id, le);
                    x = le.getX();
                    y = le.getY();
                    z = le.getZ();
                    health = le.getHealth();
                    maxHealth = le.getMaxHealth();
                    Vec3d moved = new Vec3d(le.getX() - le.lastX, 0.0D, le.getZ() - le.lastZ);
                    Vec3d old = smooth.get(id);
                    Vec3d now0 = old == null ? moved : old.multiply(0.6D).add(moved.multiply(0.4D));
                    nextSmooth.put(id, now0);
                    vx = now0.x;
                    vz = now0.z;
                    sight = sight(client, player, le) ? Foe.Sight.YES : Foe.Sight.NO;
                    dist = Math.hypot(x - px, z - pz);
                } else if (entity != null) {
                    continue; // dead / removed since the sample
                }
                bandits.add(new Foe(id, c.kind().name(), x, y, z, vx, vz, health, maxHealth, sight));
            } else if (isPlayerEntity && dist <= playerRange) {
                Entity entity = client.world.getEntityById(r.id());
                if (!(entity instanceof PlayerEntity other) || other == player || other.isSpectator() || !other.isAlive()) {
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
                if (!stopNearPlayer.on()) {
                    // Players still matter as bodies in the way, but they are not a reason to back off: they are listed far away.
                    continue;
                }
                double d = Math.hypot(other.getX() - px, other.getZ() - pz);
                seen.put(other.getUuid(), d);
                players.add(new Foe(name, "PLAYER", other.getX(), other.getY(), other.getZ(), 0.0D, 0.0D, other.getHealth(), other.getMaxHealth(),
                        Foe.Sight.UNKNOWN));
            }
        }
        smooth.clear();
        smooth.putAll(nextSmooth);
        lastPlayerDistance.clear();
        lastPlayerDistance.putAll(seen);
        VoxelView view = new LiveWorldView(client.world, world.classifier());
        CombatInputs.Me me = new CombatInputs.Me(px, player.getY(), pz, player.getYaw(), player.getHealth(), player.isOnGround(),
                snap.player().manualInput() || InputSensor.anyMovementPhysical(client));
        return new CombatInputs(now, me, bandits, players, new WorldTerrain(view), spearStatus, attack.phase(), pathStatus, pathFailures);
    }

    // ── Turning the brain's wishes into intents ──────────────────────────────

    private void applyMovementAndView(MinecraftClient client, ClientPlayerEntity player, Decision d, long now) {
        CombatState state = d.state();
        IntentPriority movePriority = state == CombatState.EVADE || state == CombatState.RETREAT ? IntentPriority.COMBAT_EVADE
                : state == CombatState.RECOVER ? IntentPriority.UNSTUCK : IntentPriority.PATHFINDING;
        InputController.Keys keys = InputController.Keys.NONE;
        float moveYaw = Float.NaN;
        lastMove = d.move();
        switch (d.move().kind()) {
            case DIRECTION -> {
                driver.stop();
                pathStatus = CombatInputs.PathStatus.NONE;
                moveYaw = Geo.yawOf(d.move().dx(), d.move().dz());
                keys = Geo.keysFor(d.move().dx(), d.move().dz(), player.getYaw(), d.move().sprint(), d.move().jump() && player.isOnGround());
            }
            case PATH_TO -> {
                LaneDriver.Drive drive = followPath(client, player, d.move().goalX(), d.move().goalZ(), now);
                if (drive != null) {
                    moveYaw = drive.yaw();
                    keys = new InputController.Keys(drive.forward(), false, false, false, drive.jump(), drive.sprint(), false);
                }
            }
            default -> {
                if (state != CombatState.SCAN) {
                    // (SCAN may be walking the patrol route: scanStep owns the driver then)
                    driver.stop();
                    pathStatus = CombatInputs.PathStatus.NONE;
                }
            }
        }
        if (lastCombatState == CombatState.SCAN && state != CombatState.SCAN) {
            driver.stop(); // a patrol path must not be taken for the way to a bandit
            pathBusy = false;
        }
        lastCombatState = state;
        String targetNow = d.targetId();
        if (targetNow == null ? lastTargetId != null : !targetNow.equals(lastTargetId)) {
            pathFailures = 0; // a new target starts with a clean path record (the brain resets its own count with it)
            lastTargetId = targetNow;
        }
        control.submit(new MovementIntent(movePriority, "bandit:" + state, keys));

        float yaw = Float.NaN;
        float pitch = 0.0F;
        IntentPriority lookPriority = IntentPriority.TARGET_LOOK;
        switch (d.look()) {
            case TARGET -> {
                LivingEntity target = d.targetId() == null ? null : live.get(d.targetId());
                if (target != null) {
                    float[] a = RotationMath.anglesTo(player.getX(), player.getEyeY(), player.getZ(), target.getX(), target.getY() + target.getHeight() * 0.65D,
                            target.getZ());
                    yaw = a[0];
                    pitch = MathHelper.clamp(a[1], -60.0F, 60.0F);
                }
            }
            case MOVEMENT -> {
                yaw = moveYaw;
                lookPriority = IntentPriority.PATHFINDING;
            }
            default -> {
            }
        }
        if (state == CombatState.EVADE || state == CombatState.RETREAT) {
            lookPriority = IntentPriority.COMBAT_EVADE;
        } else if (state == CombatState.RECOVER) {
            lookPriority = IntentPriority.UNSTUCK;
        }
        if (!Float.isNaN(yaw)) {
            float omega = lookPriority == IntentPriority.COMBAT_EVADE ? 8.0F : 6.0F;
            control.submit(RotationIntent.following(lookPriority, "bandit:" + state, yaw, pitch, omega, omega));
        }
        lastDetail = d.detail();
    }

    /** One tick of the path to a goal (the ore macro's path finder and driver); null = no way (yet). */
    private LaneDriver.@Nullable Drive followPath(MinecraftClient client, ClientPlayerEntity player, double goalX, double goalZ, long now) {
        boolean goalMoved = !Double.isNaN(pathGoalX) && Math.hypot(goalX - pathGoalX, goalZ - pathGoalZ) > 6.0D;
        if (driver.path() == null && !pathBusy && now - pathAtMs > 600L || goalMoved && !pathBusy && now - pathAtMs > 2500L) {
            requestGoalPath(client, player, goalX, goalZ, now);
        }
        if (driver.path() == null) {
            pathStatus = pathBusy ? CombatInputs.PathStatus.PLANNING : pathFailures > 0 ? CombatInputs.PathStatus.FAILED : CombatInputs.PathStatus.NONE;
            return null;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), false);
        if (drive.status() == LaneDriver.Status.ARRIVED) {
            pathStatus = CombatInputs.PathStatus.ARRIVED;
            driver.stop();
        } else if (drive.status() == LaneDriver.Status.STUCK) {
            pathStatus = CombatInputs.PathStatus.FAILED;
            pathFailures++;
            driver.stop();
        } else {
            pathStatus = CombatInputs.PathStatus.FOLLOWING;
        }
        return drive;
    }

    private void requestGoalPath(MinecraftClient client, ClientPlayerEntity player, double goalX, double goalZ, long now) {
        LongOpenHashSet goals = new LongOpenHashSet();
        int gy = MathHelper.floor(player.getY());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    goals.add(Pos.pack(MathHelper.floor(goalX) + dx, gy + dy, MathHelper.floor(goalZ) + dz));
                }
            }
        }
        requestPath(client, player, goals, PathFor.ATTACK, goalX, goalZ, now);
    }

    private void requestPath(MinecraftClient client, ClientPlayerEntity player, LongOpenHashSet goals, PathFor purpose, double goalX, double goalZ,
                             long now) {
        pathAtMs = now;
        pathGoalX = goalX;
        pathGoalZ = goalZ;
        pathFor = purpose;
        WorldSnapshot snapshot = world.snapshot(player, PATH_RADIUS, 16);
        VoxelView view = new OpenView(snapshot);
        long version = snapshot.version();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        pathBusy = true;
        pathStatus = CombatInputs.PathStatus.PLANNING;
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
            if (purpose == PathFor.PATROL) {
                if (!brain.state().equals(CombatState.SCAN)) {
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
            if (path != null && path.size() > 1) {
                driver.start(path);
                pathStatus = CombatInputs.PathStatus.FOLLOWING;
            } else {
                pathFailures++;
                pathStatus = CombatInputs.PathStatus.FAILED;
            }
        });
    }

    // ── Scan: patrol and the search timeout ─────────────────────────────────

    private void scanStep(MinecraftClient client, ClientPlayerEntity player, Decision d, long now) {
        if (brain.state() != CombatState.SCAN) {
            scanSinceMs = now;
            return;
        }
        if (spearState == SpearState.MISSING) {
            if (missingSinceMs == 0L) {
                missingSinceMs = now;
            }
            if (now - missingSinceMs > 10_000L && attack == Attack.NONE) {
                fail(client, "No spear in the hotbar.");
            }
            return;
        }
        missingSinceMs = 0L;
        if (spearState == SpearState.AVAILABLE) {
            player.getInventory().setSelectedSlot(spearSlot(player));
        }
        if (!patrol.isEmpty()) {
            patrolStep(client, player, now);
        } else if (now - scanSinceMs > searchTimeout.value() * 1000L && attack == Attack.NONE) {
            fail(client, "No bandit to fight for " + searchTimeout.value() + " s.");
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
            lastDetail = "Patrol: planning to waypoint " + (patrolIndex % patrol.size() + 1);
            return;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), false);
        control.submit(new MovementIntent(IntentPriority.PATHFINDING, "bandit:patrol",
                new InputController.Keys(drive.forward(), false, false, false, drive.jump(), false, false)));
        control.submit(RotationIntent.following(IntentPriority.PATHFINDING, "bandit:patrol", drive.yaw(), 0.0F, 4.0F, 4.0F));
        lastDetail = String.format(Locale.ROOT, "Patrol: waypoint %d/%d (%.0fm)", patrolIndex % patrol.size() + 1, patrol.size(), dist);
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            driver.stop();
        }
    }

    // ── The spear attack (aim, throw, wait, recall) ──────────────────────────

    private float aimWidth() {
        return 3.0F + aimSpeed.value() * 1.2F;
    }

    /** Lead + drop for the target now; null when it cannot be reached. */
    private SpearBallistics.@Nullable Solution solve(ClientPlayerEntity player, LivingEntity target, @Nullable String targetId) {
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
        for (Map.Entry<String, LivingEntity> e : live.entrySet()) {
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

    private void aimIntent(ClientPlayerEntity player) {
        SpearBallistics.Solution s = solution;
        if (s != null) {
            control.submit(RotationIntent.aimed(IntentPriority.TARGET_LOOK, "bandit:aim", RotationMode.TURNING, MathHelper.wrapDegrees(s.yaw()),
                    MathHelper.clamp(s.pitch(), -89.0F, 89.0F), aimWidth()));
        }
    }

    private void attackStep(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, Decision d, long now) {
        String id = d.targetId();
        LivingEntity target = id == null ? null : live.get(id);
        switch (attack) {
            case NONE -> {
                if (spearState == SpearState.AVAILABLE && brain.state().fighting()) {
                    player.getInventory().setSelectedSlot(spearSlot(player));
                }
                if (d.attackAllowed() && target != null && spear == null && spearState == SpearState.READY && now >= nextAttackMs) {
                    setAttack(Attack.AIMING, now);
                    aimedTicks = 0;
                }
            }
            case AIMING -> {
                if (!d.attackAllowed() || target == null || spearState != SpearState.READY) {
                    cancelAim(client, now, spearState == SpearState.READY ? "ATTACK_NOT_ALLOWED" : "SPEAR_NOT_READY");
                    return;
                }
                if (now - attackSinceMs > AIM_TIMEOUT_MS) {
                    cancelAim(client, now + 2_000L, "AIM_TIMEOUT");
                    return;
                }
                solution = solve(player, target, id);
                if (solution == null) {
                    cancelAim(client, now + 1_000L, "OUT_OF_BALLISTIC_REACH");
                    return;
                }
                aimIntent(player);
                float yawError = Math.abs(RotationMath.wrap(solution.yaw() - player.getYaw()));
                float pitchError = Math.abs(solution.pitch() - player.getPitch());
                lastDetail = String.format(Locale.ROOT, "aim err %.1f/%.1f", yawError, pitchError);
                aimedTicks = yawError < 1.5F && pitchError < 2.5F ? aimedTicks + 1 : 0;
                if (aimedTicks >= AIMED_TICKS) {
                    reactionTicks = REACTION_TICKS;
                    setAttack(Attack.READY, now);
                }
            }
            case READY -> {
                if (target == null || spearState != SpearState.READY || !d.attackAllowed()) {
                    cancelAim(client, now, target == null ? "TARGET_LOST" : "SPEAR_NOT_READY");
                    return;
                }
                solution = solve(player, target, id);
                if (solution == null || !sight(client, player, target)) {
                    setAttack(Attack.AIMING, now);
                    return;
                }
                aimIntent(player);
                float yawError = Math.abs(RotationMath.wrap(solution.yaw() - player.getYaw()));
                float pitchError = Math.abs(solution.pitch() - player.getPitch());
                if (yawError > 2.5F || pitchError > 4.0F) {
                    aimedTicks = 0;
                    setAttack(Attack.AIMING, now);
                    return;
                }
                String bystander = playerInLine(client, player, solution.yaw(), Math.sqrt(target.squaredDistanceTo(player)));
                if (bystander != null) {
                    // The spear pierces everything on its path - friends and strangers too.
                    lastDetail = "player in line: " + bystander;
                    aimedTicks = 0;
                    setAttack(Attack.AIMING, now);
                    return;
                }
                if (reactionTicks-- > 0) {
                    return;
                }
                clicked = false;
                setAttack(Attack.THROWING, now);
            }
            case THROWING -> throwing(client, player, spear, now);
            case FLIGHT -> flight(client, player, spear, now);
            case RECALL_DUE -> recallDue(client, player, spear, now);
            case RECALLING -> recalling(client, player, spear, now);
            case SETTLING -> {
                if (now >= nextAttackMs) {
                    setAttack(Attack.NONE, now);
                }
            }
        }
    }

    private void setAttack(Attack next, long now) {
        attack = next;
        attackSinceMs = now;
        if (tracing()) {
            ThePrisonsClient.LOGGER.info("[BanditCombat] ATTACK -> {}", next);
        }
    }

    private void cancelAim(MinecraftClient client, long nextAllowedMs, String why) {
        releaseUse(client);
        solution = null;
        aimedTicks = 0;
        nextAttackMs = Math.max(nextAttackMs, nextAllowedMs);
        setAttack(Attack.NONE, System.currentTimeMillis());
        lastDetail = "aim dropped: " + why;
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
        aimIntent(player);
        if (spear != null) {
            // Away (an instant-throw spear lets go before the charge time): let go of the key.
            releaseUse(client);
            failedThrows = 0;
            beginFlight();
            setAttack(Attack.FLIGHT, now);
            return;
        }
        long held = now - attackSinceMs;
        if (held > throwTimeout.value() * 1000L) {
            releaseUse(client);
            failedThrows++;
            if (failedThrows >= 5) {
                fail(client, "The throws do not work (no spear flies) - check \"Hold the throw\".");
                return;
            }
            nextAttackMs = now + 1_000L;
            setAttack(Attack.NONE, now);
            return;
        }
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

    private void flight(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        if (spear == null) {
            if (now - attackSinceMs < 500L) {
                return;
            }
            spearBack(now);
            return;
        }
        if (now - attackSinceMs > returnTimeout.value() * 1000L) {
            setAttack(Attack.RECALL_DUE, now);
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
        for (Map.Entry<String, LivingEntity> e : live.entrySet()) {
            LivingEntity b = e.getValue();
            Vec3d v = smooth.getOrDefault(e.getKey(), Vec3d.ZERO);
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
        lastDetail = "spear out, on the way back: " + onWay;
        if (fire) {
            setAttack(Attack.RECALL_DUE, now);
        }
    }

    private void recallDue(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        hudRecall = "REQUIRED";
        if (spear == null) {
            spearBack(now);
            return;
        }
        if (!autoRecall.on() && pendingStop == null) {
            // The player recalls by hand: just wait for the spear (the return timeout is the limit).
            if (now - attackSinceMs > returnTimeout.value() * 1000L + 5_000L) {
                fail(client, "The spear did not come back.");
            }
            return;
        }
        sendRecall(player);
        setAttack(Attack.RECALLING, now);
    }

    private void recalling(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        hudRecall = "SENT x" + recallsSent;
        if (spear == null) {
            spearBack(now);
            return;
        }
        if (now - attackSinceMs > 1500L * Math.max(1, recallsSent) && recallsSent < 6) {
            sendRecall(player);
        } else if (now - attackSinceMs > 9_000L && recallsSent >= 6) {
            fail(client, "The spear does not come back.");
        }
    }

    private void spearBack(long now) {
        throwsDone++;
        hudRecall = "NOT NEEDED";
        nextAttackMs = now + THROW_GAP_MS;
        setAttack(Attack.SETTLING, now);
    }

    private void sendRecall(ClientPlayerEntity player) {
        if (player.networkHandler != null) {
            // F: the swap-hands action. Cosmic turns it into the spear's recall.
            player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ORIGIN, Direction.DOWN));
            recallsSent++;
        }
    }

    // ── Stopping ─────────────────────────────────────────────────────────────

    /** The brain ended the macro (manual input, critical health / player, retreat limit, loop). A spear in the air is called back first. */
    private void beginStop(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, Decision d, long now) {
        pendingStop = d.stopReason();
        pendingStopQuiet = "MANUAL_INPUT".equals(d.stopReason());
        pendingStopSinceMs = now;
        driver.stop();
        control.submit(MovementIntent.none(IntentPriority.EMERGENCY, "stopping"));
        releaseUse(client);
        if (spear == null || !d.stopAfterRecall()) {
            finishStop(client);
            return;
        }
        // The spear is out: no more attacks, bring it back first.
        if (attack == Attack.FLIGHT || attack == Attack.NONE || attack == Attack.SETTLING) {
            setAttack(Attack.RECALL_DUE, now);
        }
    }

    private void stopStep(MinecraftClient client, ClientPlayerEntity player, @Nullable ProjectileEntity spear, long now) {
        control.submit(MovementIntent.none(IntentPriority.EMERGENCY, "stopping"));
        if (spear == null || now - pendingStopSinceMs > STOP_RECALL_MS) {
            finishStop(client);
            return;
        }
        attackStep(client, player, spear, new Decision(CombatState.STOPPED, "stopping", null, Decision.Move.NONE, Decision.Look.NONE, false,
                pendingStop, true, ""), now);
    }

    private void finishStop(MinecraftClient client) {
        String reason = pendingStop == null ? "stopped" : pendingStop;
        if (pendingStopQuiet) {
            releaseUse(client);
            driver.stop();
            ThePrisonsClient.LOGGER.info("[BanditMacro] {}", reason);
            disableSelf(reason);
        } else {
            fail(client, reason);
        }
    }

    private void fail(MinecraftClient client, String reason) {
        releaseUse(client);
        driver.stop();
        brain.stop(System.currentTimeMillis(), reason);
        alertStop(client, reason);
    }

    // ── Captures ─────────────────────────────────────────────────────────────

    /** What a capture says about the bandit macro: the FSM, the target, the threats, the intents, the ground around, the spear. */
    private Map<String, String> captureContext() {
        Map<String, String> m = new TreeMap<>(brain.summary(System.currentTimeMillis()));
        var rot = control.rotationWinner();
        m.put("bandit.movementOwner", control.input().winnerSource().isEmpty() ? "-" : control.input().winnerSource() + "/" + control.input().winnerPriority());
        m.put("bandit.movementIntent", lastMove.kind() + (lastMove.kind() == Decision.Move.Kind.DIRECTION
                ? String.format(Locale.ROOT, " (%.2f, %.2f)", lastMove.dx(), lastMove.dz()) : ""));
        m.put("bandit.rotationOwner", rot == null ? "-" : rot.source() + "/" + rot.priority());
        m.put("bandit.rotationIntent", rot == null ? "-" : String.format(Locale.ROOT, "%s yaw %.0f pitch %.0f", rot.follow() ? "follow" : "aim", rot.yaw(), rot.pitch()));
        m.put("bandit.pathStatus", pathStatus.name());
        m.put("bandit.attack", attack.name());
        m.put("bandit.spearStatus", spearStatus.name());
        m.put("bandit.spearState", spearState.name());
        m.put("bandit.lastDetail", lastDetail);
        StringBuilder foes = new StringBuilder();
        for (Map.Entry<String, LivingEntity> e : live.entrySet()) {
            foes.append(e.getKey()).append(String.format(Locale.ROOT, "@%.0f ", Math.sqrt(e.getValue().squaredDistanceTo(MinecraftClient.getInstance().player))));
        }
        m.put("bandit.liveBandits", foes.toString().trim());
        return m;
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
