package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The item list's state and its derived view. The view is recomputed only when something changed: the query, the category, the tiers, or the registry
 * (tracked by revisions). Reading {@link #view()} every frame costs two long comparisons.
 *
 * <p>The search index is rebuilt only when the registry changed; the tier row is derived from the entries of the chosen category, so it only appears where
 * a family really has tiers.
 */
public final class ItemListModel {
    private final ItemRegistry registry;
    private ItemFilter filter = ItemFilter.NONE;
    private long filterRevision;
    private ItemSearchIndex index;
    private long indexRevision = -1;
    private ItemView view;
    private int computations;

    public ItemListModel(ItemRegistry registry) {
        this.registry = registry;
    }

    public ItemFilter filter() {
        return filter;
    }

    public void setQuery(String q) {
        change(filter.withQuery(q));
    }

    public void setCategory(@Nullable ItemCategory c) {
        change(filter.withCategory(c));
    }

    public void setTiers(java.util.Set<String> tiers) {
        change(filter.withTiers(tiers));
    }

    public void toggleTier(String tier) {
        java.util.Set<String> next = new java.util.HashSet<>(filter.tiers());
        if (!next.remove(tier)) {
            next.add(tier);
        }
        setTiers(next);
    }

    private void change(ItemFilter next) {
        if (!next.equals(filter)) {
            filter = next;
            filterRevision++;
        }
    }

    /** How many times the view was really recomputed (a test and the dev overlay look at it). */
    public int computations() {
        return computations;
    }

    public ItemView view() {
        if (view != null && view.registryRevision() == registry.revision() && view.filterRevision() == filterRevision) {
            return view;
        }
        long t0 = System.nanoTime();
        if (index == null || indexRevision != registry.revision()) {
            index = new ItemSearchIndex(registry.all());
            indexRevision = registry.revision();
        }
        List<ItemEntry> matched = index.search(filter.query());
        long t1 = System.nanoTime();
        List<ItemEntry> out = new ArrayList<>(matched.size());
        TreeSet<Integer> tierRanks = new TreeSet<>();
        for (ItemEntry e : matched) {
            // the tier row follows the category and the search, but not the tier filter itself (or picking one would hide the others)
            if (filter.category() == null || e.category() == filter.category()) {
                if (e.tier() != null) {
                    tierRanks.add(e.tierRank());
                }
            }
            if (filter.accepts(e)) {
                out.add(e);
            }
        }
        List<String> tiers = new ArrayList<>();
        for (int rank : tierRanks) {
            tiers.add(ItemTier.ALL.get(rank));
        }
        long t2 = System.nanoTime();
        computations++;
        view = new ItemView(out, tiers, registry.revision(), filterRevision, t1 - t0, t2 - t1, registry.size());
        return view;
    }
}
