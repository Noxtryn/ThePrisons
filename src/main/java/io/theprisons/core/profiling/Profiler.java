package io.theprisons.core.profiling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight always-on profiler: named sections record their duration with two {@code nanoTime} calls. Keeps an
 * exponential moving average, the maximum of the current and previous 5 s window and a call count, so spikes stay
 * visible without storing samples. Sections may be recorded from any thread (worker jobs report their runtime).
 */
public final class Profiler {
    private static final long WINDOW_NS = 5_000_000_000L;

    public static final class Section {
        private final String name;
        private volatile double avgNs;
        private volatile long maxNs;
        private volatile long previousMaxNs;
        private volatile long windowStart = System.nanoTime();
        private volatile long calls;
        private volatile long totalNs;

        Section(String name) {
            this.name = name;
        }

        public long begin() {
            return System.nanoTime();
        }

        public void end(long startNs) {
            record(System.nanoTime() - startNs);
        }

        public synchronized void record(long durationNs) {
            long now = System.nanoTime();
            if (now - windowStart > WINDOW_NS) {
                previousMaxNs = maxNs;
                maxNs = 0L;
                windowStart = now;
            }
            maxNs = Math.max(maxNs, durationNs);
            avgNs = calls == 0 ? durationNs : avgNs * 0.95D + durationNs * 0.05D;
            calls++;
            totalNs += durationNs;
        }

        public String name() {
            return name;
        }

        public double avgMs() {
            return avgNs / 1_000_000.0D;
        }

        /** Worst case over the last 5–10 s. */
        public double maxMs() {
            return Math.max(maxNs, previousMaxNs) / 1_000_000.0D;
        }

        public long calls() {
            return calls;
        }


        synchronized void reset() {
            avgNs = 0.0D;
            maxNs = 0L;
            previousMaxNs = 0L;
            calls = 0L;
            totalNs = 0L;
            windowStart = System.nanoTime();
        }
    }

    private final ConcurrentHashMap<String, Section> sections = new ConcurrentHashMap<>();

    /** Returns (and creates) a section; cache the result instead of looking it up in hot paths. */
    public Section section(String name) {
        return sections.computeIfAbsent(name, Section::new);
    }

    public List<Section> sections() {
        List<Section> list = new ArrayList<>(sections.values());
        list.sort(Comparator.comparingDouble(Section::avgMs).reversed());
        return list;
    }

    public void reset() {
        sections.values().forEach(Section::reset);
    }

    public static String format(Section section) {
        return String.format(Locale.ROOT, "%-28s avg %6.3f ms  max %6.3f ms  calls %d", section.name(), section.avgMs(),
                section.maxMs(), section.calls());
    }
}
