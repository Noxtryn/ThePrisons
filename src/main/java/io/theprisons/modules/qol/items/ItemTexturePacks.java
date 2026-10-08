package io.theprisons.modules.qol.items;

import java.util.ArrayList;
import java.util.List;

/** The one built-in, always-on artwork overlay. It contains only approved paths; all other item paths fall through. */
public final class ItemTexturePacks {
    public static final String STANDARD_PATH = "resourcepacks/theprisons_items_standard";
    public static final String STANDARD_ID = "theprisons_items_standard";
    public static final String STANDARD_NAME = "ThePrisons Item Design";

    private ItemTexturePacks() {
    }

    /** Keeps player packs intact, then overlays the 35 official paths and finally the pre-existing look pack. */
    public static <P> List<P> assemble(List<P> built, P standard, P look) {
        List<P> out = new ArrayList<>(built);
        if (standard != null && !out.contains(standard)) {
            out.add(standard);
        }
        if (look != null && !out.contains(look)) {
            out.add(look);
        }
        return out;
    }

    /** Exact allow-list of models covered by the supplied standard artwork; random variants intentionally fall through. */
    public static boolean approvedModel(String namespace, String path) {
        if (!"theprisons".equals(namespace)) {
            return false;
        }
        return path.matches("prisons/(shard|contraband|book|book_revealed|key)/(simple|uncommon|elite|ultimate|legendary|godly)")
                || path.equals("prisons/misc/executive_shard")
                || path.matches("prisons/charge_orb/stage_[1-4]");
    }
}
