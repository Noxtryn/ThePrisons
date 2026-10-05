package com.freelocs.theprisons.core.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The blocks the world cache indexes as "targets": the union of what every module asked for. Replaces the old
 * static global set, so two modules with different targets no longer overwrite each other. Client thread only.
 */
public final class TargetRegistry {
    private final Map<Object, Set<Block>> byOwner = new IdentityHashMap<>();
    private volatile Set<Block> union = Set.of();
    private volatile int version;

    /** Replaces the owner's target set (empty = remove). */
    public void set(Object owner, Collection<Block> blocks) {
        if (blocks.isEmpty()) {
            byOwner.remove(owner);
        } else {
            byOwner.put(owner, Set.copyOf(blocks));
        }
        Set<Block> next = new HashSet<>();
        for (Set<Block> owned : byOwner.values()) {
            next.addAll(owned);
        }
        if (!next.equals(union)) {
            union = Set.copyOf(next);
            version++;
        }
    }

    public void clear(Object owner) {
        set(owner, Set.of());
    }

    public boolean contains(BlockState state) {
        return union.contains(state.getBlock());
    }

    public boolean contains(Block block) {
        return union.contains(block);
    }

    /** Block key of the state when it is a target, else 0. */
    public int keyOf(BlockState state) {
        return union.contains(state.getBlock()) ? BlockKeys.key(Registries.BLOCK.getId(state.getBlock()).toString()) : 0;
    }

    public boolean isEmpty() {
        return union.isEmpty();
    }

    public int version() {
        return version;
    }
}
