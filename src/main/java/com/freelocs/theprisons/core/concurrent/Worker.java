package com.freelocs.theprisons.core.concurrent;

import com.freelocs.theprisons.core.profiling.Profiler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The single background thread for every computation of the mod (path searches, mining plans). Replaces the
 * per-feature executors of the old code.
 *
 * <ul>
 *     <li><b>Latest wins per key:</b> submitting a job under a key cancels the pending / running job of the same
 *     owner and key. Jobs poll {@link Cancel#cancelled()} and should return early.</li>
 *     <li><b>Results on the client thread:</b> {@link #drain()} (called at tick start) runs the callbacks of
 *     finished jobs. Callbacks of cancelled jobs, or of owners cancelled via {@link #cancelAll}, never run.</li>
 *     <li><b>Isolation:</b> jobs only get immutable inputs; an exception inside a job is reported through the
 *     error handler with the owner, not thrown into the game.</li>
 * </ul>
 */
public final class Worker {
    /** Cooperative cancellation flag handed to jobs. */
    public interface Cancel {
        boolean cancelled();
    }

    /** Handle of a submitted job. */
    public static final class Job implements Cancel {
        private final Object owner;
        private final String key;
        private volatile boolean cancelled;
        private volatile boolean done;

        Job(Object owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
        }

        /** Finished (result delivered, failed or cancelled). */
        public boolean done() {
            return done;
        }

        public String key() {
            return key;
        }
    }

    private record Key(Object owner, String key) {
    }

    private final ExecutorService executor;
    private final Profiler profiler;
    private final BiConsumer<Object, Throwable> errorHandler;
    private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    /** Client thread only. */
    private final Map<Key, Job> current = new HashMap<>();

    public Worker(Profiler profiler, BiConsumer<Object, Throwable> errorHandler) {
        this.profiler = profiler;
        this.errorHandler = errorHandler;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ThePrisons-Worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * Submits a job (client thread). {@code onResult} runs on the client thread during a later {@link #drain()}.
     */
    public <T> Job submit(Object owner, String key, Function<Cancel, T> task, Consumer<T> onResult) {
        Key id = new Key(owner, key);
        Job previous = current.get(id);
        if (previous != null) {
            previous.cancel();
        }
        Job job = new Job(owner, key);
        current.put(id, job);
        Profiler.Section section = profiler.section("job:" + key);
        executor.execute(() -> {
            if (job.cancelled) {
                finish(job, id, null);
                return;
            }
            long start = section.begin();
            T result;
            try {
                result = task.apply(job);
            } catch (Throwable error) {
                results.add(() -> {
                    release(job, id);
                    if (!job.cancelled) {
                        errorHandler.accept(owner, error);
                    }
                });
                return;
            } finally {
                section.end(start);
            }
            finish(job, id, () -> onResult.accept(result));
        });
        return job;
    }

    private void finish(Job job, Key id, Runnable callback) {
        results.add(() -> {
            release(job, id);
            if (!job.cancelled && callback != null) {
                try {
                    callback.run();
                } catch (Throwable error) {
                    errorHandler.accept(job.owner, error);
                }
            }
        });
    }

    private void release(Job job, Key id) {
        job.done = true;
        if (current.get(id) == job) {
            current.remove(id);
        }
    }

    /** True while a job of this owner and key is queued or running. */
    public boolean busy(Object owner, String key) {
        Job job = current.get(new Key(owner, key));
        return job != null && !job.cancelled;
    }

    /** Cancels every job of the owner; their callbacks will not run. */
    public void cancelAll(Object owner) {
        current.entrySet().removeIf(entry -> {
            if (entry.getKey().owner() == owner) {
                entry.getValue().cancel();
                return true;
            }
            return false;
        });
    }

    /** Runs the callbacks of finished jobs. Client thread, once per tick. */
    public void drain() {
        Runnable callback;
        while ((callback = results.poll()) != null) {
            try {
                callback.run();
            } catch (Throwable error) {
                errorHandler.accept(null, error);
            }
        }
    }

    public int pending() {
        return current.size();
    }

    public void shutdown() {
        for (Job job : current.values()) {
            job.cancel();
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
