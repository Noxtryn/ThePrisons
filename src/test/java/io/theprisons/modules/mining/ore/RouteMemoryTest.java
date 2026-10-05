package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMemoryTest {
    private static long[] line(int fromX, int toX) {
        long[] nodes = new long[toX - fromX + 1];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = Pos.pack(fromX + i, 64, 0);
        }
        return nodes;
    }

    /** East 40 blocks, then north 30: an L. */
    private static long[] corner() {
        long[] nodes = new long[71];
        for (int i = 0; i <= 40; i++) {
            nodes[i] = Pos.pack(i, 64, 0);
        }
        for (int i = 1; i <= 30; i++) {
            nodes[40 + i] = Pos.pack(40, 64, -i);
        }
        return nodes;
    }

    @Test
    void aStraightTunnelIsTwoWaypointsAndABendOneMore() {
        assertEquals(2, RoutePath.compress(line(0, 100)).length);
        int[][] wp = RoutePath.compress(corner());
        assertEquals(3, wp.length);
        assertArrayEquals(new int[]{40, 64, 0}, wp[1]);
        assertEquals(71, RoutePath.expand(wp).length, "expanded back to one node per block");
        // A stair in a straight line keeps its top.
        long[] stair = new long[30];
        for (int i = 0; i < 30; i++) {
            stair[i] = Pos.pack(i, i < 10 ? 64 : i < 14 ? 64 + (i - 9) : 68, 0);
        }
        assertTrue(RoutePath.compress(stair).length >= 3, "the height change is kept");
    }

    @Test
    void sameRouteIsMergedAndTheFileIsSmall(@TempDir Path dir) throws Exception {
        RouteMemory memory = new RouteMemory();
        memory.record("plan", "Redstone Mine", corner(), 3.0D, 20, 70.0D, 0L);
        memory.record("plan", "Redstone Mine", corner(), 4.0D, 30, 70.0D, 1_000L);
        assertEquals(1, memory.size());
        RouteMemory.Route r = memory.snapshot().get(0);
        assertEquals(2, r.runs);
        assertEquals(25, r.ores);
        assertEquals(3.5D, r.widthRadius, 1e-9);
        String json = RouteCodec.encode(memory.snapshot());
        assertTrue(json.contains("\"mine_zone\":\"Redstone Mine\"") && json.contains("\"waypoints\":[[0,64,0],[40,64,0],[40,64,-30]]"), json);
        assertTrue(json.length() < 400, "compressed: " + json.length() + " chars");
        List<RouteMemory.Route> back = RouteCodec.decode(json);
        assertEquals(1, back.size());
        assertEquals(r.id, back.get(0).id);
        assertEquals(25.0D / 70.0D * 100.0D, back.get(0).efficiency(), 0.1D);
    }

    @Test
    void anOldFileWithEveryNodeIsCompressedOnLoading() {
        String v1 = "{\"version\":1,\"routes\":[{\"kind\":\"plan\",\"start\":[0,64,0],\"end\":[40,64,0],"
                + "\"line\":[[0,64,0],[10,64,0],[20,64,0],[30,64,0],[40,64,0]],\"ores\":10,\"blocks\":40.0,\"runs\":1,\"lastRunMs\":5}]}";
        List<RouteMemory.Route> routes = RouteCodec.decode(v1);
        assertEquals(1, routes.size());
        assertEquals(2, routes.get(0).waypoints.length);
        assertEquals(5L, routes.get(0).lastRunMs);
    }

    @Test
    void savesAndLoadsInTheBackgroundOnlyTheNewestSnapshot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.json");
        RouteIo io = new RouteIo();
        RouteMemory memory = new RouteMemory();
        for (int k = 0; k < 20; k++) {
            memory.record("plan", "Mine", line(k * 100, k * 100 + 40), 3.0D, 20, 40.0D, 0L);
            io.save(file, memory.snapshot());
        }
        assertTrue(io.flush(5_000L));
        assertEquals(20, RouteCodec.decode(Files.readString(file)).size(), "the last snapshot is on disk");
        AtomicReference<List<RouteMemory.Route>> loaded = new AtomicReference<>();
        io.load(file, Runnable::run, loaded::set);
        assertTrue(io.flush(5_000L));
        RouteMemory fresh = new RouteMemory();
        fresh.replaceAll(loaded.get());
        assertEquals(20, fresh.size());
    }

    @Test
    void onlyRoutesWhoseBoxHoldsThePlayerAreLookedAt() {
        RouteMemory memory = new RouteMemory();
        memory.record("plan", "Mine", corner(), 3.0D, 30, 70.0D, 0L);
        memory.record("plan", "Mine", line(1000, 1040), 3.0D, 2, 40.0D, 0L);
        long rested = RouteMemory.REST_MS + 1L;
        assertNull(memory.best(10.5D, 64, 0.5D, 1_000L, r -> true), "walked just now: its ores respawn first");
        RouteMemory.Match m = memory.best(10.5D, 64, 2.5D, rested, r -> true);
        assertNotNull(m, "standing on its line: the rest of it");
        assertEquals(10, Pos.x(m.nodes()[0]));
        assertNull(memory.best(10.5D, 64, 0.5D, rested, r -> false), "not guarded any more");
        assertNull(memory.best(10.5D, 64, 40.5D, rested, r -> true), "outside its box");
        assertNull(memory.best(10.5D, 80, 0.5D, rested, r -> true), "another level");
        assertNull(memory.best(1010.5D, 64, 0.5D, rested, r -> true), "0.05 ores per block: not worth it");
        assertTrue(memory.preferredColumns(0, 0, 512, rested, r -> true).contains(Pos.pack(20, 0, 1)));
        assertTrue(memory.preferredColumns(5000, 5000, 512, rested, r -> true).isEmpty(), "far away");
    }
}
