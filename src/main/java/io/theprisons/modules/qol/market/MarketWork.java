package io.theprisons.modules.qol.market;

import io.theprisons.ThePrisonsClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The background thread of the market overlays: item lists, price valuations of the /gz and /pb shops. Jobs get
 * immutable inputs (a {@link PriceBook#snapshot()}, plain records) and hand their result to a callback that only sets a
 * volatile field - the render thread never waits for it.
 */
final class MarketWork {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ThePrisons-Market");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private MarketWork() {
    }

    static <T> void run(String what, Supplier<T> job, Consumer<T> done) {
        EXECUTOR.execute(() -> {
            try {
                done.accept(job.get());
            } catch (RuntimeException e) {
                ThePrisonsClient.LOGGER.warn("[market] background job '{}' failed", what, e);
            }
        });
    }
}
