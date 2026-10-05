package com.freelocs.theprisons.gametest;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.analytics.StatsService;
import com.freelocs.theprisons.core.hud.HudLine;
import com.freelocs.theprisons.core.hud.HudService;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.profiling.Profiler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import java.util.Random;

/**
 * In-game test of the Ore Macro in the layout of the prison server: an ore cave with an <b>uneven floor</b>
 * (terraces one block apart) and redstone ore / redstone blocks on the floor, the walls and the ceiling. Like the
 * server plugin (CosmicMining), a mined ore <b>turns into stone at once and respawns as ore after 5 s</b> - nothing
 * disappears. The macro must walk straight lanes over the floor and mine only floor ores.
 *
 * <p>If {@code src/gametest/resources/data/theprisons-gametest/structure/redstone_mine.nbt} exists (a structure saved
 * with a structure block on the local server), it is placed instead of the generated cave.
 *
 * <p>Checks: many ores mined in the run, not a single wall / ceiling ore mined (checked server-side at the moment of
 * the break), the macro still running, no core tick above 50 ms. Logs ores per minute, lanes and travels.
 */
public final class RedstoneCaveClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/GameTest/Cave");
    private static final Identifier STRUCTURE = Identifier.of("theprisons-gametest", "redstone_mine");
    private static final BlockPos ORIGIN = new BlockPos(800, 90, 800);
    private static final int SIZE = 40;
    private static final int ROOM_MIN = 3;
    private static final int ROOM_MAX = 37;
    private static final int BASE = 3;
    private static final int CEILING = BASE + 9;
    private static final int RESPAWN_TICKS = 100;

    /** Server-side plugin stand-in: broken ores become stone, respawn later; breaks of non-floor ores are counted. */
    private static final Map<BlockPos, BlockState> RESPAWNS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> RESPAWN_AT = new ConcurrentHashMap<>();
    private static final AtomicInteger NON_FLOOR_BREAKS = new AtomicInteger();
    private static final AtomicInteger SERVER_BREAKS = new AtomicInteger();
    private static final AtomicInteger EXTRA_BREAKS = new AtomicInteger();
    private static boolean hooked;
    private static final StringBuilder TRACE = new StringBuilder();
    private static final Map<String, Integer> STANDING = new java.util.TreeMap<>();
    private static final Map<String, Integer> PITCH = new java.util.TreeMap<>();
    /** The stand-in only acts during this test (later tests expect vanilla breaking). */
    private static volatile boolean active;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ShowcaseClientGameTest.ENABLED || ShowcaseClientGameTest.MARKET || ShowcaseClientGameTest.TUNNEL) {
            return; // the showcase run shows only the showcase
        }
        hookServer();
        active = true;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            BlockPos[] spawn = new BlockPos[1];
            boolean[] custom = new boolean[1];
            int[] ores = new int[1];
            server.runOnServer(minecraft -> {
                ServerWorld world = minecraft.getOverworld();
                Optional<StructureTemplate> template = minecraft.getStructureTemplateManager().getTemplate(STRUCTURE);
                if (template.isPresent()) {
                    custom[0] = true;
                    spawn[0] = placeStructure(world, template.get());
                } else {
                    ores[0] = buildCave(world);
                    int x = ROOM_MIN + 2;
                    int z = ROOM_MIN + 2;
                    spawn[0] = ORIGIN.add(x, floor(x, z) + 1, z);
                }
            });
            server.runCommand("gamemode survival @a");
            server.runCommand("effect give @a minecraft:haste infinite 255 true");
            server.runCommand("effect give @a minecraft:resistance infinite 255 true");
            server.runCommand("effect give @a minecraft:saturation infinite 255 true");
            server.runCommand("effect give @a minecraft:night_vision infinite 0 true");
            server.runCommand("give @a minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5}]");
            server.runCommand(String.format(Locale.ROOT, "tp @a %d %d %d -45 35", spawn[0].getX(), spawn[0].getY(), spawn[0].getZ()));
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(60);
            LOGGER.info("[cave] {} | spawn {} | {} ores", custom[0] ? "structure " + STRUCTURE : "generated cave", spawn[0].toShortString(), ores[0]);
            NON_FLOOR_BREAKS.set(0);
            SERVER_BREAKS.set(0);
            EXTRA_BREAKS.set(0);

            // A warden (guard NPC with 1000 HP) in the middle of the cave: the macro must keep 15 blocks away.
            if (!custom[0]) {
                server.runCommand(String.format(Locale.ROOT,
                        "summon iron_golem %d %d %d {CustomName:\"1000\u2764 Warden\",CustomNameVisible:1b,NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}",
                        ORIGIN.getX() + WARDEN_X, ORIGIN.getY() + floor(WARDEN_X, WARDEN_Z) + 1, ORIGIN.getZ() + WARDEN_Z));
            }
            selectPackages(context, java.util.Set.of("redstone", "deepslate_redstone"));
            context.runOnClient(client -> {
                ThePrisonsCore core = ThePrisonsCore.get();
                Module macro = core.modules().get("ore_macro");
                if (macro == null || !core.modules().enable(macro)) {
                    throw new AssertionError("Floor Macro did not start: " + (macro == null ? "missing" : macro.lastStopReason()));
                }
            });

            int seconds = 120;
            // Per tick: wall contact, sideways keys, and left-right wobble of the view (direction reversals of the yaw).
            double[] m = new double[10];
            m[9] = Double.POSITIVE_INFINITY; // collisions, strafe ticks, reversals, last yaw, last delta sign, ticks, standing
            m[3] = Double.NaN;
            for (int tick = 1; tick <= seconds * 20; tick++) {
                context.waitTick();
                context.runOnClient(client -> {
                    if (client.player == null) {
                        return;
                    }
                    m[5]++;
                    if (!custom[0]) {
                        m[9] = Math.min(m[9], Math.hypot(client.player.getX() - (ORIGIN.getX() + WARDEN_X + 0.5D),
                                client.player.getZ() - (ORIGIN.getZ() + WARDEN_Z + 0.5D)));
                    }
                    if (client.player.horizontalCollision) {
                        m[0]++;
                    }
                    if (client.player.getVelocity().horizontalLength() < 0.03D) {
                        m[6]++;
                        Module macro = ThePrisonsCore.get().modules().get("ore_macro");
                        String state = macro == null ? "-" : macro.status().text().replaceAll("[ (·].*", "");
                        String key = state + (client.player.horizontalCollision ? "+wall" : "")
                                + (client.options.forwardKey.isPressed() ? "+W" : "");
                        STANDING.merge(key, 1, Integer::sum);
                    }
                    if (client.options.leftKey.isPressed() || client.options.rightKey.isPressed()) {
                        m[1]++;
                    }
                    if (client.options.backKey.isPressed()) {
                        m[7]++;
                    }
                    float p = client.player.getPitch();
                    String bucket = Math.abs(p - 50) < 2 ? "50" : Math.abs(p - 68) < 2 ? "68" : Math.abs(p - 82) < 2 ? "82" : "moving";
                    PITCH.merge(bucket, 1, Integer::sum);
                    double yaw = client.player.getYaw();
                    if (!Double.isNaN(m[3])) {
                        double delta = yaw - m[3];
                        m[8] += Math.abs(delta);
                        if (Math.abs(delta) > 0.3D) {
                            double sign = Math.signum(delta);
                            if (m[4] != 0.0D && sign != m[4]) {
                                m[2]++;
                            }
                            m[4] = sign;
                        }
                    }
                    if (m[5] >= 400 && m[5] < 600 && !Double.isNaN(m[3])) {
                        TRACE.append(String.format(Locale.ROOT, "%.0f%s ", client.player.getPitch(), client.player.isOnGround() ? "" : "^"));
                    }
                    m[3] = yaw;
                });
                if (tick % 100 == 0) {
                    LOGGER.info("[cave] {}s | {}", tick / 20, context.<String, RuntimeException>computeOnClient(client -> {
                        Module macro = ThePrisonsCore.get().modules().get("ore_macro");
                        return (macro == null ? "-" : macro.status().text()) + " | pos " + client.player.getBlockPos().subtract(ORIGIN).toShortString()
                                + String.format(Locale.ROOT, " | yaw %.0f pitch %.0f", client.player.getYaw(), client.player.getPitch());
                    }));
                    if (!context.computeOnClient(client -> macroEnabled())) {
                        break;
                    }
                }
            }
            LOGGER.info("[cave] pitch per tick, ticks 400-600 (^ = in the air): {}", TRACE);
            LOGGER.info("[cave] standing still by state: {}", STANDING);
            LOGGER.info("[cave] view pitch (ticks at 50° flat / 68° up / 80° down, or between): {}", PITCH);
            LOGGER.info("[cave] movement: standing still {} of {} ticks, backwards {}, wall contact {}, sideways keys {}, view direction reversals {} ({} per minute), turned {}° per minute",
                    (int) m[6], (int) m[5], (int) m[7], (int) m[0], (int) m[1], (int) m[2], String.format(Locale.ROOT, "%.1f", m[2] * 60.0D / seconds), (int) (m[8] * 60.0D / seconds));
            String stopReason = context.computeOnClient(client -> {
                Module macro = ThePrisonsCore.get().modules().get("ore_macro");
                return macro == null || macro.enabled() ? null : String.valueOf(macro.lastStopReason());
            });
            String stats = context.computeOnClient(client -> {
                StatsService.Session session = ThePrisonsCore.get().stats().session("ore_macro");
                return session == null ? "no session" : session.summary().toString();
            });
            int broken = SERVER_BREAKS.get() + EXTRA_BREAKS.get();
            LOGGER.info("[cave] {} ores in {} s ({} hits + {} side blocks of the pickaxe, {} per minute, {} BPS), non-floor hits {}, stop reason {}, stats {}",
                    broken, seconds, SERVER_BREAKS.get(), EXTRA_BREAKS.get(), broken * 60 / seconds,
                    String.format(Locale.ROOT, "%.2f", broken / (double) seconds), NON_FLOOR_BREAKS.get(), stopReason, stats);
            context.runOnClient(client -> {
                for (HudService.Block block : ThePrisonsCore.get().hud().blocks()) {
                    for (HudLine line : block.lines()) {
                        LOGGER.info("[cave] HUD {}: {}", line.label(), line.value());
                    }
                }
                for (Profiler.Section section : ThePrisonsCore.get().profiler().sections()) {
                    if (section.calls() > 0) {
                        LOGGER.info("[cave] {}", Profiler.format(section));
                    }
                }
            });
            if (stopReason != null) {
                throw new AssertionError("Floor Macro stopped by itself: " + stopReason);
            }
            LOGGER.info("[cave] closest to the warden: {} blocks", String.format(Locale.ROOT, "%.1f", m[9]));
            if (!custom[0] && m[9] < 13.5D) {
                throw new AssertionError("Came within " + m[9] + " blocks of the warden");
            }
            if (m[0] > m[5] * 0.1D) {
                throw new AssertionError("Ran into walls in " + (int) m[0] + " of " + (int) m[5] + " ticks");
            }
            if (NON_FLOOR_BREAKS.get() > 0) {
                throw new AssertionError("Mined " + NON_FLOOR_BREAKS.get() + " wall / ceiling ores");
            }
            // The view is fixed (along the walk, tilted down): only the ores the crosshair passes over are mined.
            if (!custom[0] && broken < 150) {
                throw new AssertionError("Only " + broken + " ores in " + seconds + " s");
            }
            double tickMax = context.computeOnClient(client -> ThePrisonsCore.get().profiler().section("core:tick").maxMs());
            if (tickMax > 50.0D) {
                throw new AssertionError("A client tick of the core took " + tickMax + " ms");
            }

            // ── Another mine: the same cave with lapis ore / lapis blocks, no restart (all ore packages are on) ──
            if (!custom[0]) {
                server.runOnServer(minecraft -> {
                    RESPAWNS.clear();
                    RESPAWN_AT.clear();
                    buildCave(minecraft.getOverworld(), new Block[]{Blocks.LAPIS_ORE, Blocks.LAPIS_BLOCK});
                });
                selectPackages(context, java.util.Set.of("lapis", "deepslate_lapis"));
                int before = SERVER_BREAKS.get() + EXTRA_BREAKS.get();
                int nonFloorBefore = NON_FLOOR_BREAKS.get();
                context.waitTicks(20 * 60);
                int lapis = SERVER_BREAKS.get() + EXTRA_BREAKS.get() - before;
                LOGGER.info("[cave] lapis mine: {} ores in 60 s ({} BPS), non-floor hits {}", lapis,
                        String.format(Locale.ROOT, "%.2f", lapis / 60.0D), NON_FLOOR_BREAKS.get() - nonFloorBefore);
                if (lapis < 60 || NON_FLOOR_BREAKS.get() > nonFloorBefore) {
                    throw new AssertionError("Lapis mine: " + lapis + " ores, " + (NON_FLOOR_BREAKS.get() - nonFloorBefore) + " wall hits");
                }
            }

            // ── Chores: server messages ─────────────────────────────────────────
            server.runCommand("give @a minecraft:sponge 3");
            context.waitTicks(10);
            int[] before = context.computeOnClient(client -> {
                var inv = client.player.getInventory();
                int sponge = -1;
                int pickaxe = -1;
                for (int i = 0; i < 36; i++) {
                    if (sponge < 0 && inv.getStack(i).isOf(net.minecraft.item.Items.SPONGE)) {
                        sponge = i;
                    }
                }
                for (int i = 0; i < 9; i++) {
                    if (pickaxe < 0 && inv.getStack(i).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) {
                        pickaxe = i;
                    }
                }
                return new int[]{sponge, pickaxe};
            });
            server.runCommand("tellraw @a \"[!] Your pickaxe energy is full! Please /extract or level it up to continue mining.\"");
            context.waitTicks(80);
            boolean restored = context.computeOnClient(client -> {
                var inv = client.player.getInventory();
                return inv.getStack(before[0]).isOf(net.minecraft.item.Items.SPONGE)
                        && inv.getStack(before[1]).isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)
                        && client.player.playerScreenHandler.getCursorStack().isEmpty() && client.currentScreen == null;
            });
            server.runCommand("tellraw @a \"[!] Your Ore Satchel is now full!\"");
            context.waitTicks(100);
            long sold = context.computeOnClient(client -> ThePrisonsCore.get().stats().session("ore_macro").counter("sellall").value());
            long extracted = context.computeOnClient(client -> ThePrisonsCore.get().stats().session("ore_macro").counter("energy_extracted").value());
            LOGGER.info("[cave] chores: energy extracted {} (sponge slot {}, pickaxe slot {}, restored {}), sellall {}, macro running {}",
                    extracted, before[0], before[1], restored, sold, context.computeOnClient(client -> macroEnabled()));
            if (extracted != 1 || !restored || sold != 1 || !context.computeOnClient(client -> macroEnabled())) {
                throw new AssertionError("Chores failed: extracted " + extracted + ", restored " + restored + ", sellall " + sold);
            }

            context.runOnClient(client -> ThePrisonsCore.get().modules().disable(ThePrisonsCore.get().modules().get("ore_macro")));
            context.waitTicks(2);
            boolean released = context.computeOnClient(client -> !client.options.forwardKey.isPressed()
                    && !client.options.jumpKey.isPressed() && !ThePrisonsCore.get().control().automating());
            if (!released) {
                throw new AssertionError("Keys still pressed after the macro stopped");
            }
            LOGGER.info("[cave] PASSED");
        } finally {
            active = false;
        }
    }

    private static void hookServer() {
        if (hooked) {
            return;
        }
        hooked = true;
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!active || !isTarget(state)) {
                return;
            }
            SERVER_BREAKS.incrementAndGet();
            if (!world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
                NON_FLOOR_BREAKS.incrementAndGet();
            }
            BlockPos key = pos.toImmutable();
            world.setBlockState(key, Blocks.STONE.getDefaultState());
            RESPAWNS.put(key, state.getBlock().getDefaultState());
            RESPAWN_AT.put(key, world.getTime() + RESPAWN_TICKS);
            // The prison pickaxe also takes the block left and right of the hit one (seen from the player).
            Direction facing = player.getHorizontalFacing();
            for (BlockPos side : new BlockPos[]{key.offset(facing.rotateYClockwise()), key.offset(facing.rotateYCounterclockwise())}) {
                BlockState sideState = world.getBlockState(side);
                if (isTarget(sideState)) {
                    EXTRA_BREAKS.incrementAndGet();
                    world.setBlockState(side, Blocks.STONE.getDefaultState());
                    RESPAWNS.put(side, sideState.getBlock().getDefaultState());
                    RESPAWN_AT.put(side, world.getTime() + RESPAWN_TICKS);
                }
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (!active) {
                return;
            }
            long now = world.getTime();
            RESPAWN_AT.entrySet().removeIf(entry -> {
                if (entry.getValue() > now) {
                    return false;
                }
                BlockState ore = RESPAWNS.remove(entry.getKey());
                if (ore != null && world.getBlockState(entry.getKey()).isOf(Blocks.STONE)) {
                    world.setBlockState(entry.getKey(), ore);
                }
                return true;
            });
        });
    }

    private static final int WARDEN_X = 22;
    private static final int WARDEN_Z = 20;

    private static void selectPackages(ClientGameTestContext context, java.util.Set<String> packs) {
        context.runOnClient(client -> ((com.freelocs.theprisons.core.setting.Settings.MultiChoiceSetting)
                ThePrisonsCore.get().modules().get("ore_macro").setting("ore_packs")).set(packs));
    }

    private static boolean macroEnabled() {
        Module macro = ThePrisonsCore.get().modules().get("ore_macro");
        return macro != null && macro.enabled();
    }

    private static boolean isTarget(BlockState state) {
        return state.isOf(Blocks.REDSTONE_ORE) || state.isOf(Blocks.REDSTONE_BLOCK)
                || state.isOf(Blocks.LAPIS_ORE) || state.isOf(Blocks.LAPIS_BLOCK);
    }

    /** Floor top (relative y) per column: terraces one block apart, like an uneven cave floor. */
    private static int floor(int x, int z) {
        int wave = (x / 7 + z / 9) % 4;
        return BASE + new int[]{0, 1, 2, 1}[wave];
    }

    /**
     * Stone shell, air above the terraced floor up to the ceiling. Floor top blocks are ore with 55 % chance (ore /
     * block mixed), walls and ceiling carry ore too (must never be mined).
     */
    private static int buildCave(ServerWorld world) {
        return buildCave(world, new Block[]{Blocks.REDSTONE_ORE, Blocks.REDSTONE_BLOCK});
    }

    private static int buildCave(ServerWorld world, Block[] kinds) {
        Random random = new Random(3);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int ores = 0;
        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                // Two inner walls split the hall into tunnels (5-8 wide) with a junction; they carry ore too.
                boolean innerWall = x >= 17 && x <= 19 && z <= 28 || x >= 8 && x <= 10 && z >= 13 || x >= 27 && x <= 29 && z >= 12;
                boolean room = x >= ROOM_MIN && x <= ROOM_MAX && z >= ROOM_MIN && z <= ROOM_MAX && !innerWall;
                int top = room ? floor(x, z) : CEILING + 2;
                for (int y = 0; y <= CEILING + 2; y++) {
                    BlockState state;
                    if (y == 0) {
                        state = Blocks.BEDROCK.getDefaultState();
                    } else if (room && y > top && y < CEILING) {
                        state = Blocks.AIR.getDefaultState();
                    } else {
                        boolean face = room && y == top || room && y == CEILING || !room && y > BASE && y < CEILING && touchesRoom(x, z);
                        boolean ore = face && random.nextDouble() < (y == top ? 0.55D : 0.4D);
                        state = ore ? kinds[random.nextInt(2)].getDefaultState() : Blocks.STONE.getDefaultState();
                        ores += ore ? 1 : 0;
                    }
                    world.setBlockState(pos.set(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z), state, Block.NOTIFY_LISTENERS);
                }
            }
        }
        return ores;
    }

    private static boolean touchesRoom(int x, int z) {
        return x >= ROOM_MIN - 1 && x <= ROOM_MAX + 1 && z >= ROOM_MIN - 1 && z <= ROOM_MAX + 1;
    }

    /** Places the saved structure and returns a spawn point on its floor near the centre. */
    private static BlockPos placeStructure(ServerWorld world, StructureTemplate template) {
        template.place(world, ORIGIN, ORIGIN, new StructurePlacementData(), world.getRandom(), Block.NOTIFY_LISTENERS);
        Vec3i size = template.getSize();
        int cx = ORIGIN.getX() + size.getX() / 2;
        int cz = ORIGIN.getZ() + size.getZ() / 2;
        for (int y = ORIGIN.getY() + size.getY() - 2; y > ORIGIN.getY(); y--) {
            BlockPos feet = new BlockPos(cx, y, cz);
            if (world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir() && !world.getBlockState(feet.down()).isAir()) {
                return feet;
            }
        }
        return ORIGIN.add(size.getX() / 2, size.getY(), size.getZ() / 2);
    }
}
