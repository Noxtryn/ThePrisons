package com.freelocs.theprisons.core.analytics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Shared statistics: one implementation of counters, rates and time accounting for every module (replaces the
 * separate stats / recorder classes of the old code).
 *
 * <ul>
 *     <li>A {@link Session} is one run of a module (e.g. one Ore Macro run). It owns named counters, each with a
 *     sliding {@value RateMeter#WINDOW_SECONDS} s rate, and time-in-state accounting ("mining", "walking" ...).</li>
 *     <li>Ending a session produces an immutable {@link Summary}; the newest {@value #HISTORY} are kept and handed to
 *     an optional sink (persisted asynchronously by the core).</li>
 * </ul>
 * Client thread only. The clock is injectable for tests.
 */
public final class StatsService {
    public static final int HISTORY = 20;

    private final LongSupplier clock;
    private final Map<String, Session> active = new LinkedHashMap<>();
    private final Deque<Summary> history = new ArrayDeque<>();
    private Consumer<Summary> sink = summary -> {
    };

    public StatsService(LongSupplier clockMs) {
        this.clock = clockMs;
    }

    public void setSink(Consumer<Summary> sink) {
        this.sink = sink;
    }

    /** Starts (or restarts) the session of a namespace, ending a running one first. */
    public Session start(String namespace) {
        end(namespace);
        Session session = new Session(namespace, clock.getAsLong());
        active.put(namespace, session);
        return session;
    }

    public @Nullable Session session(String namespace) {
        return active.get(namespace);
    }

    public @Nullable Summary end(String namespace) {
        Session session = active.remove(namespace);
        if (session == null) {
            return null;
        }
        Summary summary = session.summary();
        history.addFirst(summary);
        while (history.size() > HISTORY) {
            history.removeLast();
        }
        sink.accept(summary);
        return summary;
    }

    /** Newest first. */
    public List<Summary> history() {
        return List.copyOf(history);
    }

    /** Finished run: duration, totals, per-hour rates and time per state. */
    public record Summary(String namespace, long startedAtMs, long durationMs, Map<String, Long> counters,
                          Map<String, Long> stateMs) {
        public double perHour(String counter) {
            long value = counters.getOrDefault(counter, 0L);
            return durationMs <= 0 ? 0.0D : value * 3_600_000.0D / Math.max(durationMs, 60_000L);
        }
    }

    public final class Session {
        private final String namespace;
        private final long startedAtMs;
        private final Map<String, Counter> counters = new LinkedHashMap<>();
        private final Map<String, Long> stateMs = new LinkedHashMap<>();
        private @Nullable String state;
        private long stateSinceMs;

        Session(String namespace, long startedAtMs) {
            this.namespace = namespace;
            this.startedAtMs = startedAtMs;
            this.stateSinceMs = startedAtMs;
        }

        public Counter counter(String name) {
            return counters.computeIfAbsent(name, id -> new Counter(id, clock));
        }

        /** Switches the accounted state; time spent in the previous one is added to it. */
        public void state(String name) {
            if (name.equals(state)) {
                return;
            }
            long now = clock.getAsLong();
            if (state != null) {
                stateMs.merge(state, now - stateSinceMs, Long::sum);
            }
            state = name;
            stateSinceMs = now;
        }

        public long elapsedMs() {
            return clock.getAsLong() - startedAtMs;
        }

        public String namespace() {
            return namespace;
        }

        /** Totals so far without ending the session. */
        public Summary summary() {
            long now = clock.getAsLong();
            Map<String, Long> totals = new LinkedHashMap<>();
            for (Counter counter : counters.values()) {
                totals.put(counter.name(), counter.value());
            }
            Map<String, Long> states = new LinkedHashMap<>(stateMs);
            if (state != null) {
                states.merge(state, now - stateSinceMs, Long::sum);
            }
            return new Summary(namespace, startedAtMs, now - startedAtMs, Collections.unmodifiableMap(totals),
                    Collections.unmodifiableMap(states));
        }

        public List<Counter> counters() {
            return new ArrayList<>(counters.values());
        }
    }

    public static final class Counter {
        private final String name;
        private final RateMeter rate;
        private long value;

        Counter(String name, LongSupplier clock) {
            this.name = name;
            this.rate = new RateMeter(clock);
        }

        public void add(long amount) {
            value += amount;
            rate.add(amount);
        }

        public void increment() {
            add(1L);
        }

        public long value() {
            return value;
        }

        public String name() {
            return name;
        }

        /** Rate over the last minute, extrapolated to one hour. */
        public double recentPerHour() {
            return rate.perHour();
        }
    }
}
