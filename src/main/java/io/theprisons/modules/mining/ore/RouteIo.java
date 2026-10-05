package io.theprisons.modules.mining.ore;

import io.theprisons.ThePrisonsClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * File work of the route memory on one dedicated background thread - the client thread never touches the disk.
 *
 * <ul>
 *     <li><b>Load:</b> read + decode in the background; the result is handed to {@code deliver} (the client thread's
 *     executor), which puts it into the RAM cache. A load always runs after the writes queued before it.</li>
 *     <li><b>Save:</b> the caller passes an immutable snapshot taken on the client thread. Saves to the same file are
 *     coalesced: only the newest snapshot is written, however many routes finished meanwhile. Encoding happens on the
 *     I/O thread; writing goes over a temp file and an atomic move, so a crash never leaves half a file.</li>
 * </ul>
 * The single thread keeps the order of loads and writes, so a file is never read while it is being written.
 */
public final class RouteIo {
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "theprisons-route-io");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** The newest snapshot waiting per file. */
    private final Map<Path, List<RouteMemory.Route>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    /** Reads {@code file} in the background; {@code onLoaded} runs via {@code deliver} (an empty list without a file). */
    public void load(Path file, java.util.concurrent.Executor deliver, Consumer<List<RouteMemory.Route>> onLoaded) {
        io.execute(() -> {
            List<RouteMemory.Route> routes = List.of();
            try {
                if (Files.exists(file)) {
                    routes = RouteCodec.decode(Files.readString(file));
                }
            } catch (Exception e) {
                ThePrisonsClient.LOGGER.warn("[ore_macro] could not read learned routes {}: {}", file, e.toString());
            }
            List<RouteMemory.Route> result = routes;
            deliver.execute(() -> onLoaded.accept(result));
        });
    }

    /** Queues a write of {@code snapshot} (from {@link RouteMemory#snapshot()}) to {@code file}; returns at once. */
    public void save(Path file, List<RouteMemory.Route> snapshot) {
        pending.put(file, snapshot);
        if (draining.compareAndSet(false, true)) {
            io.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (!pending.isEmpty()) {
                for (Path file : List.copyOf(pending.keySet())) {
                    List<RouteMemory.Route> snapshot = pending.remove(file);
                    if (snapshot != null) {
                        write(file, RouteCodec.encode(snapshot));
                    }
                }
            }
        } finally {
            draining.set(false);
            // A save that came in after the loop's last look: drain again.
            if (!pending.isEmpty() && draining.compareAndSet(false, true)) {
                io.execute(this::drain);
            }
        }
    }

    static void write(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            ThePrisonsClient.LOGGER.warn("[ore_macro] could not save learned routes {}: {}", file, e.toString());
        }
    }

    /** Waits up to {@code ms} for queued work (tests, shutting down). */
    public boolean flush(long ms) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        io.execute(done::countDown);
        try {
            return done.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
