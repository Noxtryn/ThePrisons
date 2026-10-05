package com.freelocs.theprisons.core.concurrent;

import com.freelocs.theprisons.core.profiling.Profiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerTest {
    private final List<Throwable> errors = new ArrayList<>();
    private final Worker worker = new Worker(new Profiler(), (owner, error) -> errors.add(error));

    @AfterEach
    void shutdown() {
        worker.shutdown();
    }

    /** Drains until the predicate holds (results arrive asynchronously). */
    private void drainUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            worker.drain();
            Thread.sleep(2);
        }
        worker.drain();
    }

    @Test
    void resultIsDeliveredOnDrainingThreadOnly() throws Exception {
        List<String> results = new ArrayList<>();
        Thread caller = Thread.currentThread();
        Thread[] ranOn = new Thread[2];
        worker.submit(this, "job", cancel -> {
            ranOn[0] = Thread.currentThread();
            return "done";
        }, result -> {
            ranOn[1] = Thread.currentThread();
            results.add(result);
        });
        drainUntil(() -> !results.isEmpty());
        assertEquals(List.of("done"), results);
        assertNotEquals(caller, ranOn[0], "job runs on the worker thread");
        assertEquals(caller, ranOn[1], "callback runs on the draining (client) thread");
    }

    @Test
    void latestSubmissionPerKeyWins() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> results = new ArrayList<>();
        worker.submit(this, "plan", cancel -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }, results::add);
        Worker.Job second = worker.submit(this, "plan", cancel -> 2, results::add);
        release.countDown();
        drainUntil(second::done);
        assertEquals(List.of(2), results, "the superseded job's callback never runs");
    }

    @Test
    void cancelAllDropsCallbacksAndJobsSeeTheFlag() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        boolean[] sawCancel = new boolean[1];
        List<String> results = new ArrayList<>();
        Worker.Job job = worker.submit(this, "long", cancel -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!cancel.cancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            sawCancel[0] = cancel.cancelled();
            return "late";
        }, results::add);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(worker.busy(this, "long"));
        worker.cancelAll(this);
        drainUntil(job::done);
        assertTrue(sawCancel[0]);
        assertTrue(results.isEmpty());
        assertFalse(worker.busy(this, "long"));
    }

    @Test
    void failingJobIsReportedNotThrown() throws Exception {
        Worker.Job job = worker.submit(this, "bad", cancel -> {
            throw new IllegalStateException("boom");
        }, result -> {
        });
        drainUntil(job::done);
        assertEquals(1, errors.size());
        assertEquals("boom", errors.get(0).getMessage());
    }
}
