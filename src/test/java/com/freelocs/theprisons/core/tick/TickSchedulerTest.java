package com.freelocs.theprisons.core.tick;

import com.freelocs.theprisons.core.profiling.Profiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSchedulerTest {
    @Test
    void runsAtIntervalAndSpreadsEqualIntervalsOverDifferentTicks() {
        TickScheduler scheduler = new TickScheduler(new Profiler(), (owner, error) -> {
            throw new AssertionError(error);
        });
        List<List<String>> perTick = new ArrayList<>();
        List<String> current = new ArrayList<>();
        scheduler.every(this, "a", 4, () -> current.add("a"));
        scheduler.every(this, "b", 4, () -> current.add("b"));
        scheduler.every(this, "c", 4, () -> current.add("c"));
        scheduler.every(this, "fast", 1, () -> current.add("fast"));
        for (int i = 0; i < 8; i++) {
            current.clear();
            scheduler.tick();
            perTick.add(new ArrayList<>(current));
        }
        int a = 0;
        int b = 0;
        int c = 0;
        for (List<String> tick : perTick) {
            assertTrue(tick.contains("fast"));
            int slow = 0;
            for (String name : tick) {
                if (!name.equals("fast")) {
                    slow++;
                }
            }
            assertTrue(slow <= 1, "interval-4 tasks share a tick: " + tick);
            a += tick.contains("a") ? 1 : 0;
            b += tick.contains("b") ? 1 : 0;
            c += tick.contains("c") ? 1 : 0;
        }
        assertEquals(2, a);
        assertEquals(2, b);
        assertEquals(2, c);
    }

    @Test
    void cancelAllByOwnerAndFailingTaskIsCancelledAndReported() {
        Set<Object> failed = new HashSet<>();
        TickScheduler scheduler = new TickScheduler(new Profiler(), (owner, error) -> failed.add(owner));
        Object owner = new Object();
        Object crashing = new Object();
        int[] runs = new int[2];
        scheduler.every(owner, "x", 1, () -> runs[0]++);
        scheduler.every(crashing, "y", 1, () -> {
            runs[1]++;
            throw new IllegalStateException("boom");
        });
        scheduler.tick();
        scheduler.tick();
        assertEquals(2, runs[0]);
        assertEquals(1, runs[1], "a failing task runs once and is cancelled");
        assertTrue(failed.contains(crashing));
        scheduler.cancelAll(owner);
        scheduler.tick();
        assertEquals(2, runs[0]);
        assertEquals(0, scheduler.size());
    }
}
