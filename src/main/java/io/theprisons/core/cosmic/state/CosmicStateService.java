package io.theprisons.core.cosmic.state;

import io.theprisons.core.Phases;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.data.SnapshotBuilder;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.sense.FrameSampler;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.profiling.Profiler;
import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the sensors and fills the {@link CosmicStateStore}. One loop for the whole mod: it samples every
 * {@value #SAMPLE_EVERY_TICKS} ticks (5 per second), entities in a {@value #ENTITY_RADIUS}-block box, blocks in a small cube every
 * {@value #BLOCK_EVERY_TICKS} ticks, and never scans the world. The server's messages arrive by event and go into
 * {@link CosmicMemory}. Timing shows up as the profiler sections {@code cosmic:sample} / {@code cosmic:build}.
 */
public final class CosmicStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger("theprisons/cosmic");
    public static final int SAMPLE_EVERY_TICKS = 4;
    public static final double ENTITY_RADIUS = 32.0D;
    public static final int BLOCK_RADIUS = 4;
    /** The block cube is read about once a second, whatever the sample rate. */
    public static final int BLOCK_EVERY_TICKS = 20;
    /** Capture reads a wider area once. */
    public static final double CAPTURE_ENTITY_RADIUS = 48.0D;
    public static final int CAPTURE_BLOCK_RADIUS = 6;

    private final CosmicGameModel model;
    private final CosmicStateStore store = new CosmicStateStore();
    private final CosmicMemory memory = new CosmicMemory();
    private final FrameSampler sampler = new FrameSampler();
    private final Profiler.Section sampleTime;
    private final Profiler.Section buildTime;
    /** A consumer that needs wider or faster entity reads than the default (a combat macro). */
    private record Interest(double entityRadius, int everyTicks) {
    }

    /** Hard limits for what consumers may ask for. */
    public static final double MAX_ENTITY_RADIUS = 64.0D;
    public static final int MIN_SAMPLE_TICKS = 1;

    private final java.util.Map<Object, Interest> interests = new java.util.IdentityHashMap<>();
    private double entityRadius = ENTITY_RADIUS;
    private int everyTicks = SAMPLE_EVERY_TICKS;
    private long tick;
    private long lastBlockTick = Long.MIN_VALUE / 2L;
    private int samples;
    private Raw.@Nullable Blocks lastBlocks;
    private boolean enabled = true;

    public CosmicStateService(Profiler profiler) {
        this.model = loadModel();
        this.sampleTime = profiler.section("cosmic:sample");
        this.buildTime = profiler.section("cosmic:build");
    }

    private static CosmicGameModel loadModel() {
        try {
            return CosmicGameModel.loadDefault();
        } catch (RuntimeException error) {
            // A broken data file must not stop the game: the model then knows nothing (everything UNKNOWN).
            LOGGER.error("Could not load the Cosmic game model, continuing with an empty one", error);
            return CosmicGameModel.empty();
        }
    }

    public CosmicGameModel model() {
        return model;
    }

    public CosmicStateStore store() {
        return store;
    }

    public CosmicMemory memory() {
        return memory;
    }

    /** The zone from the last zone message ("" = none): what {@code ThePrisonsCore.lastZone()} returns. */
    public String zone() {
        return memory.zone();
    }

    /**
     * A consumer asks for entities within {@code radius} blocks (at most {@value #MAX_ENTITY_RADIUS}) and a sample every
     * {@code everyTicks} ticks (at least every tick). The service reads the widest radius and the fastest rate any consumer asked
     * for; {@link #release} takes the wish back (a macro that stops must call it).
     */
    public void interest(Object owner, double radius, int everyTicks) {
        interests.put(owner, new Interest(Math.min(MAX_ENTITY_RADIUS, Math.max(1.0D, radius)), Math.max(MIN_SAMPLE_TICKS, everyTicks)));
        recompute();
    }

    private final java.util.Map<Object, java.util.function.Supplier<java.util.Map<String, String>>> extras = new java.util.IdentityHashMap<>();

    /**
     * A module adds what it knows about its own state to every capture (the bandit macro: FSM state, target, threats ...). Keys should be
     * prefixed with the module ("bandit.state"). Call {@link #removeCaptureExtra} when the module stops.
     */
    public void captureExtra(Object owner, java.util.function.Supplier<java.util.Map<String, String>> supplier) {
        extras.put(owner, supplier);
    }

    public void removeCaptureExtra(Object owner) {
        extras.remove(owner);
    }

    /** What the modules add to a capture right now (never throws: a module that fails adds its error as a note). */
    public java.util.Map<String, String> captureExtras() {
        java.util.Map<String, String> all = new java.util.TreeMap<>();
        for (var e : extras.entrySet()) {
            try {
                all.putAll(e.getValue().get());
            } catch (RuntimeException error) {
                all.put("extra.error." + e.getKey().getClass().getSimpleName(), String.valueOf(error));
            }
        }
        return all;
    }

    public void release(Object owner) {
        if (interests.remove(owner) != null) {
            recompute();
        }
    }

    private void recompute() {
        double radius = ENTITY_RADIUS;
        int rate = SAMPLE_EVERY_TICKS;
        for (Interest i : interests.values()) {
            radius = Math.max(radius, i.entityRadius());
            rate = Math.min(rate, i.everyTicks());
        }
        entityRadius = radius;
        everyTicks = rate;
    }

    public double entityRadius() {
        return entityRadius;
    }

    public int sampleEveryTicks() {
        return everyTicks;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Subscribes to the core's events. Called once. */
    public void attach(EventBus bus) {
        bus.subscribe(CoreEvents.WorldChanged.class, this, event -> {
            store.reset();
            memory.onWorldChanged();
            sampler.clear();
            lastBlocks = null;
            samples = 0;
            lastBlockTick = Long.MIN_VALUE / 2L;
        });
        // Just after the world cache (WORLD) and before the modules decide (DECIDE): modules see this tick's state.
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.WORLD - 1, event -> onTick(event.client()));
    }

    /** A server message (from {@code ThePrisonsCore}'s message hook). Player chat is ignored by the memory. */
    public void onMessage(net.minecraft.text.Text message, boolean overlay, boolean fromPlayer) {
        memory.onMessage(TextStrip.strip(message.getString()), overlay, fromPlayer, System.currentTimeMillis());
    }

    /** For tests: a system line without a Minecraft {@code Text}. */
    public void onMessageForTest(String line) {
        memory.onMessage(line, false, false, System.currentTimeMillis());
    }

    private void onTick(MinecraftClient client) {
        tick++;
        if (!enabled || client.player == null || client.world == null || tick % everyTicks != 0) {
            return;
        }
        long start = sampleTime.begin();
        Raw.Frame frame;
        try {
            boolean blocks = tick - lastBlockTick >= BLOCK_EVERY_TICKS;
            if (blocks) {
                lastBlockTick = tick;
            }
            samples++;
            long now = System.currentTimeMillis();
            frame = sampler.sample(client, tick, now, new FrameSampler.Plan(entityRadius, BLOCK_RADIUS, blocks, false, true), lastBlocks,
                    memory.actionBar());
            lastBlocks = frame.blocks();
        } catch (RuntimeException error) {
            LOGGER.warn("Cosmic sampling failed once: {}", error.toString());
            return;
        } finally {
            sampleTime.end(start);
        }
        long built = buildTime.begin();
        try {
            long now = frame.nowMs();
            store.publish(SnapshotBuilder.build(frame, new SnapshotBuilder.Memory(memory.zone(), memory.event(now), memory.actionBar()), model));
        } finally {
            buildTime.end(built);
        }
    }

    /** One wide, complete frame for a capture: inventory, container slots, a larger entity box and a fresh block cube. */
    public Raw.Frame captureFrame(MinecraftClient client) {
        return sampler.sample(client, tick, System.currentTimeMillis(),
                new FrameSampler.Plan(CAPTURE_ENTITY_RADIUS, CAPTURE_BLOCK_RADIUS, true, true, true), lastBlocks, memory.actionBar());
    }

    public SnapshotBuilder.Memory memoryView(long nowMs) {
        return new SnapshotBuilder.Memory(memory.zone(), memory.event(nowMs), memory.actionBar());
    }

    public @Nullable CosmicContextSnapshot latest() {
        return store.latest();
    }
}
