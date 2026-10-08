package io.theprisons.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every item the mod knows, in one place. Seeded from the mod's own family directory and extended with items the game really shows (learned once, with a
 * hard cap). Client thread only. {@link #revision()} changes only when an entry is added, so everything derived (search index, filtered lists) knows when
 * to rebuild and otherwise never does.
 */
public final class ItemRegistry {
    public static final int MAX_ENTRIES = 4000;

    private final Map<String, ItemEntry> byKey = new LinkedHashMap<>();
    private List<ItemEntry> snapshot = List.of();
    private boolean snapshotDirty;
    private long revision;

    /** The registry with every family of the directory. */
    public static ItemRegistry seeded() {
        ItemRegistry r = new ItemRegistry();
        for (io.theprisons.modules.qol.market.ItemDirectory.Spec spec : io.theprisons.modules.qol.market.ItemDirectory.all()) {
            for (ItemEntry e : ItemEntries.fromDirectory(spec)) {
                r.add(e);
            }
        }
        return r;
    }

    /** Adds an entry; false when it exists already (the first one wins: the directory is more exact than a seen item) or the cap is reached. */
    public boolean add(ItemEntry e) {
        if (byKey.containsKey(e.key()) || byKey.size() >= MAX_ENTRIES) {
            return false;
        }
        byKey.put(e.key(), e);
        snapshotDirty = true;
        revision++;
        return true;
    }

    /** Learns a real item the game showed. Not an upgraded piece (not trackable) and not one that is known already. */
    public boolean learn(ItemFacts facts, String icon) {
        ItemClass cls = ItemClassifier.classify(facts);
        if (!cls.trackable() || byKey.containsKey(cls.catalogKey())) {
            return false;
        }
        return add(ItemEntries.build(facts, cls, icon, null, List.of(), List.of(), "SEEN"));
    }

    public ItemEntry get(String catalogKey) {
        return byKey.get(catalogKey);
    }

    public int size() {
        return byKey.size();
    }

    public long revision() {
        return revision;
    }

    /** All entries, immutable, rebuilt only after a change. */
    public List<ItemEntry> all() {
        if (snapshotDirty) {
            snapshot = Collections.unmodifiableList(new ArrayList<>(byKey.values()));
            snapshotDirty = false;
        }
        return snapshot;
    }
}
