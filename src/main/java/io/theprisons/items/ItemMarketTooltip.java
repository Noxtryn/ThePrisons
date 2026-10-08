package io.theprisons.items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Adds only market facts that the shared market service actually knows.  It deliberately has no Minecraft types so
 * the key selection and no-data behaviour are covered by unit tests and can be shared by every surface.
 */
public final class ItemMarketTooltip {
    private ItemMarketTooltip() {
    }

    public static List<String> lines(ItemFacts facts, Function<String, List<String>> detailForCatalog) {
        if (facts == null || detailForCatalog == null) {
            return List.of();
        }
        List<String> detail = detailForCatalog.apply(ItemIdentity.of(facts).catalogKey());
        if (detail == null || detail.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(detail.size() + 1);
        out.add("MARKET DATA");
        for (String line : detail) {
            if (line != null && !line.isBlank()) {
                out.add(line);
            }
        }
        return out.size() == 1 ? List.of() : List.copyOf(out);
    }

    /** Same presentation block for a registry card, whose catalog key is already authoritative. */
    public static List<String> lines(String catalogKey, Function<String, List<String>> detailForCatalog) {
        if (catalogKey == null || catalogKey.isBlank() || detailForCatalog == null) {
            return List.of();
        }
        List<String> detail = detailForCatalog.apply(catalogKey);
        if (detail == null || detail.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(detail.size() + 1);
        out.add("MARKET DATA");
        for (String line : detail) {
            if (line != null && !line.isBlank()) {
                out.add(line);
            }
        }
        return out.size() == 1 ? List.of() : List.copyOf(out);
    }
}
