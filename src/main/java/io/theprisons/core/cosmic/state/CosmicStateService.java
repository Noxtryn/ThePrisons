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
 * {@value #BLOCK_EVERY_SAMPLES} samples, and never scans the world. The server's messages arrive by event and go into
 * {@link CosmicMemory}. Timing shows up as the profiler sections {@code cosmic:sample} / {@code cosmic:build}.
 */
public final class CosmicStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger("theprisons/cosmic");
    public static final int SAMPLE_EVERY_TICKS = 4;
    public static final double ENTITY_RADIUS = 32.0D;
    public static final int BLOCK_RADIUS = 4;
    public static final int BLOCK_EVERY_SAMPLES = 5;
    /** Capture reads a wider area once. */
    public static final double CAPTURE_ENTITY_RADIUS = 48.0D;
    public static final int CAPTURE_BLOCK_RADIUS = 6;

    private final CosmicGameModel model;
    private final CosmicStateStore store = new CosmicStateStore();
    private final CosmicMemory memory = new CosmicMemory();
    private final FrameSampler sampler = new FrameSampler();
    private final Profiler.Section sampleTime;
    private final Profiler.Section buildTime;
    private long tick;
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
        if (!enabled || client.player == null || client.world == null || tick % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        long start = sampleTime.begin();
        Raw.Frame frame;
        try {
            boolean blocks = samples++ % BLOCK_EVERY_SAMPLES == 0;
            long now = System.currentTimeMillis();
            frame = sampler.sample(client, tick, now, new FrameSampler.Plan(ENTITY_RADIUS, BLOCK_RADIUS, blocks, false, true), lastBlocks,
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
