package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.core.nav.NavigationPath;
import com.freelocs.theprisons.core.nav.PathSearch;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.nav.Walkability;
import com.freelocs.theprisons.core.world.ArchiveView;
import com.freelocs.theprisons.testing.RealCave;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays of the real gold mine (game 2026-10-05): the macro left the zone at 00:14 and was stuck below a guard at
 * (641, 80, 187) until it was killed at 01:06. See {@link RealCave}.
 */
class GuardReturnReplayTest {
    private static final double[] GUARD = {639.5D, 93.0D, 191.5D};
    private static ArchiveView world;

    @BeforeAll
    static void load() {
        world = RealCave.world();
    }

    private static Walkability walk() {
        return new Walkability(world, 32, true, new Long2DoubleOpenHashMap());
    }

    @Test
    void theStuckSpotIsOutsideAndTheColumnAboveIsInside() {
        GuardArea area = RealCave.area(641, 80, 187);
        assertTrue(area.scoreboard());
        assertFalse(area.guardedNode(Pos.pack(641, 80, 187), Double.NaN, Double.NaN, Double.NaN), "learned untaxed");
        assertTrue(area.guardedNode(Pos.pack(638, 93, 181), Double.NaN, Double.NaN, Double.NaN), "learned taxed, 13 up");
    }

    @Test
    void groundFarBelowAGuardIsNeverGuardedByTheModel() {
        // The air pocket the macro was stuck in: below the guard's zone height, but within 15 blocks of it (3D) - the
        // old sphere took it for guarded, so the way "back in" led through it.
        GuardArea area = RealCave.area(641, 80, 187);
        Walkability walk = walk();
        long start = walk.settle(641.5D, 80.0D, 187.5D);
        PathSearch.Flood flood = PathSearch.flood(walk, start, 4_000, 12, Double.POSITIVE_INFINITY, 0L, () -> false);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        long[] zone0 = RealCave.zone()[0];
        it.unimi.dsi.fastutil.longs.LongOpenHashSet taxed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(zone0);
        for (int x = 630; x <= 652; x++) {
            for (int z = 178; z <= 200; z++) {
                for (int y = 76; y <= 86; y++) {
                    long key = Pos.pack(x, y, z);
                    if (!flood.reachable(key) || taxed.contains(key)) {
                        continue;
                    }
                    double dx = x + 0.5D - GUARD[0];
                    double dy = y - GUARD[1];
                    double dz = z + 0.5D - GUARD[2];
                    if (dy >= -GuardArea.GUARD_BELOW || dx * dx + dy * dy + dz * dz > 14.0D * 14.0D) {
                        continue;
                    }
                    checked++;
                    if (area.guardedNode(key, Double.NaN, Double.NaN, Double.NaN)) {
                        wrong.add(x + "," + y + "," + z);
                    }
                }
            }
        }
        assertTrue(checked > 0, "the pocket below the guard is in the replay");
        assertTrue(wrong.isEmpty(), checked + " pocket blocks checked, guarded by the model: " + wrong);
    }

    @Test
    void theWayBackLeadsTheShortWalkOntoTaxedGroundNotUpTheCeiling() {
        GuardArea area = RealCave.area(641, 80, 187);
        Walkability walk = walk();
        long start = walk.settle(641.5D, 80.0D, 187.5D);
        assertTrue(start != Long.MIN_VALUE, "the stuck spot is standable");
        GuardReturn.Result result = GuardReturn.plan(walk, start, n -> area.safeNode(n, true), 60_000, 96, () -> false);
        assertTrue(result.found(), result.reason());
        NavigationPath path = result.path();
        long end = path.goal();
        assertTrue(path.size() <= 40, "a short walk (game: 24 steps), was " + path.size() + " to " + Pos.toString(end));
        assertTrue(area.safeNode(end, true), "ends well inside on taxed ground");
        // Every node of the way is really walked: the end is not a block above the player's head.
        assertTrue(Math.abs(Pos.y(end) - 80) <= 6, "no climb through the ceiling: " + Pos.toString(end));
    }
}
