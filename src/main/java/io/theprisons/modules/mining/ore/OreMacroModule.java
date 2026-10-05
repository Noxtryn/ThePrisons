package io.theprisons.modules.mining.ore;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.Phases;
import io.theprisons.core.analytics.StatsService;
import io.theprisons.core.command.CommandService;
import io.theprisons.core.concurrent.Worker;
import io.theprisons.core.control.BlockBreaker;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.control.InputController;
import io.theprisons.core.control.RotationMath;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.hud.HudLine;
import io.theprisons.core.module.AutomationModule;
import io.theprisons.core.module.Category;
import io.theprisons.core.nav.NavigationPath;
import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.PathStraightener;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.render.Overlay;
import io.theprisons.core.setting.Settings;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.core.world.TargetRegistry;
import io.theprisons.core.world.ArchiveView;
import io.theprisons.core.nav.VoxelView;
import io.theprisons.core.world.WorldArchive;
import io.theprisons.core.world.WorldCache;
import io.theprisons.core.world.WorldSnapshot;
import io.theprisons.modules.mining.ore.route.Route;
import io.theprisons.modules.mining.ore.route.RouteCorridor;
import io.theprisons.modules.mining.ore.route.RouteRecorder;
import io.theprisons.modules.mining.ore.route.RouteStore;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Ore Macro for Cosmic-style ore caves (a mined ore turns into stone and respawns): walks through the middle of the
 * cave's tunnels and mines the floor ores it passes; walls and ceiling are never mined.
 *
 * <ul>
 *     <li><b>Steer</b> ({@link TunnelSteer}, every tick = every block): which way next, in the middle between the
 *     walls, towards the ore, turning as little as possible, not up where it is not a real way up, not back over the
 *     blocks just walked.</li>
 *     <li><b>Travel</b> ({@link LanePlanner#travel} on the worker + {@link LaneDriver}): only when no direction shows
 *     any ore for a while - walk to the richest reachable spot, then steer again.</li>
 *     <li><b>Look</b> (core {@code FollowMotion}, every rendered frame): the view follows the walking direction and the
 *     ground angle ({@link TerrainPitch}) through critically damped springs; it never looks around.</li>
 *     <li><b>Mine</b>: whatever floor ore passes under the crosshair.</li>
 *     <li><b>Remember</b> ({@link RegionMemory}, saved per server / world): yield per area and dead ends.</li>
 * </ul>
 */
public final class OreMacroModule extends AutomationModule {
    public static final String ID = "ore_macro";

    enum Phase { STEER, TRAVEL, WAIT, ROUTE, BREAK, GUARD }

    private static final long SKIP_MS = 15_000L;
    private static final long BLOCK_MS = 3_000L;
    /** View springs (1/s): yaw follows the steering within ~0.4 s, the angle changes within ~0.5 s. */
    private static final float YAW_OMEGA = 12.0F;
    private static final float PITCH_OMEGA = 9.0F;
    /** Ticks without any ore in any direction before walking to the richest spot. */
    private static final int NO_ORE_TICKS = 20;
    /** How often the mined share around the player is counted. */
    private static final int DEPLETION_TICKS = 10;
    private static final long DEPLETED_RETRY_MS = 3_000L;
    /**
     * The 6-block rule: up to 5 blocks are walked on stone / deepslate (the tunnel may lead to new ore); at this many
     * without mining an ore - and none within 5 blocks ahead - the lanes 3 blocks left and right are looked at first
     * ({@link TunnelSteer#lookAside}), and only without ore there the tunnel is left for another way.
     */
    private static final int BARREN_BLOCKS_DEFAULT = 6;
    /** Cave mode: the next look for a tunnel at the earliest after this long. */
    private static final long CAVE_RETRY_MS = 3_000L;
    /** Cave mode looks for a tunnel only after this many blocks on stone without ore ahead. */
    private static final int CAVE_BARREN_BLOCKS = 3;
    /** After a planned route failed (blocked, unguarded, only stone): no new tunnel plan for this long. */
    private static final long FAILED_PLAN_PAUSE_MS = 15_000L;
    private static final int STUCK_TICKS = 30;
    /** Stuck recovery: this many ticks walking back (0.5 s) before the new way. */
    private static final int RECOVER_TICKS = 10;
    /** Stuck this many times within {@link #STUCK_WINDOW_MS}: a planned way elsewhere. */
    private static final int STUCK_REPLAN = 3;
    private static final long STUCK_WINDOW_MS = 20_000L;
    /** Blocks walked just before (not walked back over). */
    private static final int WALKED_MEMORY = 1024;
    /** A route waypoint counts as reached within this many blocks (horizontally). */
    private static final double WAYPOINT_REACHED = 1.5D;
    /**
     * Failsafe: a waypoint that cannot be found (no path) or reached (stuck, no way on) and got no closer by
     * {@value #TROUBLE_PROGRESS} blocks within this time is skipped.
     */
    private static final long ROUTE_FAILSAFE_MS = 5_000L;
    private static final double TROUBLE_PROGRESS = 1.5D;
    /** Waypoint 1 is walked to directly within this distance / height; farther away along the route backwards. */
    private static final double APPROACH_DIRECT = 32.0D;
    private static final double APPROACH_DIRECT_HEIGHT = 6.0D;
    /** ... and this far further down (dropping down is fine, no fall damage). */
    private static final double APPROACH_DIRECT_DEPTH = 24.0D;
    /** Waypoints passed on the way to waypoint 1 count as reached within this distance. */
    private static final double APPROACH_HOP_REACHED = 3.0D;
    /** The next path of the way to waypoint 1 is planned this many blocks before the current one ends. */
    private static final double PLAN_AHEAD = 5.0D;
    /**
     * Route paths may drop this far: the server gives no fall damage, so the way to the nearest waypoint may simply
     * jump down any ledge.
     */
    private static final int ROUTE_DROP = 32;
    /** How long right click is held for an item ability (2.2 s). */
    private static final int ABILITY_HOLD_TICKS = 44;
    /** How often (ticks) the hotbar is checked for a ready pet / ability. */
    private static final int USE_CHECK_TICKS = 10;
    private static final int SORT_CHECK_TICKS = 20;
    /** After a foreign teleport: wait 3 s for the scanner, then look for ore within ~24 blocks (6 buckets of 4). */
    private static final int RELOCATE_CHECK_TICKS = 60;
    private static final int RELOCATE_BUCKETS = 6;
    /**
     * Item sorter: 250 ms between the steps, the vault must open within 5 s. /spawn and /home count down (e.g. from 11
     * s): the "in 1 seconds" line must come within 60 s, then 1.5 s until arrived.
     */
    private static final int SORT_STEP_TICKS = 5;
    private static final int SORT_OPEN_TICKS = 100;
    private static final int SORT_HOME_TICKS = 1200;
    private static final int SORT_SPAWN_TICKS = 1200;
    private static final int SORT_ARRIVE_TICKS = 30;
    /** An item the server shows no cooldown for after use is used again only after this (spam protection). */
    private static final long PET_FALLBACK_MS = 300_000L;
    private static final long ABILITY_FALLBACK_MS = 30_000L;
    /** Breaks: a warden further away than this (weighted blocks) is not walked to. */
    private static final double BREAK_MAX_DISTANCE = 150.0D;
    /** "Right beside the arden": horizontal distance. */
    private static final double BREAK_NEAR = 3.0D;
    private static final long BREAK_WALK_MS = 120_000L;
    private static final long BREAK_RETRY_MS = 60_000L;
    private static final int BREAK_PATH_TRIES = 5;
    /** Attacked by a mob: back to mining once no hit came for this long at the guard. */
    private static final long FLEE_CALM_MS = 5_000L;
    /** ... but still being hit after this long at the guard: stop with an alert. */
    private static final long FLEE_MAX_WAIT_MS = 60_000L;
    /** After a hit, at the guard: go on only when no player is within this many blocks to each side. */
    private static final double FLEE_PLAYER_RANGE = 16.0D;
    /** A real player this close shortens the excursions outside the guarded zone to the "player near" budget. */
    private static final double GUARD_PLAYER_RANGE = 32.0D;
    /** At most one /sellall per second, however often the server repeats "inventory is full". */
    private static final int SELL_COOLDOWN_TICKS = 20;
    /**
     * Guarded area: out of every circle (and off the strips between them) → back to a guard; back in once this far
     * inside. The steering itself keeps {@link GuardArea#EDGE} inside, so this only fires when something else carried
     * it out.
     */
    private static final double GUARD_BACK_INSIDE = 2.0D;
    /** Blocks-per-second learning window for the region memory (ticks). */
    private static final int LEARN_TICKS = 200;
    private static final int EXTRA_TICKS = 6;

    private final TargetRegistry targets;
    private final StatsService stats;
    private final BlockBreaker breaker = new BlockBreaker();
    private final TunnelSteer steer = new TunnelSteer();
    /** The steering of the 30.09. build (setting "Classic steering"): free walking and planned routes when on. */
    private final ClassicSteer classic = new ClassicSteer();
    private final LaneDriver driver = new LaneDriver(3.0D);
    private final TerrainPitch terrain = new TerrainPitch();
    private final RegionMemory region = new RegionMemory();
    private final Long2LongOpenHashMap skipped = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap extras = new Long2LongOpenHashMap();
    private final LongOpenHashSet walked = new LongOpenHashSet();
    /** Share of the ore around the player that is already mined (see {@link OreDepletion}). */
    private final OreDepletion depletion = new OreDepletion();
    /** No new "mined out" travel before this time (the last search found nothing better). */
    private long depletedRetryMs;
    private final LongArrayFIFOQueue walkedOrder = new LongArrayFIFOQueue();

    private final Settings.MultiChoiceSetting ores;
    private final Settings.DoubleSetting reachSetting;
    private final Settings.IntSetting maxDrop;
    private final Settings.BoolSetting sprint;
    private final Settings.BoolSetting classicSteering;
    private final Settings.BoolSetting centring;
    private final Settings.IntSetting stoneBlocks;
    private final Settings.BoolSetting routeMemoryOn;
    private final Settings.IntSetting inventoryLimit;
    private final Settings.BoolSetting antiStuck;
    private final Settings.IntSetting guardLookAhead;
    private final Settings.IntSetting minedPercent;
    private final Settings.BoolSetting planRoutes;
    /** The remembered world (all loaded chunks, saved to disk) the route planner works on. */
    private final WorldArchive archive;
    /** The planned route being walked ({@code null} = none) and the segment: waypoint planIndex → planIndex + 1. */
    private OrePlanner.@Nullable Plan plan;
    private int planIndex;
    private Worker.@Nullable Job planJob;
    private OrePlanner.@Nullable Plan planResult;
    /** Learned routes (walked to the end inside the guarded area), per server and dimension. */
    private final RouteMemory routeMemory = new RouteMemory();
    /** File work of the route memory (one background thread). */
    private final RouteIo routeIo = new RouteIo();
    /** The mine the player is in, from the server's "Welcome to the X Mine!" / "(!) X Mine" lines. */
    private String currentMine = "unknown";
    private static final java.util.regex.Pattern MINE_NAME =
            java.util.regex.Pattern.compile("^(?:\\(!\\)\\s*)?(?:welcome to the\\s+)?([A-Za-z ]{1,24}? Mine)!?$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private @Nullable Path routeMemoryFile;
    /** The plan being walked: ores counted at its start, and whether it stayed inside the guarded area so far. */
    private long planOresStart;
    private boolean planGuarded;
    private long nextPlanMs;
    private long nextCaveMs;
    /** Classic steering: the next "wall ahead" plan at the earliest then. */
    private long nextWallPlanMs;
    private static final long WALL_PLAN_MS = 15_000L;
    /** Where the last "too much stone around" new plan was made ({@code NaN} = none yet). */
    private double rockX = Double.NaN;
    private double rockZ = Double.NaN;
    private double rockShare;
    private String planInfo = "";
    private String planWhy = "";
    /** The plan leg whose direction the steering was given ({@code -1} = none yet). */
    private int directedIndex = -1;
    private final Settings.BoolSetting showPath;
    private final Settings.BoolSetting showWaypoints;
    private final Settings.BoolSetting usePet;
    private final Settings.TextSetting petName;
    private final Settings.BoolSetting useAbility;
    private final Settings.TextSetting abilityNames;
    private final Settings.BoolSetting itemSorter;
    private final Settings.TextSetting vaultShards;
    private final Settings.TextSetting vaultOther;
    private final Settings.TextSetting vaultEnergy;
    private final Settings.IntSetting energyTrip;
    private final Settings.IntSetting godlyTrip;
    private final Settings.IntSetting moneyTrip;
    private final Settings.BoolSetting openShards;
    private final Settings.BoolSetting openContrabands;
    /** The steps of the current trip to spawn (null = none). */
    private java.util.@org.jspecify.annotations.Nullable List<Trip> trip;
    private int tripIndex;
    /** "You have entered the Diamond Zone" since the /spawn. */
    private boolean zoneEntered;
    /** Pickaxe energy full and no sponge in the inventory: fetch one from the vaults on the next trip. */
    private boolean needSponge;
    private final Milestones milestones = new Milestones();
    /** A level / prestige step was reached: the next trip to spawn is due (cleared when it starts). */
    private boolean levelDue;
    private boolean spongeFetched;
    private int spongeVault;
    private int @org.jspecify.annotations.Nullable [] spongeVaults;
    private double @org.jspecify.annotations.Nullable [] walkFrom;
    /** Contrabands / shard menus opened this trip (a cap against loops). */
    private int tripRounds;
    private int itemsBefore;
    private int contrabandsBefore;
    private int aimTicks;
    private boolean shardNamesLogged;
    private boolean energyNamesLogged;
    /** Death recovery: fallen into a mine zone ("You entered a ... zone") after /warp. */
    private boolean mineZoneEntered;
    /** "Teleport ... cancelled because you moved" during a trip: /spawn or /home again. */
    private boolean teleportCancelled;
    /** After /warp: ticks of the last jump over the edge (then -1: jumped). */
    private int dropTicks;
    private float dropYaw;
    /** The next "pickaxe full?" check from then on (no hectic repeats when no sponge can be had). */
    private long fullCheckAfterMs;
    /** Human pauses in the /warp steps (-1 = not started yet). */
    private int warpLook = -1;
    private int warpSettle = -1;
    private double dropBest;
    private int dropStall;
    private int teleportRetries;
    /** Ticks left to find out whether the macro was started at spawn (the spawn work and /warp come first). */
    private int spawnCheckTicks;
    private int @org.jspecify.annotations.Nullable [] scrollVaults;
    private int scrollVault;
    private int scrollTries;
    /** Whitescrolls still to fetch (-1 = not counted yet): one per satchel + 1 for the pickaxe. */
    private int scrollNeed = -1;
    /** Absorbers still to fetch from the vaults (16 in the inventory). */
    private int absorberNeed;
    private boolean fetchingAbsorbers;
    private int scrollSlot = -1;
    private int scrollTarget = -1;
    private static final java.util.regex.Pattern MINE_ZONE = java.util.regex.Pattern.compile("you (?:have )?entered (?:a|an|the) .+ zone");
    private boolean moneyNamesLogged;
    private int mergeStep;
    /** The /pv being filled and how many were tried for this kind. */
    private int vaultNumber;
    private int vaultTry;
    /** Item sorter scan: the items the open /pv holds, the highest /pv that opened and the fallback round. */
    private java.util.Set<String> vaultKeys;
    private int vaultHighest;
    private boolean vaultFallback;
    private int[] fallbackOrder = new int[0];
    private int fallbackAt;
    /** A full vault may still take items that stack onto what it holds: try the shift-click, the stall check decides. */
    private boolean vaultMergeFull;
    private int mergeSlot;
    private int redeemClicks;
    /** A contraband / godly shard starts a trip again only from then on (one that could not be opened: no loop). */
    private long lootTripAfterMs;
    private static final int ARRIVE_TICKS = 600;
    private static final int WALK_TICKS = 80;
    private static final double WALK_BLOCKS = 8.0D;
    /** Back at home: 0.5 blocks away from a block at leg height (sneaking, 2 s at the most). */
    private static final double NUDGE_BLOCKS = 0.45D;
    private static final int NUDGE_TICKS = 40;
    private boolean nudgeBack;
    private static final int CONTRABAND_TICKS = 400;
    /** After placing a contraband: at most this long for its items once the chest is gone (8 s). */
    private static final int CONTRABAND_WAIT_TICKS = 160;
    private static final int MAX_TRIP_ROUNDS = 24;
    private static final float CONTRABAND_PITCH = 40.0F;
    /** Item sorter: the vault slot clicked last and how many items were in it (vault full = nothing moved). */
    private int sortSlot = -1;
    private int sortCount;
    private int sortTimeout;
    private boolean sortTeleporting;
    private int sortStall;
    /** Tick of a teleport the macro did not do itself (-1 = none): it stands still until the area is checked. */
    private long relocatedTick = -1L;
    private final Settings.BoolSetting breaks;
    private final Settings.IntSetting breakEveryMin;
    private final Settings.IntSetting breakEveryMax;
    private final Settings.IntSetting breakLengthMin;
    private final Settings.IntSetting breakLengthMax;
    private final Settings.BoolSetting fleeToGuard;
    private final Settings.BoolSetting guarded;
    private final Settings.BoolSetting taxFromEnergy;
    private final Settings.IntSetting outsideNear;
    /** Unguarded feet blocks walked in a row (free steering's excursion), and the last one counted. */
    private int outsideWalked;
    private long outsideBlock = Long.MIN_VALUE;
    /** Excursion used up: since when the way back in over the most ore is being planned (0 = not). */
    private long backInSince;
    /** That plan is the way back in (its result decides between the ore way and the plain way to a guard). */
    private boolean backInPlanning;
    /** How long to wait for the way back in over ore before walking straight back to a guard. */
    static final long BACK_IN_WAIT_MS = 500L;
    /**
     * The way back in may run this many blocks more outside than the budget: the zone model ends a block before the
     * real edge and the blocks walked out are marked outside with a block beside them.
     */
    static final int BACK_IN_SLACK = 3;
    /** The way back in ends this many blocks inside the zone (to each side). */
    static final int BACK_IN_DEPTH = 2;
    /** When a player was last seen near (alone only after {@value #ALONE_AFTER_MS} ms without one: no flicker). */
    private long playerSeenMs;
    private boolean guardPlayerLock;
    static final long ALONE_AFTER_MS = 5_000L;
    /** From this many other players within {@value #GUARD_PLAYER_RANGE} blocks the macro stays near the guards. */
    static final int CROWD_PLAYERS = 2;
    private final Settings.IntSetting outsideAlone;
    /** The guard tax from the energy one ore gives (see {@link EnergyTax}). */
    private final EnergyTax energyTax = new EnergyTax();
    /** Where the player stood at the last energy samples (the edge is where the jump began, not where it was seen). */
    private final double[][] taxTrail = new double[EnergyTax.WINDOW][];
    private int taxTrailNext;
    private long taxSamplesLogged;
    private boolean energyLoreLogged;
    private long loggedEnergyBlocks;
    /** Energy mode: the last place where the -4 % tax was on (walked back to when it falls away). */
    private int @org.jspecify.annotations.Nullable [] energyBack;
    private long lastOreHit = Long.MIN_VALUE;
    /** The latest ores the macro hit itself (counted there, not again when the server turns them into stone). */
    private final long[] ownHits = new long[32];
    private int ownHitNext;
    private static final double PROC_RANGE = 6.0D;
    /** Ores near the player that turned into something else without a hit of ours (procs: Shatter, Fracture...). */
    private long procOres;
    private int actionBarLogged;
    private String lastActionBar = "";
    /** The charge orbs' energy bonus (percent) last seen, per source (lore / action bar: they may differ). */
    private final java.util.Map<String, Integer> chargePercent = new java.util.HashMap<>();
    private static final java.util.regex.Pattern CHARGE = java.util.regex.Pattern.compile(
            "\\+\\s*(\\d+(?:[.,]\\d+)?)\\s*%\\s*energy\\s+gain", java.util.regex.Pattern.CASE_INSENSITIVE);
    private long nextEnergyDiagTick;
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("(\\d[\\d,.]*)([kKmMbB]?)");
    private final Settings.IntSetting guardRadius;
    /** Guards (100 HP) seen up to 128 blocks away during this run: the macro stays within the radius of one. */
    private final GuardArea guardArea = new GuardArea();
    private static final GuardArea NO_GUARDS = new GuardArea();
    /** Since when no guard is known (0 = one is known). */
    /** The sidebar's guard XP tax last read ({@code null} = no sidebar / no tax line). */
    private @org.jspecify.annotations.Nullable Boolean lastTax;
    /**
     * Sidebar reads in a row without the tax. The server rewrites the sidebar line by line, so for a moment the tax line
     * can be missing although the player is still in the zone: "outside" counts only after {@value #TAX_GONE_READS}
     * reads in a row (a wrong edge block in the middle of the tunnel would end ways there and make it turn back).
     */
    private int taxGoneReads;
    /** The sidebar's guard status must be missing this many reads in a row (its pages change: no flicker). */
    private static final int TAX_GONE_READS = 2;
    /** Way back into the guarded area: the goal the way leads to ({@code null} = being planned). */
    private double @Nullable [] guardTarget;
    private int guardTries;
    private Worker.@Nullable Job guardJob;
    /** Goals whose way led nowhere (the ground within {@value #TRIED_GOAL_RANGE} blocks is not taken again). */
    private final LongOpenHashSet triedGoals = new LongOpenHashSet();
    static final int TRIED_GOAL_RANGE = 3;
    /** The way back in searches this far (blocks to each side) / this many nodes - wider after {@link OutsideWatch#WIDE_MS}. */
    private static final int GUARD_RADIUS = 96;
    private static final int GUARD_NODES = 60_000;
    private static final int GUARD_WIDE_RADIUS = 160;
    private static final int GUARD_WIDE_NODES = 200_000;
    private int returnRadius = GUARD_RADIUS;
    private int returnNodes = GUARD_NODES;
    private String lastGuardWhy = "";
    private final OutsideWatch outsideWatch = new OutsideWatch();
    /** Escape to spawn: when /spawn was sent (-1 = to be sent, 0 = no escape). */
    private long escapeSentMs;
    /** /spawn again when the teleport has not happened after this long (moved / cancelled). */
    private static final long ESCAPE_RETRY_MS = 12_000L;
    /** "You have entered combat. Do not log out for 10s!": no /spawn until then (refreshed by every hit). */
    private long combatUntilMs;
    private static final long COMBAT_TAG_MS = 10_500L;
    /** When the last death message was seen (Cosmic respawns at once: no death screen). */
    private long deathSeenMs;
    /**
     * Attacked by a mob: the BREAK phase walks to the nearest guard instead (sprinting, no mining) and waits there until
     * {@link #FLEE_CALM_MS} without a hit; the break schedule stays untouched.
     */
    private boolean fleeing;
    private boolean fleeRequested;
    private long lastHurtMs;
    /** Every guard seen during this run (wardens, guards, enforcers) {x, y, z}: where an attacked player runs to. */
    private final List<double[]> knownGuards = new java.util.ArrayList<>();
    /** Breaks: when the next one is due, until when the current one lasts (0 = still walking to the warden). */
    private long nextBreakMs;
    private long breakUntilMs;
    private long breakStartedMs;
    private int breakTries;
    private double @Nullable [] breakWarden;
    private Worker.@Nullable Job breakJob;
    /** Every warden seen during this run {x, y, z}: a break walks to the nearest one, even when it is out of sight. */
    private final List<double[]> knownWardens = new java.util.ArrayList<>();
    /** Auto use: the hotbar slot being used, the slot held before, how long right click is still held (ticks). */
    private int useSlot = -1;
    private int useReturnSlot = -1;
    private int useHoldTicks;
    /**
     * Per item name: when the macro used it last and whether the server put it on cooldown since. Without a visible
     * cooldown an item is not used again before {@link #PET_FALLBACK_MS} / {@link #ABILITY_FALLBACK_MS}.
     */
    private final java.util.Map<String, long[]> used = new java.util.HashMap<>();
    /** Per item name (lower case, as the server writes it): when its cooldown from "... is on cooldown for 35m 4s" ends. */
    private final java.util.Map<String, Long> cooldownUntil = new java.util.HashMap<>();

    private IntOpenHashSet targetKeys = new IntOpenHashSet();
    private IntOpenHashSet allOreKeys = new IntOpenHashSet();
    /** Positions {x, y, z} of the wardens (guard NPCs, 1000 HP) in sight, refreshed every few ticks. */
    private double[][] wardens = new double[0][];
    private Phase phase = Phase.STEER;
    private long ticks;
    private int noOreTicks;
    /** Blocks walked since the last ore was mined. */
    private int barrenBlocks;
    private long barrenRetryMs;
    private int stuckTicks;
    private int recoverTicks;
    private final java.util.ArrayDeque<Long> stuckTimes = new java.util.ArrayDeque<>();
    private double lastX;
    private double lastZ;
    private long lastNode = Long.MIN_VALUE;
    private long learnStartOres;
    private long learnStartTick;
    private boolean deadEndNoted;
    private LanePlanner.@Nullable Plan travel;
    private Worker.@Nullable Job travelJob;
    private TunnelSteer.@Nullable Decision decision;
    private @Nullable Path regionFile;
    private StatsService.@Nullable Session session;
    private String status = "Idle";
    private String lastIssue = "";
    /** Packages left out for this run (mining level too low). */
    private final java.util.Set<String> excluded = new java.util.HashSet<>();
    private @Nullable String fatigueFrom;
    private Chores.Kind chore = Chores.Kind.NONE;
    private int choreStep;
    private int choreWait;
    private int spongeSlot = -1;
    private int pickaxeSlot = -1;
    private final java.util.Random random = new java.util.Random();
    /** Satchel or inventory full: /sellall is sent on the next tick while the macro keeps walking and mining. */
    private boolean sellPending;
    private long lastSellTick = -SELL_COOLDOWN_TICKS;
    /** Hand-placed borders ({@code /theprisons set border}): no walking or mining within 5 blocks. */
    private final BorderMarks borders;
    private final RouteStore routes;
    private final Settings.ChoiceSetting route;
    /** The route being walked ({@code null} = tunnel mode) and the waypoint the macro walks to. */
    private @Nullable Route activeRoute;
    private int routeIndex;
    /**
     * On the way to the start waypoint (at the start and after a teleport): the pathfinder's way, mining on the move; {@link #routeIndex}
     * is then the waypoint walked to next on that way.
     */
    private boolean approaching;
    /** Where the route begins after the way there: the nearest waypoint at the start, waypoint 1 after the last one. */
    private int approachGoal;
    private int routeFailures;
    /** Since when the current waypoint makes trouble (0 = none) and how far away it was then. */
    private long routeTroubleMs;
    private double routeTroubleDist;
    /** Start waypoints tried in a row (none reachable → the macro stops). */
    private int routeStartTries;
    private long routeRetryTick;
    private Worker.@Nullable Job routeJob;

    public OreMacroModule(ControlService control, WorldCache world, TargetRegistry targets, StatsService stats, Worker worker,
                          BorderMarks borders, RouteStore routes, WorldArchive archive) {
        super(ID, "Ore Macro", Category.MINING, "Macros",
                "Pick the ore and start: walks through the middle of the cave's tunnels, decides block by block where "
                        + "the most ore is, and mines the floor ores on the move. Never mines walls or the ceiling.",
                // Block scanner: 32 blocks around the player (±16 high); the cache keeps blocks up to 64 blocks away
                // (scan radius + WorldCache.EVICT_MARGIN), so a way already seen stays known a while longer.
                GLFW.GLFW_KEY_K, control, world, 32, 16);
        this.targets = targets;
        this.stats = stats;
        this.borders = borders;
        this.routes = routes;
        this.archive = archive;
        route = add(new Settings.ChoiceSetting("route", "Route", "Off (tunnel mode)",
                () -> routes.options(MinecraftClient.getInstance())))
                .description("1. pick a route (recorded with the keys \"Start/Stop route recording\" in Controls), 2. pick the "
                        + "ores below. From the start point the pathfinder walks to waypoint 1, then along the route (free to fetch "
                        + "the picked ores up to 5 blocks beside it) and from the last waypoint back to waypoint 1.").group("Route");
        ores = multi("ore_packs", "Ores", OreCatalog.DEFAULT_SELECTION, OreCatalog.options())
                .description("The ore of the mine. Each includes the ore, deepslate ore and the ore block.").group("Ores")
                .required(value -> !value.isEmpty(), "Pick at least one ore.");
        // Everything below is the tuned standard configuration and not shown to the user.
        reachSetting = decimal("reach", "Mining reach", 4.4D, 2.5D, 6.0D, 0.1D).group("Standard").visibleWhen(() -> false);
        maxDrop = integer("max_drop", "Max drop", 3, 1, 4, 1).group("Standard").visibleWhen(() -> false);
        sprint = bool("sprint", "Auto sprint", true)
                .description("Sprints on straight parts of the way. Also: /prisons sprint [on|off].").group("Movement");
        classicSteering = bool("classic_steering", "Classic steering (30.09)", true)
                .description("The steering of the 30.09 build: the best direction with ore becomes a long straight axis, "
                        + "walked on the richest strip beside it with a soft pull to the middle. Off: the newer steering "
                        + "(tunnel map, cave mode, look aside).").group("Movement");
        centring = bool("centring", "Tunnel centring", true)
                .description("Walks the middle of the tunnel: half way between the left and the right wall (D_left / D_right), "
                        + "following its bends. Off: straight lines from wall to wall.").group("Movement");
        stoneBlocks = integer("stone_blocks", "Stone blocks before a new way", BARREN_BLOCKS_DEFAULT, 1, 10, 1)
                .description("After this many blocks walked on stone / deepslate without an ore (and none 3 blocks left and "
                        + "right) the way or planned route is dropped and a new one planned.").group("Movement");
        routeMemoryOn = bool("route_memory", "Route memory", true)
                .description("Saves routes walked inside the guarded area to config/theprisons/routes_memory and takes them "
                        + "again (and prefers their lines) instead of planning anew.").group("Route memory");
        planRoutes = bool("plan_routes", "Plan routes (world memory)", true)
                .description("Reads every loaded chunk up to " + WorldArchive.RADIUS + " blocks to each side, saves it (next "
                        + "start: everything is known at once) and plans long routes from ore to ore inside the guarded area. "
                        + "Plans anew when 75 % of the blocks around are stone / deepslate, when a guard gives no safety any more, "
                        + "and checks every 5 s for a clearly better route.").group("Movement");
        minedPercent = integer("mined_percent", "Go to ore when mined (%)", 65, 10, 100, 5)
                .description("When this share of all ores within " + OreDepletion.RADIUS + " blocks has turned into "
                        + "stone / deepslate, the macro walks straight to the richest ore it knows.").group("Movement");
        // Off by default: the macro just does its work, no lines in the world.
        showPath = bool("show_path", "Show pathfinder line", false)
                .description("Draws the way the macro walks (pathfinder path or chosen direction).").group("Display");
        showWaypoints = bool("show_waypoints", "Show waypoints", false)
                .description("Route mode: draws only the next 2 waypoints and the line between them.").group("Display");
        usePet = bool("use_pet", "Use pet when ready", true)
                .description("A pet in the hotbar that is off cooldown is used right away: select it, right click, back.")
                .group("Auto use");
        petName = text("pet_name", "Pet name contains", "anti xp tax pet", 64)
                .description("Part of the pet's name (comma separated for several).").group("Auto use")
                .visibleWhen(usePet::on)
                .required(value -> !Chores.nameParts(value).isEmpty(), "Enter (part of) your pet's name.");
        useAbility = bool("use_ability", "Use item abilities when ready", true)
                .description("An ability item in the hotbar that is off cooldown: stop, hold right click for 2.2 s without "
                        + "mining, back to the pickaxe, walk on.").group("Auto use");
        abilityNames = text("ability_names", "Ability items contain", "fireball", 128)
                .description("Parts of the ability items' names, comma separated (e.g. \"fireball, meteor\").").group("Auto use")
                .visibleWhen(useAbility::on)
                .required(value -> !Chores.nameParts(value).isEmpty(), "Enter (part of) the ability item's name.");
        itemSorter = bool("item_sorter", "Item sorter", true)
                .description("Trips to spawn (/sethome tmp, /spawn, shards into the shard vault, everything else into the "
                        + "other vault, /home tmp, /delhome tmp, go on) when: 10 Godly shards, a contraband, 8M energy, "
                        + "10M money, the inventory limit, or every 5th pickaxe level / prestige.")
                .group("Item sorter");
        inventoryLimit = integer("inventory_share", "Inventory limit (%)", 65, 50, 95, 5)
                .description("The item sorter starts when this share of the 36 inventory slots holds items that are no "
                        + "blocks (ores and other sellable blocks do not count).")
                .group("Item sorter").visibleWhen(itemSorter::on);
        vaultShards = text("vault_shards", "Private Vault - Shards", "7", 40)
                .description("Your /pv numbers for the prismarine shards (several: \"7, 12\"). A full one: the next "
                        + "one, or the next free /pv that belongs to no other kind - added here.").group("Item sorter")
                .visibleWhen(itemSorter::on)
                .required(value -> ItemSorter.vault(value) > 0, "Enter the number of your shard vault (/pv).");
        vaultOther = text("vault_other", "Private Vault - Fallback (optional)", "", 40)
                .description("Everything the sorter finds no vault for (the other items are put into the /pv that already "
                        + "holds the same item, found by scanning /pv 1, 2, 3 ...): first into these /pv numbers (several: "
                        + "\"8, 10\"), then into any /pv with room. Pickaxes (except hotbar slot 1) go to /tinker, ores are sold.")
                .group("Item sorter")
                .visibleWhen(itemSorter::on)
                .required(value -> ItemSorter.vaults(value).isEmpty()
                                || java.util.Collections.disjoint(ItemSorter.vaults(value), ItemSorter.vaults(vaultShards.get())),
                        "The fallback vault must not be the shard vault.");
        vaultEnergy = text("vault_energy", "Private Vault - Energy", "9", 40)
                .description("Your /pv numbers for Cosmic Energy (light blue dye; several: \"9, 13\"). Empty: the energy "
                        + "stays in the inventory.")
                .group("Item sorter").visibleWhen(itemSorter::on);
        energyTrip = integer("energy_trip_millions", "Energy to the vault from (millions)", 8, 1, 100, 1)
                .description("When the light blue dye in the inventory holds this much Cosmic Energy: /spawn, into the "
                        + "energy vault, back.").group("Item sorter").visibleWhen(itemSorter::on);
        godlyTrip = integer("godly_shards_trip", "Godly shards before a trip", 10, 1, 64, 1)
                .description("The macro only goes to spawn (/sethome tmp, item sorter, shards opened) once the inventory "
                        + "holds this many Godly shards - one is not worth a trip.").group("Item sorter").visibleWhen(itemSorter::on);
        moneyTrip = integer("money_trip_millions", "Redeem money from (millions)", 10, 1, 1000, 1)
                .description("When the money notes (paper) in the inventory are worth this much: /spawn, the notes stacked "
                        + "together and right clicked, back.").group("Item sorter").visibleWhen(itemSorter::on);
        openContrabands = bool("open_contrabands", "Open contrabands at spawn", true)
                .description("On every trip to spawn: 5 blocks forward, each contraband (ender chest) into the hotbar "
                        + "(swapped with an item that is no pickaxe, shard, contraband or money), looking 40 degrees down, "
                        + "right click, wait for the 3 items.").group("Item sorter").visibleWhen(itemSorter::on);
        openShards = bool("open_shards", "Open shards at spawn", true)
                .description("On every trip to spawn: a shard of the lowest tier into the hotbar, right click, shift-click "
                        + "the other shards in from the highest tier down to the lowest, Roll all shards, Esc.")
                .group("Item sorter").visibleWhen(itemSorter::on);
        breaks = bool("breaks", "Breaks at a warden", true)
                .description("Every 10-30 min (random): walk to the nearest warden, stand completely still there for "
                        + "10-30 s (no mining, walking or looking around), then go on.").group("Breaks");
        breakEveryMin = integer("break_every_min", "Break every (min, from)", 10, 1, 120, 1).group("Breaks");
        breakEveryMax = integer("break_every_max", "Break every (min, to)", 30, 1, 180, 1).group("Breaks");
        breakLengthMin = integer("break_length_min", "Break length (s, from)", 10, 1, 300, 1).group("Breaks");
        breakLengthMax = integer("break_length_max", "Break length (s, to)", 30, 1, 600, 1).group("Breaks");
        antiStuck = bool("anti_stuck", "Anti-stuck", true)
                .description("No progress for 1.5 s although walking: 0.5 s back, that direction avoided for a moment, a new "
                        + "way; 3 times within 20 s: a planned way elsewhere.").group("Recovery");
        guardLookAhead = integer("guard_look_ahead", "Guard look-ahead (blocks)", 2, 2, 8, 1)
                .description("Ways end this many blocks before the edge of the guarded area, so the macro turns in time "
                        + "instead of walking up to the edge.").group("Recovery");
        fleeToGuard = bool("flee_to_guard", "Combat failsafe: run to a guard", true)
                .description("Any hit (mob, NPC or player): never fight back - stop mining, run to the nearest guard "
                        + "(also ones remembered from earlier runs), wait there until 5 s without a hit and no player is "
                        + "seen within 16 blocks to each side, then go on. Hit by a guard or no guard known: stop with an "
                        + "alert. Killed anyway: respawn, whitescrolls from the vaults onto the pickaxe and satchels, "
                        + "/warp back to the mine.").group("Defence");
        guarded = bool("guarded", "Stay in the guarded area", true)
                .description("Guards (100 HP) are found automatically up to 128 blocks away and remembered. The macro "
                        + "never walks further than the radius from a guard: ways, lanes and paths end at the edge. "
                        + "Outside (start, teleport, knocked away): walk back to the nearest guard. No guard found within "
                        + "10 s: stop with an alert.").group("Defence");
        taxFromEnergy = bool("tax_from_energy", "Guard tax from energy per ore (experimental)", false)
                .description("Inside or outside the guarded area is told by the energy one ore gives (pickaxe lore, read "
                        + "every tick), not by the sidebar: 2-12 % more per ore = the tax fell away (outside, turn back), "
                        + "2-12 % less = inside again. 20 % or more (25 %, 50 %, 100 %) is an energy booster and changes "
                        + "nothing. Off (default): the sidebar - \"Guard XP Tax N%\" or \"Guarded\" = inside, neither = "
                        + "outside, back to the last place it was shown.").group("Defence").visibleWhen(guarded::on);
        outsideNear = integer("outside_near", "Unguarded blocks, 2+ players near", 3, 0, 10, 1)
                .description("How far the macro may walk out of the guarded zone (no guard XP tax) while 2 or more other "
                        + "players are within 32 blocks, then it turns back at once - unless it is in another guarded zone "
                        + "by then. 0 = never.")
                .group("Defence").visibleWhen(guarded::on);
        outsideAlone = integer("outside_free", "Unguarded blocks, fewer players", 48, 0, 200, 1)
                .description("With fewer than 2 other players within 32 blocks (for 5 s) the macro may walk through "
                        + "unguarded ground like through the guarded zone, up to this many blocks in a row - routes and the "
                        + "free steering go wherever the ore is. 2 players come near: back to the guards, no long way "
                        + "outside.")
                .group("Defence").visibleWhen(guarded::on);
        guardRadius = integer("guard_radius", "Guarded radius (blocks)", 15, 4, 32, 1)
                .description("How far from a guard the macro may go.").group("Defence").visibleWhen(guarded::on);
        ores.onChange(value -> syncTargets());
    }

    /** {@code /prisons sprint [on|off]}: toggles or sets the auto sprint setting. */
    public void registerCommands(CommandService commands) {
        commands.contribute(root -> root.then(ClientCommandManager.literal("sprint")
                .executes(ctx -> setSprint(ctx.getSource(), !sprint.on()))
                .then(ClientCommandManager.literal("on").executes(ctx -> setSprint(ctx.getSource(), true)))
                .then(ClientCommandManager.literal("off").executes(ctx -> setSprint(ctx.getSource(), false)))));
    }

    private int setSprint(FabricClientCommandSource source, boolean on) {
        sprint.set(on);
        source.sendFeedback(Text.literal("Auto sprint " + (on ? "enabled." : "disabled."))
                .formatted(on ? Formatting.GREEN : Formatting.GRAY));
        return 1;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected @Nullable String canStart(MinecraftClient client) {
        if (!route.off() && routes.find(client, route.get()) == null) {
            return "Route \"" + route.get() + "\" is not saved in this world.";
        }
        return OreCatalog.resolve(ores.get()).isEmpty() ? "Pick an ore first." : null;
    }

    @Override
    protected void onStart(MinecraftClient client) {
        // Started at spawn: sort, open, then /warp to the mine first (checked over the first 2 s - the sidebar).
        spawnCheckTicks = 40;
        skipped.clear();
        extras.clear();
        forgetWalked();
        steer.reset();
        classic.reset();
        terrain.reset();
        driver.stop();
        travel = null;
        travelJob = null;
        noOreTicks = 0;
        barrenBlocks = 0;
        barrenRetryMs = 0L;
        depletion.clear();
        depletedRetryMs = 0L;
        stuckTicks = 0;
        recoverTicks = 0;
        stuckTimes.clear();
        lastIssue = "";
        excluded.clear();
        fatigueFrom = null;
        chore = Chores.Kind.NONE;
        milestones.reset();
        levelDue = false;
        relocatedTick = -1L;
        sellPending = false;
        knownWardens.clear();
        knownGuards.clear();
        guardArea.clear();
        outsideWalked = 0;
        outsideBlock = Long.MIN_VALUE;
        backInSince = 0L;
        backInPlanning = false;
        playerSeenMs = System.currentTimeMillis();
        guardPlayerLock = false;
        loggedGuards = 0;
        lastTax = null;
        taxGoneReads = 0;
        energyTax.reset();
        java.util.Arrays.fill(taxTrail, null);
        taxTrailNext = 0;
        taxSamplesLogged = 0L;
        energyLoreLogged = false;
        loggedEnergyBlocks = 0L;
        energyBack = null;
        triedGoals.clear();
        returnRadius = GUARD_RADIUS;
        returnNodes = GUARD_NODES;
        lastGuardWhy = "";
        outsideWatch.reset();
        escapeSentMs = 0L;
        combatUntilMs = 0L;
        deathSeenMs = 0L;
        lastOreHit = Long.MIN_VALUE;
        java.util.Arrays.fill(ownHits, Long.MIN_VALUE);
        ownHitNext = 0;
        procOres = 0L;
        actionBarLogged = 0;
        lastActionBar = "";
        chargePercent.clear();
        nextEnergyDiagTick = 0L;
        guardTarget = null;
        guardJob = null;
        fleeing = false;
        fleeRequested = false;
        endBreak(System.currentTimeMillis());
        steer.guide(null, null);
        activeRoute = route.off() ? null : routes.find(client, route.get());
        routeIndex = 0;
        routeFailures = 0;
        routeTroubleMs = 0L;
        routeStartTries = 0;
        routeRetryTick = 0;
        routeJob = null;
        syncTargets();
        loadRegion(client);
        resetPlan();
        openArchive(client);
        loadGuards(client);
        loadGuardZone(client);
        loadRouteMemory(client);
        session = stats.start(ID);
        learnStartOres = 0;
        learnStartTick = 0;
        phase = activeRoute != null ? Phase.ROUTE : Phase.STEER;
        if (activeRoute != null && client.player != null) {
            enterRoute(client.player, activeRoute);
        }
        on(CoreEvents.TickEnd.class, Phases.DECIDE, event -> decide(event.client()));
        on(CoreEvents.TickEnd.class, Phases.ACT, event -> act(event.client()));
        on(CoreEvents.PlayerBrokeBlock.class, this::onBroken);
        on(CoreEvents.BlockChanged.class, this::onBlockChanged);
        on(CoreEvents.ChatReceived.class, this::onChat);
        on(CoreEvents.PlayerHurt.class, this::onHurt);
        on(CoreEvents.WorldRender.class, this::renderOverlay);
        every(2400, "save-region", () -> {
            saveRegion();
            saveGuards();
            saveGuardZone();
        });
    }

    @Override
    protected void onStop(MinecraftClient client) {
        saveRegion();
        saveGuards();
        saveGuardZone();
        archive.close();
        resetPlan();
        breaker.cancel(client);
        targets.clear(this);
        stats.end(ID);
        session = null;
        driver.stop();
        decision = null;
        if (chore == Chores.Kind.USE_ABILITY || chore == Chores.Kind.USE_PET) {
            // Stopped in the middle of using an item: let go of right click, the pickaxe back in hand.
            client.options.useKey.setPressed(false);
            if (client.player != null) {
                restoreSlot(client.player);
            }
        }
        chore = Chores.Kind.NONE;
        activeRoute = null;
        routeJob = null;
        breakJob = null;
        status = "Idle";
    }

    @Override
    public boolean onRelocated(String reason) {
        breaker.cancel(MinecraftClient.getInstance());
        control.input().clear();
        skipped.clear();
        forgetWalked();
        steer.reset();
        classic.reset();
        driver.stop();
        travel = null;
        travelJob = null;
        barrenBlocks = 0;
        count("relocations");
        if (!travelling()) {
            // Not our own trip: stand still (no mining) until it is clear whether this is still a mine.
            relocatedTick = ticks;
        }
        lastIssue = "moved (teleport / reset)";
        resetPlan();
        routeJob = null;
        breakJob = null;
        guardJob = null;
        guardTarget = null;
        breakUntilMs = 0L;
        fleeing = false;
        phase = activeRoute != null ? Phase.ROUTE : Phase.STEER;
        ClientPlayerEntity moved = MinecraftClient.getInstance().player;
        if (activeRoute != null && moved != null) {
            // Somewhere else now: find the way to waypoint 1 again.
            enterRoute(moved, activeRoute);
        }
        return true;
    }

    @Override
    public boolean travelling() {
        // The escape's /spawn is our own teleport too (not a foreign one that stops the macro).
        return chore == Chores.Kind.SORT_ITEMS || chore == Chores.Kind.DEATH_RECOVERY || escapeSentMs != 0L;
    }

    /** Killed: respawn, whitescrolls, /warp back (with the combat failsafe on); the server's zone worlds: a teleport. */
    @Override
    public boolean survivesDeath() {
        return fleeToGuard.on();
    }

    private void syncTargets() {
        IntOpenHashSet keys = new IntOpenHashSet();
        java.util.Set<String> packs = new java.util.LinkedHashSet<>(ores.get());
        packs.removeAll(excluded);
        for (Block block : OreCatalog.resolve(packs)) {
            keys.add(BlockKeys.key(OreCatalog.id(block)));
        }
        IntOpenHashSet all = new IntOpenHashSet();
        for (Block block : OreCatalog.allBlocks()) {
            all.add(BlockKeys.key(OreCatalog.id(block)));
        }
        targetKeys = keys;
        allOreKeys = all;
        if (enabled()) {
            // The scanner indexes every ore: the selected ones are mined, the others mark the border of the mine.
            targets.set(this, OreCatalog.allBlocks());
        }
    }

    // ── Every tick ───────────────────────────────────────────────────────────

    private void decide(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld clientWorld = client.world;
        if (player == null || clientWorld == null) {
            return;
        }
        if (System.currentTimeMillis() < menuHoldUntilMs && !player.isDead()) {
            // The market scan has the screen: stand still.
            control.input().clear();
            breaker.cancel(client);
            return;
        }
        if (player.isDead() || client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
            if (chore != Chores.Kind.DEATH_RECOVERY) {
                startDeath(client, player, "died");
            }
            if (ticks % 20 == 0) {
                // Respawn at once (the death screen's button).
                player.requestRespawn();
                if (client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
                    client.setScreen(null);
                }
            }
            return;
        }
        if (spawnCheckTicks > 0 && chore == Chores.Kind.NONE) {
            // Before anything that waits for a mine (at spawn there is no ore: those checks would wait forever).
            spawnCheckTicks--;
            if (atSpawn(client, clientWorld)) {
                spawnCheckTicks = 0;
                relocatedTick = -1L;
                startSpawnStart(player);
            } else if (spawnCheckTicks == 0) {
                ThePrisonsClient.LOGGER.info("[ore_macro] not at spawn (sidebar zone / last zone: {})",
                        io.theprisons.core.ThePrisonsCore.lastZone());
            }
        }
        if (phase == Phase.BREAK && breakUntilMs > 0L && fleeRequested && reactToAttack(client, player)) {
            // Attacked while standing at the warden for a break: that becomes the wait for calm at the guard.
            return;
        }
        if (phase == Phase.BREAK && breakUntilMs > 0L) {
            // The break: complete standstill - no walking, mining or looking around; chat chores wait until after.
            holdStill(client, player);
            return;
        }
        if (relocatedTick >= 0L && chore == Chores.Kind.NONE) {
            // Warped somewhere (spawn, another warp, mine reset): no mining, no walking until the scanner has seen
            // the new place. No ore around = not a mine any more → stop the macro.
            control.input().clear();
            breaker.cancel(client);
            status = "Teleported - checking the area";
            if (ticks - relocatedTick < RELOCATE_CHECK_TICKS) {
                return;
            }
            relocatedTick = -1L;
            if (world.store().ores().density(player.getBlockX(), player.getBlockY(), player.getBlockZ(), RELOCATE_BUCKETS) == 0) {
                alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                        "Teleported away from the mine (no ore around) - macro stopped."));
                return;
            }
        }
        if (sellPending && ticks - lastSellTick >= SELL_COOLDOWN_TICKS) {
            // No pause: the command goes out while the macro goes on.
            sellPending = false;
            lastSellTick = ticks;
            player.networkHandler.sendChatCommand("sellall");
            count("sellall_inventory");
        }
        if (chore == Chores.Kind.NONE && ticks % 20 == 0 && System.currentTimeMillis() >= fullCheckAfterMs) {
            net.minecraft.item.ItemStack pick = hotbarPickaxe(player);
            if (pick != null && Chores.pickaxeFull(io.theprisons.core.client.ClientReadouts.lore(pick))) {
                // Full of energy: it would mine nothing - a sponge onto it (fetched at spawn if none here).
                fullCheckAfterMs = System.currentTimeMillis() + 30_000L;
                breaker.cancel(client);
                control.input().clear();
                ThePrisonsClient.LOGGER.info("[ore_macro] the pickaxe is full of energy - using a sponge");
                chore = Chores.Kind.EXTRACT_ENERGY;
                choreStep = 0;
                choreWait = 10;
            }
        }
        if (chore == Chores.Kind.NONE && ticks % 10 == 0) {
            // Chosen ores around: the pickaxe in hand (mining); none: something else in hand.
            boolean oresAround = world.store().ores().density(player.getBlockX(), player.getBlockY(), player.getBlockZ(), RELOCATE_BUCKETS) > 0;
            boolean holding = player.getMainHandStack().isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
            if (oresAround && !holding && hotbarPickaxe(player) != null) {
                selectPickaxe(player);
            } else if (!oresAround && holding) {
                selectEmpty(player);
            }
        }
        if (chore != Chores.Kind.NONE) {
            // A chore (energy extraction, pet, ability, sorting) has priority over walking and runs with the inventory open.
            runChore(client, player);
            return;
        }
        if (control.input().paused()) {
            status = "Paused (screen open)";
            return;
        }
        if (fleeRequested && reactToAttack(client, player)) {
            return;
        }
        if (ticks % SORT_CHECK_TICKS == 0 && phase != Phase.BREAK && startSort(player)) {
            // Inventory full of prismarine shards: the item sorter runs from the next tick on.
            return;
        }
        if (ticks % USE_CHECK_TICKS == 0 && startAutoUse(player)) {
            // A pet / item ability is ready: that chore runs from the next tick on.
            return;
        }
        if (player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            // The server refuses an ore (mining level too low) and punishes every try: leave that ore out and go on
            // with the others; stop only when nothing is left.
            if (fatigueFrom != null && excluded.add(fatigueFrom)) {
                notify("Mining level too low for " + fatigueFrom + " - leaving it out.", io.theprisons.core.module.ModuleHost.Level.WARNING);
                count("excluded_" + fatigueFrom);
                syncTargets();
            }
            if (targetKeys.isEmpty()) {
                disableSelf("Mining Fatigue: mining level too low for these ores");
                return;
            }
            control.input().clear();
            breaker.cancel(client);
            status = "Waiting for Mining Fatigue to end";
            return;
        }
        ticks++;
        long now = System.currentTimeMillis();
        steer.borders(borders.zones(client));
        guardArea.radius(guardRadius.value());
        guardArea.edge(guardLookAhead.value());
        steer.centring(centring.on());
        steer.guards(guarded.on() ? guardArea : NO_GUARDS);
        classic.guards(guarded.on() ? guardArea : NO_GUARDS);
        classic.borders(borders.zones(client));
        skipped.long2LongEntrySet().removeIf(entry -> entry.getLongValue() < now);
        countExtras(clientWorld);
        rememberWalked(player);
        learn(player, now);
        if (ticks % 5 == 0) {
            wardens = findWardens(clientWorld, player);
            remember(knownWardens, wardens);
            double[][] guards = findGuards(clientWorld, player);
            remember(knownGuards, guards);
            double[][] areaGuards = findAreaGuards(clientWorld, player);
            guardArea.update(areaGuards, player.getX(), player.getY(), player.getZ(), now);
            if (areaGuards.length == 0 && ticks % GUARD_CENSUS_TICKS == 5) {
                // No guard in sight here (also in a mine whose guards look different): what is around, for the log.
                logGuardCensus(client, clientWorld, player);
            }
            if (guardArea.size() != loggedGuards) {
                loggedGuards = guardArea.size();
                StringBuilder at = new StringBuilder();
                for (double[] g : guardArea.guards()) {
                    at.append(String.format(Locale.ROOT, " (%.0f %.0f %.0f)", g[0], g[1], g[2]));
                }
                ThePrisonsClient.LOGGER.info("[ore_macro] {} guards known:{}", loggedGuards, at);
            }
        }
        // Excursions outside the guarded zone: no other player within 32 blocks (for 5 s) = up to "alone" blocks (8),
        // a player near = up to "near" blocks (4), then back in - unless the way out led into another guarded zone
        // (the guard tax is back: the walked blocks are counted from zero). A player coming near is noticed at once.
        int crowd = playersNear(client, player, GUARD_PLAYER_RANGE);
        String guardPlayer = crowd + " players";
        boolean playerClose = crowd >= CROWD_PLAYERS;
        if (playerClose) {
            playerSeenMs = now;
        }
        if (playerClose != guardPlayerLock || ticks % 10 == 0) {
            guardPlayerLock = playerClose;
            boolean alone = !playerClose && now - playerSeenMs >= ALONE_AFTER_MS;
            int budget = alone ? outsideAlone.value() : outsideNear.value();
            int before = guardArea.outsideBudget();
            if (budget != before) {
                ThePrisonsClient.LOGGER.info("[ore_macro] {}: up to {} unguarded blocks",
                        alone ? "fewer than " + CROWD_PLAYERS + " players near" : guardPlayer + " near", budget);
                guardArea.outsideBudget(budget);
                if (budget < before) {
                    // Less room than planned: no plan / corridor that was made for the larger budget goes on.
                    guardArea.corridor(null);
                    dropPlan();
                    travel = null;
                    travelJob = null;
                    routeJob = null;
                } else {
                    OrePlanner.Plan walking = plan;
                    guardArea.corridor(walking == null ? null : walking.nodes());
                }
            }
        }
        // Every tick: the edge of the tax zone is noticed within a block, not 2-3 blocks too late.
        readTax(client, clientWorld, player);
        countOutside(player);
        if (guarded.on() && phase != Phase.BREAK && keepGuarded(client, player, now)) {
            return;
        }
        if (phase != Phase.BREAK && breaks.on() && now >= nextBreakMs) {
            startBreak(player, now);
        }
        switch (phase) {
            case STEER -> steer(player, now);
            case TRAVEL -> travel(player);
            case ROUTE -> followRoute(player, now);
            case BREAK -> walkToBreak(player, now);
            case GUARD -> walkToGuard(client, player, now);
            case WAIT -> {
                control.input().clear();
                look(player, player.getYaw(), null, null);
                status = "Waiting: " + lastIssue + (guarded.on() ? String.format(Locale.ROOT, " (unguarded %d of %d blocks%s)",
                        outsideWalked, guardArea.outsideBudget(), guardPlayerLock ? ", player near" : "") : "");
                if (ticks % 40 == 0) {
                    phase = Phase.STEER;
                }
            }
        }
        StatsService.Session run = session;
        if (run != null) {
            run.state(phase.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Block by block: the next direction in the middle of the tunnel. */
    private void steer(ClientPlayerEntity player, long now) {
        boolean planning = planRoutes.on() && activeRoute == null;
        if (planning) {
            followPlan(player, now);
        }
        boolean useClassic = classicSteering.on();
        TunnelSteer.Decision d = useClassic
                ? classic.decide(steerView(player), targetKeys::contains, this::isForeign, player.getX(), player.getY(),
                player.getZ(), player.getYaw(), player.isOnGround(), walked, zone -> region.factor(zone, now), wardens, now)
                : steer.decide(steerView(player), targetKeys::contains, this::isForeign, player.getX(), player.getY(),
                player.getZ(), player.getYaw(), player.isOnGround(), walked, zone -> region.factor(zone, now), wardens, now);
        decision = d;
        String change = steer.takeWayChange();
        if (change != null) {
            ThePrisonsClient.LOGGER.info("[ore_macro] {}", change);
        }
        String caveChange = steer.takeCaveChange();
        if (caveChange != null) {
            ThePrisonsClient.LOGGER.info("[ore_macro] {}", caveChange);
        }
        if (ticks % 200 == 0) {
            logCache(player);
        }
        if (ticks % 40 == 0) {
            // Trace every 2 s: what the steering does, so a game log shows the whole picture.
            ThePrisonsClient.LOGGER.info("[ore_macro] trace {} {} | {} | free {} ore ahead {} | stone blocks {} | {}",
                    player.getBlockPos().toShortString(), botState(), useClassic ? classic.debugState() : steer.debugState(),
                    String.format(Locale.ROOT, "%.1f", d.free()), d.oreAhead() ? "yes" : "no", barrenBlocks,
                    plan != null ? "route " + planInfo : "no route");
        }
        if (!d.forward() && guardArea.clearOutsideNear(player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            count("unwalled");
        }
        if (!d.forward() && plan != null) {
            // The planned way cannot be walked from here (the steering knows better): plan anew, steer freely meanwhile.
            count("plan_blocked");
            dropPlan();
            nextCaveMs = now + FAILED_PLAN_PAUSE_MS;
            if (now >= nextPlanMs) {
                // Not every tick: the same blocked route came back 200 times in 20 s (22:46 log).
                nextPlanMs = now + PLAN_RETRY_MS;
                requestPlan(player, new OrePlanner.Avoid(player.getX(), player.getZ(), player.getYaw()), "way blocked");
            }
            control.input().clear();
            look(player, player.getYaw(), null, null);
            return;
        }
        if (!d.forward()) {
            control.input().clear();
            look(player, player.getYaw(), null, null);
            lastIssue = "no way on from here";
            count("no_way");
            if (planning && planJob == null) {
                // Trapped (e.g. walking back and forth in a pocket): a planned way somewhere else.
                requestPlan(player, new OrePlanner.Avoid(player.getX(), player.getZ(), player.getYaw()), "no way on from here");
            }
            phase = Phase.WAIT;
            return;
        }
        if (recoverTicks > 0) {
            // Stuck recovery: a short step back before the new way (a wedged player gets free of the edge).
            recoverTicks--;
            control.input().set(new InputController.Keys(false, true, false, false, false, false, false));
            status = "Stuck: stepping back";
            return;
        }
        boolean wallHit = useClassic && classic.takeWallHit();
        if (useClassic && planning && plan == null && planJob == null && now >= nextWallPlanMs
                && (!d.oreAhead() || wallHit && classic.wallTurnedBack())) {
            // Classic steering at a wall with no ore ahead after the turn, or one it would turn back from: the planner
            // (the whole remembered world, stairs and spiral stairs to other levels, ways round) looks for a way to more
            // ore - a wall is not only "turn round"; the straight rays cannot follow a spiral stair, the planner can.
            // No ore ahead on the straight ways (or a wall it would turn back from): the planner looks at once - a
            // (spiral) stair up to ore beats a flat way without ore; the straight rays cannot follow a spiral.
            nextWallPlanMs = now + WALL_PLAN_MS;
            count("plans_wall");
            requestPlan(player, null, wallHit ? "wall ahead: a way round or up to more ore" : "no ore ahead: a way (also up a stair) to ore");
        }
        if (planning && !useClassic && steer.inCave() && plan == null && planJob == null && now >= nextCaveMs
                && !d.oreAhead() && barrenBlocks >= CAVE_BARREN_BLOCKS) {
            // Cave mode: the walls opened up and the cave gives no ore here any more (none ahead, a few blocks walked on
            // stone) - the best tunnel leaving the cave (ores / distance, guarded, flat). An ore field without walls is
            // mined, not left.
            nextCaveMs = now + CAVE_RETRY_MS;
            requestTunnel(player);
        }
        if (d.deadEnd() && !deadEndNoted) {
            deadEndNoted = true;
            count("dead_ends");
            region.recordDeadEnd(LanePlanner.zoneOf(Pos.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ())), now);
        } else if (!d.deadEnd()) {
            deadEndNoted = false;
        }
        float rel = Math.abs(RotationMath.wrap(d.yaw() - player.getYaw()));
        boolean forward = rel < LaneDriver.WALK_ALIGNMENT;
        boolean run = sprint.on() && forward && rel < 25.0F && d.free() > 3.0D && !d.jump();
        boolean jump = forward && (d.jump() || player.horizontalCollision && player.isOnGround());
        control.input().set(new InputController.Keys(forward, false, false, false, jump, run, false));
        look(player, d.yaw(), d.aheadFeet(), d.aheadDist());
        status = String.format(Locale.ROOT, plan != null ? "Route (%.0fm free, %.0f ore ahead)"
                        : steer.inCave() ? "Cave (%.0fm free, %.0f ore ahead)" : "Tunnel (%.0fm free, %.0f ore ahead)",
                d.free(), d.ore());

        // No progress although walking: avoid this direction for a moment.
        double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        lastX = player.getX();
        lastZ = player.getZ();
        stuckTicks = forward && moved < 0.02D ? stuckTicks + 1 : 0;
        if (stuckTicks > STUCK_TICKS) {
            stuckTicks = 0;
            count("stuck");
            steer.block(d.heading(), now + BLOCK_MS);
            classic.block(d.heading(), now + BLOCK_MS);
            recoverTicks = antiStuck.on() ? RECOVER_TICKS : 0;
            stuckTimes.addLast(now);
            while (!stuckTimes.isEmpty() && now - stuckTimes.peekFirst() > STUCK_WINDOW_MS) {
                stuckTimes.removeFirst();
            }
            ThePrisonsClient.LOGGER.info("[ore_macro] stuck at {} heading {}: stepping back, avoiding it for a moment",
                    player.getBlockPos().toShortString(), Math.round(d.heading()));
            if (antiStuck.on() && stuckTimes.size() >= STUCK_REPLAN && planning && planJob == null) {
                // Stuck again and again here: a planned way somewhere else.
                stuckTimes.clear();
                if (plan != null) {
                    dropPlan();
                }
                requestPlan(player, new OrePlanner.Avoid(player.getX(), player.getZ(), d.heading()), "stuck again and again");
            }
        }

        if (ticks % DEPLETION_TICKS == 0) {
            depletion.update(world.store(), targetKeys::contains, player.getX(), player.getY(), player.getZ());
        }
        // Most of the ore around mined - but walking and mining comes first: not while there is ore within 5 blocks ahead.
        boolean minedOut = !d.oreAhead() && depletion.minedShare() * 100.0D >= minedPercent.value();
        // Walked 5 blocks on stone / deepslate without mining an ore: another way to the ores - but not while the
        // steering has ore within 5 blocks ahead (walking on there is the way to the ores; travel could lead back).
        boolean barren = !d.oreAhead() && barrenBlocks >= stoneBlocks.value() && now >= barrenRetryMs;
        if (barren && !useClassic && steer.lookAside(steerView(player), targetKeys::contains, this::isForeign, player.getX(), player.getY(),
                player.getZ(), walked)) {
            // Ore up to 3 blocks beside the lane: that side of the tunnel first, the count starts again.
            count("barren_aside");
            barrenBlocks = 0;
            barren = false;
        }
        if (barren && planning && plan != null && planJob == null) {
            // 6 blocks on stone on a planned route and no ore 3 blocks aside either: the route is dropped, a new one
            // planned away from this stone.
            count("plan_barren");
            dropPlan();
            barrenBlocks = 0;
            barrenRetryMs = now + DEPLETED_RETRY_MS;
            nextCaveMs = now + FAILED_PLAN_PAUSE_MS;
            requestPlan(player, new OrePlanner.Avoid(player.getX(), player.getZ(), d.heading()), stoneBlocks.value() + " blocks on stone on the route");
            return;
        }

        // No ore in any direction for a while - or most of the ore around already mined: walk to the richest spot.
        noOreTicks = d.ore() > 0.0D ? 0 : noOreTicks + 1;
        if (planning && plan != null) {
            // The planned route already leads to the ores: no extra way to the richest spot.
            noOreTicks = 0;
            return;
        }
        if (planning && (noOreTicks > NO_ORE_TICKS || minedOut && now >= depletedRetryMs || barren)
                && planWhenEmpty(player, now, minedOut || barren, minedOut ? "most ore around mined"
                : barren ? stoneBlocks.value() + " blocks on stone" : "no ore ahead")) {
            // The planner over the remembered world finds the way to the ores; the short travel only when it found none.
            return;
        }
        if (travelJob == null && (noOreTicks > NO_ORE_TICKS || minedOut && now >= depletedRetryMs || barren)) {
            requestTravel(player);
        }
        LanePlanner.Plan found = travel;
        if (found != null) {
            travel = null;
            if (found.kind() != LanePlanner.Kind.TRAVEL && minedOut) {
                // Nothing better known: mine on here and look again a bit later.
                depletedRetryMs = now + DEPLETED_RETRY_MS;
            }
            if (found.kind() != LanePlanner.Kind.TRAVEL && barren) {
                barrenRetryMs = now + DEPLETED_RETRY_MS;
            }
            if (found.kind() == LanePlanner.Kind.TRAVEL && found.path() != null && (d.ore() <= 0.0D || minedOut || barren)
                    && !borders.zones(MinecraftClient.getInstance()).crosses(found.path(), player.getY(), player.getX(), player.getZ())) {
                driver.start(found.path());
                phase = Phase.TRAVEL;
                barrenBlocks = 0;
                count("travels");
            }
            noOreTicks = 0;
        }
    }

    /** Walking to the richest spot (the only planned route); then steering again. */
    private void travel(ClientPlayerEntity player) {
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), sprint.on() || fleeing);
        control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), drive.sprint(), false));
        look(player, drive.yaw(), null, null);
        status = String.format(Locale.ROOT, "To ore (%.0fm)", driver.remaining());
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            driver.stop();
            steer.reset();
        classic.reset();
            barrenBlocks = 0;
            phase = Phase.STEER;
        }
    }

    private void requestTravel(ClientPlayerEntity player) {
        WorldSnapshot snapshot = world.snapshot(player, 48, 16);
        long start = new Walkability(snapshot.copyView(), maxDrop.value()).settle(player.getX(), player.getY(), player.getZ());
        if (start == Long.MIN_VALUE) {
            return;
        }
        LongOpenHashSet skip = new LongOpenHashSet(skipped.keySet());
        IntOpenHashSet keys = new IntOpenHashSet(targetKeys);
        Long2DoubleOpenHashMap factors = region.snapshot(System.currentTimeMillis());
        LanePlanner.Params params = new LanePlanner.Params(2, LanePlanner.Params.DEFAULT.maxLength(), 40, 3, maxDrop.value(), reach(player));
        // Only the hand-placed borders limit where it walks.
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        it.unimi.dsi.fastutil.longs.Long2DoubleMap costs = guardedCosts(forbidden, player);
        count("plans");
        travelJob = submit("travel", cancel -> LanePlanner.travel(snapshot, keys::contains,
                new Walkability(snapshot, maxDrop.value(), true, costs), start, skip, params, factors::get, cancel::cancelled), plan -> {
            travelJob = null;
            travel = plan;
        });
    }

    /** With "Stay in the guarded area": nodes outside it (not closer to a guard than the player) are forbidden too. */
    private it.unimi.dsi.fastutil.longs.Long2DoubleMap guardedCosts(Long2DoubleOpenHashMap forbidden, ClientPlayerEntity player) {
        return guardedCosts(forbidden, player, false);
    }

    /**
     * @param excursions the route planner: a few unguarded blocks in a row are allowed (checked per way with
     *                   {@link #allowedWays}); travel and recorded-route paths stay strictly inside
     */
    private it.unimi.dsi.fastutil.longs.Long2DoubleMap guardedCosts(Long2DoubleOpenHashMap forbidden, ClientPlayerEntity player,
                                                                    boolean excursions) {
        if (!guarded.on() || guardArea.isEmpty()) {
            return forbidden;
        }
        GuardArea area = guardArea.copy();
        area.corridor(null);
        if (!excursions) {
            area.outsideBudget(0);
        }
        return area.penalties(forbidden, player.getX(), player.getY(), player.getZ());
    }

    /** Border areas (closer to the centre than the player is now) are forbidden for travel paths too. */
    private static void forbidBorders(Long2DoubleOpenHashMap forbidden, ClientPlayerEntity player, BorderZones zones) {
        for (double[] c : zones.zones()) {
            double radius = Math.min(c[3], Math.hypot(c[0] - player.getX(), c[2] - player.getZ()) - 1.0D);
            int r = (int) Math.ceil(radius);
            int cx = (int) Math.floor(c[0]);
            int cy = (int) Math.floor(c[1]);
            int cz = (int) Math.floor(c[2]);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    for (int dy = (int) -BorderZones.HEIGHT; dy <= BorderZones.HEIGHT; dy++) {
                        forbidden.put(Pos.pack(cx + dx, cy + dy, cz + dz), Double.POSITIVE_INFINITY);
                    }
                }
            }
        }
    }

    // ── Planned routes (world memory) ────────────────────────────────────────

    private static final long PLAN_RETRY_MS = 3_000L;
    /** "Too much stone": this share of the exposed blocks around is stone / deepslate (the rest ore). */
    private static final double ROCK_SHARE = 0.75D;
    private static final int ROCK_RADIUS = 5;
    private static final int ROCK_MIN_BLOCKS = 20;
    /** After a "too much stone" new plan the next one only this many blocks further on. */
    private static final double ROCK_MOVE = 8.0D;
    private static final int ROCK_TICKS = 10;
    /** A waypoint counts as reached this close (blocks, sideways) or when the player is past it. */
    private static final double PLAN_REACHED = 2.5D;
    /** Past a waypoint along its leg counts only this close (blocks) beside the leg. */
    private static final double PLAN_SIDE = 4.0D;
    private static final int PLAN_VERTICAL = 64;

    private void resetPlan() {
        dropPlan();
        classic.clearCourse();
        planJob = null;
        planResult = null;
        nextPlanMs = 0L;
        rockX = Double.NaN;
        rockZ = Double.NaN;
        rockShare = 0.0D;
        planInfo = "";
    }

    private void dropPlan() {
        steer.direct(Double.NaN);
        classic.guide(null, null);
        plan = null;
        planIndex = 0;
        guardArea.corridor(null);
    }

    private void openArchive(MinecraftClient client) {
        Path file = worldFile(client, "world");
        if (planRoutes.on() && file != null) {
            // One folder per server and dimension: config/theprisons/world/<server>_<dimension>/.
            archive.open(client, file.resolveSibling(file.getFileName().toString().replace(".json", "")));
        }
    }

    /**
     * Scan → plan → walk: takes over a finished plan and moves on along its waypoints (the tunnel steering drives and
     * mines, the route line is its guide - see {@link TunnelSteer#guide}). Straight on comes first: while the steering
     * has ore ahead and no route is being walked nothing is planned. A route is planned only when the way ahead has no
     * ore any more ({@link #steer}), when 75 % of the blocks around are stone / deepslate (then in another direction)
     * or when the route would leave the guarded area. A walked route ends in free steering, which goes on straight.
     */
    private void followPlan(ClientPlayerEntity player, long now) {
        if (ticks % 100 == 0) {
            openArchive(MinecraftClient.getInstance());
        }
        OrePlanner.Plan result = planResult;
        if (result != null) {
            planResult = null;
            adoptPlan(player, result, now);
        }
        OrePlanner.Plan p = plan;
        if (p != null) {
            int[][] wp = p.waypoints();
            while (planIndex < wp.length - 1 && planPassed(player, wp[planIndex], wp[planIndex + 1])) {
                planIndex++;
            }
            if (planIndex >= wp.length - 1) {
                // At the rich spot: the steering goes on straight from here and mines; a new route only on a reason.
                count("plan_done");
                ThePrisonsClient.LOGGER.info("[ore_macro] route walked at {} ({} waypoints)", player.getBlockPos().toShortString(), wp.length);
                rememberRoute(p, now);
                dropPlan();
                planInfo = "route walked";
            } else {
                if (!guarded.on() || guardArea.isEmpty() || !guardArea.guardedHere(player.getX(), player.getY(), player.getZ())) {
                    // Not known to be guarded here: this route is not remembered as a guarded one.
                    planGuarded = false;
                }
            }
            if (plan == null) {
                // Walked (dropped above).
            } else if (!guardSafe(p, player)) {
                count("plan_unguarded");
                dropPlan();
                nextCaveMs = now + FAILED_PLAN_PAUSE_MS;
                requestPlan(player, null, "a guard gives no safety there any more");
            } else {
                // The leg's direction (from where the player is to its end) is the steering's way: in the middle of
                // the tunnel, lanes beside it only for ore - the free tunnel mode, not the recorded-route mode.
                int[] to = wp[planIndex + 1];
                if (planIndex != directedIndex) {
                    directedIndex = planIndex;
                    steer.direct(RotationMath.yawOf(to[0] + 0.5D - player.getX(), to[2] + 0.5D - player.getZ()));
                    // Classic steering walks the planned leg as its guide line, like a recorded route.
                    classic.guide(wp[planIndex], to);
                }
            }
        }
        TunnelSteer.Decision last = decision;
        boolean oreAhead = last != null && last.oreAhead();
        if (ticks % ROCK_TICKS == 0) {
            rockShare = rockShare(player);
            boolean moved = Double.isNaN(rockX) || Math.hypot(player.getX() - rockX, player.getZ() - rockZ) >= ROCK_MOVE;
            // Not while a route is walked: it may lead over stone to the ores on purpose.
            if (rockShare >= ROCK_SHARE && moved && !oreAhead && plan == null && planJob == null) {
                rockX = player.getX();
                rockZ = player.getZ();
                count("plan_stone");
                requestPlan(player, new OrePlanner.Avoid(player.getX(), player.getZ(), player.getYaw()),
                        String.format(Locale.ROOT, "%.0f %% stone around", rockShare * 100.0D));
            }
        }
    }

    /** Plans a way to the ores when the way ahead has none (instead of the short travel); false while it may not. */
    private boolean planWhenEmpty(ClientPlayerEntity player, long now, boolean another, String why) {
        if (planJob != null) {
            return true;
        }
        if (now < nextPlanMs) {
            // The last plan found nothing (or the world is not read yet): the old way to the richest spot meanwhile.
            return false;
        }
        requestPlan(player, another ? new OrePlanner.Avoid(player.getX(), player.getZ(), player.getYaw()) : null, why);
        return true;
    }

    private void adoptPlan(ClientPlayerEntity player, OrePlanner.Plan result, long now) {
        if (result.kept()) {
            return;
        }
        if (!result.found()) {
            nextPlanMs = now + PLAN_RETRY_MS;
            planInfo = result.reason();
            return;
        }
        plan = result;
        planIndex = 0;
        // The steering may follow the route over the few unguarded blocks the planner allowed.
        guardArea.corridor(result.nodes());
        StatsService.Session started = session;
        planOresStart = started == null ? 0L : started.counter("ores").value();
        planGuarded = true;
        directedIndex = -1;
        barrenBlocks = 0;
        noOreTicks = 0;
        planInfo = result.reason();
        count("plans_route");
        ThePrisonsClient.LOGGER.info("[ore_macro] new route ({}): {} from {}", planWhy, result.reason(), player.getBlockPos().toShortString());
    }

    /** Past waypoint {@code to}: within {@value #PLAN_REACHED} blocks of it, or further along the segment than its end. */
    private static boolean planPassed(ClientPlayerEntity player, int[] from, int[] to) {
        double tx = to[0] + 0.5D;
        double tz = to[2] + 0.5D;
        if (Math.hypot(player.getX() - tx, player.getZ() - tz) <= PLAN_REACHED) {
            return true;
        }
        double fx = from[0] + 0.5D;
        double fz = from[2] + 0.5D;
        double len = Math.hypot(tx - fx, tz - fz);
        if (len < 1.0E-6D) {
            return true;
        }
        double along = ((player.getX() - fx) * (tx - fx) + (player.getZ() - fz) * (tz - fz)) / len;
        // Past the end only near the leg: far beside it (e.g. walked back to the guards along it) the leg is not walked,
        // else whole legs were skipped and the route ended long before its ores (game 22:58, 23:01).
        double side = Math.abs((player.getX() - fx) * (tz - fz) - (player.getZ() - fz) * (tx - fx)) / len;
        return along >= len - 0.5D && side <= PLAN_SIDE;
    }

    /**
     * The rest of the route still keeps to the guarded area: no more unguarded blocks in a row than allowed now (fewer
     * once a player comes near), ending inside. Without "stay guarded" / without anything known: yes.
     */
    private boolean guardSafe(OrePlanner.Plan p, ClientPlayerEntity player) {
        if (!guarded.on() || guardArea.isEmpty()) {
            return true;
        }
        long[] nodes = p.nodes();
        int from = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < nodes.length; i++) {
            double d = Math.hypot(Pos.x(nodes[i]) + 0.5D - player.getX(), Pos.z(nodes[i]) + 0.5D - player.getZ())
                    + Math.abs(Pos.y(nodes[i]) - player.getY());
            if (d < bestD) {
                bestD = d;
                from = i;
            }
        }
        long[] ahead = java.util.Arrays.copyOfRange(nodes, from, nodes.length);
        // One block of slack: what the tax shows while walking out there may make the stretch a block longer than planned.
        int allowed = guardArea.outsideBudget() == 0 ? 0 : guardArea.maxOutsideRun() + 1;
        return guardArea.longestOutside(ahead, player.getX(), player.getY(), player.getZ()) <= allowed;
    }

    /**
     * Excursions of the free steering: unguarded feet blocks walked in a row are counted; while fewer than the budget
     * (3 with a player near, 5-7 alone) it may walk on over unguarded ground near the zone, then back in.
     */
    private void countOutside(ClientPlayerEntity player) {
        if (guardArea.isEmpty() || guardArea.guardedHere(player.getX(), player.getY(), player.getZ())) {
            outsideWalked = 0;
            outsideBlock = Long.MIN_VALUE;
        } else {
            long block = Pos.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ());
            if (block != outsideBlock) {
                outsideBlock = block;
                outsideWalked++;
            }
        }
        boolean roam = guarded.on() && phase != Phase.GUARD && outsideWalked < guardArea.outsideBudget();
        guardArea.roam(roam);
        if (overrun() && plan != null) {
            // The route said "back in soon", but no guard tax for far longer (the zone is only a model): straight back.
            ThePrisonsClient.LOGGER.info("[ore_macro] {} blocks without guard tax on a route: dropped, straight back to the zone",
                    outsideWalked);
            count("excursion_overrun");
            dropPlan();
        }
    }

    /**
     * Far more blocks without guard tax in a row than allowed: the excursion (the budget) and the longest way back in
     * ({@link GuardArea#maxOutsideRun()}), +2.
     */
    private boolean overrun() {
        if (!guarded.on()) {
            return false;
        }
        if (backInSince != 0L) {
            // On the way back in (the excursion is used up): only the way in may still be crossed.
            return outsideWalked > guardArea.outsideBudget() + backInLead(guardArea.outsideBudget(), guardArea.outsideBudget()) + 2;
        }
        return outsideWalked > guardArea.outsideBudget() + guardArea.maxOutsideRun() + BACK_IN_SLACK + 2;
    }

    /** Planned ways may leave the guarded area for no more than the allowed unguarded blocks in a row. */
    private java.util.function.Predicate<long[]> allowedWays(ClientPlayerEntity player) {
        if (!guarded.on() || guardArea.isEmpty()) {
            return OrePlanner.ANY_WAY;
        }
        GuardArea area = guardArea.copy();
        area.corridor(null);
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        int budget = area.maxOutsideRun();
        return nodes -> area.longestOutside(nodes, px, py, pz) <= budget;
    }

    /** Share of stone / deepslate among the exposed blocks (stone + picked ore) within {@value #ROCK_RADIUS} blocks. */
    private double rockShare(ClientPlayerEntity player) {
        io.theprisons.core.world.SectionStore view = world.store();
        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();
        int rock = 0;
        int ore = 0;
        for (int dx = -ROCK_RADIUS; dx <= ROCK_RADIUS; dx++) {
            for (int dz = -ROCK_RADIUS; dz <= ROCK_RADIUS; dz++) {
                if (dx * dx + dz * dz > ROCK_RADIUS * ROCK_RADIUS) {
                    continue;
                }
                for (int dy = -2; dy <= 3; dy++) {
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    if (!io.theprisons.core.nav.Cell.isFull(view.cell(x, y, z)) || !view.exposed(x, y, z)) {
                        continue;
                    }
                    int key = view.ore(x, y, z);
                    if (key == 0) {
                        rock++;
                    } else if (targetKeys.contains(key)) {
                        ore++;
                    }
                }
            }
        }
        return rock + ore < ROCK_MIN_BLOCKS ? 0.0D : rock / (double) (rock + ore);
    }

    /** Plans on the worker thread over the remembered world; the result is taken over in {@link #followPlan}. */
    private void requestPlan(ClientPlayerEntity player, OrePlanner.@Nullable Avoid avoid, String why) {
        requestPlan(player, avoid, why, false);
    }

    /**
     * @param backIn the excursion is used up: the way back into the guarded zone over the most ore (the planner's best
     *               route that gets back in within a few blocks and not where it left the zone)
     */
    private void requestPlan(ClientPlayerEntity player, OrePlanner.@Nullable Avoid avoid, String why, boolean backIn) {
        if (!backIn && planJob == null && takeRemembered(player, avoid, why)) {
            return;
        }
        Worker.Job running = planJob;
        if (backIn && running != null) {
            running.cancel();
            planJob = null;
        }
        backInPlanning = backIn;
        if (!archive.isOpen() || archive.sectionCount() == 0) {
            nextPlanMs = System.currentTimeMillis() + PLAN_RETRY_MS;
            planInfo = "reading the world";
            return;
        }
        int radius = OrePlanner.Params.DEFAULT.radius();
        ArchiveView view = archive.view(player.getBlockX(), player.getBlockZ(), radius + 16,
                player.getBlockY() - PLAN_VERTICAL, player.getBlockY() + PLAN_VERTICAL);
        long start = new Walkability(view, maxDrop.value()).settle(player.getX(), player.getY(), player.getZ());
        if (start == Long.MIN_VALUE) {
            nextPlanMs = System.currentTimeMillis() + PLAN_RETRY_MS;
            planInfo = "standing where the world is not known yet";
            return;
        }
        long[] keep = backIn ? null : remainingPlan(player);
        IntOpenHashSet keys = new IntOpenHashSet(targetKeys);
        Long2DoubleOpenHashMap factors = region.snapshot(System.currentTimeMillis());
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        it.unimi.dsi.fastutil.longs.Long2DoubleMap costs = guardedCosts(forbidden, player, true);
        java.util.function.Predicate<long[]> allowed = backIn ? backInWays(player) : allowedWays(player);
        // The walked course, not where the player looks (at a wall it looks sideways / back): routes go on, not back.
        double course = classic.course();
        float yaw = Double.isNaN(course) ? player.getYaw() : (float) course;
        int drop = maxDrop.value();
        it.unimi.dsi.fastutil.longs.LongSet known = !routeMemoryOn.on() ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET : routeMemory.preferredColumns(player.getX(), player.getZ(),
                OrePlanner.Params.DEFAULT.radius(), System.currentTimeMillis(), this::rememberedGuarded);
        planWhy = why;
        count("plans");
        GuardArea zone = guardArea.copy();
        zone.corridor(null);
        zone.roam(false);
        // The way back in ends well inside (2 blocks), not at the edge where the guard tax may not show yet.
        java.util.function.LongPredicate guardedNode = n -> zone.deepNode(n, BACK_IN_DEPTH);
        // Nothing well inside within the budget: any guarded ground, and the stretch out may be one budget longer.
        java.util.function.LongPredicate anyGuarded = n -> zone.guardedNode(n, Double.NaN, Double.NaN, Double.NaN);
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        int longer = zone.maxOutsideRun() + BACK_IN_SLACK;
        int looseLead = backInLead(zone.outsideBudget(), outsideWalked) + 2;
        java.util.function.Predicate<long[]> looser = nodes -> zone.leadingOutside(nodes) <= looseLead
                && zone.longestOutside(nodes, px, py, pz) <= longer;
        planJob = submit("plan", cancel -> backIn
                ? backInPlan(view, keys::contains,
                new Walkability(view, drop, true, OrePlanner.centred(view, costs)).climbCost(OrePlanner.CLIMB_COST), start,
                yaw, guardedNode, anyGuarded, factors::get, allowed, looser, cancel::cancelled)
                : OrePlanner.plan(view, keys::contains,
                new Walkability(view, drop, true, OrePlanner.preferring(OrePlanner.centred(view, costs), known)).climbCost(OrePlanner.CLIMB_COST), start,
                yaw, keep, avoid, factors::get, OrePlanner.Params.DEFAULT, allowed, cancel::cancelled), result -> {
            planJob = null;
            planResult = result;
        });
    }

    /** Worker thread: the way back in well inside the zone, else onto any guarded ground with a longer stretch out. */
    private static OrePlanner.Plan backInPlan(VoxelView view, java.util.function.IntPredicate isTarget, Walkability walk,
                                              long start, float yaw, java.util.function.LongPredicate deep,
                                              java.util.function.LongPredicate guarded, java.util.function.LongToDoubleFunction factors,
                                              java.util.function.Predicate<long[]> allowed,
                                              java.util.function.Predicate<long[]> looser, java.util.function.BooleanSupplier cancelled) {
        OrePlanner.Plan plan = OrePlanner.backIn(view, isTarget, walk, start, yaw, deep, factors, OrePlanner.Params.DEFAULT,
                allowed, cancelled);
        if (plan.found() || cancelled.getAsBoolean()) {
            return plan;
        }
        return OrePlanner.backIn(view, isTarget, walk, start, yaw, guarded, factors, OrePlanner.Params.DEFAULT, looser, cancelled);
    }

    /**
     * The way back in after an excursion: back in the zone within the budget (8 alone) of unguarded blocks - the
     * excursion has used its blocks already - and ending inside. Which way in is the planner's choice: the most ore, straight on first (not back the way it came).
     */
    private java.util.function.Predicate<long[]> backInWays(ClientPlayerEntity player) {
        GuardArea area = guardArea.copy();
        area.corridor(null);
        area.roam(false);
        return backInRule(area, player.getX(), player.getY(), player.getZ(), backInLead(area.outsideBudget(), outsideWalked));
    }

    /**
     * Unguarded blocks the way back in may still cross before the zone: what is left of the excursion (budget less the
     * blocks walked out), at least {@value #BACK_IN_SLACK} (the model's edge lies a block off). Game 2026-10-05: the old
     * rule only limited the longest unguarded stretch, so the way "back in" led 15 more blocks out first.
     */
    static int backInLead(int budget, int walked) {
        return Math.max(BACK_IN_SLACK, budget - walked + BACK_IN_SLACK);
    }

    /** The way back in: guarded ground within {@code lead} blocks, and later stretches out within the excursion rule. */
    static java.util.function.Predicate<long[]> backInRule(GuardArea area, double px, double py, double pz, int lead) {
        int out = area.outsideBudget() + BACK_IN_SLACK;
        return nodes -> area.leadingOutside(nodes) <= lead && area.longestOutside(nodes, px, py, pz) <= out;
    }

    /**
     * Cave mode: plans the way to the best tunnel leaving the cave ({@link OrePlanner#tunnel}) over the remembered world
     * (512 blocks to each side), walked like any planned route.
     */
    private void requestTunnel(ClientPlayerEntity player) {
        if (takeRemembered(player, null, "cave")) {
            return;
        }
        if (!archive.isOpen() || archive.sectionCount() == 0) {
            return;
        }
        int radius = OrePlanner.Params.DEFAULT.radius();
        ArchiveView view = archive.view(player.getBlockX(), player.getBlockZ(), radius + 16,
                player.getBlockY() - PLAN_VERTICAL, player.getBlockY() + PLAN_VERTICAL);
        long start = new Walkability(view, maxDrop.value()).settle(player.getX(), player.getY(), player.getZ());
        if (start == Long.MIN_VALUE) {
            return;
        }
        IntOpenHashSet keys = new IntOpenHashSet(targetKeys);
        Long2DoubleOpenHashMap factors = region.snapshot(System.currentTimeMillis());
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        it.unimi.dsi.fastutil.longs.Long2DoubleMap costs = guardedCosts(forbidden, player, true);
        java.util.function.Predicate<long[]> allowed = allowedWays(player);
        // The walked course, not where the player looks (at a wall it looks sideways / back): routes go on, not back.
        double course = classic.course();
        float yaw = Double.isNaN(course) ? player.getYaw() : (float) course;
        int drop = maxDrop.value();
        it.unimi.dsi.fastutil.longs.LongSet known = !routeMemoryOn.on() ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET : routeMemory.preferredColumns(player.getX(), player.getZ(),
                OrePlanner.Params.DEFAULT.radius(), System.currentTimeMillis(), this::rememberedGuarded);
        planWhy = "cave";
        count("plans_tunnel");
        planJob = submit("tunnel", cancel -> OrePlanner.tunnel(view, keys::contains,
                new Walkability(view, drop, true, OrePlanner.preferring(OrePlanner.centred(view, costs), known)).climbCost(OrePlanner.CLIMB_COST), start,
                yaw, factors::get, OrePlanner.Params.DEFAULT, allowed, cancel::cancelled), result -> {
            planJob = null;
            planResult = result;
        });
    }

    // ── Steering view: live world cache, the saved world archive where the cache does not know a block yet ──

    private static final int STEER_ARCHIVE_RADIUS = 64;
    private static final int STEER_ARCHIVE_TICKS = 100;
    private @Nullable VoxelView steerView;
    private int steerViewX = Integer.MIN_VALUE;
    private int steerViewZ;
    private long steerViewTick = Long.MIN_VALUE;
    private net.minecraft.client.world.ClientWorld steerWorld;

    /**
     * What the steering sees: the live world cache, and where it does not know a block yet (just joined, chunks still
     * being read) the saved world archive - so not yet scanned blocks are not taken for walls. Live blocks always win
     * (the archive may be older). The archive part is a frozen copy around the player, renewed every
     * {@value #STEER_ARCHIVE_TICKS} ticks or after 24 blocks.
     */
    private VoxelView steerView(ClientPlayerEntity player) {
        io.theprisons.core.world.SectionStore live = world.store();
        net.minecraft.client.world.ClientWorld clientWorld = MinecraftClient.getInstance().world;
        int px = player.getBlockX();
        int pz = player.getBlockZ();
        if (steerView == null || clientWorld != steerWorld || ticks - steerViewTick >= STEER_ARCHIVE_TICKS
                || Math.abs(px - steerViewX) > 24 || Math.abs(pz - steerViewZ) > 24) {
            // Three layers: the scanned cache (fast), the loaded client world for what the scanner has not read yet
            // (walking into a new area: the blocks are there, only not scanned - they must not count as walls), and the
            // saved world archive for chunks not loaded.
            VoxelView loaded = clientWorld == null ? null : new io.theprisons.core.world.LiveWorldView(clientWorld, world.classifier());
            VoxelView saved = archive.isOpen() && archive.sectionCount() > 0
                    ? archive.view(px, pz, STEER_ARCHIVE_RADIUS, player.getBlockY() - PLAN_VERTICAL, player.getBlockY() + PLAN_VERTICAL)
                    : null;
            steerView = new VoxelView() {
                private VoxelView source(int x, int y, int z) {
                    if (live.cell(x, y, z) != io.theprisons.core.nav.Cell.UNKNOWN) {
                        return live;
                    }
                    if (loaded != null && loaded.cell(x, y, z) != io.theprisons.core.nav.Cell.UNKNOWN) {
                        return loaded;
                    }
                    return saved != null ? saved : live;
                }

                @Override
                public int cell(int x, int y, int z) {
                    return source(x, y, z).cell(x, y, z);
                }

                @Override
                public int ore(int x, int y, int z) {
                    return source(x, y, z).ore(x, y, z);
                }

                @Override
                public boolean mineFloor(int x, int y, int z) {
                    return source(x, y, z).mineFloor(x, y, z);
                }
            };
            steerWorld = clientWorld;
            steerViewX = px;
            steerViewZ = pz;
            steerViewTick = ticks;
        }
        return steerView;
    }

    /** Diagnostics: how much of the floor around the player the world cache knows (unknown blocks count as walls). */
    private void logCache(ClientPlayerEntity player) {
        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();
        int unknown = 0;
        int total = 0;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    total++;
                    if (world.store().cell(px + dx, py + dy, pz + dz) == io.theprisons.core.nav.Cell.UNKNOWN) {
                        unknown++;
                    }
                }
            }
        }
        ThePrisonsClient.LOGGER.info("[ore_macro] cache around {}: {} of {} blocks unknown, {} sections; steering {}",
                player.getBlockPos().toShortString(), unknown, total, world.store().size(), status);
    }

    /** The planned way from the node nearest to the player on ({@code null} without a plan). */
    private long @Nullable [] remainingPlan(ClientPlayerEntity player) {
        OrePlanner.Plan p = plan;
        if (p == null || p.nodes().length < 2) {
            return null;
        }
        long[] nodes = p.nodes();
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < nodes.length; i++) {
            double d = Math.hypot(Pos.x(nodes[i]) + 0.5D - player.getX(), Pos.z(nodes[i]) + 0.5D - player.getZ())
                    + Math.abs(Pos.y(nodes[i]) - player.getY());
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return nearest >= nodes.length - 1 ? null : java.util.Arrays.copyOfRange(nodes, nearest, nodes.length);
    }

    // ── Learned routes (routes_memory) ───────────────────────────────────────

    private void loadRouteMemory(MinecraftClient client) {
        Path file = worldFile(client, "routes_memory");
        routeMemoryFile = file;
        if (file == null) {
            return;
        }
        // Read in the background; the routes go into the RAM cache on the client thread - if still this world.
        routeIo.load(file, client::execute, routes -> {
            if (file.equals(routeMemoryFile)) {
                routeMemory.replaceAll(routes);
                if (!routes.isEmpty()) {
                    ThePrisonsClient.LOGGER.info("[ore_macro] {} learned routes loaded", routes.size());
                }
            }
        });
    }

    /** A planned route walked to its end: kept when it stayed inside the guarded area the whole way. */
    private void rememberRoute(OrePlanner.Plan p, long now) {
        StatsService.Session run = session;
        Path file = routeMemoryFile;
        if (!routeMemoryOn.on() || !planGuarded || run == null || file == null || p.nodes().length < 2) {
            return;
        }
        int ores = (int) Math.max(0L, run.counter("ores").value() - planOresStart);
        double width = RoutePath.widthRadius(new Walkability(world.store(), maxDrop.value()), p.nodes());
        routeMemory.record("cave".equals(planWhy) ? "tunnel" : "plan", currentMine, p.nodes(), width, ores,
                Math.max(1.0D, p.cost()), now);
        count("routes_learned");
        // An immutable snapshot taken here; encoding and writing happen on the route I/O thread.
        routeIo.save(file, routeMemory.snapshot());
    }

    /**
     * A learned route that starts here, gave enough ore, has rested long enough and still lies inside the guarded area
     * (and, when asked for another direction, does not start the old way): taken at once instead of planning anew.
     */
    private boolean takeRemembered(ClientPlayerEntity player, OrePlanner.@Nullable Avoid avoid, String why) {
        if (!routeMemoryOn.on() || routeMemory.size() == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        double course = classic.course();
        RouteMemory.Match m = routeMemory.best(player.getX(), player.getY(), player.getZ(), now, route -> {
            if (!rememberedGuarded(route)) {
                return false;
            }
            if (!Double.isNaN(course)) {
                // A learned route back the way just walked: no (one stretch, no return).
                int[] a = route.waypoints[0];
                int[] b = route.waypoints[1];
                float dir = RotationMath.yawOf(b[0] - a[0], b[2] - a[2]);
                if (Math.abs(RotationMath.wrap(dir - (float) course)) > ClassicSteer.COURSE_BACK) {
                    return false;
                }
            }
            if (avoid != null) {
                int[] a = route.waypoints[0];
                int[] b = route.waypoints[1];
                float dir = RotationMath.yawOf(b[0] - a[0], b[2] - a[2]);
                if (Math.abs(RotationMath.wrap(dir - avoid.heading())) < OrePlanner.AVOID_DEGREES) {
                    return false;
                }
            }
            return true;
        });
        if (m == null) {
            return false;
        }
        RouteMemory.Route r = m.route();
        long[] nodes = m.nodes();
        planWhy = why;
        count("plans_remembered");
        adoptPlan(player, new OrePlanner.Plan(nodes, OrePlanner.waypoints(nodes), r.ores, r.blocks, r.yield(), false,
                String.format(Locale.ROOT, "learned route (%d runs, %.2f ores/block)", r.runs, r.yield())), now);
        return true;
    }

    /** A learned route still inside the guarded area (with "stay guarded" on: guards / tax zone known and every node in it). */
    private boolean rememberedGuarded(RouteMemory.Route route) {
        if (!guarded.on()) {
            return true;
        }
        if (guardArea.isEmpty()) {
            return false;
        }
        long[] nodes = route.nodes();
        for (int i = 0; i < nodes.length; i += 4) {
            if (!guardArea.predictInside(Pos.x(nodes[i]) + 0.5D, Pos.y(nodes[i]), Pos.z(nodes[i]) + 0.5D)) {
                return false;
            }
        }
        return true;
    }

    /** Learned routes kept for this world (the session HUD shows them). */
    public int learnedRoutes() {
        return routeMemory.size();
    }

    /** Loads the learned routes of the world the player is in, if not loaded yet (also while the macro is off). */
    public void syncRouteMemory(MinecraftClient client) {
        if (client.world == null) {
            return;
        }
        Path file = worldFile(client, "routes_memory");
        if (file != null && !file.equals(routeMemoryFile)) {
            loadRouteMemory(client);
        }
    }

    /** Guards from earlier runs: known (area, planning) at once, forgotten when in sight range and not there. */
    private void loadGuards(MinecraftClient client) {
        Path file = worldFile(client, "guards");
        if (file == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return Files.exists(file) ? Files.readString(file) : null;
            } catch (IOException e) {
                return null;
            }
        }, Util.getIoWorkerExecutor()).thenAcceptAsync(json -> {
            if (json == null || !enabled()) {
                return;
            }
            try {
                double[][] saved = new com.google.gson.Gson().fromJson(json, double[][].class);
                if (saved != null) {
                    for (double[] g : saved) {
                        if (g != null && g.length >= 3) {
                            guardArea.remember(g[0], g[1], g[2]);
                        }
                    }
                    ThePrisonsClient.LOGGER.info("[ore_macro] {} guards remembered from earlier runs", saved.length);
                }
            } catch (RuntimeException ignored) {
                // A broken file only means the guards are found by sight again.
            }
        }, client);
    }

    private void saveGuards() {
        Path file = worldFile(MinecraftClient.getInstance(), "guards");
        if (file == null || guardArea.size() == 0) {
            return;
        }
        double[][] out = new double[guardArea.size()][];
        for (int i = 0; i < out.length; i++) {
            double[] g = guardArea.guards().get(i);
            out[i] = new double[]{g[0], g[1], g[2]};
        }
        String json = new com.google.gson.Gson().toJson(out);
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, json);
            } catch (IOException ignored) {
                // Tried again with the next save.
            }
        });
    }

    /** config/theprisons/guardzone/&lt;server&gt;_&lt;dimension&gt;.bin: the blocks where the guard XP tax was on / gone. */
    private static @Nullable Path zoneFile(MinecraftClient client) {
        Path file = worldFile(client, "guardzone");
        return file == null ? null : file.resolveSibling(file.getFileName().toString().replace(".json", ".bin"));
    }

    /** The guard tax zone from earlier runs: where it was on (inside) and gone (outside). */
    private void loadGuardZone(MinecraftClient client) {
        Path file = zoneFile(client);
        if (file == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            if (!Files.exists(file)) {
                return null;
            }
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.util.zip.GZIPInputStream(Files.newInputStream(file))))) {
                long[][] out = new long[2][];
                for (int k = 0; k < 2; k++) {
                    int n = in.readInt();
                    out[k] = new long[n];
                    for (int i = 0; i < n; i++) {
                        out[k][i] = in.readLong();
                    }
                }
                return out;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }, Util.getIoWorkerExecutor()).thenAcceptAsync(zone -> {
            if (zone == null || !enabled()) {
                return;
            }
            // "No tax" blocks are kept for good (the user: never go there again, even for ore).
            guardArea.restore(zone[0], zone[1]);
            ThePrisonsClient.LOGGER.info("[ore_macro] guard tax zone from earlier runs: {} blocks inside, {} outside{}",
                    zone[0].length, zone[1].length, "");
        }, client);
    }

    private void saveGuardZone() {
        Path file = zoneFile(MinecraftClient.getInstance());
        if (file == null || guardArea.insideBlocks() == 0 && guardArea.outsideBlocks() == 0) {
            return;
        }
        long[] in = guardArea.taxedBlocks();
        long[] out = guardArea.untaxedBlocks();
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                        new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp))))) {
                    for (long[] part : new long[][]{in, out}) {
                        o.writeInt(part.length);
                        for (long cell : part) {
                            o.writeLong(cell);
                        }
                    }
                }
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Tried again with the next save.
            }
        });
    }

    // ── Route mode ───────────────────────────────────────────────────────────

    /**
     * Walks the route as a loop (... → last → 1 → 2 → ...). To the start waypoint (the nearest one) with the
     * pathfinder, mining on the way (dropping down is fine - no fall damage). From there the normal tunnel steering drives - straight, in the middle, mining on the
     * move - with the route line to the next waypoint as its guide (after the last one: to waypoint 1, no pause): never
     * a step back, and up to {@link RouteCorridor#WIDTH} blocks beside the line where there is ore, never further. Only when the steering finds no way on, the pathfinder takes
     * it to the waypoint (emergency). Failsafe: a waypoint not found or reached for {@value #ROUTE_FAILSAFE_MS} ms is
     * skipped.
     */
    private void followRoute(ClientPlayerEntity player, long now) {
        Route r = activeRoute;
        if (r == null || r.waypoints().isEmpty()) {
            phase = Phase.STEER;
            return;
        }
        int[] wp = r.waypoints().get(routeIndex);
        double dist = Math.hypot(wp[0] + 0.5D - player.getX(), wp[2] + 0.5D - player.getZ());
        // Waypoints on the way to waypoint 1 are only stops: reached from further away, no need to hit them exactly.
        double reach = approaching && routeIndex != approachGoal ? APPROACH_HOP_REACHED : WAYPOINT_REACHED;
        boolean reached = dist < reach && Math.abs(wp[1] - player.getY()) < 2.5D;
        if (!reached && !approaching) {
            // Walking beside the line (up to the corridor width) counts too, once the waypoint is passed.
            double[] q = RouteCorridor.project(previousWaypoint(r), wp, player.getX(), player.getZ());
            reached = q[0] >= q[2] - 0.5D && q[1] <= RouteCorridor.WIDTH + 0.5D && Math.abs(wp[1] - player.getY()) < 2.5D;
        }
        if (reached) {
            reachedWaypoint(player, r);
            return;
        }
        if (routeTroubleMs != 0L && dist < routeTroubleDist - TROUBLE_PROGRESS) {
            // Getting closer again: the trouble is over.
            routeTroubleMs = 0L;
        }
        if (routeTroubleMs != 0L && now - routeTroubleMs >= ROUTE_FAILSAFE_MS) {
            count("route_failsafe");
            skipWaypoint(player, r);
            return;
        }
        if (approaching || driver.path() != null || routeJob != null) {
            routePath(player, r, wp, dist);
        } else {
            routeSteer(player, r, wp, now);
        }
    }

    /** Between two waypoints: the tunnel steering, guided by the route. */
    private void routeSteer(ClientPlayerEntity player, Route r, int[] wp, long now) {
        steer.guide(previousWaypoint(r), wp);
        TunnelSteer.Decision d = steer.decide(steerView(player), targetKeys::contains, this::isForeign, player.getX(), player.getY(),
                player.getZ(), player.getYaw(), player.isOnGround(), walked, zone -> region.factor(zone, now), wardens, now);
        decision = d;
        if (!d.forward()) {
            emergency(player, r, wp, "no way on");
            return;
        }
        float rel = Math.abs(RotationMath.wrap(d.yaw() - player.getYaw()));
        boolean forward = rel < LaneDriver.WALK_ALIGNMENT;
        boolean run = sprint.on() && forward && rel < 25.0F && d.free() > 3.0D && !d.jump();
        boolean jump = forward && (d.jump() || player.horizontalCollision && player.isOnGround());
        control.input().set(new InputController.Keys(forward, false, false, false, jump, run, false));
        look(player, d.yaw(), d.aheadFeet(), d.aheadDist());
        status = String.format(Locale.ROOT, "Route %s: to waypoint %d/%d (%.0fm free, %.0f ore ahead)", r.name(), routeIndex + 1,
                r.waypoints().size(), d.free(), d.ore());

        double moved = Math.hypot(player.getX() - lastX, player.getZ() - lastZ);
        lastX = player.getX();
        lastZ = player.getZ();
        stuckTicks = forward && moved < 0.02D ? stuckTicks + 1 : 0;
        if (stuckTicks > STUCK_TICKS) {
            stuckTicks = 0;
            count("stuck");
            routeFailures++;
            routeTrouble(player, "stuck");
            steer.block(d.heading(), now + BLOCK_MS);
            if (routeFailures >= 2) {
                emergency(player, r, wp, "stuck");
            }
        }
    }

    /** Route paths (also the way to the start waypoint) mine on the move: the mining view at the floor. */
    private void routeLook(ClientPlayerEntity player, float yaw) {
        look(player, yaw, null, null);
    }

    /**
     * No stop-and-go on the way to waypoint 1: {@value #PLAN_AHEAD} blocks before the end of the path the next one is
     * planned (to the next waypoint when this path ends at the current one, else on from a partial path) and taken
     * over while walking.
     */
    private void planAhead(ClientPlayerEntity player, Route r, int[] wp) {
        NavigationPath path = driver.path();
        if (!approaching || path == null || routeJob != null || ticks < routeRetryTick || driver.remaining() > PLAN_AHEAD) {
            return;
        }
        long goal = path.goal();
        boolean endsThere = Math.hypot(Pos.x(goal) - wp[0], Pos.z(goal) - wp[2]) <= 2.0D && Math.abs(Pos.y(goal) - wp[1]) <= 2;
        if (!endsThere) {
            requestRoutePath(player, wp);
        } else if (routeIndex > approachGoal) {
            routeIndex = approachHop(player, r, approachGoal, routeIndex - 1);
            routeFailures = 0;
            requestRoutePath(player, r.waypoints().get(routeIndex));
        }
    }

    /** The steering cannot go on: the pathfinder walks to the waypoint (mining on the way), then the steering again. */
    private void emergency(ClientPlayerEntity player, Route r, int[] wp, String why) {
        control.input().clear();
        look(player, player.getYaw(), null, null);
        count("route_emergency");
        routeTrouble(player, why);
        if (ticks >= routeRetryTick) {
            requestRoutePath(player, wp);
        }
    }

    /** Pathfinder leg: to waypoint 1 (walking only), or the emergency way to a waypoint. */
    private void routePath(ClientPlayerEntity player, Route r, int[] wp, double dist) {
        if (driver.path() != null) {
            LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                    player.isOnGround(), player.horizontalCollision), sprint.on());
            control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), drive.sprint(), false));
            routeLook(player, drive.yaw());
            status = String.format(Locale.ROOT, "Route %s: %s waypoint %d/%d (%.0fm)", r.name(),
                    approaching ? "pathfinder (mining) to start" : "pathfinder to", routeIndex + 1, r.waypoints().size(), dist);
            if (drive.status() == LaneDriver.Status.FOLLOWING) {
                planAhead(player, r, wp);
            } else {
                if (drive.status() == LaneDriver.Status.STUCK) {
                    count("route_stuck");
                    routeTrouble(player, "stuck on the path");
                }
                // Not approaching: the steering takes over again from wherever the path ended.
                driver.stop();
            }
            return;
        }
        control.input().clear();
        routeLook(player, player.getYaw());
        status = String.format(Locale.ROOT, "Route %s: finding the way to waypoint %d/%d", r.name(), routeIndex + 1, r.waypoints().size());
        if (routeJob != null || ticks < routeRetryTick) {
            return;
        }
        requestRoutePath(player, wp);
    }

    /** Something keeps the macro from the current waypoint: starts the failsafe clock (if not running yet). */
    private void routeTrouble(ClientPlayerEntity player, String why) {
        lastIssue = why;
        Route r = activeRoute;
        if (routeTroubleMs == 0L && r != null) {
            int[] wp = r.waypoints().get(routeIndex);
            routeTroubleMs = System.currentTimeMillis();
            routeTroubleDist = Math.hypot(wp[0] + 0.5D - player.getX(), wp[2] + 0.5D - player.getZ());
        }
    }

    /**
     * Where the route is joined (start, teleport): standing on the route already (within the corridor between two
     * waypoints) it simply walks on to the next one - no step back; otherwise the pathfinder walks to the nearest
     * waypoint (mining on the way, drops allowed) and the route begins there.
     */
    private void enterRoute(ClientPlayerEntity player, Route r) {
        List<int[]> points = r.waypoints();
        int onSegment = -1;
        double bestSide = Double.MAX_VALUE;
        for (int k = 0; k < points.size() && points.size() > 1; k++) {
            int[] a = points.get(k);
            int[] b = points.get((k + 1) % points.size());
            double[] q = RouteCorridor.project(a, b, player.getX(), player.getZ());
            double frac = q[2] < 1.0E-6D ? 0.0D : Math.max(0.0D, Math.min(1.0D, q[0] / q[2]));
            double height = a[1] + (b[1] - a[1]) * frac;
            if (q[1] <= RouteCorridor.WIDTH && q[0] >= 0.0D && q[0] <= q[2] && Math.abs(player.getY() - height) <= 3.0D
                    && q[1] < bestSide) {
                onSegment = k;
                bestSide = q[1];
            }
        }
        if (onSegment >= 0) {
            approaching = false;
            approachGoal = onSegment;
            routeIndex = (onSegment + 1) % points.size();
            ThePrisonsClient.LOGGER.info("[ore_macro] route {}: on the route between waypoints {} and {}", r.name(), onSegment + 1,
                    routeIndex + 1);
            return;
        }
        int nearest = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = weightedDistance(player, points.get(i));
            if (d < bestDistance) {
                nearest = i;
                bestDistance = d;
            }
        }
        approaching = true;
        approachGoal = nearest;
        routeIndex = nearest;
        ThePrisonsClient.LOGGER.info("[ore_macro] route {}: starting at the nearest waypoint {}", r.name(), nearest + 1);
    }

    /** Next waypoint after reaching the current one; after the last one waypoint 1 - the route is a loop. */
    private void reachedWaypoint(ClientPlayerEntity player, Route r) {
        int count = r.waypoints().size();
        count("route_reached");
        if (approaching && routeIndex != approachGoal) {
            setRouteTarget(approachHop(player, r, approachGoal, routeIndex - 1));
            return;
        }
        if (approaching) {
            approaching = false;
            routeStartTries = 0;
            count("route_started");
            ThePrisonsClient.LOGGER.info("[ore_macro] route {}: at waypoint {}, mining from here", r.name(), approachGoal + 1);
        } else if (routeIndex == count - 1) {
            // Past the last waypoint: straight on to waypoint 1 like any other part of the route - mining, no pause.
            count("route_laps");
        }
        setRouteTarget((routeIndex + 1) % count);
    }

    /** The waypoint before the current one (before waypoint 1: the last one - the way back closes the loop). */
    private int[] previousWaypoint(Route r) {
        int count = r.waypoints().size();
        return r.waypoints().get((routeIndex - 1 + count) % count);
    }

    private void skipWaypoint(ClientPlayerEntity player, Route r) {
        count("route_skipped");
        if (approaching && routeIndex == approachGoal) {
            // The waypoint to begin at is not reachable: begin at the next one instead.
            if (++routeStartTries >= r.waypoints().size()) {
                disableSelf("No waypoint of \"" + r.name() + "\" is reachable from here");
                return;
            }
            notify("Waypoint " + (routeIndex + 1) + " of \"" + r.name() + "\" is not reachable - trying the next one.",
                    io.theprisons.core.module.ModuleHost.Level.WARNING);
            approachGoal = (approachGoal + 1) % r.waypoints().size();
            setRouteTarget(approachGoal);
            return;
        }
        notify("Waypoint " + (routeIndex + 1) + " of \"" + r.name() + "\" is not reachable - skipping it.",
                io.theprisons.core.module.ModuleHost.Level.WARNING);
        if (approaching) {
            setRouteTarget(Math.max(approachGoal, routeIndex - 1));
        } else {
            reachedWaypoint(player, r);
        }
    }

    private void setRouteTarget(int index) {
        routeIndex = index;
        routeFailures = 0;
        routeTroubleMs = 0L;
        routeJob = null;
        driver.stop();
    }

    /**
     * The next waypoint on the way to waypoint {@code goal}: the goal itself when it is near enough for the pathfinder
     * (the block cache only knows the surroundings), else the nearest waypoint between the goal and {@code maxIndex},
     * from where the route is walked backwards - a way known to be walkable.
     */
    private static int approachHop(ClientPlayerEntity player, Route r, int goal, int maxIndex) {
        int[] target = r.waypoints().get(goal);
        if (Math.hypot(target[0] + 0.5D - player.getX(), target[2] + 0.5D - player.getZ()) <= APPROACH_DIRECT
                && target[1] - player.getY() <= APPROACH_DIRECT_HEIGHT && player.getY() - target[1] <= APPROACH_DIRECT_DEPTH) {
            return goal;
        }
        int best = goal;
        double bestDistance = Double.MAX_VALUE;
        for (int i = goal; i <= Math.min(maxIndex, r.waypoints().size() - 1); i++) {
            double d = weightedDistance(player, r.waypoints().get(i));
            if (d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    /**
     * Horizontal distance; a waypoint higher up counts three times (another floor is far away), one further down only
     * once - the macro may simply drop down (no fall damage).
     */
    private static double weightedDistance(ClientPlayerEntity player, int[] p) {
        double up = p[1] - player.getY();
        return Math.hypot(p[0] + 0.5D - player.getX(), p[2] + 0.5D - player.getZ()) + (up > 0.0D ? 3.0D * up : -up);
    }

    private void requestRoutePath(ClientPlayerEntity player, int[] wp) {
        int radius = Math.min(160, Math.max(Math.abs(wp[0] - player.getBlockX()), Math.abs(wp[2] - player.getBlockZ())) + 16);
        WorldSnapshot snapshot = world.snapshot(player, radius + 16, 48);
        long start = new Walkability(snapshot.copyView(), ROUTE_DROP).settle(player.getX(), player.getY(), player.getZ());
        routeRetryTick = ticks + 20;
        if (start == Long.MIN_VALUE) {
            return;
        }
        LongOpenHashSet goals = new LongOpenHashSet();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    goals.add(Pos.pack(wp[0] + dx, wp[1] + dy, wp[2] + dz));
                }
            }
        }
        // Only the hand-placed borders limit where it walks.
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        it.unimi.dsi.fastutil.longs.Long2DoubleMap costs = guardedCosts(forbidden, player);
        int drop = ROUTE_DROP;
        count("route_plans");
        routeJob = submit("route", cancel -> {
            Walkability walk = new Walkability(snapshot, drop, true, costs);
            PathSearch.Result found = PathSearch.findPath(walk, start, goals, 60_000, radius, snapshot.version(), cancel::cancelled);
            return found.path() == null ? found : new PathSearch.Result(found.status(), PathStraightener.apply(found.path(), walk),
                    found.expanded(), found.reason());
        }, found -> {
            routeJob = null;
            if (phase != Phase.ROUTE) {
                return;
            }
            if (found.usable() && found.path() != null && found.path().size() > 1) {
                driver.start(found.path());
            } else {
                ClientPlayerEntity current = MinecraftClient.getInstance().player;
                if (current != null) {
                    routeTrouble(current, "no way to waypoint " + (routeIndex + 1) + ": " + found.reason());
                }
            }
        });
    }

    /** Ore of a package that is not selected (another mine): a border. */
    private boolean isForeign(int key) {
        return allOreKeys.contains(key) && !targetKeys.contains(key);
    }

    /**
     * The wardens: Cosmic Prisons guard NPCs (Citizens entities with 1000 HP, "Warden" in the name or in the name tag
     * hologram above them). Attacking one makes the player a villain, so the macro keeps its distance.
     */
    private static double[][] findWardens(ClientWorld clientWorld, ClientPlayerEntity player) {
        List<double[]> found = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : clientWorld.getEntities()) {
            if (entity == player || entity.squaredDistanceTo(player) > 96.0D * 96.0D) {
                continue;
            }
            if (isWarden(entity)) {
                found.add(new double[]{entity.getX(), entity.getY(), entity.getZ()});
            }
        }
        return found.toArray(new double[0][]);
    }

    static boolean isWarden(net.minecraft.entity.Entity entity) {
        if (entity.getType() == net.minecraft.entity.EntityType.WARDEN) {
            return true;
        }
        net.minecraft.text.Text custom = entity.getCustomName();
        String name = (custom != null ? custom.getString() : entity.getName().getString()).toLowerCase(Locale.ROOT);
        return name.contains("warden");
    }

    /** Guards, wardens and enforcers in sight: where an attacked player runs to. */
    private static double[][] findGuards(ClientWorld clientWorld, ClientPlayerEntity player) {
        List<double[]> found = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : clientWorld.getEntities()) {
            if (entity != player && entity.squaredDistanceTo(player) <= GuardArea.SCAN_RANGE * GuardArea.SCAN_RANGE && isGuard(entity)) {
                found.add(new double[]{entity.getX(), entity.getY(), entity.getZ()});
            }
        }
        return found.toArray(new double[0][]);
    }

    private static final int GUARD_CENSUS_TICKS = 600;
    /** Guards known when last logged. */
    private int loggedGuards;

    /** The sidebar's guard XP tax at the player's feet block, into the guarded area (see {@link GuardArea#tax}). */
    private void readTax(MinecraftClient client, ClientWorld clientWorld, ClientPlayerEntity player) {
        if (taxFromEnergy.on()) {
            readEnergyTax(player);
            return;
        }
        Boolean tax = guardTax(client, clientWorld);
        logTaxPercent(client, clientWorld, player);
        taxGoneReads = Boolean.FALSE.equals(tax) ? taxGoneReads + 1 : 0;
        if (Boolean.FALSE.equals(tax) && taxGoneReads < TAX_GONE_READS) {
            // Possibly only the sidebar being rewritten: not outside yet.
            tax = null;
        }
        boolean wentOff = Boolean.FALSE.equals(tax) && Boolean.TRUE.equals(lastTax);
        if (tax != null && !tax.equals(lastTax)) {
            ThePrisonsClient.LOGGER.info("[ore_macro] guard xp tax {} at {}", tax ? "on (in the guarded zone)" : "gone (outside)",
                    player.getBlockPos().toShortString());
        }
        if (tax != null) {
            lastTax = tax;
        }
        guardArea.tax(tax, player.getBlockX(), player.getBlockY(), player.getBlockZ());
        if (wentOff) {
            // Walked out: the edge runs across the way here (the walking direction, not where the view points).
            double dx = player.getX() - player.lastX;
            double dz = player.getZ() - player.lastZ;
            float walkYaw = dx * dx + dz * dz > 1.0E-4D ? RotationMath.yawOf(dx, dz) : player.getYaw();
            guardArea.edge(player.getBlockX(), player.getBlockY(), player.getBlockZ(), walkYaw);
        }
    }

    /**
     * The charge orbs' bonus, read from the action bar only (the one true value, the user says): it changes with the
     * orbs on the pickaxe, and with it the energy per ore - a new energy reference, not the guard tax.
     */
    private void chargeBonus(String line, String where) {
        java.util.regex.Matcher m = CHARGE.matcher(line);
        if (!m.find()) {
            m = PERCENT.matcher(line);
            if (!line.toLowerCase(Locale.ROOT).contains("charge") || !m.find()) {
                return;
            }
        }
        int percent = (int) Math.round(Double.parseDouble(m.group(1).replace(',', '.')));
        Integer before = chargePercent.put(where, percent);
        if (before != null && before != percent) {
            energyTax.rebase();
            ThePrisonsClient.LOGGER.info("[ore_macro] charge orb bonus {}% -> {}% ({}) - new energy reference", before, percent, where);
        }
    }

    /** A number in a sidebar line ("Energy: 1,234,567" / "12.5M"); -1 = none. */
    static long sidebarNumber(String line) {
        java.util.regex.Matcher m = NUMBER.matcher(line);
        long found = -1L;
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            try {
                double v = Double.parseDouble(digits.endsWith(".") ? digits.substring(0, digits.length() - 1) : digits);
                switch (m.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> v *= 1_000D;
                    case "m" -> v *= 1_000_000D;
                    case "b" -> v *= 1_000_000_000D;
                    default -> {
                    }
                }
                found = Math.round(v);
            } catch (NumberFormatException ignored) {
                // not a number after all
            }
        }
        return found;
    }

    /** The guard tax from the energy per ore (the held pickaxe's lore, every tick) into the guarded area. */
    private void readEnergyTax(ClientPlayerEntity player) {
        // The run starts in the guarded area (the user's rule); the energy tells when it is left.
        energyTax.assume(true);
        net.minecraft.item.ItemStack held = player.getMainHandStack();
        EnergyTax.Change change = null;
        boolean pickaxe = held.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
        List<String> lore = pickaxe ? io.theprisons.core.client.ClientReadouts.lore(held) : List.of();
        long energy = -1L;
        String source = "";
        energy = io.theprisons.modules.hud.CosmicStats.loreEnergy(lore);
        if (energy >= 0L) {
            source = "lore:" + io.theprisons.core.client.TextStrip.strip(held.getName().getString());
        }
        List<String> side = null;
        if (energy < 0L) {
            // No energy in the lore: an energy number in the sidebar (it changes with every ore).
            side = io.theprisons.core.client.ClientReadouts.sidebar(MinecraftClient.getInstance(), MinecraftClient.getInstance().world);
            if (side != null) {
                for (String line : side) {
                    String plain = io.theprisons.core.client.TextStrip.strip(line);
                    if (plain.toLowerCase(Locale.ROOT).contains("energy")) {
                        energy = sidebarNumber(plain);
                        if (energy >= 0L) {
                            source = "sidebar";
                            break;
                        }
                    }
                }
            }
        }
        if (energy >= 0L) {
            long before = energyTax.samples();
            change = energyTax.energy(source, energy);
            if (energyTax.samples() != before) {
                taxTrail[taxTrailNext] = new double[]{player.getX(), player.getY(), player.getZ()};
                taxTrailNext = (taxTrailNext + 1) % taxTrail.length;
            }
        }
        if (energyTax.samples() < 20L && ticks >= nextEnergyDiagTick) {
            // Until it measures: what the server gives (the item, its lore, the sidebar) - to fix the reading from the log.
            nextEnergyDiagTick = ticks + 200L;
            if (side == null) {
                side = io.theprisons.core.client.ClientReadouts.sidebar(MinecraftClient.getInstance(), MinecraftClient.getInstance().world);
            }
            ThePrisonsClient.LOGGER.info("[ore_macro] energy diag: item {} pickaxe {} | energy {} from {} | rises {} ores {} (procs {}) samples {} | lore {} | sidebar {}",
                    net.minecraft.registry.Registries.ITEM.getId(held.getItem()), pickaxe, energy, source.isEmpty() ? "nothing" : source,
                    energyTax.rises(), energyTax.oresTotal(), procOres, energyTax.samples(), lore,
                    side == null ? "none" : side.stream().map(io.theprisons.core.client.TextStrip::strip).toList());
        }
        String event = energyTax.takeEvent();
        if (event != null) {
            ThePrisonsClient.LOGGER.info("[ore_macro] {} at {}", event, player.getBlockPos().toShortString());
        }
        if (energyTax.blocks() > loggedEnergyBlocks) {
            // Every measured block (diagnostics: how steady the energy per ore is against the 4 % tax).
            loggedEnergyBlocks = energyTax.blocks();
            ThePrisonsClient.LOGGER.info(String.format(Locale.ROOT, "[ore_macro] energy block %.1f per ore over %d ores (reference %.1f, %+.1f%%) at %s",
                    energyTax.lastBlock(), energyTax.lastBlockOres(), energyTax.reference(),
                    (energyTax.lastBlock() / energyTax.reference() - 1.0D) * 100.0D, player.getBlockPos().toShortString()));
        }
        if (energyTax.samples() >= taxSamplesLogged + 40L) {
            taxSamplesLogged = energyTax.samples();
            ThePrisonsClient.LOGGER.info(String.format(Locale.ROOT, "[ore_macro] energy per ore %.2f (reference %.2f), %s, %d samples",
                    energyTax.perOre(), energyTax.reference(), Boolean.FALSE.equals(energyTax.taxed()) ? "outside" : "taxed",
                    energyTax.samples()));
        }
        Boolean tax = energyTax.taxed();
        boolean wentOff = change != null && !change.taxed();
        if (change != null) {
            ThePrisonsClient.LOGGER.info(String.format(Locale.ROOT, "[ore_macro] guard tax %s by energy per ore %.2f -> %.2f (%+.1f%%) at %s",
                    change.taxed() ? "on (in the guarded zone)" : "gone (outside)", change.before(), change.after(),
                    (change.after() / change.before() - 1.0D) * 100.0D, player.getBlockPos().toShortString()));
        }
        lastTax = tax;
        if (wentOff) {
            // The jump began some measured rises ago: that position is the edge, not the one now.
            double[] at = taxTrail[Math.floorMod(taxTrailNext - 1 - change.pairsAgo(), taxTrail.length)];
            int ex = at == null ? player.getBlockX() : (int) Math.floor(at[0]);
            int ey = at == null ? player.getBlockY() : (int) Math.floor(at[1] + 0.01D);
            int ez = at == null ? player.getBlockZ() : (int) Math.floor(at[2]);
            // The last place with the -4 %: straight back there (marked taxed first - the outside marks below must
            // leave the state "outside", or the way back would not start).
            double[] last = taxTrail[Math.floorMod(taxTrailNext - 2 - change.pairsAgo(), taxTrail.length)];
            if (last == null) {
                last = at;
            }
            energyBack = last == null ? null : new int[]{(int) Math.floor(last[0]), (int) Math.floor(last[1] + 0.01D), (int) Math.floor(last[2])};
            if (energyBack != null) {
                guardArea.tax(Boolean.TRUE, energyBack[0], energyBack[1], energyBack[2]);
                ThePrisonsClient.LOGGER.info("[ore_macro] guard tax gone by energy: back to the last taxed place {} {} {}",
                        energyBack[0], energyBack[1], energyBack[2]);
            }
            // Everything walked since the jump began is outside - saved for good, never walked again even for ore.
            for (int back = 0; back <= change.pairsAgo(); back++) {
                double[] p = taxTrail[Math.floorMod(taxTrailNext - 1 - back, taxTrail.length)];
                if (p != null) {
                    guardArea.tax(Boolean.FALSE, (int) Math.floor(p[0]), (int) Math.floor(p[1] + 0.01D), (int) Math.floor(p[2]));
                }
            }
            guardArea.tax(Boolean.FALSE, player.getBlockX(), player.getBlockY(), player.getBlockZ());
            double dx = player.getX() - (at == null ? player.lastX : at[0]);
            double dz = player.getZ() - (at == null ? player.lastZ : at[2]);
            float walkYaw = dx * dx + dz * dz > 1.0E-4D ? RotationMath.yawOf(dx, dz) : player.getYaw();
            guardArea.edge(ex, ey, ez, walkYaw);
            return;
        }
        guardArea.tax(tax, player.getBlockX(), player.getBlockY(), player.getBlockZ());
    }

    /**
     * No guard recognised: logs the living things around (type, name, max HP, UUID version, tab list) so the server's
     * guards can be told apart in the log.
     */
    private static void logGuardCensus(MinecraftClient client, ClientWorld clientWorld, ClientPlayerEntity player) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (net.minecraft.entity.Entity entity : clientWorld.getEntities()) {
            if (entity == player || !(entity instanceof net.minecraft.entity.LivingEntity living)
                    || entity.squaredDistanceTo(player) > 48.0D * 48.0D || n >= 16) {
                continue;
            }
            n++;
            net.minecraft.text.Text custom = entity.getCustomName();
            out.append(String.format(Locale.ROOT, "%n  %s \"%s\"%s hp %.0f uuid v%d%s at %s",
                    net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()),
                    entity.getName().getString(), custom != null ? " custom \"" + custom.getString() + "\"" : "",
                    living.getMaxHealth(), entity.getUuid().version(),
                    client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerListEntry(entity.getUuid()) != null
                            ? " (tab list)" : "",
                    entity.getBlockPos().toShortString()));
        }
        ThePrisonsClient.LOGGER.info("[ore_macro] no guard recognised; living things within 48 blocks: {}{}", n,
                out);
    }

    /** The guards that make the guarded area (up to {@link GuardArea#SCAN_RANGE} blocks away). */
    private static double[][] findAreaGuards(ClientWorld clientWorld, ClientPlayerEntity player) {
        List<double[]> found = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : clientWorld.getEntities()) {
            if (entity != player && entity.squaredDistanceTo(player) <= GuardArea.SCAN_RANGE * GuardArea.SCAN_RANGE
                    && isAreaGuard(entity) && !isRealPlayer(MinecraftClient.getInstance(), entity)) {
                found.add(new double[]{entity.getX(), entity.getY(), entity.getZ()});
            }
        }
        return found.toArray(new double[0][]);
    }

    /**
     * Max HP of the Cosmic Prisons guards (wiki "Guards"): Guard 100, Enforcer 250, Warden 1000. Every guard type
     * guards the area around it.
     */
    private static final float[] GUARD_HEALTH = {100.0F, 250.0F, 1000.0F};
    /** Names of the guard types (wiki "Guards"): standard guards plus the mine / special guards. */
    private static final String[] GUARD_NAMES = {"guard", "enforcer", "warden", "overseer", "corrections", "medic", "riot",
            "officer", "sentinel", "sentry", "security", "jailer"};
    /** Cosmic's guard NPCs are player entities with a profile name like "guard_d06_caac5f" (in every mine). */
    private static final java.util.regex.Pattern NPC_GUARD_PROFILE = java.util.regex.Pattern.compile("^guard_[0-9a-f]+_[0-9a-f]+$");

    /**
     * A guard NPC in player shape: profile name "guard_..." (whatever name it shows), or a shown name with a guard type
     * on a player entity that is no real player (it carries a custom name). Never a bandit.
     */
    static boolean npcGuard(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.entity.player.PlayerEntity)) {
            return false;
        }
        String profile = entity.getName().getString().toLowerCase(Locale.ROOT);
        net.minecraft.text.Text custom = entity.getCustomName();
        String shown = custom == null ? "" : custom.getString().toLowerCase(Locale.ROOT);
        if (shown.contains("bandit") || profile.contains("bandit")) {
            return false;
        }
        if (NPC_GUARD_PROFILE.matcher(profile).matches()) {
            return true;
        }
        if (custom == null) {
            return false;
        }
        for (String guard : GUARD_NAMES) {
            if (shown.contains(guard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A guard that makes a guarded area: a living entity with a guard type in its name (Guard, Enforcer, Warden,
     * Overseer, Corrections, Medic, Riot), or a non-hostile one with a guard's max HP (100 / 250 / 1000).
     */
    static boolean isAreaGuard(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.entity.LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (isWarden(entity) || npcGuard(entity)) {
            return true;
        }
        net.minecraft.text.Text custom = entity.getCustomName();
        String name = (custom != null ? custom.getString() : entity.getName().getString()).toLowerCase(Locale.ROOT);
        if (name.contains("bandit")) {
            return false;
        }
        for (String guard : GUARD_NAMES) {
            if (name.contains(guard)) {
                // By name also a hostile-type mob (a server may dress its guards as e.g. vindicators).
                return true;
            }
        }
        if (entity instanceof net.minecraft.entity.mob.Monster) {
            return false;
        }
        for (float hp : GUARD_HEALTH) {
            if (Math.abs(living.getMaxHealth() - hp) < 0.5F) {
                return true;
            }
        }
        return false;
    }

    /** Wardens (1000 HP), guards (100 HP) and enforcers (250 HP): the server's guard NPCs, by HP, name or name tag. */
    static boolean isGuard(net.minecraft.entity.Entity entity) {
        if (isWarden(entity) || isAreaGuard(entity) || npcGuard(entity)) {
            return true;
        }
        net.minecraft.text.Text custom = entity.getCustomName();
        String name = (custom != null ? custom.getString() : entity.getName().getString()).toLowerCase(Locale.ROOT);
        return !name.contains("bandit") && (name.contains("guard") || name.contains("enforcer"));
    }

    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");

    /**
     * The sidebar's guard XP tax (wiki "Guards": being near guards taxes the XP): {@code null} = no sidebar, or no tax
     * line in it; true = a tax line with more than 0 % (or without a number); false = 0 % / none. Without a line the
     * caller decides (once a line was seen, a missing one means outside). A value on the next line counts too.
     */
    /**
     * The tax's percent, logged with the position when it changes (at most every 2 s): it rises towards a guard
     * (game logs 2026-10-04/05: 10 % about 8 blocks from one, 8-9 % at 7-10, 5-7 % at 19-25) - data to learn the zone.
     */
    private void logTaxPercent(MinecraftClient client, ClientWorld clientWorld, ClientPlayerEntity player) {
        List<String> lines = io.theprisons.core.client.ClientReadouts.sidebar(client, clientWorld);
        int percent = lines == null ? -1 : taxPercent(lines);
        long now = System.currentTimeMillis();
        if (percent >= 0 && percent != loggedTaxPercent && now - taxPercentLoggedMs >= 2_000L) {
            loggedTaxPercent = percent;
            taxPercentLoggedMs = now;
            ThePrisonsClient.LOGGER.info("[ore_macro] guard xp tax {}% at {}", percent, player.getBlockPos().toShortString());
        }
    }

    /** "Guard XP Tax 5%" in the sidebar → 5; -1 = no such line. */
    static int taxPercent(List<String> lines) {
        for (String raw : lines) {
            String line = io.theprisons.core.client.TextStrip.strip(raw).toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}% ]", "").strip();
            java.util.regex.Matcher m = TAX_PERCENT.matcher(line);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    private static final java.util.regex.Pattern TAX_PERCENT = java.util.regex.Pattern.compile("guard xp tax (\\d{1,3})%");
    private int loggedTaxPercent = -1;
    private long taxPercentLoggedMs;

    static @org.jspecify.annotations.Nullable Boolean guardTax(MinecraftClient client, ClientWorld clientWorld) {
        List<String> lines = io.theprisons.core.client.ClientReadouts.sidebar(client, clientWorld);
        if (lines == null) {
            return null;
        }
        for (String raw : lines) {
            // Cosmic ends every line with an invisible control character: letters, digits, % and blanks only.
            String line = io.theprisons.core.client.TextStrip.strip(raw).toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}% ]", "").strip();
            // Guard status on either sidebar page: "Guard XP Tax 5%" (whatever the %) or "Guarded".
            if (line.contains("guard") && line.contains("tax")) {
                return Boolean.TRUE;
            }
            if (line.startsWith("guarded")) {
                return Boolean.TRUE;
            }
        }
        // The sidebar is there, but no guard status on it: outside.
        return Boolean.FALSE;
    }


    /** A real player: a player entity that is in the tab list (Citizens NPCs look like players but are not listed). */
    private static boolean isRealPlayer(MinecraftClient client, net.minecraft.entity.Entity entity) {
        // NPCs in player shape: Citizens and the like use version 2 UUIDs; Cosmic's guards ("guard_452_e3d1c7", in the
        // tab list, v4 UUID) carry a custom name ("Guard") - a real player never has one.
        return entity instanceof net.minecraft.entity.player.PlayerEntity
                && entity.getUuid().version() != 2
                && entity.getCustomName() == null
                && !entity.getName().getString().toLowerCase(Locale.ROOT).startsWith("guard_")
                && client.getNetworkHandler() != null
                && client.getNetworkHandler().getPlayerListEntry(entity.getUuid()) != null;
    }

    // ── Guarded area ─────────────────────────────────────────────────────────

    /**
     * Keeps the macro in the guarded area. Outside it: at once back in as fast as it can ({@link Phase#GUARD}) - it
     * never stops for that. No guard known (and no tax): it walks and mines on (the HUD says so) and keeps looking.
     * @return true = this tick is done
     */
    private boolean keepGuarded(MinecraftClient client, ClientPlayerEntity player, long now) {
        if (guardArea.isEmpty()) {
            return false;
        }
        if (phase == Phase.GUARD) {
            return false;
        }
        // Inside a guard's circle or on the strip to the next guard (a gap of up to 5 blocks) - or, with no way on, at
        // least within the steering's limit (it would only stand at the edge).
        if (guardArea.inside(player.getX(), player.getY(), player.getZ())
                && !(phase == Phase.WAIT && !guardArea.scoreboard() && !guardArea.insideBy(player.getX(), player.getY(), player.getZ(), guardArea.edge()))) {
            backInSince = 0L;
            return false;
        }
        int back = backIn(player, now);
        if (back == BACK_IN_WAITING) {
            // Planning runs on the worker: the steering walks and mines on meanwhile.
            return false;
        }
        if (back == BACK_IN_ROUTE) {
            // The way back in over the ore is walked like any route (its corridor counts as inside).
            return false;
        }
        driver.stop();
        travel = null;
        travelJob = null;
        routeJob = null;
        triedGoals.clear();
        returnRadius = GUARD_RADIUS;
        returnNodes = GUARD_NODES;
        outsideWatch.start(System.currentTimeMillis());
        phase = Phase.GUARD;
        count("guard_returns");
        nextGuard(client, player);
        return false;
    }

    /**
     * The excursion is used up (user: alone 8 blocks, a player near 2-4): not back the way it came, but the planned way
     * into the zone over the most ore. Walks on while it is planned ({@value #BACK_IN_WAIT_MS} ms at most); the
     * route found is walked like any other (its corridor makes it "inside"). Nothing found: false, the plain way back
     * to a guard follows.
     */
    private static final int BACK_IN_NONE = 0;
    private static final int BACK_IN_WAITING = 1;
    private static final int BACK_IN_ROUTE = 2;

    /** {@link #BACK_IN_WAITING} = walk on while it is planned, {@link #BACK_IN_ROUTE} = walking it, else the plain way back. */
    private int backIn(ClientPlayerEntity player, long now) {
        if (guardArea.outsideBudget() == 0 || !planRoutes.on() || activeRoute != null || phase != Phase.STEER || overrun()
                || guardPlayerLock) {
            // 2+ players near: no planning, straight back to the guards (a long way outside is what they must not see).
            return BACK_IN_NONE;
        }
        if (backInSince == 0L) {
            backInSince = now;
            dropPlan();
            planResult = null;
            requestPlan(player, null, "excursion used up: back in over the most ore", true);
            ThePrisonsClient.LOGGER.info("[ore_macro] {} unguarded blocks walked at {}: planning the way back in over the most ore",
                    outsideWalked, player.getBlockPos().toShortString());
        }
        OrePlanner.Plan result = planResult;
        if (result != null && backInPlanning) {
            planResult = null;
            backInPlanning = false;
            if (result.found() && !result.kept()) {
                adoptPlan(player, result, now);
                if (guardArea.inside(player.getX(), player.getY(), player.getZ())) {
                    count("back_in_by_ore");
                    return BACK_IN_ROUTE;
                }
                ThePrisonsClient.LOGGER.info("[ore_macro] way back in does not start where the player stands: straight back");
                dropPlan();
            } else {
                ThePrisonsClient.LOGGER.info("[ore_macro] no way back in over ore ({}): straight back to the zone", result.reason());
            }
            backInSince = now - BACK_IN_WAIT_MS;
            return BACK_IN_NONE;
        }
        if (now - backInSince >= BACK_IN_WAIT_MS) {
            backInPlanning = false;
            return BACK_IN_NONE;
        }
        // The macro does not stand while the way back in is planned: it walks and mines on (the keys of this tick stay).
        status = "Way back in: planning";
        return BACK_IN_WAITING;
    }

    /**
     * Outside the zone: the way back in is planned anew - to the safe ground nearest by walking ({@link GuardReturn}),
     * not to a block or guard nearest by air (game 2026-10-05: that one lay 13 blocks up behind a ceiling of ore).
     * Energy mode keeps its own target: the last place with the -4 %.
     */
    private void nextGuard(MinecraftClient client, ClientPlayerEntity player) {
        if (guardArea.scoreboard() && taxFromEnergy.on() && energyBack != null) {
            guardTarget = new double[]{energyBack[0] + 0.5D, energyBack[1], energyBack[2] + 0.5D};
        } else {
            guardTarget = null;
        }
        guardTries = 0;
        driver.stop();
        requestGuardPath(player);
    }

    /** Back into the guarded area: the pathfinder's way onto safe ground, mining on the move; done once well inside. */
    private void walkToGuard(MinecraftClient client, ClientPlayerEntity player, long now) {
        int[] taxed = energyBack;
        if (taxFromEnergy.on() && taxed != null && Math.hypot(player.getX() - taxed[0] - 0.5D, player.getZ() - taxed[2] - 0.5D) <= 1.5D
                && Math.abs(player.getY() - taxed[1]) <= 1.5D) {
            // At the last place with the -4 %: inside again at once (measuring it again would take two blocks of ores).
            energyTax.forceInside();
            guardArea.tax(Boolean.TRUE, player.getBlockX(), player.getBlockY(), player.getBlockZ());
            energyBack = null;
            ThePrisonsClient.LOGGER.info("[ore_macro] back at the last taxed place {} {} {}: in the guarded zone again",
                    taxed[0], taxed[1], taxed[2]);
        }
        // Scoreboard mode: back as soon as the tax is on again.
        boolean back = guardArea.insideBy(player.getX(), player.getY(), player.getZ(), GUARD_BACK_INSIDE);
        if (!guarded.on() || back) {
            if (outsideWatch.active()) {
                ThePrisonsClient.LOGGER.info("[ore_macro] back in the guarded zone after {} s outside at {}",
                        (now - outsideWatch.since()) / 1000L, player.getBlockPos().toShortString());
            }
            driver.stop();
            guardTarget = null;
            guardJob = null;
            escapeSentMs = 0L;
            outsideWatch.reset();
            steer.reset();
            classic.reset();
            phase = activeRoute != null ? Phase.ROUTE : Phase.STEER;
            return;
        }
        if (watchOutside(client, player, now)) {
            return;
        }
        double[] g = guardTarget;
        status = g == null ? "Back into the guarded zone: finding the way"
                : String.format(Locale.ROOT, "Back into the guarded zone (%.0fm)", Math.hypot(g[0] - player.getX(), g[2] - player.getZ()));
        if (driver.path() == null) {
            if (g != null && Math.hypot(g[0] - player.getX(), g[2] - player.getZ()) < 1.2D && Math.abs(g[1] - player.getY()) <= 1.5D) {
                // At the goal but not back in (tax mode: no tax here - it is marked outside now): a new way.
                guardTarget = null;
            }
            if (g != null && Math.abs(g[1] - player.getY()) <= 2.0D) {
                // A way is being planned: on towards the goal at its height, sprinting (jumping up steps).
                float yaw = RotationMath.yawOf(g[0] - player.getX(), g[2] - player.getZ());
                boolean facing = Math.abs(RotationMath.wrap(yaw - player.getYaw())) < LaneDriver.WALK_ALIGNMENT;
                control.input().set(new InputController.Keys(facing, false, false, false,
                        facing && player.horizontalCollision && player.isOnGround(), facing, false));
                look(player, yaw, null, null);
            } else {
                // No goal at this height: never walk blind under a goal far above / below - wait for the way.
                control.input().clear();
            }
            if (guardJob == null && ticks >= routeRetryTick) {
                requestGuardPath(player);
            }
            return;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), true);
        // Back in as fast as it can: always sprinting.
        control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), drive.sprint(), false));
        look(player, drive.yaw(), null, null);
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            // Path ended short (or stuck): plan the rest.
            driver.stop();
        }
    }

    /**
     * The outside watchdog ({@link OutsideWatch}): no way in for a while → another way, a wider search, the escape to
     * spawn (and /warp back), and only then a stop. While a player is within {@value #GUARD_PLAYER_RANGE} blocks it
     * escapes after {@link OutsideWatch#ESCAPE_LOCKED_MS} ms. @return true = this tick is done
     */
    private boolean watchOutside(MinecraftClient client, ClientPlayerEntity player, long now) {
        double[] g = guardTarget;
        double toGoal = g == null ? Double.POSITIVE_INFINITY
                : Math.hypot(g[0] - player.getX(), g[2] - player.getZ()) + Math.abs(g[1] - player.getY());
        OutsideWatch.Step step = outsideWatch.update(now, toGoal, guardPlayerLock);
        if (step != OutsideWatch.Step.NONE) {
            ThePrisonsClient.LOGGER.info("[ore_macro] outside watchdog: {} ({} s outside{}, at {})", step,
                    (now - outsideWatch.since()) / 1000L, guardPlayerLock ? ", player near" : "",
                    player.getBlockPos().toShortString());
            count("watchdog_" + step.name().toLowerCase(Locale.ROOT));
        }
        switch (step) {
            case NEW_WAY -> {
                if (g != null) {
                    // The way there led nowhere: that goal (and the ground around it) is not taken again.
                    triedGoals.add(Pos.pack((int) Math.floor(g[0]), (int) Math.floor(g[1]), (int) Math.floor(g[2])));
                }
                guardTarget = null;
                driver.stop();
                requestGuardPath(player);
            }
            case WIDE -> {
                returnRadius = GUARD_WIDE_RADIUS;
                returnNodes = GUARD_WIDE_NODES;
                triedGoals.clear();
                guardTarget = null;
                driver.stop();
                requestGuardPath(player);
            }
            case ESCAPE -> escapeSentMs = -1L;
            case GIVE_UP -> {
                alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                        "Outside the guarded zone and no way back in - macro stopped."));
                return true;
            }
            default -> {
            }
        }
        if (escapeSentMs != 0L) {
            return escape(client, player, now);
        }
        return false;
    }

    /**
     * Escape: /spawn (stands still for the server's teleport countdown), then the same way back to the mine as after a
     * death (/warp). In combat (10 s after "You have entered combat") the server refuses /spawn: it keeps walking.
     * @return true = this tick is done
     */
    private boolean escape(MinecraftClient client, ClientPlayerEntity player, long now) {
        ClientWorld clientWorld = client.world;
        if (clientWorld != null && atSpawn(client, clientWorld)) {
            ThePrisonsClient.LOGGER.info("[ore_macro] escaped to spawn: /warp back to the mine");
            count("escapes");
            escapeSentMs = 0L;
            outsideWatch.reset();
            startRecovery(client, player, "escaped to spawn", "escapes_recovered");
            return true;
        }
        if (now < combatUntilMs) {
            // No teleport in combat: walk on towards the zone meanwhile.
            status = "Escape: waiting for the combat tag";
            return false;
        }
        if (escapeSentMs <= 0L || now - escapeSentMs > ESCAPE_RETRY_MS) {
            driver.stop();
            control.input().clear();
            breaker.cancel(client);
            selectEmpty(player);
            player.networkHandler.sendChatCommand("spawn");
            ThePrisonsClient.LOGGER.info("[ore_macro] escape: /spawn from {}", player.getBlockPos().toShortString());
            escapeSentMs = now;
        }
        // The teleport countdown is cancelled by moving: stand still.
        control.input().clear();
        breaker.cancel(client);
        status = "Escape: /spawn";
        return true;
    }

    private void requestGuardPath(ClientPlayerEntity player) {
        int radius = returnRadius;
        VoxelView view;
        long version;
        if (archive.isOpen() && archive.sectionCount() > 0) {
            // The remembered world: also the parts of the mine out of the client's sight.
            view = archive.view(player.getBlockX(), player.getBlockZ(), radius + 16,
                    player.getBlockY() - PLAN_VERTICAL, player.getBlockY() + PLAN_VERTICAL);
            version = 0L;
        } else {
            WorldSnapshot snapshot = world.snapshot(player, radius + 16, 48);
            view = snapshot;
            version = snapshot.version();
        }
        long start = new Walkability(view, ROUTE_DROP).settle(player.getX(), player.getY(), player.getZ());
        routeRetryTick = ticks + 20;
        if (start == Long.MIN_VALUE) {
            return;
        }
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        int drop = ROUTE_DROP;
        int nodes = returnNodes;
        count("guard_plans");
        double[] fixed = guardTarget;
        if (fixed != null) {
            // Energy mode: straight back to the last place with the -4 %.
            LongOpenHashSet goals = new LongOpenHashSet();
            int gx = (int) Math.floor(fixed[0]);
            int gy = (int) Math.floor(fixed[1]);
            int gz = (int) Math.floor(fixed[2]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        goals.add(Pos.pack(gx + dx, gy + dy, gz + dz));
                    }
                }
            }
            guardJob = submit("guard", cancel -> {
                Walkability walk = new Walkability(view, drop, true, forbidden);
                PathSearch.Result found = PathSearch.findPath(walk, start, goals, nodes, radius, version, cancel::cancelled);
                return found.path() == null ? null : PathStraightener.apply(found.path(), walk);
            }, path -> {
                guardJob = null;
                if (phase == Phase.GUARD && path != null && path.size() > 1) {
                    driver.start(path);
                }
            });
            return;
        }
        GuardArea area = guardArea.copy();
        area.corridor(null);
        area.roam(false);
        long[] tried = triedGoals.toLongArray();
        java.util.function.LongPredicate notTried = n -> {
            for (long t : tried) {
                if (Math.abs(Pos.x(t) - Pos.x(n)) <= TRIED_GOAL_RANGE && Math.abs(Pos.z(t) - Pos.z(n)) <= TRIED_GOAL_RANGE
                        && Math.abs(Pos.y(t) - Pos.y(n)) <= TRIED_GOAL_RANGE) {
                    return false;
                }
            }
            return true;
        };
        guardJob = submit("guard", cancel -> {
            Walkability walk = new Walkability(view, drop, true, forbidden);
            // Ground where the server showed the tax first; only without any: a guard's zone (the model).
            GuardReturn.Result found = GuardReturn.plan(walk, start, n -> notTried.test(n) && area.safeNode(n, true), nodes,
                    radius, cancel::cancelled);
            if (!found.found() && !cancel.cancelled()) {
                found = GuardReturn.plan(walk, start, n -> notTried.test(n) && area.safeNode(n, false), nodes, radius,
                        cancel::cancelled);
            }
            return found.found() ? new GuardReturn.Result(PathStraightener.apply(found.path(), walk), found.reached(), found.reason())
                    : found;
        }, found -> {
            guardJob = null;
            if (phase != Phase.GUARD) {
                return;
            }
            if (!found.found()) {
                if (!found.reason().equals(lastGuardWhy)) {
                    lastGuardWhy = found.reason();
                    ThePrisonsClient.LOGGER.info("[ore_macro] no way back into the guarded zone: {}", found.reason());
                }
                count("guard_no_way");
                return;
            }
            long goal = found.path().goal();
            guardTarget = new double[]{Pos.x(goal) + 0.5D, Pos.y(goal), Pos.z(goal) + 0.5D};
            outsideWatch.target(System.currentTimeMillis());
            String why = "way back in: " + found.reason() + " to " + Pos.toString(goal);
            if (!why.equals(lastGuardWhy)) {
                lastGuardWhy = why;
                ThePrisonsClient.LOGGER.info("[ore_macro] {}", why);
            }
            driver.start(found.path());
        });
    }

    // ── Breaks at a warden ───────────────────────────────────────────────────

    /** Adds the NPCs in sight to the ones seen during this run (the same NPC = within 4 blocks: newest position). */
    private static void remember(List<double[]> known, double[][] seen) {
        for (double[] w : seen) {
            boolean found = false;
            for (double[] k : known) {
                if (Math.abs(k[0] - w[0]) < 4.0D && Math.abs(k[1] - w[1]) < 4.0D && Math.abs(k[2] - w[2]) < 4.0D) {
                    // The same warden (they stand still or walk a little): keep the newest position.
                    k[0] = w[0];
                    k[1] = w[1];
                    k[2] = w[2];
                    found = true;
                    break;
                }
            }
            if (!found) {
                known.add(w.clone());
            }
        }
    }

    /** Break due: pick the nearest known warden and find the way there; without one, try again in a minute. */
    private void startBreak(ClientPlayerEntity player, long now) {
        if (chore != Chores.Kind.NONE) {
            return;
        }
        double[] nearest = null;
        double best = Double.MAX_VALUE;
        for (double[] w : knownWardens) {
            double d = Math.hypot(w[0] - player.getX(), w[2] - player.getZ()) + 3.0D * Math.abs(w[1] - player.getY());
            if (d < best) {
                best = d;
                nearest = w;
            }
        }
        if (nearest == null || best > BREAK_MAX_DISTANCE) {
            nextBreakMs = now + BREAK_RETRY_MS;
            lastIssue = "break due, no warden nearby";
            return;
        }
        breakWarden = nearest.clone();
        breakTries = 0;
        breakUntilMs = 0L;
        breakStartedMs = now;
        breaker.cancel(MinecraftClient.getInstance());
        driver.stop();
        travel = null;
        travelJob = null;
        routeJob = null;
        phase = Phase.BREAK;
        count("breaks");
        ThePrisonsClient.LOGGER.info("[ore_macro] break: walking to the warden at {} {} {}",
                Math.round(nearest[0]), Math.round(nearest[1]), Math.round(nearest[2]));
        if (atWarden(player)) {
            beginPause(now);
        } else {
            requestBreakPath(player);
        }
    }

    /** On the way to the warden: only walking (the pathfinder's way), the pause starts next to it. */
    private void walkToBreak(ClientPlayerEntity player, long now) {
        double[] w = breakWarden;
        if (w == null) {
            endBreak(now);
            return;
        }
        if (atWarden(player)) {
            driver.stop();
            beginPause(now);
            holdStill(MinecraftClient.getInstance(), player);
            return;
        }
        if (now - breakStartedMs > BREAK_WALK_MS) {
            giveUpBreak("warden not reached in time");
            return;
        }
        if (driver.path() == null) {
            control.input().clear();
            look(player, player.getYaw(), null, null);
            status = "Break: looking for the way to the warden";
            if (breakJob == null && ticks >= routeRetryTick) {
                if (++breakTries > BREAK_PATH_TRIES) {
                    giveUpBreak("no way to the warden");
                    return;
                }
                requestBreakPath(player);
            }
            return;
        }
        LaneDriver.Drive drive = driver.tick(new LaneDriver.Player(player.getX(), player.getY(), player.getZ(), player.getYaw(),
                player.isOnGround(), player.horizontalCollision), sprint.on());
        control.input().set(new InputController.Keys(drive.forward(), false, false, false, drive.jump(), drive.sprint(), false));
        look(player, drive.yaw(), null, null);
        status = String.format(Locale.ROOT, fleeing ? "Attacked: running to the guard (%.0fm)" : "Break: to the warden (%.0fm)",
                Math.hypot(w[0] - player.getX(), w[2] - player.getZ()));
        if (drive.status() != LaneDriver.Status.FOLLOWING) {
            // Path ended short of the warden (or stuck): plan the rest.
            driver.stop();
        }
    }

    private boolean atWarden(ClientPlayerEntity player) {
        double[] w = breakWarden;
        return w != null && Math.hypot(w[0] - player.getX(), w[2] - player.getZ()) <= BREAK_NEAR
                && Math.abs(w[1] - player.getY()) <= 2.0D;
    }

    private void requestBreakPath(ClientPlayerEntity player) {
        double[] w = breakWarden;
        if (w == null) {
            return;
        }
        int wx = (int) Math.floor(w[0]);
        int wy = (int) Math.floor(w[1]);
        int wz = (int) Math.floor(w[2]);
        int radius = Math.min(160, Math.max(Math.abs(wx - player.getBlockX()), Math.abs(wz - player.getBlockZ())) + 16);
        WorldSnapshot snapshot = world.snapshot(player, radius + 16, 48);
        long start = new Walkability(snapshot.copyView(), maxDrop.value()).settle(player.getX(), player.getY(), player.getZ());
        routeRetryTick = ticks + 20;
        if (start == Long.MIN_VALUE) {
            return;
        }
        // Goals: the ring right beside the warden (not its own block).
        LongOpenHashSet goals = new LongOpenHashSet();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d < 1.5D || d > BREAK_NEAR) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    goals.add(Pos.pack(wx + dx, wy + dy, wz + dz));
                }
            }
        }
        // Only the hand-placed borders are forbidden: the keep-away circle of the wardens is exactly where it goes.
        Long2DoubleOpenHashMap forbidden = new Long2DoubleOpenHashMap();
        forbidBorders(forbidden, player, borders.zones(MinecraftClient.getInstance()));
        int drop = maxDrop.value();
        breakJob = submit("break", cancel -> {
            Walkability walk = new Walkability(snapshot, drop, true, forbidden);
            PathSearch.Result found = PathSearch.findPath(walk, start, goals, 60_000, radius, snapshot.version(), cancel::cancelled);
            return found.path() == null ? found : new PathSearch.Result(found.status(), PathStraightener.apply(found.path(), walk),
                    found.expanded(), found.reason());
        }, found -> {
            breakJob = null;
            if (phase == Phase.BREAK && breakUntilMs == 0L && found.usable() && found.path() != null && found.path().size() > 1) {
                driver.start(found.path());
            }
        });
    }

    private void beginPause(long now) {
        if (fleeing) {
            breakUntilMs = Math.max(now, lastHurtMs) + FLEE_CALM_MS;
            breakStartedMs = now;
            ThePrisonsClient.LOGGER.info("[ore_macro] attacked: at the guard, waiting for calm");
            return;
        }
        int min = breakLengthMin.value();
        int max = Math.max(min, breakLengthMax.value());
        breakUntilMs = now + (min + random.nextInt(max - min + 1)) * 1000L;
        ThePrisonsClient.LOGGER.info("[ore_macro] break: {} s at the warden", (breakUntilMs - now) / 1000L);
    }

    /** Standing still: keys up, no mining, the view stays exactly where it is. */
    private void holdStill(MinecraftClient client, ClientPlayerEntity player) {
        long now = System.currentTimeMillis();
        control.input().clear();
        breaker.cancel(client);
        control.rotation().follow(player.getYaw(), player.getPitch(), YAW_OMEGA, PITCH_OMEGA);
        if (fleeing) {
            if (now - breakStartedMs > FLEE_MAX_WAIT_MS && now - lastHurtMs < FLEE_CALM_MS) {
                alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                        "Still attacked after 60 s at the guard - macro stopped."));
                return;
            }
            breakUntilMs = Math.max(breakUntilMs, lastHurtMs + FLEE_CALM_MS);
            // Only after a hit: on when no player is seen within 16 blocks to each side.
            String near = playerNearby(MinecraftClient.getInstance(), player);
            if (near != null) {
                breakUntilMs = Math.max(breakUntilMs, now + 1_000L);
                status = "At the guard, player nearby: " + near;
                return;
            }
        }
        status = String.format(Locale.ROOT, fleeing ? "At the guard, waiting (%d s)" : "Break (%d s)",
                Math.max(0L, (breakUntilMs - now + 999L) / 1000L));
        if (now >= breakUntilMs) {
            count(fleeing ? "flee_done" : "breaks_done");
            endBreak(now);
            steer.reset();
        classic.reset();
            if (activeRoute != null) {
                // Back to the route from here (nearest waypoint), as after a teleport.
                phase = Phase.ROUTE;
                enterRoute(player, activeRoute);
            } else {
                phase = Phase.STEER;
            }
        }
    }

    private void giveUpBreak(String why) {
        long now = System.currentTimeMillis();
        if (chore == Chores.Kind.DEATH_RECOVERY || now - deathSeenMs < 30_000L) {
            // Killed while fleeing: the death recovery goes on (respawn, whitescrolls, /warp back) - no stop.
            fleeing = false;
            endBreak(now);
            return;
        }
        if (fleeing) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) {
                alertStop(MinecraftClient.getInstance(), player, io.theprisons.core.i18n.I18n.t(
                        "Attacked and no way to a guard - macro stopped."));
            }
            return;
        }
        lastIssue = "break: " + why;
        count("breaks_failed");
        ThePrisonsClient.LOGGER.info("[ore_macro] break: {} - trying again in a minute", why);
        endBreak(now);
        nextBreakMs = now + BREAK_RETRY_MS;
        driver.stop();
        steer.reset();
        classic.reset();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (activeRoute != null && player != null) {
            phase = Phase.ROUTE;
            enterRoute(player, activeRoute);
        } else {
            phase = Phase.STEER;
        }
    }

    /** Clears the break state and draws the time of the next one (a flight to a guard keeps the break schedule). */
    private void endBreak(long now) {
        if (fleeing) {
            fleeing = false;
            breakUntilMs = 0L;
            breakWarden = null;
            breakJob = null;
            return;
        }
        int min = breakEveryMin.value();
        int max = Math.max(min, breakEveryMax.value());
        nextBreakMs = now + (min * 60L + random.nextInt((max - min) * 60 + 1)) * 1000L;
        breakUntilMs = 0L;
        breakWarden = null;
        breakJob = null;
    }

    // ── Attacked: run to a guard ─────────────────────────────────────────────

    /**
     * Hit by a hostile mob/Bandit NPC → hit back once (when enabled), then run to a guard.
     * On this private fork Bandits may be PlayerEntity instances, so attacker type/tab status is not used
     * to classify them as real players. Guards are still excluded. Environmental damage is ignored.
     */
    private void onHurt(CoreEvents.PlayerHurt event) {
        net.minecraft.entity.Entity attacker = event.attacker();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (attacker == null || player == null || attacker == player || !fleeToGuard.on()) {
            return;
        }
        lastHurtMs = System.currentTimeMillis();
        count("attacked");
        // On this private Cosmic fork, hostile Bandits are encoded as PlayerEntity.
        // Do not classify the attacker by PlayerEntity/tab-list status: the damage event's
        // attacker is the actual hostile entity that hit us. Guards are filtered below.
        if (isGuard(attacker)) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t("Attacked by a guard - macro stopped."));
            return;
        }
        if (!fleeing && !fleeRequested) {
            // Never fight back: straight to the nearest guard.
            fleeRequested = true;
        }
    }

    /** The reaction to a hit: run to the nearest guard (no fighting). @return true = this tick is done */
    private boolean reactToAttack(MinecraftClient client, ClientPlayerEntity player) {
        fleeRequested = false;
        return !startFlee(client, player, System.currentTimeMillis());
    }

    /** A real player within the legacy combat/flee radius; null = none. */
    private static @Nullable String playerNearby(MinecraftClient client, ClientPlayerEntity player) {
        return playerNearby(client, player, FLEE_PLAYER_RANGE);
    }

    /** Real players (not the own one) inside a true Euclidean 3D radius. */
    private static int playersNear(MinecraftClient client, ClientPlayerEntity player, double radius) {
        if (client.world == null) {
            return 0;
        }
        double maxSq = radius * radius;
        int count = 0;
        for (net.minecraft.entity.player.PlayerEntity other : client.world.getPlayers()) {
            if (other == player || !isRealPlayer(client, other)) {
                continue;
            }
            double dx = other.getX() - player.getX();
            double dy = other.getY() - player.getY();
            double dz = other.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz <= maxSq) {
                count++;
            }
        }
        return count;
    }

    /** Finds another real player inside a true Euclidean 3D radius. */
    private static @Nullable String playerNearby(MinecraftClient client, ClientPlayerEntity player, double radius) {
        if (client.world == null) {
            return null;
        }
        double maxSq = radius * radius;
        for (net.minecraft.entity.player.PlayerEntity other : client.world.getPlayers()) {
            if (other == player || !isRealPlayer(client, other)) {
                continue;
            }
            double dx = other.getX() - player.getX();
            double dy = other.getY() - player.getY();
            double dz = other.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz <= maxSq) {
                return other.getName().getString();
            }
        }
        return null;
    }

    /** Starts the run to the nearest known guard; none known → stop with an alert. @return false when stopped */
    private boolean startFlee(MinecraftClient client, ClientPlayerEntity player, long now) {
        double[] nearest = null;
        double best = Double.MAX_VALUE;
        List<double[]> candidates = new java.util.ArrayList<>(knownGuards);
        // Guards remembered from earlier runs count too: any guard is better than standing there.
        candidates.addAll(guardArea.guards());
        for (double[] g : candidates) {
            double d = Math.hypot(g[0] - player.getX(), g[2] - player.getZ()) + 3.0D * Math.abs(g[1] - player.getY());
            if (d < best) {
                best = d;
                nearest = g;
            }
        }
        if (nearest == null) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t("Attacked and no guard known - macro stopped."));
            return false;
        }
        if (phase == Phase.BREAK && !fleeing) {
            // Attacked during a break: the break counts as done, the next one comes on schedule.
            endBreak(now);
        }
        fleeing = true;
        breakWarden = nearest.clone();
        breakTries = 0;
        breakUntilMs = 0L;
        breakStartedMs = now;
        breaker.cancel(client);
        driver.stop();
        travel = null;
        travelJob = null;
        routeJob = null;
        phase = Phase.BREAK;
        count("flee");
        ThePrisonsClient.LOGGER.info("[ore_macro] attacked: running to the guard at {} {} {}",
                Math.round(nearest[0]), Math.round(nearest[1]), Math.round(nearest[2]));
        if (atWarden(player)) {
            beginPause(now);
        } else {
            requestBreakPath(player);
        }
        return true;
    }

    // ── Chores: reactions to server messages ─────────────────────────────────

    /** Server (system) messages only - a player writing the same text in chat does not trigger anything. */
    private void onChat(CoreEvents.ChatReceived event) {
        if (!event.fromPlayer() && event.overlay()) {
            String bar = io.theprisons.core.client.TextStrip.strip(event.message().getString());
            String barLower = bar.toLowerCase(Locale.ROOT);
            if (barLower.contains("energy") || barLower.contains("charge")) {
                if (actionBarLogged < 8 && !bar.equals(lastActionBar)) {
                    // The action bar's energy line, to learn its form from the log (action bar text is not logged).
                    actionBarLogged++;
                    ThePrisonsClient.LOGGER.info("[ore_macro] action bar: {}", bar);
                }
                lastActionBar = bar;
                chargeBonus(bar, "action bar");
            }
            return;
        }
        if (event.fromPlayer()) {
            return;
        }
        String text = io.theprisons.core.client.TextStrip.strip(event.message().getString());
        String lowerText = text.toLowerCase(Locale.ROOT);
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        long nowMs = System.currentTimeMillis();
        if (Chores.combat(text)) {
            combatUntilMs = nowMs + COMBAT_TAG_MS;
        }
        if (self != null && Chores.died(text, self.getName().getString())) {
            // Cosmic respawns at once (no death screen): the chat is the only sign. Once per death (2-3 lines).
            if (chore != Chores.Kind.DEATH_RECOVERY && nowMs - deathSeenMs > 5_000L) {
                startDeath(MinecraftClient.getInstance(), self, "killed: " + text);
            }
            deathSeenMs = nowMs;
            return;
        }
        if (lowerText.contains("booster") && lowerText.contains("energy") && !lowerText.contains("<")
                && !lowerText.contains("»")) {
            // An energy booster started / ended (not a player's chat offer): the energy per ore jumps, not the tax.
            energyTax.rebase();
            ThePrisonsClient.LOGGER.info("[ore_macro] energy booster message - new energy reference: {}", text);
        }
        java.util.regex.Matcher mine = MINE_NAME.matcher(text);
        if (mine.matches()) {
            currentMine = mine.group(1).trim();
        }
        Object[] cooldown = Chores.cooldown(text == null ? "" : text);
        if (cooldown != null) {
            // The item was used too early: wait exactly as long as the server says instead of the fallback time.
            cooldownUntil.put((String) cooldown[0], System.currentTimeMillis() + (Long) cooldown[1] + 2_000L);
            return;
        }
        if (chore == Chores.Kind.DEATH_RECOVERY && MINE_ZONE.matcher(lowerText).find() && !lowerText.contains("diamond zone")) {
            // Fallen into the mine after /warp.
            mineZoneEntered = true;
        }
        if (lowerText.contains("you have entered the diamond zone")) {
            ClientPlayerEntity me = MinecraftClient.getInstance().player;
            if (chore == Chores.Kind.SORT_ITEMS) {
                zoneEntered = true;
                return;
            }
            if (chore == Chores.Kind.NONE && me != null && onlyKeptItems(me)) {
                // At spawn without a trip or a warp countdown, nothing left but the whitescrolled items: died.
                startDeath(MinecraftClient.getInstance(), me, "at spawn with an empty inventory");
                return;
            }
        }
        if (chore == Chores.Kind.SORT_ITEMS && lowerText.contains("cancelled because you moved")) {
            teleportCancelled = true;
            return;
        }
        if (chore == Chores.Kind.SORT_ITEMS && ItemSorter.teleporting(text == null ? "" : text)) {
            sortTeleporting = true;
            return;
        }
        Chores.Kind kind = Chores.forMessage(text == null ? "" : text);
        if (kind == Chores.Kind.SELL_ALL_NOW) {
            sellPending = true;
        } else if (kind != Chores.Kind.NONE && chore == Chores.Kind.NONE) {
            chore = kind;
            choreStep = 0;
            choreWait = 0;
        }
    }

    private void runChore(MinecraftClient client, ClientPlayerEntity player) {
        control.input().clear();
        breaker.cancel(client);
        control.rotation().follow(player.getYaw(), player.getPitch(), YAW_OMEGA, PITCH_OMEGA);
        if (choreWait > 0) {
            choreWait--;
            return;
        }
        switch (chore) {
            case EXTRACT_ENERGY -> extractEnergy(client, player);
            case USE_PET -> usePet(client, player);
            case USE_ABILITY -> useAbility(client, player);
            case SORT_ITEMS -> sortItems(client, player);
            case REDEEM_MONEY -> redeemMoney(client, player);
            case DEATH_RECOVERY -> deathRecovery(client, player);
            default -> chore = Chores.Kind.NONE;
        }
    }

    // ── Item sorter: prismarine shards and the rest into private vaults ──────

    /** The steps of a trip to spawn. */
    private enum Trip { SETHOME, SPAWN, ARRIVE, WALK, CONTRABANDS, SHARDS, MONEY, SELL, TINKER, VAULT_SHARDS, VAULT_OTHER, MERGE_ENERGY, VAULT_ENERGY, SPONGE, HOME, HOME_ARRIVE, DELHOME, NUDGE, WARP }

    /**
     * A trip to spawn when the item sorter is due, a sponge must be fetched, or the energy items hold
     * {@code energy_trip_millions}: only the steps needed now (contrabands and shards are opened on every trip).
     */
    private boolean startSort(ClientPlayerEntity player) {
        net.minecraft.item.ItemStack held = hotbarPickaxe(player);
        if (held != null && milestones.crossed(Milestones.read(
                io.theprisons.core.client.TextStrip.strip(held.getName().getString()),
                io.theprisons.core.client.ClientReadouts.lore(held)))) {
            levelDue = true;
            ThePrisonsClient.LOGGER.info("[ore_macro] pickaxe level step reached: trip to spawn");
        }
        if (chore != Chores.Kind.NONE || !itemSorter.on() || ItemSorter.vault(vaultShards.get()) < 0) {
            return false;
        }
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        ItemSorter.Item[] items = new ItemSorter.Item[ItemSorter.INVENTORY_SLOTS];
        int contrabands = 0;
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = item(inventory.getStack(slot));
            if (ItemSorter.contraband(items[slot])) {
                contrabands++;
            }
        }
        List<String> abilities = Chores.nameParts(abilityNames.get());
        long energy = energyCarried(player);
        boolean energyVault = ItemSorter.vault(vaultEnergy.get()) > 0;
        boolean energyDue = energyVault && energy >= energyTrip.value() * 1_000_000L;
        boolean sortDue = ItemSorter.crowded(items, abilities, inventoryLimit.value());
        int godlyShards = 0;
        for (int slot = 0; slot < items.length; slot++) {
            if (ItemSorter.godlyShard(items[slot])) {
                godlyShards += Math.max(1, inventory.getStack(slot).getCount());
            }
        }
        boolean godly = godlyShards >= godlyTrip.value();
        long money = moneyCarried(player);
        boolean moneyDue = money >= moneyTrip.value() * 1_000_000L;
        boolean lootDue = System.currentTimeMillis() >= lootTripAfterMs && (openContrabands.on() && contrabands > 0 || openShards.on() && godly);
        if (!sortDue && !needSponge && !energyDue && !lootDue && !moneyDue && !levelDue) {
            return false;
        }
        if (guarded.on() && !guardArea.isEmpty() && !guardArea.guardedHere(player.getX(), player.getY(), player.getZ())) {
            // To spawn only from the guarded zone (back from /home tmp there too); the macro walks back in first.
            return false;
        }
        // At spawn always 8 blocks forward first: only there "You have entered the Diamond Zone" comes (and
        // contrabands / shards can be opened).
        List<Trip> plan = new java.util.ArrayList<>(List.of(Trip.SETHOME, Trip.SPAWN, Trip.ARRIVE, Trip.WALK));
        boolean openC = openContrabands.on() && contrabands > 0;
        boolean openS = openShards.on() && ItemSorter.shardStacks(items) > 0;
        if (openC) {
            plan.add(Trip.CONTRABANDS);
        }
        if (openS) {
            plan.add(Trip.SHARDS);
        }
        if (money > 0L || countOf(player, ItemSorter::money) > 0) {
            // At spawn anyway: every money note is redeemed, however little (10M only decides an extra trip).
            plan.add(Trip.MONEY);
        }
        if (extraPickaxes(player) > 0) {
            // Every pickaxe except the one in hotbar slot 1 goes to the Tinkerer.
            plan.add(Trip.TINKER);
        }
        plan.add(Trip.VAULT_SHARDS);
        plan.add(Trip.VAULT_OTHER);
        if (energyVault) {
            plan.add(Trip.MERGE_ENERGY);
            plan.add(Trip.VAULT_ENERGY);
        }
        if (needSponge) {
            plan.add(Trip.SPONGE);
        }
        plan.add(Trip.SELL);
        plan.addAll(List.of(Trip.HOME, Trip.HOME_ARRIVE, Trip.DELHOME, Trip.NUDGE));
        trip = plan;
        tripIndex = 0;
        mergeStep = 0;
        teleportRetries = 0;
        teleportCancelled = false;
        // Stand completely still from now on: the server cancels the teleport countdown on any movement.
        selectEmpty(player);
        driver.stop();
        travel = null;
        travelJob = null;
        control.input().clear();
        tripRounds = 0;
        spongeVaults = null;
        spongeVault = 0;
        spongeFetched = false;
        chore = Chores.Kind.SORT_ITEMS;
        choreStep = 0;
        choreWait = 0;
        sortTeleporting = false;
        count("item_sorts");
        ThePrisonsClient.LOGGER.info("[ore_macro] trip to spawn ({}{}{}{}{}{}{}): {} shard stacks, {} contrabands, {} energy, ${} money - steps {}",
                sortDue ? "items" : "", needSponge ? " sponge" : "", energyDue ? " energy" : "", levelDue ? " level" : "",
                contrabands > 0 ? " contraband" : "", godly ? " godly shard" : "", moneyDue ? " money" : "",
                ItemSorter.shardStacks(items), contrabands, energy, money, plan);
        levelDue = false;
        return true;
    }

    private static ItemSorter.Item item(net.minecraft.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemSorter.Item.EMPTY;
        }
        net.minecraft.component.type.LoreComponent lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
        boolean plain = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME) == null
                && (lore == null || lore.lines().isEmpty());
        return new ItemSorter.Item(
                net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString(),
                io.theprisons.core.client.TextStrip.strip(stack.getName().getString()),
                stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES),
                stack.getItem() instanceof net.minecraft.item.BlockItem, plain);
    }

    /** Cosmic Energy in the light blue dye of the inventory (name and lore). */
    private long energyCarried(ClientPlayerEntity player) {
        long total = 0L;
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            net.minecraft.item.ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !ItemSorter.energy(item(stack))) {
                continue;
            }
            List<String> lines = new java.util.ArrayList<>();
            lines.add(io.theprisons.core.client.TextStrip.strip(stack.getName().getString()));
            lines.addAll(io.theprisons.core.client.ClientReadouts.lore(stack));
            if (!energyNamesLogged) {
                energyNamesLogged = true;
                ThePrisonsClient.LOGGER.info("[ore_macro] energy item: {}", lines);
            }
            total += ItemSorter.energyAmount(lines) * stack.getCount();
        }
        return total;
    }

    /**
     * At spawn: the sidebar's "Current Zone" is "Safezone" (also right after logging in, when no "You entered ... zone"
     * message came), or the last zone entered was the Diamond Zone.
     */
    private static boolean atSpawn(MinecraftClient client, ClientWorld clientWorld) {
        if ("diamond".equals(io.theprisons.core.ThePrisonsCore.lastZone())) {
            return true;
        }
        List<String> side = io.theprisons.core.client.ClientReadouts.sidebar(client, clientWorld);
        return side != null && spawnZone(side);
    }

    /**
     * The sidebar's "Current Zone" is the safe zone. Cosmic ends every sidebar line with an invisible control character
     * (U+0088, U+0089, ... to keep the lines apart): only letters, digits and blanks are compared.
     */
    static boolean spawnZone(List<String> side) {
        for (int i = 0; i < side.size(); i++) {
            String line = plain(side.get(i));
            if (line.startsWith("current zone")) {
                String zone = line.substring("current zone".length()).strip();
                if (zone.isEmpty() && i + 1 < side.size()) {
                    zone = plain(side.get(i + 1));
                }
                return zone.contains("safe") || zone.contains("spawn") || zone.contains("diamond");
            }
        }
        return false;
    }

    private static String plain(String line) {
        return io.theprisons.core.client.TextStrip.strip(line).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", "").strip();
    }

    /**
     * The macro started at spawn: what can be done there (contrabands, shards, money, the vaults, a missing sponge),
     * then /warp to the chosen mine.
     */
    private void startSpawnStart(ClientPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        int contrabands = 0;
        int shards = 0;
        boolean sponge = false;
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            ItemSorter.Item it = item(inventory.getStack(slot));
            contrabands += ItemSorter.contraband(it) ? 1 : 0;
            shards += ItemSorter.shard(it) ? 1 : 0;
            sponge |= ItemSorter.SPONGE.equals(it.id());
        }
        List<Trip> plan = new java.util.ArrayList<>();
        boolean openC = openContrabands.on() && contrabands > 0;
        boolean openS = openShards.on() && shards > 0;
        plan.add(Trip.WALK);
        if (openC) {
            plan.add(Trip.CONTRABANDS);
        }
        if (openS) {
            plan.add(Trip.SHARDS);
        }
        if (countOf(player, ItemSorter::money) > 0) {
            plan.add(Trip.MONEY);
        }
        if (itemSorter.on() && ItemSorter.vault(vaultShards.get()) > 0) {
            if (extraPickaxes(player) > 0) {
                plan.add(Trip.TINKER);
            }
            plan.add(Trip.VAULT_SHARDS);
            plan.add(Trip.VAULT_OTHER);
            if (ItemSorter.vault(vaultEnergy.get()) > 0) {
                plan.add(Trip.MERGE_ENERGY);
                plan.add(Trip.VAULT_ENERGY);
            }
            if (!sponge) {
                plan.add(Trip.SPONGE);
            }
        }
        plan.add(Trip.SELL);
        plan.add(Trip.WARP);
        trip = plan;
        tripIndex = 0;
        tripRounds = 0;
        mergeStep = 0;
        spongeVaults = null;
        spongeVault = 0;
        spongeFetched = false;
        chore = Chores.Kind.SORT_ITEMS;
        choreStep = 0;
        choreWait = 10;
        selectEmpty(player);
        ThePrisonsClient.LOGGER.info("[ore_macro] started at spawn: steps {}", plan);
    }

    /** The trip to spawn, one step at a time (250 ms between the steps). */
    private void sortItems(MinecraftClient client, ClientPlayerEntity player) {
        List<Trip> steps = trip;
        if (steps == null || tripIndex >= steps.size()) {
            finishTrip();
            return;
        }
        List<String> abilities = Chores.nameParts(abilityNames.get());
        switch (steps.get(tripIndex)) {
            case SETHOME -> {
                status = "Trip: /sethome " + ItemSorter.HOME;
                player.networkHandler.sendChatCommand("sethome " + ItemSorter.HOME);
                advance(SORT_STEP_TICKS);
            }
            case SPAWN -> {
                status = "Trip: /spawn";
                zoneEntered = false;
                walkFrom = new double[]{player.getX(), player.getZ()};
                player.networkHandler.sendChatCommand("spawn");
                sortTimeout = ARRIVE_TICKS;
                advance(0);
            }
            case ARRIVE -> {
                if (retryTeleport(client, player, Trip.SPAWN)) {
                    return;
                }
                // At spawn: the teleport moved us (or "You have entered the Diamond Zone") - 30 s at the latest.
                double[] from = walkFrom;
                boolean teleported = from != null && Math.hypot(player.getX() - from[0], player.getZ() - from[1]) > 20.0D;
                if (!zoneEntered && !teleported && --sortTimeout > 0) {
                    status = "Trip: waiting for the teleport to spawn";
                    return;
                }
                advance(SORT_ARRIVE_TICKS + humanDelay());
            }
            case WALK -> walkForward(player);
            case CONTRABANDS -> openContrabands(client, player);
            case SHARDS -> openShards(client, player);
            case VAULT_SHARDS -> vaultStep(client, player, vaultShards, ItemSorter::shard, "sorted_shards");
            case VAULT_OTHER -> sortVaults(client, player, abilities);
            case TINKER -> tinker(client, player);
            case MONEY -> {
                if (redeemStep(client, player)) {
                    advance(SORT_STEP_TICKS);
                }
            }
            case SELL -> {
                // At the end of the sorting, always: plain ores, ingots, ore blocks and the satchels' ores are sold.
                status = "Trip: /sellall";
                player.networkHandler.sendChatCommand("sellall");
                count("sellall_trip");
                advance(SORT_STEP_TICKS + humanDelay());
            }
            case MERGE_ENERGY -> {
                status = "Trip: stacking the energy";
                if (mergeStacks(client, player, ItemSorter::energy)) {
                    advance(SORT_STEP_TICKS);
                }
            }
            case VAULT_ENERGY -> vaultStep(client, player, vaultEnergy, ItemSorter::energy, "sorted_energy");
            case SPONGE -> fetchSponge(client, player);
            case HOME -> {
                status = "Trip: /home " + ItemSorter.HOME;
                sortTeleporting = false;
                player.networkHandler.sendChatCommand("home " + ItemSorter.HOME);
                sortTimeout = SORT_HOME_TICKS;
                advance(0);
            }
            case HOME_ARRIVE -> {
                if (retryTeleport(client, player, Trip.HOME)) {
                    return;
                }
                if (!sortTeleporting) {
                    if (--sortTimeout <= 0) {
                        alertStop(client, player, io.theprisons.core.i18n.I18n.f(
                                "Item sorter: no \"Teleporting you to %s home in 1 seconds\" message within 60 s.", ItemSorter.HOME));
                    }
                    return;
                }
                advance(SORT_ARRIVE_TICKS);
            }
            case DELHOME -> {
                player.networkHandler.sendChatCommand("delhome " + ItemSorter.HOME);
                advance(1);
            }
            case NUDGE -> nudgeFromWall(player);
            case WARP -> {
                // Started at spawn: on to the mine as after a death (/warp, the ore, walk in).
                trip = null;
                chore = Chores.Kind.DEATH_RECOVERY;
                choreStep = 10;
                choreWait = humanDelay();
                warpLook = -1;
                warpSettle = -1;
                mineZoneEntered = false;
            }
        }
    }

    /** The teleport was cancelled (moved): back to its command step, at most 3 times. @return true = retrying */
    private boolean retryTeleport(MinecraftClient client, ClientPlayerEntity player, Trip command) {
        if (!teleportCancelled) {
            return false;
        }
        teleportCancelled = false;
        List<Trip> steps = trip;
        if (++teleportRetries > 3 || steps == null || !steps.contains(command)) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t("Trip: the teleport was cancelled 3 times (moved)."));
            return true;
        }
        ThePrisonsClient.LOGGER.info("[ore_macro] teleport cancelled (moved): /{} again", command == Trip.SPAWN ? "spawn" : "home");
        control.input().clear();
        tripIndex = steps.indexOf(command);
        choreStep = 0;
        choreWait = 20;
        return true;
    }

    private void advance(int waitTicks) {
        tripIndex++;
        choreStep = 0;
        choreWait = waitTicks;
    }

    private void finishTrip() {
        status = "Running";
        trip = null;
        chore = Chores.Kind.NONE;
        ClientPlayerEntity me = MinecraftClient.getInstance().player;
        if (me != null) {
            selectPickaxe(me);
        }
        if (spongeFetched) {
            // The sponge from the vault onto the pickaxe now.
            spongeFetched = false;
            needSponge = false;
            chore = Chores.Kind.EXTRACT_ENERGY;
            choreStep = 0;
            choreWait = 10;
        }
    }

    /** 5 blocks forward from the spawn point (contrabands and shards only open there). */
    private void walkForward(ClientPlayerEntity player) {
        if (choreStep == 0) {
            walkFrom = new double[]{player.getX(), player.getZ()};
            sortTimeout = WALK_TICKS;
            choreStep = 1;
            selectEmpty(player);
        }
        double[] from = walkFrom;
        double walked = from == null ? WALK_BLOCKS : Math.hypot(player.getX() - from[0], player.getZ() - from[1]);
        if (walked >= WALK_BLOCKS || --sortTimeout <= 0) {
            control.input().clear();
            advance(SORT_STEP_TICKS);
            return;
        }
        status = "Trip: 5 blocks forward";
        control.input().set(new io.theprisons.core.control.InputController.Keys(true, false, false, false, false, false, false));
    }

    /**
     * Back at /home tmp, before mining again: a block in front at leg height → 0.5 blocks back, a block behind → 0.5
     * blocks forward (sneaking, so it stops close to 0.5). Both or neither: stay.
     */
    private void nudgeFromWall(ClientPlayerEntity player) {
        if (choreStep == 0) {
            net.minecraft.util.math.Direction facing = player.getHorizontalFacing();
            BlockPos feet = player.getBlockPos();
            boolean front = legBlocked(player, feet.offset(facing));
            boolean behind = legBlocked(player, feet.offset(facing.getOpposite()));
            if (front == behind) {
                advance(SORT_STEP_TICKS);
                return;
            }
            nudgeBack = front;
            walkFrom = new double[]{player.getX(), player.getZ()};
            sortTimeout = NUDGE_TICKS;
            choreStep = 1;
            ThePrisonsClient.LOGGER.info("[ore_macro] home: block {} at leg height, 0.5 blocks {}",
                    front ? "in front" : "behind", front ? "back" : "forward");
        }
        double[] from = walkFrom;
        double walked = from == null ? NUDGE_BLOCKS : Math.hypot(player.getX() - from[0], player.getZ() - from[1]);
        if (walked >= NUDGE_BLOCKS || --sortTimeout <= 0) {
            control.input().clear();
            advance(SORT_STEP_TICKS);
            return;
        }
        status = nudgeBack ? "Trip: 0.5 blocks back" : "Trip: 0.5 blocks forward";
        control.input().set(new io.theprisons.core.control.InputController.Keys(!nudgeBack, nudgeBack, false, false, false, false, true));
    }

    private static boolean legBlocked(ClientPlayerEntity player, BlockPos pos) {
        net.minecraft.world.World world = player.getEntityWorld();
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /** Items in the 36 inventory slots (counted, for "the items came"). */
    private static int itemSum(ClientPlayerEntity player) {
        int sum = 0;
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            sum += player.getInventory().getStack(slot).getCount();
        }
        return sum;
    }

    private static int countOf(ClientPlayerEntity player, java.util.function.Predicate<ItemSorter.Item> what) {
        int n = 0;
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            if (what.test(item(player.getInventory().getStack(slot)))) {
                n++;
            }
        }
        return n;
    }

    /**
     * The first inventory slot (0-8 hotbar, 9-35 main) matching; the hotbar slot it is in, or -1 after swapping one from
     * the main inventory into the hotbar (with an item that may go: no pickaxe, shard, contraband or money), or -2 when
     * there is none / no hotbar slot may be used.
     */
    private int toHotbar(MinecraftClient client, ClientPlayerEntity player, java.util.function.Predicate<ItemSorter.Item> what) {
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (what.test(item(inventory.getStack(slot)))) {
                return slot;
            }
        }
        int from = -1;
        for (int slot = 9; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            if (what.test(item(inventory.getStack(slot)))) {
                from = slot;
                break;
            }
        }
        if (from < 0) {
            return -2;
        }
        int to = -1;
        for (int slot = 0; slot < 9 && to < 0; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                to = slot;
            }
        }
        for (int slot = 0; slot < 9 && to < 0; slot++) {
            if (ItemSorter.swappable(item(inventory.getStack(slot)))) {
                to = slot;
            }
        }
        if (to < 0) {
            ThePrisonsClient.LOGGER.info("[ore_macro] no hotbar slot may be swapped (pickaxe, shards, contrabands, money only)");
            return -2;
        }
        if (client.interactionManager != null) {
            // Main inventory slot n is screen slot n in the player's own screen; the button is the hotbar slot.
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, from, to,
                    net.minecraft.screen.slot.SlotActionType.SWAP, player);
        }
        return -1;
    }

    /** Each contraband: into the hotbar, look 40 degrees down, right click, wait for the 3 items. */
    private void openContrabands(MinecraftClient client, ClientPlayerEntity player) {
        float yaw = player.getYaw();
        switch (choreStep) {
            case 0 -> {
                if (tripRounds >= MAX_TRIP_ROUNDS) {
                    advance(SORT_STEP_TICKS);
                    return;
                }
                int hot = toHotbar(client, player, ItemSorter::contraband);
                if (hot == -2) {
                    advance(SORT_STEP_TICKS);
                    return;
                }
                if (hot == -1) {
                    choreWait = 5;
                    return;
                }
                status = "Trip: opening a contraband";
                player.getInventory().setSelectedSlot(hot);
                contrabandsBefore = countOf(player, ItemSorter::contraband);
                itemsBefore = itemSum(player);
                aimTicks = 0;
                choreStep = 1;
                choreWait = 3;
            }
            case 1 -> {
                control.rotation().follow(yaw, CONTRABAND_PITCH, YAW_OMEGA, PITCH_OMEGA);
                if (++aimTicks >= 12) {
                    choreStep = 2;
                }
            }
            case 2 -> {
                control.rotation().follow(yaw, CONTRABAND_PITCH, YAW_OMEGA, PITCH_OMEGA);
                if (client.interactionManager != null) {
                    if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
                            && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                        client.interactionManager.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
                    } else {
                        client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
                    }
                }
                count("contrabands_opened");
                sortTimeout = CONTRABAND_TICKS;
                choreStep = 3;
                choreWait = 20;
            }
            default -> {
                control.rotation().follow(yaw, CONTRABAND_PITCH, YAW_OMEGA, PITCH_OMEGA);
                boolean used = countOf(player, ItemSorter::contraband) < contrabandsBefore
                        || itemSum(player) < itemsBefore;
                // The chest left the hand (-1) and its 3 items came: net +2 items - or it is gone and 8 s passed (the
                // items may go elsewhere: money, energy, a satchel).
                boolean gone = countOf(player, ItemSorter::contraband) < contrabandsBefore;
                if (itemSum(player) >= itemsBefore + 2 || gone && CONTRABAND_TICKS - sortTimeout >= CONTRABAND_WAIT_TICKS) {
                    tripRounds++;
                    selectEmpty(player);
                    choreStep = 0;
                    choreWait = 10;
                    return;
                }
                if (--sortTimeout <= 0) {
                    ThePrisonsClient.LOGGER.info("[ore_macro] contraband: {} after 20 s", used ? "no items came" : "not placed");
                    tripRounds++;
                    if (!used) {
                        // Could not be opened: no new trip for it for 2 minutes (no loop).
                        lootTripAfterMs = System.currentTimeMillis() + 120_000L;
                        advance(SORT_STEP_TICKS);
                        return;
                    }
                    choreStep = 0;
                    choreWait = 10;
                }
            }
        }
    }

    /**
     * Shards: one of the lowest tier into the hotbar, right click (the menu opens with that one in), shift-click the
     * others in from the highest tier down to the lowest, "Roll all shards" (diamond horse armor), Esc - the rewards
     * come into the inventory. Again while shards are left.
     */
    private void openShards(MinecraftClient client, ClientPlayerEntity player) {
        switch (choreStep) {
            case 0 -> {
                net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
                int lowest = Integer.MAX_VALUE;
                List<String> names = new java.util.ArrayList<>();
                for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
                    ItemSorter.Item it = item(inventory.getStack(slot));
                    if (ItemSorter.shard(it)) {
                        lowest = Math.min(lowest, ItemSorter.shardRank(it.name()));
                        names.add(it.name());
                    }
                }
                if (lowest == Integer.MAX_VALUE || tripRounds >= MAX_TRIP_ROUNDS) {
                    advance(SORT_STEP_TICKS);
                    return;
                }
                if (!shardNamesLogged) {
                    shardNamesLogged = true;
                    ThePrisonsClient.LOGGER.info("[ore_macro] shards: {}", names);
                }
                int tier = lowest;
                int hot = toHotbar(client, player, it -> ItemSorter.shard(it) && ItemSorter.shardRank(it.name()) == tier);
                if (hot == -2) {
                    advance(SORT_STEP_TICKS);
                    return;
                }
                if (hot == -1) {
                    choreWait = 5;
                    return;
                }
                status = "Trip: opening shards";
                player.getInventory().setSelectedSlot(hot);
                choreStep = 1;
                choreWait = 3;
            }
            case 1 -> {
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
                }
                sortTimeout = 60;
                sortSlot = -1;
                sortStall = 0;
                choreStep = 2;
            }
            case 2 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        ThePrisonsClient.LOGGER.info("[ore_macro] the shard menu did not open");
                        lootTripAfterMs = System.currentTimeMillis() + 120_000L;
                        advance(SORT_STEP_TICKS);
                    }
                    return;
                }
                if (client.currentScreen != null) {
                    ThePrisonsClient.LOGGER.info("[ore_macro] shard menu: {}", client.currentScreen.getTitle().getString());
                }
                choreStep = 3;
            }
            case 3 -> {
                // The rarest shards first, the lowest tier last.
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                int start = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                int best = -1;
                int bestRank = Integer.MIN_VALUE;
                for (int slot = start; slot < handler.slots.size(); slot++) {
                    ItemSorter.Item it = item(handler.getSlot(slot).getStack());
                    if (ItemSorter.shard(it) && ItemSorter.shardRank(it.name()) > bestRank) {
                        bestRank = ItemSorter.shardRank(it.name());
                        best = slot;
                    }
                }
                if (best < 0) {
                    choreStep = 4;
                    return;
                }
                if (best == sortSlot && handler.getSlot(best).getStack().getCount() >= sortCount) {
                    // Nothing went in: the menu is full.
                    if (++sortStall >= 10) {
                        choreStep = 4;
                    }
                    return;
                }
                sortStall = 0;
                sortSlot = best;
                sortCount = handler.getSlot(best).getStack().getCount();
                if (client.interactionManager != null) {
                    client.interactionManager.clickSlot(handler.syncId, best, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                }
                choreWait = 1;
            }
            case 4 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                int roll = -1;
                for (int slot = 0; slot < menu; slot++) {
                    if (ItemSorter.ROLL_ALL.equals(item(handler.getSlot(slot).getStack()).id())) {
                        roll = slot;
                        break;
                    }
                }
                if (roll >= 0) {
                    click(client, handler, roll);
                    count("shards_rolled");
                } else {
                    ThePrisonsClient.LOGGER.info("[ore_macro] no \"Roll all shards\" button (diamond horse armor) in the shard menu");
                }
                choreStep = 5;
                choreWait = 3;
            }
            default -> {
                player.closeHandledScreen();
                selectEmpty(player);
                tripRounds++;
                choreStep = 0;
                choreWait = 20;
            }
        }
    }

    /**
     * /pv N, shift-click everything matching in, Esc; skipped when nothing matches. A full vault: the kind's next vault,
     * then the next free /pv number no other kind uses (added to the kind's setting, so the kinds stay apart).
     */
    private void vaultStep(MinecraftClient client, ClientPlayerEntity player, Settings.TextSetting setting,
                           java.util.function.Predicate<ItemSorter.Item> what, String counter) {
        switch (choreStep) {
            case 0 -> {
                if (ItemSorter.vault(setting.get()) < 0 || countOf(player, what) == 0) {
                    advance(0);
                    return;
                }
                vaultTry = 0;
                vaultNumber = ItemSorter.vaults(setting.get()).get(0);
                choreStep = 1;
            }
            case 1 -> {
                status = "Trip: /pv " + vaultNumber;
                player.networkHandler.sendChatCommand("pv " + vaultNumber);
                sortSlot = -1;
                sortStall = 0;
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 2;
            }
            case 2 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        // This /pv does not open (not unlocked): the next one.
                        ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} did not open", vaultNumber);
                        nextVault(client, player, setting);
                    }
                    return;
                }
                int state = fillVault(client, player, handler, what, counter);
                if (state == FILL_DONE) {
                    choreStep = 3;
                    choreWait = SORT_STEP_TICKS;
                } else if (state == FILL_FULL) {
                    player.closeHandledScreen();
                    ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} is full", vaultNumber);
                    choreWait = SORT_STEP_TICKS;
                    nextVault(client, player, setting);
                }
            }
            default -> {
                player.closeHandledScreen();
                List<Integer> mine = ItemSorter.vaults(setting.get());
                if (!mine.contains(vaultNumber)) {
                    // A new vault for this kind: remembered in its setting.
                    mine.add(vaultNumber);
                    setting.set(String.join(", ", mine.stream().map(String::valueOf).toList()));
                    ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} added to {}", vaultNumber, setting.id());
                }
                advance(SORT_STEP_TICKS);
            }
        }
    }

    /** The kind's next vault: its listed ones first, then the next /pv number no kind uses; none left: stop. */
    private void nextVault(MinecraftClient client, ClientPlayerEntity player, Settings.TextSetting setting) {
        List<Integer> mine = ItemSorter.vaults(setting.get());
        int at = mine.indexOf(vaultNumber);
        if (at >= 0 && at + 1 < mine.size()) {
            vaultNumber = mine.get(at + 1);
            choreStep = 1;
            return;
        }
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Settings.TextSetting kind : List.of(vaultShards, vaultOther, vaultEnergy)) {
            taken.addAll(ItemSorter.vaults(kind.get()));
        }
        int n = Math.max(vaultNumber, 0) + 1;
        while (n <= MAX_VAULT && (taken.contains(n) || n <= vaultNumber)) {
            n++;
        }
        if (n > MAX_VAULT || ++vaultTry > MAX_VAULT) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.f(
                    "Item sorter: every /pv is full (tried up to /pv %d). Empty one and start the macro again.", MAX_VAULT));
            return;
        }
        vaultNumber = n;
        choreStep = 1;
    }

    /** Pickaxes outside hotbar slot 1 (inventory slot 0): the mining pickaxe stays, the others are tinkered. */
    private static int extraPickaxes(ClientPlayerEntity player) {
        int n = 0;
        for (int slot = 1; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            if (player.getInventory().getStack(slot).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                n++;
            }
        }
        return n;
    }

    /**
     * /tinker, shift-click every pickaxe except the one in hotbar slot 1 into the offer area, click ACCEPT (slot 4), Esc.
     * Skipped when there is none; a /tinker that does not open is skipped after 5 s.
     */
    private void tinker(MinecraftClient client, ClientPlayerEntity player) {
        switch (choreStep) {
            case 0 -> {
                if (extraPickaxes(player) == 0) {
                    advance(0);
                    return;
                }
                status = "Trip: /tinker";
                player.networkHandler.sendChatCommand("tinker");
                sortSlot = -1;
                sortStall = 0;
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 1;
            }
            case 1 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        ThePrisonsClient.LOGGER.info("[ore_macro] /tinker did not open");
                        advance(SORT_STEP_TICKS);
                    }
                    return;
                }
                int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                int miningPickaxe = handler.slots.size() - 9; // hotbar slot 1 stays
                if (sortSlot >= 0 && handler.getSlot(sortSlot).getStack().isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                    // Not moved yet: up to 0.5 s, then the offer area is full - accept what is in it.
                    if (++sortStall >= 10) {
                        choreStep = 2;
                    }
                    return;
                }
                sortSlot = -1;
                sortStall = 0;
                for (int slot = menu; slot < handler.slots.size(); slot++) {
                    net.minecraft.item.ItemStack stack = handler.getSlot(slot).getStack();
                    if (slot != miningPickaxe && !stack.isEmpty() && stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                        sortSlot = slot;
                        if (client.interactionManager != null) {
                            client.interactionManager.clickSlot(handler.syncId, slot, 0,
                                    net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                        }
                        count("tinkered");
                        choreWait = 1;
                        return;
                    }
                }
                choreStep = 2;
                choreWait = SORT_STEP_TICKS;
            }
            case 2 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler.slots.size() > TINKER_ACCEPT_SLOT && client.interactionManager != null) {
                    String name = io.theprisons.core.client.TextStrip.strip(
                            handler.getSlot(TINKER_ACCEPT_SLOT).getStack().getName().getString());
                    if (name.toUpperCase(Locale.ROOT).contains("ACCEPT")) {
                        client.interactionManager.clickSlot(handler.syncId, TINKER_ACCEPT_SLOT, 0,
                                net.minecraft.screen.slot.SlotActionType.PICKUP, player);
                        ThePrisonsClient.LOGGER.info("[ore_macro] tinker offer accepted");
                    }
                }
                choreStep = 3;
                choreWait = SORT_STEP_TICKS * 2;
            }
            default -> {
                player.closeHandledScreen();
                advance(SORT_STEP_TICKS);
            }
        }
    }

    /**
     * Everything the shard and energy steps leave (not the pickaxe, satchels of the chosen ores, sponges, pets, ability
     * items, ores): /pv 1, 2, 3 ... one after the other; in each, the items that the vault already holds (same id and
     * name) are shift-clicked in. What no vault holds goes into any /pv with room - first the fallback numbers of the
     * settings, then 1, 2, 3 ... except the shard and energy vaults.
     */
    private void sortVaults(MinecraftClient client, ClientPlayerEntity player, List<String> abilities) {
        List<String> kept = useAbility.on() ? abilities : List.of();
        boolean pets = usePet.on();
        List<String> satchels = new java.util.ArrayList<>(ores.get());
        java.util.function.Predicate<ItemSorter.Item> movable =
                it -> ItemSorter.other(it, kept, pets) || ItemSorter.otherSatchel(it, satchels);
        switch (choreStep) {
            case 0 -> {
                if (countOf(player, movable) == 0) {
                    advance(0);
                    return;
                }
                vaultNumber = 1;
                vaultHighest = 0;
                vaultFallback = false;
                choreStep = 1;
            }
            case 1 -> {
                status = "Trip: /pv " + vaultNumber + (vaultFallback ? " (free space)" : " (scan)");
                player.networkHandler.sendChatCommand("pv " + vaultNumber);
                vaultKeys = null;
                sortSlot = -1;
                sortStall = 0;
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 2;
            }
            case 2 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        // Does not open (not unlocked; access is monotone, so no later one opens either).
                        ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} did not open", vaultNumber);
                        sortVaultLocked(client, player);
                    }
                    return;
                }
                int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                if (vaultKeys == null) {
                    vaultHighest = Math.max(vaultHighest, vaultNumber);
                    vaultKeys = new java.util.HashSet<>();
                    for (int slot = 0; slot < menu; slot++) {
                        net.minecraft.item.ItemStack stack = handler.getSlot(slot).getStack();
                        if (!stack.isEmpty()) {
                            vaultKeys.add(ItemSorter.key(item(stack)));
                        }
                    }
                    ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} holds {} kinds of items", vaultNumber, vaultKeys.size());
                }
                java.util.Set<String> keys = vaultKeys;
                boolean fallback = vaultFallback;
                vaultMergeFull = !fallback;
                int state;
                try {
                    state = fillVault(client, player, handler,
                            it -> movable.test(it) && (fallback || keys.contains(ItemSorter.key(it))), "sorted_other");
                } finally {
                    vaultMergeFull = false;
                }
                if (state == FILL_DONE || state == FILL_FULL) {
                    if (state == FILL_FULL) {
                        ThePrisonsClient.LOGGER.info("[ore_macro] /pv {} is full", vaultNumber);
                    }
                    choreStep = 3;
                    choreWait = SORT_STEP_TICKS;
                }
            }
            default -> {
                player.closeHandledScreen();
                if (countOf(player, movable) == 0) {
                    advance(SORT_STEP_TICKS);
                    return;
                }
                if (!vaultFallback && vaultNumber < MAX_VAULT) {
                    vaultNumber++;
                    choreStep = 1;
                } else if (vaultFallback) {
                    nextFallbackVault(client, player);
                } else {
                    startFallback(client, player);
                }
            }
        }
    }

    private void sortVaultLocked(MinecraftClient client, ClientPlayerEntity player) {
        if (vaultFallback) {
            nextFallbackVault(client, player);
        } else {
            startFallback(client, player);
        }
    }

    /** The scan is over: what is left goes into any /pv with room. */
    private void startFallback(MinecraftClient client, ClientPlayerEntity player) {
        if (vaultHighest == 0 && ItemSorter.vaults(vaultOther.get()).isEmpty()) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                    "Item sorter: no private vault (/pv) could be opened."));
            return;
        }
        java.util.Set<Integer> reserved = new java.util.HashSet<>(ItemSorter.vaults(vaultShards.get()));
        reserved.addAll(ItemSorter.vaults(vaultEnergy.get()));
        java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>(ItemSorter.vaults(vaultOther.get()));
        for (int v = 1; v <= vaultHighest; v++) {
            if (!reserved.contains(v)) {
                order.add(v);
            }
        }
        if (order.isEmpty()) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                    "Item sorter: every /pv is full. Empty one and start the macro again."));
            return;
        }
        vaultFallback = true;
        fallbackOrder = order.stream().mapToInt(Integer::intValue).toArray();
        fallbackAt = 0;
        vaultNumber = fallbackOrder[0];
        choreStep = 1;
    }

    private void nextFallbackVault(MinecraftClient client, ClientPlayerEntity player) {
        if (++fallbackAt >= fallbackOrder.length) {
            alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                    "Item sorter: every /pv is full. Empty one and start the macro again."));
            return;
        }
        vaultNumber = fallbackOrder[fallbackAt];
        choreStep = 1;
    }

    private static final int TINKER_ACCEPT_SLOT = 4;

    /** A sponge from the vaults (other, energy, shards, then 1-9) into the inventory. */
    private void fetchSponge(MinecraftClient client, ClientPlayerEntity player) {
        if (spongeVaults == null) {
            java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>();
            for (String v : List.of(vaultOther.get(), vaultEnergy.get(), vaultShards.get())) {
                if (ItemSorter.vault(v) > 0) {
                    order.add(ItemSorter.vault(v));
                }
            }
            for (int v = 1; v <= 9; v++) {
                order.add(v);
            }
            spongeVaults = order.stream().mapToInt(Integer::intValue).toArray();
            spongeVault = 0;
        }
        int[] vaults = spongeVaults;
        switch (choreStep) {
            case 0 -> {
                if (spongeVault >= vaults.length) {
                    if (!needSponge) {
                        // Only stocking up (started at spawn): none anywhere is no reason to stop.
                        ThePrisonsClient.LOGGER.info("[ore_macro] no sponge in /pv 1-9 to take along");
                        advance(SORT_STEP_TICKS);
                        return;
                    }
                    alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                            "Pickaxe energy full and no sponge in the inventory or in /pv 1-9."));
                    return;
                }
                status = "Trip: looking for a sponge in /pv " + vaults[spongeVault];
                player.networkHandler.sendChatCommand("pv " + vaults[spongeVault]);
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 1;
            }
            case 1 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        // This vault does not exist / did not open: the next one.
                        choreStep = 2;
                    }
                    return;
                }
                int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                for (int slot = 0; slot < menu; slot++) {
                    if (ItemSorter.SPONGE.equals(item(handler.getSlot(slot).getStack()).id())) {
                        if (client.interactionManager != null) {
                            client.interactionManager.clickSlot(handler.syncId, slot, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                        }
                        spongeFetched = true;
                        ThePrisonsClient.LOGGER.info("[ore_macro] sponge taken from /pv {}", vaults[spongeVault]);
                        break;
                    }
                }
                choreStep = 2;
                choreWait = 5;
            }
            default -> {
                player.closeHandledScreen();
                if (spongeFetched) {
                    advance(SORT_STEP_TICKS);
                } else {
                    spongeVault++;
                    choreStep = 0;
                    choreWait = SORT_STEP_TICKS;
                }
            }
        }
    }

    // ── Death: respawn, whitescrolls, back to the mine ───────────────────────

    private void startDeath(MinecraftClient client, ClientPlayerEntity player, String why) {
        startRecovery(client, player, why, "deaths");
        ThePrisonsClient.LOGGER.info("[ore_macro] died ({}): respawn, whitescrolls, /warp back", why);
    }

    /** At spawn (died, or escaped there): whitescrolls checked, then /warp back to the mine - the death recovery chore. */
    private void startRecovery(MinecraftClient client, ClientPlayerEntity player, String why, String counter) {
        player.closeHandledScreen();
        control.input().clear();
        breaker.cancel(client);
        driver.stop();
        travel = null;
        travelJob = null;
        routeJob = null;
        guardJob = null;
        guardTarget = null;
        fleeing = false;
        fleeRequested = false;
        endBreak(System.currentTimeMillis());
        trip = null;
        chore = Chores.Kind.DEATH_RECOVERY;
        choreStep = 0;
        choreWait = 0;
        sortTimeout = 0;
        scrollVaults = null;
        scrollTries = 0;
        scrollNeed = -1;
        absorberNeed = 0;
        fetchingAbsorbers = false;
        mineZoneEntered = false;
        escapeSentMs = 0L;
        outsideWatch.reset();
        phase = Phase.STEER;
        count(counter);
        lastIssue = why;
    }

    /**
     * Where to jump down into a mine after /warp: the point at the platform's edge and the last jump - {@code side}
     * -1 = to the left, 0 = straight, 1 = to the right towards {@code landing} (steered in the air).
     */
    private record WarpDrop(int x, int y, int z, int side, int @Nullable [] landing) {
    }

    /** The drop points per mine (from the user, 2026-10-03). */
    private static final java.util.Map<String, WarpDrop> WARP_DROPS = java.util.Map.of(
            "gold", new WarpDrop(654, 176, 238, -1, null),
            "redstone", new WarpDrop(-77, 179, -509, 0, null),
            "lapis", new WarpDrop(-543, 221, 449, 0, null),
            "iron", new WarpDrop(-1384, 196, 151, 0, null),
            "coal", new WarpDrop(-1043, 188, -415, 1, new int[]{-1027, 119, -418}));

    /** The drop point of the chosen mine (the first chosen ore: "minecraft:gold_ore" → gold); null = none known. */
    private @Nullable WarpDrop warpDrop() {
        for (String id : warpOres()) {
            String base = id.replace("minecraft:", "").replace("_ore", "");
            WarpDrop drop = WARP_DROPS.get(base);
            if (drop != null) {
                return drop;
            }
        }
        return null;
    }

    /** After /warp: the drop point missed or not reachable straight - the planner's way to the chosen ore. */
    private void warpFallback(ClientPlayerEntity player, String why) {
        control.input().clear();
        ThePrisonsClient.LOGGER.info("[ore_macro] after /warp: {} - walking to the ore with the planner", why);
        selectPickaxe(player);
        chore = Chores.Kind.NONE;
        phase = Phase.STEER;
        steer.reset();
        classic.reset();
        resetPlan();
        forgetWalked();
        if (planRoutes.on()) {
            requestPlan(player, null, "after /warp: to the chosen ore");
        }
    }

    /** A human pause: 1.5-3 s (in ticks). */
    private int humanDelay() {
        return 30 + random.nextInt(31);
    }

    /** Slow view turns for the chores (no hectic snapping). */
    private static final float CALM_OMEGA = 4.0F;

    /** Only items that survive a death are left (pickaxe, satchels, sponges - or nothing). */
    private static boolean onlyKeptItems(ClientPlayerEntity player) {
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            ItemSorter.Item it = item(player.getInventory().getStack(slot));
            if (!it.empty() && !it.pickaxe() && !it.name().toLowerCase(Locale.ROOT).contains("satchel")
                    && !ItemSorter.SPONGE.equals(it.id())) {
                return false;
            }
        }
        return true;
    }

    private static boolean whitescroll(ItemSorter.Item item) {
        String name = item.name().toLowerCase(Locale.ROOT);
        return name.contains("white scroll") || name.contains("whitescroll");
    }

    /** The pickaxe / a satchel: must be whitescrolled before going to the mine. */
    private static boolean needsScroll(net.minecraft.item.ItemStack stack) {
        ItemSorter.Item it = item(stack);
        if (it.empty() || !(it.pickaxe() || it.name().toLowerCase(Locale.ROOT).contains("satchel"))) {
            return false;
        }
        for (String line : io.theprisons.core.client.ClientReadouts.lore(stack)) {
            if (line.toLowerCase(Locale.ROOT).contains("whitescrolled")) {
                return false;
            }
        }
        return true;
    }

    /** The normal ore blocks of the chosen mine (deepslate ores: their normal version) - clicked in the /warp menu. */
    private java.util.Set<String> warpOres() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Block block : OreCatalog.resolve(ores.get())) {
            String id = OreCatalog.id(block).replace("deepslate_", "");
            if (id.endsWith("_ore")) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * After a death: respawn → (at spawn) every pickaxe and satchel whitescrolled - the scrolls from the vaults if
     * needed; never on to the mine without → /warp, the chosen ore → 8 blocks forward, jumping, until "You entered a
     * ... zone" → mining again.
     */
    private void deathRecovery(MinecraftClient client, ClientPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        switch (choreStep) {
            case 0 -> {
                status = "Died: waiting for the respawn";
                if (player.isDead()) {
                    return;
                }
                // At spawn: a moment of waiting (killed - the server sets everything back), then the vaults.
                choreStep = 1;
                choreWait = 40 + random.nextInt(41);
            }
            case 1 -> {
                // Which items still need a whitescroll?
                scrollTarget = -1;
                scrollSlot = -1;
                int satchels = 0;
                int heldScrolls = 0;
                for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
                    net.minecraft.item.ItemStack stack = inventory.getStack(slot);
                    if (scrollTarget < 0 && needsScroll(stack)) {
                        scrollTarget = slot;
                    }
                    ItemSorter.Item it = item(stack);
                    if (it.name().toLowerCase(Locale.ROOT).contains("satchel")) {
                        satchels++;
                    }
                    if (whitescroll(it)) {
                        heldScrolls += Math.max(1, stack.getCount());
                        if (scrollSlot < 0) {
                            scrollSlot = slot;
                        }
                    }
                }
                if (scrollNeed < 0) {
                    // As many whitescrolls as satchels + 1 for the pickaxe, out of the /pv.
                    scrollNeed = Math.max(0, satchels + 1 - heldScrolls);
                    ThePrisonsClient.LOGGER.info("[ore_macro] died: {} satchels + pickaxe, {} whitescrolls in the inventory, {} to fetch",
                            satchels, heldScrolls, scrollNeed);
                }
                if (scrollNeed > 0 && scrollTarget >= 0) {
                    fetchingAbsorbers = false;
                    scrollVaults = null;
                    scrollVault = 0;
                    choreStep = 2;
                    return;
                }
                if (scrollTarget < 0) {
                    startAbsorbers(player);
                    return;
                }
                if (scrollSlot < 0 || ++scrollTries > 12) {
                    alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                            "Died: the whitescroll did not go onto the pickaxe / satchel - not going back to the mine."));
                    return;
                }
                choreStep = 5;
            }
            case 2, 3, 4 -> fetchScroll(client, player);
            case 5 -> {
                status = "Died: whitescroll onto " + item(inventory.getStack(scrollTarget)).name();
                client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(player));
                choreStep = 6;
                choreWait = 6;
            }
            case 6 -> {
                click(client, player.playerScreenHandler, screenSlot(scrollSlot));
                choreStep = 7;
                choreWait = 4;
            }
            case 7 -> {
                click(client, player.playerScreenHandler, screenSlot(scrollTarget));
                choreStep = 8;
                choreWait = 4;
            }
            case 8 -> {
                net.minecraft.screen.PlayerScreenHandler handler = player.playerScreenHandler;
                if (!handler.getCursorStack().isEmpty() && !whitescroll(item(handler.getCursorStack()))) {
                    // The server swapped instead of applying: the item goes back first.
                    click(client, handler, screenSlot(scrollTarget));
                } else if (!handler.getCursorStack().isEmpty()) {
                    // The rest of the scrolls back where they came from.
                    click(client, handler, screenSlot(scrollSlot));
                }
                choreStep = 9;
                choreWait = 4;
            }
            case 9 -> {
                player.closeHandledScreen();
                count("whitescrolls_applied");
                choreStep = 1;
                choreWait = 10;
            }
            case 10 -> {
                status = "/warp";
                selectEmpty(player);
                player.networkHandler.sendChatCommand("warp");
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 11;
            }
            case 11 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        alertStop(client, player, io.theprisons.core.i18n.I18n.t("Died: the /warp menu did not open."));
                    }
                    return;
                }
                java.util.Set<String> wanted = warpOres();
                int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
                int found = -1;
                for (int slot = 0; slot < menu && found < 0; slot++) {
                    if (wanted.contains(item(handler.getSlot(slot).getStack()).id())) {
                        found = slot;
                    }
                }
                if (found < 0) {
                    alertStop(client, player, io.theprisons.core.i18n.I18n.f("Died: no %s in the /warp menu.", wanted));
                    return;
                }
                if (warpLook < 0) {
                    // The menu is open: a human looks for the ore first.
                    warpLook = humanDelay();
                    choreWait = warpLook;
                    return;
                }
                warpLook = -1;
                ThePrisonsClient.LOGGER.info("[ore_macro] /warp: {}", item(handler.getSlot(found).getStack()).name());
                click(client, handler, found);
                mineZoneEntered = false;
                walkFrom = new double[]{player.getX(), player.getZ(), player.getY()};
                sortTimeout = ARRIVE_TICKS;
                choreStep = 12;
                choreWait = 10;
            }
            case 12 -> {
                // The warp's countdown / teleport: walk in once the position jumped (20 s at the latest).
                if (player.currentScreenHandler != player.playerScreenHandler) {
                    player.closeHandledScreen();
                }
                double[] before = walkFrom;
                boolean teleported = before != null && Math.hypot(player.getX() - before[0], player.getZ() - before[1]) > 20.0D;
                if (!teleported && --sortTimeout > 0) {
                    status = "Waiting for the warp";
                    return;
                }
                if (warpSettle < 0) {
                    // Arrived: look around a moment before walking (the world loads in, as a human waits).
                    warpSettle = 30 + humanDelay();
                    choreWait = warpSettle;
                    return;
                }
                warpSettle = -1;
                walkFrom = new double[]{player.getX(), player.getZ(), player.getY()};
                dropTicks = 0;
                dropBest = Double.MAX_VALUE;
                dropStall = 0;
                sortTimeout = 300;
                choreStep = 13;
                choreWait = 20;
            }
            case 13 -> {
                // To the drop point (sprinting, jumping), one last jump over the edge; no drop point near: about 8
                // blocks straight on, jumping - until "You entered a ... zone".
                status = "Walking into the mine";
                if (mineZoneEntered) {
                    control.input().clear();
                    choreStep = 14;
                    choreWait = 20;
                    return;
                }
                if (--sortTimeout <= 0) {
                    // Not sure it is in: the planner takes it to the ore from wherever it is.
                    warpFallback(player, "no zone message and no landing within 15 s");
                    return;
                }
                WarpDrop drop = warpDrop();
                double toDrop = drop == null ? Double.MAX_VALUE : Math.hypot(player.getX() - drop.x() - 0.5D, player.getZ() - drop.z() - 0.5D);
                if (dropTicks == 0) {
                    if (drop == null || toDrop > 40.0D) {
                        warpFallback(player, drop == null ? "no drop point for this mine" : "landed " + Math.round(toDrop) + " blocks from the drop point");
                        return;
                    }
                    // Sprinting and jumping straight to the edge; no progress for 3 s (an obstacle): the planner.
                    if (toDrop < dropBest - 0.5D) {
                        dropBest = toDrop;
                        dropStall = 0;
                    } else if (++dropStall > 60) {
                        warpFallback(player, "no way straight to the drop point");
                        return;
                    }
                    float yaw = RotationMath.yawOf(drop.x() + 0.5D - player.getX(), drop.z() + 0.5D - player.getZ());
                    control.rotation().follow(yaw, 0.0F, CALM_OMEGA, CALM_OMEGA);
                    if (Math.abs(RotationMath.wrap(yaw - player.getYaw())) > 20.0F) {
                        // Calmly: face the drop point first, then walk.
                        control.input().clear();
                        dropStall = Math.max(0, dropStall - 1);
                        return;
                    }
                    if (toDrop <= 1.2D) {
                        // At the edge: the last jump down.
                        dropYaw = yaw;
                        dropTicks = 15;
                    }
                    double speed = Math.hypot(player.getVelocity().x, player.getVelocity().z);
                    // Jump only where a step stops the walk.
                    boolean jump = player.isOnGround() && speed < 0.05D && dropStall > 5;
                    // Slow and human: walking, no sprint, calm turns.
                    control.input().set(new io.theprisons.core.control.InputController.Keys(true, false, false, false, jump, false, false));
                    return;
                }
                if (dropTicks > 0) {
                    dropTicks--;
                    int side = drop == null ? 0 : drop.side();
                    float yaw = dropYaw;
                    if (side > 0 && drop.landing() != null) {
                        // Hard to the right: steered in the air towards the mine's start.
                        yaw = RotationMath.yawOf(drop.landing()[0] + 0.5D - player.getX(), drop.landing()[2] + 0.5D - player.getZ());
                        if (!player.isOnGround() || player.getY() > drop.landing()[1] + 1.0D) {
                            dropTicks = Math.max(dropTicks, 1);
                        }
                    }
                    control.rotation().follow(yaw, 0.0F, YAW_OMEGA, PITCH_OMEGA);
                    boolean go = dropTicks > 0;
                    control.input().set(new io.theprisons.core.control.InputController.Keys(go, false, go && side < 0,
                            go && side > 0 && drop.landing() == null, go && player.isOnGround(), true, false));
                    if (dropTicks == 0) {
                        dropTicks = -1;
                    }
                    return;
                }
                if (dropTicks < 0) {
                    // Jumped down: landed well below the platform = in the mine (the zone message may not come -
                    // the Gold Mine says "Welcome to the Gold Mine!" already at the warp).
                    control.input().clear();
                    if (drop != null && player.isOnGround() && player.getY() <= drop.y() - 4.0D) {
                        choreStep = 14;
                        choreWait = 10;
                    }
                    return;
                }
                double[] from = walkFrom;
                boolean far = from != null && Math.hypot(player.getX() - from[0], player.getZ() - from[1]) >= 8.0D;
                control.input().set(new io.theprisons.core.control.InputController.Keys(!far, false, false, false, !far, false, false));
            }
            default -> {
                ThePrisonsClient.LOGGER.info("[ore_macro] in the mine: mining again");
                selectPickaxe(player);
                chore = Chores.Kind.NONE;
                phase = Phase.STEER;
                steer.reset();
                classic.reset();
                resetPlan();
                forgetWalked();
            }
        }
    }

    /** 16 absorbers into the inventory (the rest of the way is the /warp); then the whitescrolls are done. */
    private static final int ABSORBERS = 16;

    private static boolean absorber(ItemSorter.Item item) {
        return item.name().toLowerCase(Locale.ROOT).contains("absorber");
    }

    private void startAbsorbers(ClientPlayerEntity player) {
        int held = 0;
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            net.minecraft.item.ItemStack stack = player.getInventory().getStack(slot);
            if (absorber(item(stack))) {
                held += Math.max(1, stack.getCount());
            }
        }
        absorberNeed = Math.max(0, ABSORBERS - held);
        if (absorberNeed == 0) {
            choreStep = 10;
            choreWait = humanDelay();
            warpLook = -1;
            warpSettle = -1;
            return;
        }
        fetchingAbsorbers = true;
        scrollVaults = null;
        scrollVault = 0;
        choreStep = 2;
    }

    /** The first empty slot of the player's part of an open container (after its {@code menu} slots), -1 = none. */
    private static int emptyPlayerSlot(net.minecraft.screen.ScreenHandler handler, int menu) {
        for (int slot = menu; slot < handler.slots.size(); slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Takes exactly {@code need} items of a kind out of the open vault: whole stacks by shift click, from a larger one
     * the missing amount (stack on the cursor, one by one onto an empty slot with right clicks, the rest back).
     *
     * @return how many were taken
     */
    private int takeExact(MinecraftClient client, ClientPlayerEntity player, net.minecraft.screen.ScreenHandler handler,
                          java.util.function.Predicate<ItemSorter.Item> what, int need) {
        if (client.interactionManager == null) {
            return 0;
        }
        int menu = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
        int taken = 0;
        for (int slot = 0; slot < menu && taken < need; slot++) {
            net.minecraft.item.ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !what.test(item(stack))) {
                continue;
            }
            int count = stack.getCount();
            int want = need - taken;
            if (count <= want) {
                client.interactionManager.clickSlot(handler.syncId, slot, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                taken += count;
                continue;
            }
            int target = emptyPlayerSlot(handler, menu);
            if (target < 0) {
                break;
            }
            net.minecraft.screen.slot.SlotActionType pickup = net.minecraft.screen.slot.SlotActionType.PICKUP;
            client.interactionManager.clickSlot(handler.syncId, slot, 0, pickup, player);
            for (int i = 0; i < want; i++) {
                client.interactionManager.clickSlot(handler.syncId, target, 1, pickup, player);
            }
            client.interactionManager.clickSlot(handler.syncId, slot, 0, pickup, player);
            taken += want;
        }
        return taken;
    }

    /**
     * Whitescrolls / absorbers from the vaults (other, energy, shards, then 1-9) into the inventory, exactly as many as
     * needed; steps 2-4.
     */
    private void fetchScroll(MinecraftClient client, ClientPlayerEntity player) {
        if (scrollVaults == null) {
            java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>();
            for (String v : List.of(vaultOther.get(), vaultEnergy.get(), vaultShards.get())) {
                if (ItemSorter.vault(v) > 0) {
                    order.add(ItemSorter.vault(v));
                }
            }
            for (int v = 1; v <= 9; v++) {
                order.add(v);
            }
            scrollVaults = order.stream().mapToInt(Integer::intValue).toArray();
            scrollVault = 0;
        }
        int[] vaults = scrollVaults;
        boolean absorbers = fetchingAbsorbers;
        int need = absorbers ? absorberNeed : scrollNeed;
        switch (choreStep) {
            case 2 -> {
                if (need <= 0 || scrollVault >= vaults.length) {
                    if (absorbers) {
                        ThePrisonsClient.LOGGER.info("[ore_macro] died: {} absorbers still missing after all vaults", absorberNeed);
                        absorberNeed = 0;
                        fetchingAbsorbers = false;
                        choreStep = 10;
                        choreWait = humanDelay();
                        warpLook = -1;
                        warpSettle = -1;
                        return;
                    }
                    if (countOf(player, OreMacroModule::whitescroll) == 0) {
                        alertStop(client, player, io.theprisons.core.i18n.I18n.t(
                                "Died: no whitescroll in the inventory or in /pv 1-9 - not going back to the mine without."));
                        return;
                    }
                    // Fewer than wanted: what there is is used.
                    scrollNeed = 0;
                    choreStep = 1;
                    choreWait = SORT_STEP_TICKS;
                    return;
                }
                status = "Died: " + (absorbers ? "absorbers" : "whitescrolls") + " from /pv " + vaults[scrollVault];
                player.networkHandler.sendChatCommand("pv " + vaults[scrollVault]);
                sortTimeout = SORT_OPEN_TICKS;
                choreStep = 3;
            }
            case 3 -> {
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler == player.playerScreenHandler || handler.slots.size() <= ItemSorter.INVENTORY_SLOTS) {
                    if (--sortTimeout <= 0) {
                        scrollVault++;
                        choreStep = 2;
                    }
                    return;
                }
                int taken = takeExact(client, player, handler, absorbers ? OreMacroModule::absorber : OreMacroModule::whitescroll, need);
                if (taken > 0) {
                    ThePrisonsClient.LOGGER.info("[ore_macro] {} {} taken from /pv {}", taken, absorbers ? "absorbers" : "whitescrolls",
                            vaults[scrollVault]);
                }
                if (absorbers) {
                    absorberNeed -= taken;
                } else {
                    scrollNeed -= taken;
                }
                scrollVault++;
                choreStep = 4;
                choreWait = 5 + random.nextInt(6);
            }
            default -> {
                player.closeHandledScreen();
                // More to fetch: the next vault; else on (scrolls: applied from step 1; absorbers: /warp).
                if (absorbers) {
                    choreStep = absorberNeed > 0 ? 2 : 10;
                    if (absorberNeed <= 0) {
                        fetchingAbsorbers = false;
                        warpLook = -1;
                        warpSettle = -1;
                    }
                    choreWait = humanDelay();
                } else {
                    choreStep = scrollNeed > 0 ? 2 : 1;
                    choreWait = SORT_STEP_TICKS;
                }
            }
        }
    }

    /** The first pickaxe in the hotbar; null = none. */
    private static net.minecraft.item.@Nullable ItemStack hotbarPickaxe(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            net.minecraft.item.ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                return stack;
            }
        }
        return null;
    }

    /** Not mining (trips, spawn, /warp): an empty hotbar slot in hand - none free: any item but a pickaxe. */
    private static void selectEmpty(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isEmpty()) {
                player.getInventory().setSelectedSlot(slot);
                return;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (!player.getInventory().getStack(slot).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                player.getInventory().setSelectedSlot(slot);
                return;
            }
        }
    }

    /** The first pickaxe of the hotbar in hand again (after contrabands, shards, money). */
    private static void selectPickaxe(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                player.getInventory().setSelectedSlot(slot);
                return;
            }
        }
    }

    /** Player screen slot of inventory slot n (hotbar 0-8 = screen 36-44, main 9-35 = 9-35). */
    private static int screenSlot(int inventorySlot) {
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }

    /**
     * Stacks of one kind spread over the inventory put together: pick the first up, gather all of its kind onto it
     * (double click), put it back. {@code true} when done (or nothing to merge).
     */
    private boolean mergeStacks(MinecraftClient client, ClientPlayerEntity player, java.util.function.Predicate<ItemSorter.Item> what) {
        net.minecraft.screen.PlayerScreenHandler handler = player.playerScreenHandler;
        switch (mergeStep) {
            case 0 -> {
                int first = -1;
                int stacks = 0;
                for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
                    if (what.test(item(player.getInventory().getStack(slot)))) {
                        stacks++;
                        if (first < 0) {
                            first = slot;
                        }
                    }
                }
                if (stacks < 2 || client.interactionManager == null) {
                    return true;
                }
                mergeSlot = screenSlot(first);
                click(client, handler, mergeSlot);
                mergeStep = 1;
                choreWait = 2;
                return false;
            }
            case 1 -> {
                client.interactionManager.clickSlot(handler.syncId, mergeSlot, 0, net.minecraft.screen.slot.SlotActionType.PICKUP_ALL, player);
                mergeStep = 2;
                choreWait = 2;
                return false;
            }
            default -> {
                if (!handler.getCursorStack().isEmpty()) {
                    click(client, handler, mergeSlot);
                }
                mergeStep = 0;
                count("stacks_merged");
                return true;
            }
        }
    }

    /** Money notes in the inventory: their value from name and lore. */
    private long moneyCarried(ClientPlayerEntity player) {
        long total = 0L;
        for (int slot = 0; slot < ItemSorter.INVENTORY_SLOTS; slot++) {
            net.minecraft.item.ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !ItemSorter.money(item(stack))) {
                continue;
            }
            List<String> lines = new java.util.ArrayList<>();
            lines.add(io.theprisons.core.client.TextStrip.strip(stack.getName().getString()));
            lines.addAll(io.theprisons.core.client.ClientReadouts.lore(stack));
            if (!moneyNamesLogged) {
                moneyNamesLogged = true;
                ThePrisonsClient.LOGGER.info("[ore_macro] money note: {}", lines);
            }
            total += ItemSorter.moneyAmount(lines) * stack.getCount();
        }
        return total;
    }

    /** Trip step: money notes stacked together, into the hotbar, right clicked until none is left. */
    private boolean redeemStep(MinecraftClient client, ClientPlayerEntity player) {
        if (chore == Chores.Kind.SORT_ITEMS) {
            redeemMoney(client, player);
            return choreStep >= 3;
        }
        return true;
    }

    /** Money notes (paper): stacked together, into the hotbar, right clicked until none is left, pickaxe back in hand. */
    private void redeemMoney(MinecraftClient client, ClientPlayerEntity player) {
        switch (choreStep) {
            case 0 -> {
                status = "Redeeming money notes";
                if (mergeStacks(client, player, ItemSorter::money)) {
                    choreStep = 1;
                    choreWait = 3;
                }
            }
            case 1 -> {
                int hot = toHotbar(client, player, ItemSorter::money);
                if (hot == -2) {
                    choreStep = 3;
                    return;
                }
                if (hot == -1) {
                    choreWait = 5;
                    return;
                }
                player.getInventory().setSelectedSlot(hot);
                redeemClicks = 0;
                choreStep = 2;
                choreWait = 3;
            }
            case 2 -> {
                net.minecraft.item.ItemStack held = player.getMainHandStack();
                if (!ItemSorter.money(item(held)) || redeemClicks >= 128) {
                    // This stack is redeemed (or the server takes no more): next stack, if any.
                    choreStep = countOf(player, ItemSorter::money) > 0 && redeemClicks < 128 ? 1 : 3;
                    choreWait = 3;
                    return;
                }
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
                }
                redeemClicks++;
                count("money_redeemed");
                choreWait = 3;
            }
            default -> {
                if (countOf(player, ItemSorter::money) > 0) {
                    ThePrisonsClient.LOGGER.info("[ore_macro] money notes left after right clicking");
                }
            }
        }
    }

    private static final int FILL_WORKING = 0;
    private static final int FILL_DONE = 1;
    private static final int FILL_FULL = 2;
    private static final int MAX_VAULT = 54;

    /**
     * One shift-click per tick from the inventory part of the open vault: {@link #FILL_DONE} when nothing is left to
     * move, {@link #FILL_FULL} when the vault's last slot is taken or a click moved nothing.
     */
    private int fillVault(MinecraftClient client, ClientPlayerEntity player, net.minecraft.screen.ScreenHandler handler,
                          java.util.function.Predicate<ItemSorter.Item> what, String counter) {
        int vaultSlots = handler.slots.size() - ItemSorter.INVENTORY_SLOTS;
        if (sortSlot >= 0 && handler.getSlot(sortSlot).getStack().getCount() >= sortCount
                && !handler.getSlot(sortSlot).getStack().isEmpty()) {
            // Nothing moved: give the server up to 0.5 s, then the vault is full.
            return ++sortStall >= 10 ? FILL_FULL : FILL_WORKING;
        }
        sortStall = 0;
        boolean anyLeft = false;
        for (int slot = vaultSlots; slot < handler.slots.size(); slot++) {
            if (what.test(item(handler.getSlot(slot).getStack()))) {
                anyLeft = true;
                break;
            }
        }
        if (!anyLeft) {
            sortSlot = -1;
            return FILL_DONE;
        }
        boolean free = false;
        for (int slot = 0; slot < vaultSlots; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) {
                free = true;
                break;
            }
        }
        if (!free && !vaultMergeFull) {
            return FILL_FULL;
        }
        for (int slot = vaultSlots; slot < handler.slots.size(); slot++) {
            net.minecraft.item.ItemStack stack = handler.getSlot(slot).getStack();
            if (what.test(item(stack))) {
                sortSlot = slot;
                sortCount = stack.getCount();
                if (client.interactionManager != null) {
                    client.interactionManager.clickSlot(handler.syncId, slot, 0,
                            net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
                }
                count(counter);
                choreWait = 1;
                return FILL_WORKING;
            }
        }
        return FILL_DONE;
    }

    /** Stops the whole macro: alert sound, red chat message and the reason as notification. */
    private void alertStop(MinecraftClient client, ClientPlayerEntity player, String reason) {
        player.closeHandledScreen();
        chore = Chores.Kind.NONE;
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND, 1.0F, 1.0F));
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.7F, 1.0F));
        io.theprisons.core.setup.ModChat.show(io.theprisons.core.setup.ModChat.header("Ore Macro stopped"));
        io.theprisons.core.setup.ModChat.show(Text.literal("  • " + reason).formatted(Formatting.RED));
        ThePrisonsClient.LOGGER.warn("[ore_macro] {}", reason);
        disableSelf(reason);
    }

    // ── Auto use: pet and item abilities from the hotbar ─────────────────────

    /**
     * Starts using a ready pet (first) or item ability from the hotbar. Ready = the server shows no cooldown on it; an
     * item the server never showed a cooldown for after the macro used it waits {@link #PET_FALLBACK_MS} /
     * {@link #ABILITY_FALLBACK_MS} instead (no spamming).
     */
    private boolean startAutoUse(ClientPlayerEntity player) {
        boolean pets = usePet.on();
        boolean abilities = useAbility.on();
        if ((!pets && !abilities) || chore != Chores.Kind.NONE) {
            return false;
        }
        long now = System.currentTimeMillis();
        net.minecraft.entity.player.PlayerInventory inventory = player.getInventory();
        net.minecraft.entity.player.ItemCooldownManager cooldowns = player.getItemCooldownManager();
        String[] names = new String[9];
        boolean[] petReady = new boolean[9];
        boolean[] abilityReady = new boolean[9];
        for (int slot = 0; slot < 9; slot++) {
            net.minecraft.item.ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                names[slot] = "";
                continue;
            }
            String name = io.theprisons.core.client.TextStrip.strip(stack.getName().getString());
            names[slot] = name;
            boolean cooling = cooldowns.isCoolingDown(stack);
            long[] last = used.get(name.toLowerCase(Locale.ROOT));
            if (cooling && last != null) {
                // The server shows the cooldown: from now on its end says when the item is ready again.
                last[1] = 1L;
            }
            boolean waiting = cooling || serverCooldown(name.toLowerCase(Locale.ROOT), now);
            petReady[slot] = !waiting && readyAgain(last, now, PET_FALLBACK_MS);
            abilityReady[slot] = !waiting && readyAgain(last, now, ABILITY_FALLBACK_MS);
        }
        int pet = pets ? Chores.readySlot(names, petReady, Chores.nameParts(petName.get())) : -1;
        int ability = abilities && pet < 0 ? Chores.readySlot(names, abilityReady, Chores.nameParts(abilityNames.get())) : -1;
        int slot = pet >= 0 ? pet : ability;
        if (slot < 0) {
            return false;
        }
        used.put(names[slot].toLowerCase(Locale.ROOT), new long[]{now, 0L});
        useSlot = slot;
        useReturnSlot = inventory.getSelectedSlot();
        chore = pet >= 0 ? Chores.Kind.USE_PET : Chores.Kind.USE_ABILITY;
        choreStep = 0;
        choreWait = 0;
        status = (pet >= 0 ? "Using pet: " : "Using ability: ") + names[slot];
        ThePrisonsClient.LOGGER.info("[ore_macro] {} ready in hotbar slot {}: using it", names[slot], slot + 1);
        return true;
    }

    /** The server said in chat that this item (its name contains the one in the message) is still on cooldown. */
    private boolean serverCooldown(String itemName, long now) {
        for (java.util.Map.Entry<String, Long> entry : cooldownUntil.entrySet()) {
            if (now < entry.getValue() && itemName.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** Never used, or its cooldown was seen and is over, or (no cooldown seen) the fallback time passed. */
    private static boolean readyAgain(long @Nullable [] last, long now, long fallbackMs) {
        if (last == null) {
            return true;
        }
        return last[1] == 1L ? now - last[0] >= 2_000L : now - last[0] >= fallbackMs;
    }

    /** Pet ready: select it, right click once, back to the item held before. */
    private void usePet(MinecraftClient client, ClientPlayerEntity player) {
        switch (choreStep++) {
            case 0 -> {
                player.getInventory().setSelectedSlot(useSlot);
                choreWait = 3;
            }
            case 1 -> {
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
                }
                count("pet_used");
                choreWait = 4;
            }
            case 2 -> {
                restoreSlot(player);
                choreWait = 3;
            }
            default -> chore = Chores.Kind.NONE;
        }
    }

    /** Item ability ready: stand, hold right click for 2.2 s (no mining), back to the item held before, walk on. */
    private void useAbility(MinecraftClient client, ClientPlayerEntity player) {
        switch (choreStep++) {
            case 0 -> {
                player.getInventory().setSelectedSlot(useSlot);
                useHoldTicks = ABILITY_HOLD_TICKS;
                choreWait = 3;
            }
            case 1 -> {
                if (useHoldTicks-- > 0) {
                    client.options.useKey.setPressed(true);
                    choreStep--;
                    return;
                }
                client.options.useKey.setPressed(false);
                count("ability_used");
                choreWait = 2;
            }
            case 2 -> {
                restoreSlot(player);
                choreWait = 3;
            }
            default -> chore = Chores.Kind.NONE;
        }
    }

    private void restoreSlot(ClientPlayerEntity player) {
        if (useReturnSlot >= 0 && useReturnSlot < 9) {
            player.getInventory().setSelectedSlot(useReturnSlot);
        }
        useSlot = -1;
        useReturnSlot = -1;
    }

    /** Pickaxe energy full: sponge onto the first hotbar pickaxe, sponge back, go on. */
    private void extractEnergy(MinecraftClient client, ClientPlayerEntity player) {
        net.minecraft.screen.PlayerScreenHandler handler = player.playerScreenHandler;
        switch (choreStep++) {
            case 0 -> {
                status = "Pickaxe energy full, using a sponge";
                choreWait = 4 + random.nextInt(5);
            }
            case 1 -> {
                client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(player));
                choreWait = 6 + random.nextInt(5);
            }
            case 2 -> {
                String[] ids = new String[handler.slots.size()];
                boolean[] pickaxes = new boolean[handler.slots.size()];
                for (int slot = 0; slot < ids.length; slot++) {
                    net.minecraft.item.ItemStack stack = handler.getSlot(slot).getStack();
                    ids[slot] = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
                    pickaxes[slot] = stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
                }
                int[] found = Chores.slots(ids, pickaxes);
                spongeSlot = found[0];
                pickaxeSlot = found[1];
                if (spongeSlot < 0 && pickaxeSlot >= 0 && itemSorter.on()) {
                    // No sponge here: fetch one from the vaults at spawn, then apply it.
                    player.closeHandledScreen();
                    chore = Chores.Kind.NONE;
                    needSponge = true;
                    ThePrisonsClient.LOGGER.info("[ore_macro] pickaxe energy full, no sponge in the inventory: fetching one at spawn");
                    startSort(player);
                    return;
                }
                if (spongeSlot < 0 || pickaxeSlot < 0) {
                    player.closeHandledScreen();
                    chore = Chores.Kind.NONE;
                    disableSelf(spongeSlot < 0 ? "Pickaxe energy full and no sponge in the inventory"
                            : "Pickaxe energy full and no pickaxe in the hotbar");
                    return;
                }
                choreWait = 3 + random.nextInt(4);
            }
            case 3 -> {
                click(client, handler, spongeSlot);
                choreWait = 4 + random.nextInt(4);
            }
            case 4 -> {
                click(client, handler, pickaxeSlot);
                choreWait = 4 + random.nextInt(4);
            }
            case 5 -> {
                if (handler.getCursorStack().isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                    // The server swapped instead of applying the sponge: the pickaxe goes back into its hotbar slot first.
                    click(client, handler, pickaxeSlot);
                    count("energy_swap_undone");
                }
                choreWait = 3 + random.nextInt(3);
            }
            case 6 -> {
                if (!handler.getCursorStack().isEmpty()) {
                    // Put the sponge (or what is left of it) back where it came from.
                    click(client, handler, spongeSlot);
                }
                choreWait = 4 + random.nextInt(3);
            }
            case 7 -> {
                player.closeHandledScreen();
                count("energy_extracted");
                choreWait = 6;
            }
            default -> chore = Chores.Kind.NONE;
        }
    }

    private static void click(MinecraftClient client, net.minecraft.screen.ScreenHandler handler, int slot) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.clickSlot(handler.syncId, slot, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, client.player);
        }
    }

    // ── Look (the motion itself runs every frame in the core) ────────────────

    private void look(ClientPlayerEntity player, float yaw, double @Nullable [] aheadFeet, double @Nullable [] aheadDist) {
        double[] feet = aheadFeet;
        double[] dist = aheadDist;
        if (feet == null || dist == null) {
            NavigationPath path = phase == Phase.TRAVEL || phase == Phase.ROUTE || phase == Phase.BREAK || phase == Phase.GUARD
                    ? driver.path() : null;
            int count = path == null ? 0 : Math.max(0, Math.min(10, path.size() - 1 - driver.index()));
            feet = new double[count];
            dist = new double[count];
            for (int k = 0; k < count; k++) {
                int i = driver.index() + 1 + k;
                feet[k] = path.feet()[i];
                dist[k] = Math.hypot(path.x(i) - player.getX(), path.z(i) - player.getZ());
            }
        }
        terrain.update(player.getY(), player.isOnGround(), feet, dist);
        control.rotation().follow(yaw, terrain.pitch(), YAW_OMEGA, PITCH_OMEGA);
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    private void rememberWalked(ClientPlayerEntity player) {
        long node = Pos.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        if (node == lastNode || !player.isOnGround()) {
            return;
        }
        if (lastNode != Long.MIN_VALUE && (Pos.x(node) != Pos.x(lastNode) || Pos.z(node) != Pos.z(lastNode))) {
            barrenBlocks++;
        }
        lastNode = node;
        if (walked.add(node)) {
            walkedOrder.enqueue(node);
            while (walkedOrder.size() > WALKED_MEMORY) {
                walked.remove(walkedOrder.dequeueLong());
            }
        }
    }

    private void forgetWalked() {
        walked.clear();
        walkedOrder.clear();
        lastNode = Long.MIN_VALUE;
    }

    /** Every {@value #LEARN_TICKS} ticks: the blocks per second of that stretch belong to the area the player is in. */
    private void learn(ClientPlayerEntity player, long now) {
        StatsService.Session run = session;
        if (run == null || ticks - learnStartTick < LEARN_TICKS) {
            return;
        }
        long mined = run.counter("ores").value();
        if (phase == Phase.STEER) {
            region.recordLane(LanePlanner.zoneOf(Pos.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ())),
                    (mined - learnStartOres) / (LEARN_TICKS / 20.0D), now);
        }
        learnStartOres = mined;
        learnStartTick = ticks;
    }

    private void loadRegion(MinecraftClient client) {
        region.clear();
        Path file = regionPath(client);
        regionFile = file;
        if (file == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return Files.exists(file) ? Files.readString(file) : null;
            } catch (IOException e) {
                return null;
            }
        }, Util.getIoWorkerExecutor()).thenAcceptAsync(json -> {
            if (json != null && enabled() && file.equals(regionFile)) {
                region.fromJson(json);
            }
        }, client);
    }

    private void saveRegion() {
        Path file = regionFile;
        if (file == null || !region.dirty()) {
            return;
        }
        String json = region.toJson();
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, json);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                // Learned data is a bonus; the next save tries again.
            }
        });
    }

    private static @Nullable Path regionPath(MinecraftClient client) {
        return worldFile(client, "regions");
    }

    /** config/theprisons/{@code folder}/&lt;server&gt;_&lt;dimension&gt;.json, {@code null} outside a world. */
    public static @Nullable Path worldFile(MinecraftClient client, String folder) {
        if (client.world == null) {
            return null;
        }
        String server = client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().address
                : client.getServer() != null ? "local-" + client.getServer().getSaveProperties().getLevelName() : "unknown";
        String name = (server + "_" + client.world.getRegistryKey().getValue()).replaceAll("[^A-Za-z0-9._-]", "_");
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve(folder).resolve(name + ".json");
    }

    // ── Mine: whatever floor ore is under the crosshair (after the view was applied) ──

    private void act(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld clientWorld = client.world;
        if (player == null || clientWorld == null || control.input().paused()) {
            return;
        }
        if (System.currentTimeMillis() < menuHoldUntilMs) {
            breaker.cancel(client);
            return;
        }
        if (chore != Chores.Kind.NONE || relocatedTick >= 0L) {
            // Chores (item sorter trip to /spawn and back, energy, pet, ability) and right after a warp: never mine.
            breaker.cancel(client);
            return;
        }
        if (phase == Phase.BREAK) {
            // On the way to a break the macro only walks. (The way to the route's start waypoint mines on the move.)
            breaker.cancel(client);
            return;
        }
        BlockBreaker.Aim aim = BlockBreaker.crosshair(player, reach(player));
        long aimed = aim == null ? Long.MIN_VALUE : aim.pos().asLong();
        if (aim == null || skipped.containsKey(aimed) || !isTarget(clientWorld.getBlockState(aim.pos())) || !floorOre(aimed)
                || borders.zones(client).inside(aim.pos().getX() + 0.5D, aim.pos().getY() + 1.0D, aim.pos().getZ() + 0.5D)) {
            breaker.cancel(client);
            return;
        }
        rememberSides(clientWorld, aim.pos());
        fatigueFrom = OreCatalog.packOf(clientWorld.getBlockState(aim.pos()).getBlock());
        breaker.hit(client, player, aim);
        // An ore hit counts as mined: with the server's insta-mine the ore turns into stone at once and the client's
        // own "block broken" event may never come - the stone-block count must start again here.
        barrenBlocks = 0;
        if (aimed != lastOreHit) {
            // One ore for the guard tax by energy (each block once, however many ticks it takes).
            lastOreHit = aimed;
            energyTax.ore(1, world.classifier().target(clientWorld.getBlockState(aim.pos())));
            ownHits[ownHitNext] = aimed;
            ownHitNext = (ownHitNext + 1) % ownHits.length;
        }
        if (breaker.breakingTicks() > 40) {
            // Not breaking (tool / level / server refuses it): leave it and walk on.
            skipped.put(aimed, System.currentTimeMillis() + SKIP_MS);
            count("target_failures");
            lastIssue = "ore does not break";
            breaker.cancel(client);
        }
    }

    /** A floor ore: open above and not part of a wall. */
    private boolean floorOre(long pos) {
        int x = Pos.x(pos);
        int y = Pos.y(pos);
        int z = Pos.z(pos);
        return LanePlanner.surfaceOre(world.store(), targetKeys::contains, x, y, z)
                && !LanePlanner.steep(new Walkability(world.store(), maxDrop.value()), Pos.pack(x, y + 1, z));
    }

    /** The blocks the pickaxe may take together with the hit one (left / right; which side is up to the server). */
    private void rememberSides(ClientWorld clientWorld, BlockPos pos) {
        for (BlockPos side : new BlockPos[]{pos.east(), pos.west(), pos.north(), pos.south()}) {
            if (isTarget(clientWorld.getBlockState(side))) {
                extras.put(side.asLong(), ticks + EXTRA_TICKS);
            }
        }
    }

    /** Side blocks that turned from ore into something else right after a hit were mined by it: count them. */
    private void countExtras(ClientWorld clientWorld) {
        if (extras.isEmpty()) {
            return;
        }
        extras.long2LongEntrySet().removeIf(entry -> {
            if (!isTarget(clientWorld.getBlockState(BlockPos.fromLong(entry.getLongKey())))) {
                count("ores");
                count("extra_blocks");
                return true;
            }
            return entry.getLongValue() < ticks;
        });
    }

    /**
     * An ore near the player turned into something else (stone, air): mined - by our hit (counted at the hit) or by a
     * proc (Shatter, Fracture, Splinter...). Every mined ore gives energy, so every one counts for the energy per ore.
     */
    private void onBlockChanged(CoreEvents.BlockChanged event) {
        if (!taxFromEnergy.on()) {
            return;
        }
        int type = world.classifier().target(event.previous());
        if (type == 0 || !targetKeys.contains(type) || world.classifier().target(event.state()) != 0) {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        long pos = event.pos();
        if (player == null || Math.abs(Pos.x(pos) + 0.5D - player.getX()) > PROC_RANGE
                || Math.abs(Pos.y(pos) + 0.5D - player.getY()) > PROC_RANGE || Math.abs(Pos.z(pos) + 0.5D - player.getZ()) > PROC_RANGE) {
            return;
        }
        for (long own : ownHits) {
            if (own == pos) {
                return;
            }
        }
        procOres++;
        energyTax.ore(1, type);
    }

    private void onBroken(CoreEvents.PlayerBrokeBlock event) {
        extras.remove(event.pos());
        int key = world.classifier().target(event.state());
        if (key == 0 || !targetKeys.contains(key)) {
            return;
        }
        count("ores");
        barrenBlocks = 0;
        StatsService.Session run = session;
        if (run != null) {
            run.counter("ore:" + BlockKeys.id(key).replace("minecraft:", "")).increment();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isTarget(BlockState state) {
        int key = world.classifier().target(state);
        return key != 0 && targetKeys.contains(key);
    }

    private double reach(ClientPlayerEntity player) {
        return Math.min(reachSetting.value(), player.getBlockInteractionRange() - 0.1D);
    }

    private void count(String counter) {
        StatsService.Session run = session;
        if (run != null) {
            run.counter(counter).increment();
        }
    }

    // ── Status, HUD, overlay ─────────────────────────────────────────────────

    /**
     * The pathfinder's state for the session HUD: Tunnel / Cave (free steering), To tunnel / Planned (a planned route),
     * Flight (back into the guarded zone), Route, Travel, Break, Wait; "" when off.
     */
    /** The automation's state for the session HUD ("" when off). */
    /** The current step in words ("Trip: /home tmp", "To ore (4m)" ...), empty while off. */
    public String statusText() {
        return enabled() ? status : "";
    }

    /**
     * Another feature needs the screen for a moment (the market scan opens /ah, /ee): the macro stands still and
     * does nothing until {@code untilMs} - no mining, no walking, no chores.
     */
    public void holdForMenu(long untilMs) {
        menuHoldUntilMs = Math.max(menuHoldUntilMs, untilMs);
    }

    /** Busy with something that must not be interrupted by a menu: a chore (trip, vault, warp), fleeing, outside the zone. */
    public boolean busy() {
        return enabled() && (chore != Chores.Kind.NONE || fleeing || fleeRequested || phase == Phase.GUARD
                || escapeSentMs != 0L || System.currentTimeMillis() < combatUntilMs);
    }

    private long menuHoldUntilMs;

    public String botState() {
        if (!enabled()) {
            return "";
        }
        if (fleeing || fleeRequested) {
            return "ESCAPING";
        }
        return switch (chore) {
            case SORT_ITEMS -> "SORTING LOOT";
            case REDEEM_MONEY -> "REDEEMING MONEY";
            case DEATH_RECOVERY -> "DIED: RECOVERING";
            case SELL_ALL_NOW -> "SELLING";
            case EXTRACT_ENERGY -> "EXTRACTING ENERGY";
            case USE_PET, USE_ABILITY -> "USING ITEM";
            case NONE -> switch (phase) {
                case GUARD -> "RETURNING TO GUARDS";
                case BREAK -> "BREAK";
                case WAIT -> "WAITING";
                default -> recoverTicks > 0 ? "RECOVERING" : "MINING";
            };
        };
    }

    public String pathMode() {
        if (!enabled()) {
            return "";
        }
        return switch (phase) {
            case GUARD -> "Flight";
            case ROUTE -> "Route";
            case TRAVEL -> "Travel";
            case BREAK -> "Break";
            case WAIT -> "Wait";
            case STEER -> plan != null ? ("cave".equals(planWhy) ? "To tunnel" : "Planned")
                    : !classicSteering.on() && steer.inCave() ? "Cave" : "Tunnel";
        };
    }

    @Override
    public Status status() {
        if (!enabled()) {
            String reason = lastStopReason();
            return reason == null ? Status.OFF : new Status(StatusLevel.ERROR, "Stopped: " + reason);
        }
        StatsService.Session run = session;
        long mined = run == null ? 0 : run.counter("ores").value();
        return new Status(phase == Phase.WAIT ? StatusLevel.WARNING : StatusLevel.ACTIVE, status + " · " + mined + " ores");
    }

    @Override
    public void collectHud(List<HudLine> out) {
        StatsService.Session run = session;
        if (run != null) {
            StatsService.Counter mined = run.counter("ores");
            double average = mined.value() * 1000.0D / Math.max(1000L, run.elapsedMs());
            out.add(new HudLine("BPS", String.format(Locale.ROOT, "%.2f (avg %.2f)", mined.recentPerHour() / 3600.0D, average), 0xFF00E5FF));
            out.add(new HudLine("Uptime", formatDuration(run.elapsedMs()), 0xFFFFC14D));
            out.add(new HudLine("Blocks", String.valueOf(mined.value()), 0xFF65F59B));
        }
        if (guarded.on() && enabled()) {
            ClientPlayerEntity me = MinecraftClient.getInstance().player;
            double d = me == null ? Double.POSITIVE_INFINITY : guardArea.distance(me.getX(), me.getY(), me.getZ());
            if (guardArea.scoreboard()) {
                boolean in = me != null && guardArea.inside(me.getX(), me.getY(), me.getZ());
                out.add(new HudLine("Guard", String.format(Locale.ROOT, "XP tax %s (%d known, %d edge blocks, %d guards)",
                        in ? "on" : "off", guardArea.insideBlocks(), guardArea.outsideBlocks(), guardArea.size()),
                        in ? 0xFF65F59B : 0xFFFF5C5C));
            } else {
                out.add(new HudLine("Guard", guardArea.isEmpty() ? "none found" : String.format(Locale.ROOT, "%.0fm / %d (%d known)",
                        d, guardRadius.value(), guardArea.size()), d <= guardRadius.value() ? 0xFF65F59B : 0xFFFF5C5C));
            }
        }
        if (planRoutes.on() && enabled()) {
            OrePlanner.Plan p = plan;
            out.add(new HudLine("Plan", p != null ? String.format(Locale.ROOT, "%s · waypoint %d/%d", planInfo, planIndex + 2,
                    p.waypoints().length) : planInfo.isEmpty() ? "planning" : planInfo, 0xFF4DD8FF));
            out.add(new HudLine("World", String.format(Locale.ROOT, "%d sections, %d chunks to read, %.0f %% stone around",
                    archive.sectionCount(), archive.pendingChunks(), rockShare * 100.0D), 0xFF9AA0B8));
        }
        out.add(new HudLine("Angle", String.format(Locale.ROOT, "%.0f° (%s)", terrain.pitch(),
                terrain.state().name().toLowerCase(Locale.ROOT)), 0xFFC084FC));
        Route walking = activeRoute;
        if (walking != null) {
            out.add(new HudLine("Route", walking.name() + " · " + (approaching ? "to start, via " : "") + "waypoint " + (routeIndex + 1)
                    + "/" + walking.waypoints().size(), 0xFF4DD8FF));
        } else {
            out.add(new HudLine("Region", region.size() + " areas known", 0xFF9AA0B8));
            out.add(new HudLine("Mined here", String.format(Locale.ROOT, "%.0f %% of %d ores", depletion.minedShare() * 100.0D,
                    depletion.total()), 0xFF9AA0B8));
        }
        if (wardens.length > 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            double nearest = Double.POSITIVE_INFINITY;
            if (client.player != null) {
                for (double[] w : wardens) {
                    nearest = Math.min(nearest, Math.hypot(w[0] - client.player.getX(), w[2] - client.player.getZ()));
                }
            }
            out.add(new HudLine("Wardens", String.format(Locale.ROOT, "%d (nearest %.0fm)", wardens.length, nearest), 0xFFFF5E6C));
        }
        out.add(new HudLine("State", status, 0xFFB0B8C8));
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000L;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L);
    }

    private void renderOverlay(CoreEvents.WorldRender event) {
        if (!showPath.on() && !showWaypoints.on()) {
            return;
        }
        Overlay overlay = Overlay.begin(event.context());
        MinecraftClient client = MinecraftClient.getInstance();
        if (overlay == null || client.player == null) {
            return;
        }
        Route walking = activeRoute;
        if (walking != null && showWaypoints.on() && !walking.waypoints().isEmpty()) {
            // Only the waypoint walked to and the one after it (the route is a loop: after the last comes waypoint 1).
            List<int[]> all = walking.waypoints();
            int next = Math.floorMod(routeIndex, all.size());
            int after = (next + 1) % all.size();
            boolean one = after == next;
            List<int[]> shown = one ? List.of(all.get(next)) : List.of(all.get(next), all.get(after));
            RouteRecorder.drawRoute(overlay, shown, 0, one ? new int[]{next + 1} : new int[]{next + 1, after + 1});
        }
        if (!showPath.on()) {
            return;
        }
        NavigationPath path = phase == Phase.TRAVEL || phase == Phase.ROUTE || phase == Phase.BREAK || phase == Phase.GUARD ? driver.path() : null;
        if (path != null) {
            for (int i = Math.max(0, driver.index()); i < path.size() - 1; i++) {
                overlay.line(path.x(i), path.feet()[i] + 0.1D, path.z(i), path.x(i + 1), path.feet()[i + 1] + 0.1D, path.z(i + 1),
                        0xFF4DD8FF, 2.5F);
            }
            return;
        }
        TunnelSteer.Decision d = decision;
        if (d != null && phase == Phase.STEER) {
            // The chosen direction as far as it is free.
            double rad = Math.toRadians(d.heading());
            double x = client.player.getX();
            double y = client.player.getY() + 0.1D;
            double z = client.player.getZ();
            overlay.line(x, y, z, x - Math.sin(rad) * d.free(), y, z + Math.cos(rad) * d.free(), 0xFF65F59B, 2.5F);
        }
    }
}
