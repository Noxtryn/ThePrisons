package io.theprisons.core.cosmic.sense;

import io.theprisons.core.cosmic.data.Raw;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Counts the blocks in a small cube around the player (default radius 4 = 729 block states): ores by block id, everything else as
 * "#air", "#solid", "#fluid". Read at a low rate by the service (about once a second); loaded chunks only, no chunk loading.
 */
public final class BlockSensor {
    public static final int MAX_RADIUS = 8;
    private final Map<BlockState, String> keys = new IdentityHashMap<>();

    public Raw.@Nullable Blocks sample(MinecraftClient client, int radius) {
        ClientPlayerEntity p = client.player;
        ClientWorld world = client.world;
        if (p == null || world == null) {
            return null;
        }
        int r = Math.max(1, Math.min(MAX_RADIUS, radius));
        BlockPos center = p.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Map<String, Integer> counts = new HashMap<>();
        int scanned = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!world.isChunkLoaded(pos)) {
                        continue;
                    }
                    scanned++;
                    counts.merge(keyOf(world.getBlockState(pos)), 1, Integer::sum);
                }
            }
        }
        BlockState below = world.getBlockState(center.down());
        boolean solidBelow = !below.isAir() && below.getFluidState().isEmpty() && below.isFullCube(world, center.down());
        boolean headroom = world.getBlockState(center.up(1)).isAir() && world.getBlockState(center.up(2)).isAir();
        return new Raw.Blocks(r, scanned, counts, solidBelow, headroom);
    }

    private String keyOf(BlockState state) {
        String key = keys.get(state);
        if (key == null) {
            if (state.isAir()) {
                key = "#air";
            } else if (!state.getFluidState().isEmpty()) {
                key = "#fluid";
            } else {
                String id = Registries.BLOCK.getId(state.getBlock()).toString();
                key = id.endsWith("_ore") ? id : "#solid";
            }
            if (keys.size() > 4096) {
                keys.clear();
            }
            keys.put(state, key);
        }
        return key;
    }
}
