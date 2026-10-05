package com.freelocs.theprisons.core.analytics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatsServiceTest {
    private long now = 1_000_000L;

    @Test
    void countersRatesAndStateTime() {
        List<StatsService.Summary> sink = new ArrayList<>();
        StatsService stats = new StatsService(() -> now);
        stats.setSink(sink::add);
        StatsService.Session session = stats.start("ore_macro");
        session.state("walking");
        StatsService.Counter ores = session.counter("ores");
        for (int second = 0; second < 30; second++) {
            ores.add(2);
            now += 1_000L;
        }
        session.state("mining");
        now += 10_000L;
        assertEquals(5_400.0D, ores.recentPerHour(), 1.0D, "60 ores in 40 s of runtime");
        StatsService.Summary summary = stats.end("ore_macro");
        assertEquals(60L, summary.counters().get("ores"));
        assertEquals(30_000L, summary.stateMs().get("walking"));
        assertEquals(10_000L, summary.stateMs().get("mining"));
        assertEquals(40_000L, summary.durationMs());
        assertEquals(1, sink.size());
        assertNull(stats.session("ore_macro"));
    }

    @Test
    void rateWindowForgetsOldEvents() {
        RateMeter meter = new RateMeter(() -> now);
        meter.add(100);
        now += 61_000L;
        assertEquals(0L, meter.windowTotal());
        meter.add(6);
        assertEquals(6L, meter.windowTotal());
    }

    @Test
    void historyIsBounded() {
        StatsService stats = new StatsService(() -> now);
        for (int i = 0; i < StatsService.HISTORY + 5; i++) {
            stats.start("x");
            stats.end("x");
        }
        assertEquals(StatsService.HISTORY, stats.history().size());
    }
}
