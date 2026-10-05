package com.freelocs.theprisons.core.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns block registry ids ("minecraft:redstone_ore") to small positive keys so ore data can be stored
 * in short arrays. Keys are only stable within one session.
 */
public final class BlockKeys {
    private static final Map<String, Integer> KEYS = new HashMap<>();
    private static final List<String> IDS = new ArrayList<>(List.of(""));

    private BlockKeys() {
    }

    public static synchronized int key(String id) {
        Integer existing = KEYS.get(id);
        if (existing != null) {
            return existing;
        }
        if (IDS.size() >= SectionSnapshot.KEY_MASK) {
            throw new IllegalStateException("Too many distinct ore block ids");
        }
        int key = IDS.size();
        IDS.add(id);
        KEYS.put(id, key);
        return key;
    }

    public static synchronized String id(int key) {
        return key > 0 && key < IDS.size() ? IDS.get(key) : "";
    }
}
