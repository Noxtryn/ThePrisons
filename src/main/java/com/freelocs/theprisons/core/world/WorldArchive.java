package com.freelocs.theprisons.core.world;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.nav.Cell;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.profiling.Profiler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The macro's memory of the world ("virtual world"): every block of every chunk the client has loaded, up to
 * {@value #RADIUS} blocks to each side, reduced to air / solid / danger / which ore, and saved to region files - so
 * on the next start the macro already knows the whole mine, also the parts out of sight.
 *
 * <ul>
 *     <li><b>Only while open:</b> a module {@link #open}s it for a world folder; closed it scans and stores nothing.</li>
 *     <li><b>Full scan:</b> every loaded chunk is read once per session ({@value #VERTICAL} blocks above and below the
 *     player), nearest first, within {@value #BUDGET_NS} ns per tick. Server block updates and chunk (re)loads make
 *     their sections be read again, so mined ores (now stone) and respawned ores are kept up to date.</li>
 *     <li><b>Disk:</b> regions of 32x32 chunks ({@link ArchiveCodec}), loaded around the player when opened and as it
 *     walks, written every {@value #SAVE_TICKS} ticks when changed and when closed. Regions further than
 *     {@value #KEEP_RADIUS} blocks are dropped from memory once saved.</li>
 * </ul>
 * The client only receives chunks within the server's view distance: what lies further out comes from earlier runs.
 * Client thread only; workers get an {@link ArchiveView} via {@link #view}.
 */
public final class WorldArchive {
    public static final int RADIUS = 512;
    public static final int VERTICAL = 64;
    public static final long BUDGET_NS = 1_000_000L;
    static final int KEEP_RADIUS = 768;
    static final int SAVE_TICKS = 1200;
    private static final int SWEEP_TICKS = 40;
    private static final int HOUSEKEEPING_TICKS = 200;

    private final WorldCache cache;
    private final Profiler.Section profile;
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final Long2ObjectOpenHashMap<ArchiveSection> sections = new Long2ObjectOpenHashMap<>();
    /** Sections read from the live world in this session (newer than anything on disk). */
    private final LongOpenHashSet fresh = new LongOpenHashSet();
    /** Sections to read again first (block updates). */
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    /** Loaded chunks (packed x, 0, z) not read completely in this session. */
    private final LongOpenHashSet pendingChunks = new LongOpenHashSet();
    private long[] sweep = new long[0];
    private int sweepCursor;
    private final LongOpenHashSet dirtyRegions = new LongOpenHashSet();
    private final LongOpenHashSet loadedRegions = new LongOpenHashSet();
    private final LongOpenHashSet loadingRegions = new LongOpenHashSet();
    /** Ore palette: block id per index, code per id, block key per index. */
    private final List<String> oreIds = new ArrayList<>();
    private final Object2IntOpenHashMap<String> codeOfId = new Object2IntOpenHashMap<>();
    private int[] oreKeys = new int[0];
    private final Reference2IntOpenHashMap<BlockState> codeCache = new Reference2IntOpenHashMap<>();
    private int targetsVersion = -1;

    private @Nullable Path dir;
    private @Nullable ClientWorld world;
    private int ticks;
    private long version;
    private long readSections;

    public WorldArchive(WorldCache cache, Profiler profiler) {
        this.cache = cache;
        this.profile = profiler.section("world:archive");
        codeCache.defaultReturnValue(-1);
    }

    // ── Open / close ─────────────────────────────────────────────────────────

    public boolean isOpen() {
        return dir != null;
    }

    public @Nullable Path dir() {
        return dir;
    }

    /** Starts scanning and storing for this world folder (no-op when it is already open). */
    public void open(MinecraftClient client, Path folder) {
        if (folder.equals(dir) && client.world == world) {
            return;
        }
        close();
        dir = folder;
        world = client.world;
        ClientPlayerEntity player = client.player;
        ClientWorld w = world;
        if (player != null && w != null) {
            int pcx = player.getBlockX() >> 4;
            int pcz = player.getBlockZ() >> 4;
            int r = RADIUS >> 4;
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    if (w.getChunkManager().getWorldChunk(cx, cz, false) != null) {
                        pendingChunks.add(Pos.pack(cx, 0, cz));
                    }
                }
            }
            loadAround(player.getBlockX(), player.getBlockZ());
        }
        ThePrisonsClient.LOGGER.info("[archive] opened {} ({} loaded chunks to read)", folder, pendingChunks.size());
    }

    /** Saves what changed and forgets everything. */
    public void close() {
        if (dir == null) {
            return;
        }
        saveDirty(true);
        dir = null;
        world = null;
        sections.clear();
        fresh.clear();
        dirty.clear();
        pendingChunks.clear();
        sweep = new long[0];
        sweepCursor = 0;
        dirtyRegions.clear();
        loadedRegions.clear();
        loadingRegions.clear();
        version++;
    }

    // ── Events (forwarded by the core) ───────────────────────────────────────

    public void onWorldChanged(@Nullable ClientWorld current) {
        if (current != world) {
            close();
        }
    }

    public void onChunkLoaded(ClientWorld loadedWorld, int cx, int cz) {
        if (dir != null && loadedWorld == world) {
            pendingChunks.add(Pos.pack(cx, 0, cz));
        }
    }

    public void onBlockChanged(ClientWorld changedWorld, long pos) {
        if (dir != null && changedWorld == world) {
            dirty.add(Pos.sectionOf(pos));
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(MinecraftClient client) {
        if (dir == null) {
            return;
        }
        if (client.world != world) {
            close();
            return;
        }
        ClientPlayerEntity player = client.player;
        ClientWorld w = world;
        if (player == null || w == null) {
            return;
        }
        long start = profile.begin();
        try {
            ticks++;
            if (cache.targets().version() != targetsVersion) {
                // Which blocks are ores changed: states are classified anew, the loaded chunks read again.
                targetsVersion = cache.targets().version();
                codeCache.clear();
                fresh.clear();
                pendingChunks.addAll(loadedChunksAround(w, player));
            }
            int px = player.getBlockX();
            int py = player.getBlockY();
            int pz = player.getBlockZ();
            if (ticks % HOUSEKEEPING_TICKS == 0) {
                evict(px, pz);
                loadAround(px, pz);
            }
            if (ticks % SAVE_TICKS == 0) {
                saveDirty(false);
            }
            while (!dirty.isEmpty() && System.nanoTime() - start < BUDGET_NS) {
                long key = dirty.removeFirstLong();
                read(w, Pos.x(key), Pos.y(key), Pos.z(key));
            }
            if (ticks % SWEEP_TICKS == 0 || sweepCursor >= sweep.length) {
                rebuildSweep(px, pz);
            }
            while (sweepCursor < sweep.length && System.nanoTime() - start < BUDGET_NS) {
                long chunk = sweep[sweepCursor++];
                if (!pendingChunks.contains(chunk)) {
                    continue;
                }
                int cx = Pos.x(chunk);
                int cz = Pos.z(chunk);
                if (w.getChunkManager().getWorldChunk(cx, cz, false) == null) {
                    pendingChunks.remove(chunk);
                    continue;
                }
                int minSy = Math.max(w.getBottomSectionCoord(), (py - VERTICAL) >> 4);
                int maxSy = Math.min(w.getTopSectionCoord() - 1, (py + VERTICAL) >> 4);
                for (int sy = minSy; sy <= maxSy; sy++) {
                    read(w, cx, sy, cz);
                }
                pendingChunks.remove(chunk);
            }
        } finally {
            profile.end(start);
        }
    }

    private List<Long> loadedChunksAround(ClientWorld w, ClientPlayerEntity player) {
        List<Long> chunks = new ArrayList<>();
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int r = RADIUS >> 4;
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (w.getChunkManager().getWorldChunk(cx, cz, false) != null) {
                    chunks.add(Pos.pack(cx, 0, cz));
                }
            }
        }
        return chunks;
    }

    /** The pending chunks within {@link #RADIUS}, nearest first; the ones further out are dropped. */
    private void rebuildSweep(int px, int pz) {
        int pcx = px >> 4;
        int pcz = pz >> 4;
        int r = RADIUS >> 4;
        LongArrayList near = new LongArrayList();
        LongArrayList far = new LongArrayList();
        for (long chunk : pendingChunks) {
            int dx = Pos.x(chunk) - pcx;
            int dz = Pos.z(chunk) - pcz;
            (Math.abs(dx) > r || Math.abs(dz) > r ? far : near).add(chunk);
        }
        // Out of range now: read when the player comes closer again (the chunk load event marks it anew).
        for (long chunk : far) {
            pendingChunks.remove(chunk);
        }
        long[] order = new long[near.size()];
        for (int i = 0; i < order.length; i++) {
            long chunk = near.getLong(i);
            long dx = Pos.x(chunk) - pcx;
            long dz = Pos.z(chunk) - pcz;
            order[i] = (dx * dx + dz * dz) << 32 | i;
        }
        java.util.Arrays.sort(order);
        long[] keys = new long[order.length];
        for (int k = 0; k < order.length; k++) {
            keys[k] = near.getLong((int) (order[k] & 0xFFFFFFFFL));
        }
        sweep = keys;
        sweepCursor = 0;
    }

    /** Reads one section of a loaded chunk into the archive. */
    private void read(ClientWorld w, int sx, int sy, int sz) {
        WorldChunk chunk = w.getChunkManager().getWorldChunk(sx, sz, false);
        if (chunk == null) {
            return;
        }
        int index = w.getSectionIndex(sy << 4);
        ChunkSection[] array = chunk.getSectionArray();
        if (index < 0 || index >= array.length) {
            return;
        }
        ChunkSection section = array[index];
        long now = System.currentTimeMillis();
        ArchiveSection next;
        if (section == null || section.isEmpty()) {
            next = new ArchiveSection(sx, sy, sz, null, ArchiveSection.AIR, now);
        } else {
            byte[] codes = new byte[ArchiveSection.VOLUME];
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        codes[ArchiveSection.index(lx, ly, lz)] = (byte) code(section.getBlockState(lx, ly, lz));
                    }
                }
            }
            next = ArchiveSection.of(sx, sy, sz, codes, now);
        }
        long key = next.key();
        fresh.add(key);
        readSections++;
        ArchiveSection previous = sections.put(key, next);
        if (!next.sameContent(previous)) {
            version++;
            dirtyRegions.add(ArchiveCodec.regionOf(sx, sz));
        }
    }

    private int code(BlockState state) {
        if (state.isAir()) {
            return ArchiveSection.AIR;
        }
        int cached = codeCache.getInt(state);
        if (cached >= 0) {
            return cached;
        }
        int cell = cache.classifier().cell(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        int code;
        if (Cell.isDanger(cell) || Cell.isLiquid(cell)) {
            code = ArchiveSection.DANGER;
        } else if (!Cell.hasCollision(cell)) {
            code = ArchiveSection.AIR;
        } else if (cache.targets().contains(state)) {
            code = oreCode(Registries.BLOCK.getId(state.getBlock()).toString());
        } else if (BlockClassifier.isMineFloor(state.getBlock())) {
            code = ArchiveSection.SOLID;
        } else {
            code = ArchiveSection.OTHER;
        }
        codeCache.put(state, code);
        return code;
    }

    /** The archive's code for an ore block id (new ids are added to the palette; a full palette stores solid). */
    synchronized int oreCode(String id) {
        int existing = codeOfId.getOrDefault(id, -1);
        if (existing >= 0) {
            return existing;
        }
        if (oreIds.size() >= ArchiveSection.MAX_ORES) {
            return ArchiveSection.SOLID;
        }
        int code = ArchiveSection.ORE_BASE + oreIds.size();
        oreIds.add(id);
        codeOfId.put(id, code);
        int[] keys = java.util.Arrays.copyOf(oreKeys, oreIds.size());
        keys[keys.length - 1] = BlockKeys.key(id);
        oreKeys = keys;
        return code;
    }

    // ── Disk ─────────────────────────────────────────────────────────────────

    private void loadAround(int px, int pz) {
        Path folder = dir;
        if (folder == null) {
            return;
        }
        int r = (KEEP_RADIUS >> 4 >> ArchiveCodec.REGION_SHIFT) + 1;
        int prx = px >> 4 >> ArchiveCodec.REGION_SHIFT;
        int prz = pz >> 4 >> ArchiveCodec.REGION_SHIFT;
        MinecraftClient client = MinecraftClient.getInstance();
        for (int rx = prx - r; rx <= prx + r; rx++) {
            for (int rz = prz - r; rz <= prz + r; rz++) {
                long region = Pos.pack(rx, 0, rz);
                if (loadedRegions.contains(region) || loadingRegions.contains(region)) {
                    continue;
                }
                Path file = ArchiveCodec.file(folder, region);
                loadingRegions.add(region);
                CompletableFuture.supplyAsync(() -> {
                    if (!Files.exists(file)) {
                        return List.<ArchiveSection>of();
                    }
                    try {
                        return ArchiveCodec.read(file, this::oreCode);
                    } catch (IOException | RuntimeException error) {
                        ThePrisonsClient.LOGGER.warn("[archive] could not read {}", file, error);
                        return List.<ArchiveSection>of();
                    }
                }, Util.getIoWorkerExecutor()).thenAcceptAsync(loaded -> {
                    if (!folder.equals(dir) || !loadingRegions.remove(region)) {
                        return;
                    }
                    loadedRegions.add(region);
                    int added = 0;
                    for (ArchiveSection s : loaded) {
                        // What was read live this session is newer than the file.
                        if (!fresh.contains(s.key())) {
                            sections.put(s.key(), s);
                            added++;
                        }
                    }
                    if (added > 0) {
                        version++;
                        ThePrisonsClient.LOGGER.info("[archive] loaded {} sections from {}", added, file.getFileName());
                    }
                }, client);
            }
        }
    }

    /** Writes every changed region; {@code now} = right here (closing), else on the IO thread. */
    private void saveDirty(boolean now) {
        Path folder = dir;
        if (folder == null || dirtyRegions.isEmpty()) {
            return;
        }
        List<String> palette;
        synchronized (this) {
            palette = List.copyOf(oreIds);
        }
        for (long region : dirtyRegions) {
            if (!loadedRegions.contains(region)) {
                // Its file is still being read: written with the next save (else the file's sections would be lost).
                continue;
            }
            List<ArchiveSection> content = new ArrayList<>();
            for (ArchiveSection s : sections.values()) {
                if (ArchiveCodec.regionOf(s.sx(), s.sz()) == region) {
                    content.add(s);
                }
            }
            Path file = ArchiveCodec.file(folder, region);
            Runnable write = () -> {
                try {
                    ArchiveCodec.write(file, content, palette);
                } catch (IOException error) {
                    ThePrisonsClient.LOGGER.warn("[archive] could not write {}", file, error);
                }
            };
            if (now) {
                write.run();
            } else {
                Util.getIoWorkerExecutor().execute(write);
            }
        }
        dirtyRegions.removeIf((java.util.function.LongPredicate) loadedRegions::contains);
    }

    /** Drops regions far away (saved, not dirty) from memory; their files stay. */
    private void evict(int px, int pz) {
        int prx = px >> 4 >> ArchiveCodec.REGION_SHIFT;
        int prz = pz >> 4 >> ArchiveCodec.REGION_SHIFT;
        int r = (KEEP_RADIUS >> 4 >> ArchiveCodec.REGION_SHIFT) + 1;
        LongOpenHashSet drop = new LongOpenHashSet();
        for (long region : loadedRegions) {
            if ((Math.abs(Pos.x(region) - prx) > r || Math.abs(Pos.z(region) - prz) > r) && !dirtyRegions.contains(region)) {
                drop.add(region);
            }
        }
        if (drop.isEmpty()) {
            return;
        }
        loadedRegions.removeAll(drop);
        sections.values().removeIf(s -> drop.contains(ArchiveCodec.regionOf(s.sx(), s.sz())));
        fresh.removeIf((java.util.function.LongPredicate) key -> drop.contains(ArchiveCodec.regionOf(Pos.x(key), Pos.z(key))));
        version++;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Frozen copy of the sections within {@code radius} blocks (Chebyshev) and {@code [minY, maxY]}, for a worker. */
    public ArchiveView view(int centerX, int centerZ, int radius, int minY, int maxY) {
        Long2ObjectOpenHashMap<ArchiveSection> copy = new Long2ObjectOpenHashMap<>();
        int minCx = (centerX - radius) >> 4;
        int maxCx = (centerX + radius) >> 4;
        int minCz = (centerZ - radius) >> 4;
        int maxCz = (centerZ + radius) >> 4;
        int minSy = minY >> 4;
        int maxSy = maxY >> 4;
        for (ArchiveSection s : sections.values()) {
            if (s.sx() >= minCx && s.sx() <= maxCx && s.sz() >= minCz && s.sz() <= maxCz && s.sy() >= minSy && s.sy() <= maxSy) {
                copy.put(s.key(), s);
            }
        }
        int[] keys;
        synchronized (this) {
            keys = oreKeys;
        }
        return new ArchiveView(copy, keys);
    }

    /** Changes whenever the stored blocks change. */
    public long version() {
        return version;
    }

    public int sectionCount() {
        return sections.size();
    }

    public int pendingChunks() {
        return pendingChunks.size();
    }

    public long readSections() {
        return readSections;
    }

    /** Regions (32x32 chunks) in memory. */
    public int loadedRegions() {
        return loadedRegions.size();
    }
}
