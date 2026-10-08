package io.theprisons.items;

import java.util.ArrayList;
import java.util.List;

/**
 * The prepared search over a fixed list of entries. The text of every entry is normalised when the index is built; a search compares the already
 * normalised strings only - no lore, no normalising per entry, no allocation per entry.
 *
 * <p>Every word of the query must appear in the entry (in any order). Ranking: the whole name equals the query, the name starts with it, a word of the
 * name starts with it, anything else that matches (alias, category ...).
 */
public final class ItemSearchIndex {
    private final List<ItemEntry> entries;
    private final String[] text;
    private final String[] name;

    public ItemSearchIndex(List<ItemEntry> entries) {
        this.entries = List.copyOf(entries);
        this.text = new String[this.entries.size()];
        this.name = new String[this.entries.size()];
        for (int i = 0; i < text.length; i++) {
            text[i] = this.entries.get(i).searchText();
            name[i] = this.entries.get(i).nameText();
        }
    }

    public int size() {
        return entries.size();
    }

    /** The entries that match, best first; the whole list (in registry order) for an empty query. */
    public List<ItemEntry> search(String query) {
        String q = SearchText.normalize(query);
        if (q.isEmpty()) {
            return entries;
        }
        String[] words = q.split(" ");
        int n = entries.size();
        int[] rank = new int[n];
        int hits = 0;
        for (int i = 0; i < n; i++) {
            if (!matches(text[i], words)) {
                rank[i] = -1;
                continue;
            }
            hits++;
            String nm = name[i];
            rank[i] = nm.equals(q) ? 0 : nm.startsWith(q) ? 1 : nm.contains(" " + q) ? 2 : wordStart(nm, words) ? 3 : 4;
        }
        List<ItemEntry> out = new ArrayList<>(hits);
        for (int r = 0; r <= 4 && out.size() < hits; r++) {
            for (int i = 0; i < n; i++) {
                if (rank[i] == r) {
                    out.add(entries.get(i));
                }
            }
        }
        return out;
    }

    private static boolean matches(String hay, String[] words) {
        for (String w : words) {
            if (!hay.contains(w)) {
                return false;
            }
        }
        return true;
    }

    private static boolean wordStart(String nm, String[] words) {
        for (String w : words) {
            if (!(nm.startsWith(w) || nm.contains(" " + w))) {
                return false;
            }
        }
        return true;
    }
}
