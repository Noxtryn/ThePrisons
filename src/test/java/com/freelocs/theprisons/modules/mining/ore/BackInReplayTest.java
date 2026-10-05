package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.Walkability;
import com.freelocs.theprisons.core.world.ArchiveView;
import com.freelocs.theprisons.core.world.BlockKeys;
import com.freelocs.theprisons.testing.RealCave;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The way back in after an excursion, replayed where the game's went wrong (2026-10-05 00:13:58, 8 unguarded blocks
 * walked at (643, 78, 195)): its routes led on through 17, 19 and 30 unguarded blocks before the zone. The way back
 * in must reach guarded ground within the rest of the budget - never deeper out first.
 */
class BackInReplayTest {
    private static final double PX = 643.5D;
    private static final double PY = 78.0D;
    private static final double PZ = 195.5D;

    private static OrePlanner.Plan plan(ArchiveView world, GuardArea area, Predicate<long[]> allowed) {
        IntOpenHashSet gold = new IntOpenHashSet();
        for (String id : new String[]{"minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:gold_block"}) {
            gold.add(BlockKeys.key(id));
        }
        Walkability walk = new Walkability(world, 32, true,
                area.penalties(new Long2DoubleOpenHashMap(), PX, PY, PZ)).climbCost(OrePlanner.CLIMB_COST);
        long start = walk.settle(PX, PY, PZ);
        assertTrue(start != Long.MIN_VALUE);
        return OrePlanner.backIn(world, gold::contains, walk, start, 160.0F, n -> area.deepNode(n, OreMacroModule.BACK_IN_DEPTH),
                p -> 1.0D, OrePlanner.Params.DEFAULT, allowed, () -> false);
    }

    @Test
    void theWayBackInNeverLeadsDeeperOutFirst() {
        ArchiveView world = RealCave.world();
        GuardArea area = RealCave.area(643, 78, 195);
        area.outsideBudget(8);
        // The old rule (only the longest unguarded stretch counted): a way that first goes 15 blocks further out.
        OrePlanner.Plan old = plan(world, area,
                nodes -> area.longestOutside(nodes, PX, PY, PZ) <= 8 + OreMacroModule.BACK_IN_SLACK);
        assertTrue(old.found() && area.leadingOutside(old.nodes()) > OreMacroModule.BACK_IN_SLACK,
                "the game's fault is in the replay: " + old.reason());

        int rest = OreMacroModule.backInLead(8, 8);
        OrePlanner.Plan plan = plan(world, area, OreMacroModule.backInRule(area, PX, PY, PZ, rest));
        if (plan.found()) {
            int lead = area.leadingOutside(plan.nodes());
            assertTrue(lead <= rest, "unguarded blocks before the zone: " + lead + " (rest " + rest + "), " + plan.reason());
        }
        // Nothing over ore within the rest: the plain way back, the shortest walk onto taxed ground.
        Walkability plain = new Walkability(world, 32, true, new Long2DoubleOpenHashMap());
        GuardReturn.Result back = GuardReturn.plan(plain, plain.settle(PX, PY, PZ), n -> area.safeNode(n, true), 60_000, 96,
                () -> false);
        assertTrue(back.found() && back.path().size() <= 40, back.reason());
    }
}
