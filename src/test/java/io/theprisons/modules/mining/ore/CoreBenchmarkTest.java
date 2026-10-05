package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.PathSearch;
import io.theprisons.core.nav.Pos;
import io.theprisons.core.nav.Walkability;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.core.world.SectionSnapshot;
import io.theprisons.core.world.SectionStore;
import io.theprisons.core.world.WorldSnapshot;
import io.theprisons.testing.CaveFixture;
import io.theprisons.testing.TestMine;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "After" numbers of the new core, job for job comparable to the archived {@code LegacyBaselineBenchmarkTest}
 * (same cave fixture: seed 42, 900 veins). Written to {@code build/benchmarks/core-after.txt}.
 */
class CoreBenchmarkTest {
    private static final int REDSTONE = BlockKeys.key(TestMine.REDSTONE_ORE);

    @Test
    void after() throws IOException {
        TestMine mine = CaveFixture.cave(42L, 900);
        SectionStore store = mine.store();
        long start = CaveFixture.start(mine);
        WorldSnapshot world = store.snapshot(Pos.x(start), Pos.z(start), 200, 0, 63);
        Walkability walk = new Walkability(world, 3);

        PathSearch.Flood reach = PathSearch.flood(walk, start, 200_000, 128, Double.POSITIVE_INFINITY, 0L, () -> false);
        long far = start;
        double[] farCost = {0};
        long[] farNode = {start};
        reach.forEachReached((pos, cost, feet) -> {
            if (cost > farCost[0]) {
                farCost[0] = cost;
                farNode[0] = pos;
            }
        });
        far = farNode[0];
        long goal = far;

        double astar = time(() -> assertTrue(PathSearch.findPath(new Walkability(world.copyView(), 3), start, LongList.of(goal), 200_000, 128, 0L, () -> false).found()));
        double flood = time(() -> PathSearch.flood(new Walkability(world.copyView(), 3), start, 60_000, 48, Double.POSITIVE_INFINITY, 0L, () -> false));

        // Client thread part of one re-plan: the snapshot (reference copy) and the request.
        double clientPlan = time(() -> store.snapshot(Pos.x(start), Pos.z(start), 56, Pos.y(start) - 48, Pos.y(start) + 48));
        // Ore Macro decision on the worker (lanes in 8 directions, tunnel centre, parallel lanes, scanner fallback).
        double workerPlan = time(() -> LanePlanner.plan(store.snapshot(Pos.x(start), Pos.z(start), 56, Pos.y(start) - 48, Pos.y(start) + 48),
                key -> key == REDSTONE, start, 0.0F, LongSet.of(), LanePlanner.Params.DEFAULT, () -> false));
        double workerZonePlan = workerPlan;

        // Scanner ingest: re-putting every section (a full rescan) incl. ore index maintenance.
        List<SectionSnapshot> sections = new ArrayList<>();
        store.sections().forEach(sections::add);
        double ingest = time(() -> {
            for (SectionSnapshot section : sections) {
                store.put(new SectionSnapshot(section.sx(), section.sy(), section.sz(),
                        section.cells() == null ? null : section.cells().clone(), section.ores() == null ? null : section.ores().clone(),
                        section.oreSlots(), section.scannedAtMs() + 1));
            }
        }) / sections.size();

        double inReach = 0.0D;

        String line = String.format(Locale.ROOT,
                "[after] ores=%d sections=%d | A* far goal (%.0f cost): %.2f ms | flood r48: %.2f ms | client re-plan: %.3f ms | "
                        + "worker lane plan: %.2f ms (%.2f) | section ingest (client): %.3f ms/section | (%.3f)",
                store.ores().size(), sections.size(), farCost[0], astar, flood, clientPlan, workerPlan, workerZonePlan, ingest, inReach);
        System.out.println(line);
        Path out = Path.of("build", "benchmarks", "core-after.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, line + System.lineSeparator());
        assertTrue(astar < 2_000 && flood < 2_000 && workerPlan < 5_000, "worker jobs got drastically slower");
    }

    private static Long2DoubleOpenHashMap ones() {
        Long2DoubleOpenHashMap map = new Long2DoubleOpenHashMap();
        map.defaultReturnValue(1.0D);
        return map;
    }

    /** Median of 7 runs after 3 warm-ups, in ms. */
    static double time(Runnable task) {
        for (int i = 0; i < 3; i++) {
            task.run();
        }
        double[] runs = new double[7];
        for (int i = 0; i < runs.length; i++) {
            long begin = System.nanoTime();
            task.run();
            runs[i] = (System.nanoTime() - begin) / 1_000_000.0D;
        }
        Arrays.sort(runs);
        return runs[3];
    }
}
