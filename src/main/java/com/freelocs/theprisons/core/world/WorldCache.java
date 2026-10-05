package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.profiling.Profiler;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The shared block cache of all modules: what the client knows about the blocks around the player.
 *
 * <ul>
 *     <li><b>Demand driven:</b> modules {@link #acquire} an interest (radius); without any interest nothing is
 *     scanned, and the data is dropped after {@value #IDLE_CLEAR_TICKS} idle ticks.</li>
 *     <li><b>Budgeted:</b> at most {@value #MAX_SECTIONS_PER_TICK} section copies and {@value #BUDGET_NS} ns per tick,
 *     closest first. Only chunks the client already has are read; nothing is requested or written to disk.</li>
 *     <li><b>Event driven:</b> server block updates mark their section dirty (re-extracted first), chunk (re)loads
 *     mark the chunk dirty, our own breaks are patched immediately. A slow safety rescan of the sections near the
 *     player ({@value #SAFETY_RESCAN_MS} ms) catches anything a missed event would leave stale.</li>
 *     <li><b>Bounded memory:</b> sections outside {@code radius + }{@value #EVICT_MARGIN} or in unloaded chunks are
 *     evicted.</li>
 * </ul>
 * Client thread only; workers get {@link WorldSnapshot}s via {@link #snapshot}.
 */
public final class WorldCache {
    public static final int MAX_SECTIONS_PER_TICK = 6;
    public static final long BUDGET_NS = 1_000_000L;
    public static final long SAFETY_RESCAN_MS = 10_000L;
    private static final int SAFETY_RADIUS = 16;
    private static final int EVICT_MARGIN = 32;
    private static final int IDLE_CLEAR_TICKS = 600;

    /** Scan area wanted by one owner. */
    public record Interest(int radius, int vertical) {
    }

    private final SectionStore store = new SectionStore();
    private final TargetRegistry targets;
    private final BlockClassifier classifier;
    private final Profiler.Section scanProfile;
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final Map<Object, Interest> interests = new IdentityHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();

    private @Nullable ClientWorld world;
    private long[] planKeys = new long[512];
    private long[] planOrder = new long[512];
    private int planSize;
    private int planCursor;
    private int ticks;
    private int idleTicks;
    private long targetsChangedAtMs;
    private int radius;
    private int vertical;
    private long extracted;
    /** Plan entries (sorted first) for sections never read yet. */
    private int planFresh;

    public WorldCache(TargetRegistry targets, Profiler profiler) {
        this.targets = targets;
        this.classifier = new BlockClassifier(targets);
        this.scanProfile = profiler.section("world:scan");
    }

    public SectionStore store() {
        return store;
    }

    public BlockClassifier classifier() {
        return classifier;
    }

    public TargetRegistry targets() {
        return targets;
    }

    public @Nullable ClientWorld world() {
        return world;
    }


    // ── Interests ────────────────────────────────────────────────────────────

    public void acquire(Object owner, int wantedRadius, int wantedVertical) {
        interests.put(owner, new Interest(wantedRadius, wantedVertical));
        recomputeArea();
    }

    public void release(Object owner) {
        if (interests.remove(owner) != null) {
            recomputeArea();
        }
    }

    public boolean active() {
        return !interests.isEmpty();
    }

    private void recomputeArea() {
        radius = 0;
        vertical = 0;
        for (Interest interest : interests.values()) {
            radius = Math.max(radius, interest.radius());
            vertical = Math.max(vertical, interest.vertical());
        }
        planSize = 0;
    }

    // ── Events ───────────────────────────────────────────────────────────────

    public void onWorldChanged(@Nullable ClientWorld current) {
        store.clear();
        dirty.clear();
        planSize = 0;
        planCursor = 0;
        world = current;
    }

    public void onBlockChanged(ClientWorld changedWorld, long pos) {
        if (changedWorld == world && active()) {
            long key = Pos.sectionOf(pos);
            if (store.get(key) != null) {
                dirty.add(key);
            }
        }
    }

    public void onChunkLoaded(ClientWorld loadedWorld, int cx, int cz) {
        if (loadedWorld != world || !active()) {
            return;
        }
        for (SectionSnapshot section : store.sections()) {
            if (section.sx() == cx && section.sz() == cz) {
                dirty.add(section.key());
            }
        }
        planSize = 0;
    }

    public void onChunkUnloaded(ClientWorld unloadedWorld, int cx, int cz) {
        if (unloadedWorld == world) {
            store.removeIf(section -> section.sx() == cx && section.sz() == cz);
        }
    }

    /** Our own break: patch the stored cell right away instead of waiting for a rescan. */
    public void onPlayerBrokeBlock(ClientWorld brokenWorld, long pos) {
        if (brokenWorld != world) {
            return;
        }
        mutable.set(pos);
        BlockState now = brokenWorld.getBlockState(mutable);
        store.patch(pos, classifier.cell(now, brokenWorld, mutable), classifier.stored(now));
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(MinecraftClient client) {
        if (client.world != world) {
            onWorldChanged(client.world);
        }
        ClientPlayerEntity player = client.player;
        ClientWorld current = world;
        if (current == null || player == null) {
            return;
        }
        if (classifier.refreshTargets()) {
            // Target data depends on the registry: every section needs a fresh extraction.
            targetsChangedAtMs = System.currentTimeMillis();
            planSize = 0;
        }
        ticks++;
        if (!active()) {
            if (++idleTicks == IDLE_CLEAR_TICKS) {
                store.clear();
            }
            return;
        }
        idleTicks = 0;
        long start = scanProfile.begin();
        try {
            int px = player.getBlockX();
            int py = player.getBlockY();
            int pz = player.getBlockZ();
            if (ticks % 40 == 0) {
                evict(current, px, pz);
            }
            if (ticks % 10 == 0 || planCursor >= planSize) {
                rebuildPlan(current, px, py, pz);
            }
            int budget = MAX_SECTIONS_PER_TICK;
            // Sections never read yet (walking into a new area) first, half the budget: changed sections (mining turns
            // ore into stone all the time) must not starve them - unread blocks count as walls for the steering.
            int fresh = MAX_SECTIONS_PER_TICK / 2;
            while (fresh > 0 && planCursor < planFresh && System.nanoTime() - start < BUDGET_NS) {
                long key = planKeys[(int) planOrder[planCursor++]];
                if (extract(current, Pos.x(key), Pos.y(key), Pos.z(key))) {
                    budget--;
                    fresh--;
                }
            }
            while (budget > 0 && !dirty.isEmpty() && System.nanoTime() - start < BUDGET_NS) {
                long key = dirty.removeFirstLong();
                if (extract(current, Pos.x(key), Pos.y(key), Pos.z(key))) {
                    budget--;
                }
            }
            while (budget > 0 && planCursor < planSize && System.nanoTime() - start < BUDGET_NS) {
                long key = planKeys[(int) planOrder[planCursor++]];
                if (extract(current, Pos.x(key), Pos.y(key), Pos.z(key))) {
                    budget--;
                }
            }
        } finally {
            scanProfile.end(start);
        }
    }

    private void rebuildPlan(ClientWorld w, int px, int py, int pz) {
        int chunkRadius = (radius >> 4) + 1;
        int minSy = Math.max(w.getBottomSectionCoord(), (py - vertical) >> 4);
        int maxSy = Math.min(w.getTopSectionCoord() - 1, (py + vertical) >> 4);
        long now = System.currentTimeMillis();
        long staleBefore = now - SAFETY_RESCAN_MS;
        int pcx = px >> 4;
        int pcz = pz >> 4;
        planSize = 0;
        planCursor = 0;
        planFresh = 0;
        for (int cx = pcx - chunkRadius; cx <= pcx + chunkRadius; cx++) {
            for (int cz = pcz - chunkRadius; cz <= pcz + chunkRadius; cz++) {
                double chunkDistance = Math.hypot((cx << 4) + 8 - px, (cz << 4) + 8 - pz);
                if (chunkDistance > radius + 12 || w.getChunkManager().getWorldChunk(cx, cz, false) == null) {
                    continue;
                }
                for (int sy = minSy; sy <= maxSy; sy++) {
                    long key = Pos.pack(cx, sy, cz);
                    SectionSnapshot existing = store.get(key);
                    double distance = Math.hypot(chunkDistance, (sy << 4) + 8 - py);
                    int priority;
                    if (existing == null || existing.scannedAtMs() < targetsChangedAtMs) {
                        priority = 0;
                        planFresh++;
                    } else if (distance <= SAFETY_RADIUS && existing.scannedAtMs() < staleBefore) {
                        priority = 1;
                    } else {
                        continue;
                    }
                    if (planSize == planKeys.length) {
                        planKeys = java.util.Arrays.copyOf(planKeys, planSize * 2);
                        planOrder = java.util.Arrays.copyOf(planOrder, planSize * 2);
                    }
                    planKeys[planSize] = key;
                    // Sort key: priority first, then distance; low 16 bits carry the index back.
                    planOrder[planSize] = ((long) priority << 40) | ((long) Math.min(0xFFFFFF, (int) (distance * 16)) << 16) | planSize;
                    planSize++;
                }
            }
        }
        java.util.Arrays.sort(planOrder, 0, planSize);
        for (int i = 0; i < planSize; i++) {
            planOrder[i] &= 0xFFFFL;
        }
    }

    private void evict(ClientWorld w, int px, int pz) {
        int limit = radius + EVICT_MARGIN;
        store.removeIf(section -> Math.abs((section.sx() << 4) + 8 - px) > limit || Math.abs((section.sz() << 4) + 8 - pz) > limit
                || w.getChunkManager().getWorldChunk(section.sx(), section.sz(), false) == null);
    }

    /** Copies one section of a loaded chunk. Returns false when nothing was read. */
    private boolean extract(ClientWorld w, int sx, int sy, int sz) {
        WorldChunk chunk = w.getChunkManager().getWorldChunk(sx, sz, false);
        if (chunk == null) {
            return false;
        }
        int index = w.getSectionIndex(sy << 4);
        ChunkSection[] sections = chunk.getSectionArray();
        if (index < 0 || index >= sections.length) {
            return false;
        }
        ChunkSection section = sections[index];
        long now = System.currentTimeMillis();
        SectionSnapshot snapshot;
        if (section == null || section.isEmpty()) {
            snapshot = SectionSnapshot.of(sx, sy, sz, new char[SectionSnapshot.VOLUME], new short[SectionSnapshot.VOLUME], now);
        } else {
            char[] cells = new char[SectionSnapshot.VOLUME];
            short[] ores = new short[SectionSnapshot.VOLUME];
            int baseX = sx << 4;
            int baseY = sy << 4;
            int baseZ = sz << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        int i = SectionSnapshot.index(lx, ly, lz);
                        mutable.set(baseX + lx, baseY + ly, baseZ + lz);
                        cells[i] = (char) classifier.cell(state, w, mutable);
                        ores[i] = (short) classifier.stored(state);
                    }
                }
            }
            snapshot = SectionSnapshot.of(sx, sy, sz, cells, ores, now);
        }
        store.put(snapshot);
        extracted++;
        return true;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Immutable view for a worker job, centred on the player. */
    public WorldSnapshot snapshot(ClientPlayerEntity player, int snapshotRadius, int snapshotVertical) {
        return store.snapshot(player.getBlockX(), player.getBlockZ(), snapshotRadius,
                player.getBlockY() - snapshotVertical, player.getBlockY() + snapshotVertical);
    }


}
