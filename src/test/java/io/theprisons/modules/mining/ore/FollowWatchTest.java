package io.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FollowWatchTest {
    private static FollowWatch.Step run(FollowWatch w, long from, long to, Map<String, Double> near, FollowWatch.Step[] last) {
        FollowWatch.Step first = FollowWatch.Step.NONE;
        for (long t = from; t <= to; t += 250L) {
            FollowWatch.Result r = w.update(t, near);
            if (r.step() != FollowWatch.Step.NONE) {
                first = r.step();
                last[0] = r.step();
                break;
            }
        }
        return first;
    }

    @Test
    void passingPlayerIsIgnored() {
        FollowWatch w = new FollowWatch();
        assertEquals(FollowWatch.Step.NONE, run(w, 1000, 10_000, Map.of("Walker", 15.0), new FollowWatch.Step[1]));
        assertEquals(FollowWatch.Step.NONE, run(w, 10_250, 20_000, Map.of("Walker", 60.0), new FollowWatch.Step[1]));
    }

    @Test
    void followerIsAskedThenEscaped() {
        FollowWatch w = new FollowWatch();
        FollowWatch.Step[] last = new FollowWatch.Step[1];
        assertEquals(FollowWatch.Step.ASK, run(w, 1000, 60_000, Map.of("Tail", 10.0), last));
        // still there right after: not yet
        assertEquals(FollowWatch.Step.NONE, run(w, 47_000, 50_000, Map.of("Tail", 10.0), last));
        assertEquals(FollowWatch.Step.ESCAPE, run(w, 50_250, 90_000, Map.of("Tail", 10.0), last));
    }

    @Test
    void shortGapsDoNotBreakIt() {
        FollowWatch w = new FollowWatch();
        FollowWatch.Step[] last = new FollowWatch.Step[1];
        assertEquals(FollowWatch.Step.NONE, run(w, 1000, 20_000, Map.of("Tail", 10.0), last));
        assertEquals(FollowWatch.Step.NONE, run(w, 20_250, 22_000, Map.of("Tail", 40.0), last));
        assertEquals(FollowWatch.Step.ASK, run(w, 22_250, 70_000, Map.of("Tail", 10.0), last));
    }
}
