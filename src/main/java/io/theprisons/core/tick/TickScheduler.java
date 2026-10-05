package io.theprisons.core.tick;

import io.theprisons.core.profiling.Profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Periodic tasks with a fixed interval in ticks. Tasks sharing an interval get different phase offsets (the least
 * used one), so e.g. three "every 20 ticks" jobs run on three different ticks instead of stacking on one.
 * Every task is measured by the {@link Profiler}. Client thread only.
 */
public final class TickScheduler {
    /** Handle to cancel a task. */
    public interface Task {
        void cancel();

        boolean cancelled();
    }

    private final class Entry implements Task {
        final Object owner;
        final String name;
        final int interval;
        final int phase;
        final Runnable body;
        final Profiler.Section section;
        boolean cancelled;

        Entry(Object owner, String name, int interval, int phase, Runnable body) {
            this.owner = owner;
            this.name = name;
            this.interval = interval;
            this.phase = phase;
            this.body = body;
            this.section = profiler.section("task:" + name);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }

    private final Profiler profiler;
    private final BiConsumer<Object, Throwable> errorHandler;
    private final List<Entry> tasks = new ArrayList<>();
    private long tick;

    public TickScheduler(Profiler profiler, BiConsumer<Object, Throwable> errorHandler) {
        this.profiler = profiler;
        this.errorHandler = errorHandler;
    }

    /**
     * @param intervalTicks 1 = every tick
     */
    public Task every(Object owner, String name, int intervalTicks, Runnable body) {
        int interval = Math.max(1, intervalTicks);
        int[] load = new int[interval];
        for (Entry entry : tasks) {
            if (!entry.cancelled && entry.interval == interval) {
                load[entry.phase]++;
            }
        }
        int phase = 0;
        for (int i = 1; i < interval; i++) {
            if (load[i] < load[phase]) {
                phase = i;
            }
        }
        Entry entry = new Entry(owner, name, interval, phase, body);
        tasks.add(entry);
        return entry;
    }

    public void cancelAll(Object owner) {
        for (Entry entry : tasks) {
            if (entry.owner == owner) {
                entry.cancelled = true;
            }
        }
    }

    /** Runs the tasks due this tick. Call once per client tick. */
    public void tick() {
        tick++;
        tasks.removeIf(entry -> entry.cancelled);
        // Index loop: tasks scheduled while running are picked up next tick (they were appended after size()).
        int size = tasks.size();
        for (int i = 0; i < size; i++) {
            Entry entry = tasks.get(i);
            if (entry.cancelled || tick % entry.interval != entry.phase) {
                continue;
            }
            long start = entry.section.begin();
            try {
                entry.body.run();
            } catch (Throwable error) {
                entry.cancelled = true;
                errorHandler.accept(entry.owner, error);
            } finally {
                entry.section.end(start);
            }
        }
    }


    public int size() {
        int count = 0;
        for (Entry entry : tasks) {
            count += entry.cancelled ? 0 : 1;
        }
        return count;
    }
}
