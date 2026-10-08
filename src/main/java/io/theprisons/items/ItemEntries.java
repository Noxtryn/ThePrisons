package io.theprisons.items;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds registry entries (everything that is expensive is done here, once). */
public final class ItemEntries {
    private ItemEntries() {
    }

    /** @param tiers EMPTY for a family without rarities; @param variants non-tier variants of the family */
    public static ItemEntry build(ItemFacts facts, ItemClass cls, String icon, @Nullable String model, List<String> tiers, List<String> variants, String source) {
        ItemIdentity identity = ItemIdentity.of(facts, cls);
        String display = cls.displayName();
        List<String> aliases = ItemAliases.of(cls.family());
        String internal = facts.customId() != null ? facts.customId() : display.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        StringBuilder text = new StringBuilder();
        text.append(display).append(' ').append(internal.replace('_', ' ')).append(' ').append(cls.category().label()).append(' ').append(cls.subcategory());
        if (cls.tier() != null) {
            text.append(' ').append(cls.tier());
        }
        if (cls.variant() != null) {
            text.append(' ').append(cls.variant());
        }
        for (String alias : aliases) {
            text.append(' ').append(alias);
        }
        return new ItemEntry(identity, display, internal, cls.category(), cls.subcategory(), cls.family(), cls.tier(), cls.variant(), aliases,
                SearchText.normalize(text.toString()), SearchText.normalize(display), new ItemRenderData(icon, display, model), new ItemMetadata(tiers, variants, source));
    }

    /** A directory entry: a family with its (known) tiers or variants - one entry per variant. */
    public static List<ItemEntry> fromDirectory(io.theprisons.modules.qol.market.ItemDirectory.Spec spec) {
        List<ItemEntry> out = new ArrayList<>();
        List<String> tiers = new ArrayList<>();
        List<String> variants = new ArrayList<>();
        for (String r : spec.rarities()) {
            if (ItemTier.isTier(r)) {
                tiers.add(ItemTier.canonical(r));
            } else {
                variants.add(r);
            }
        }
        for (String name : spec.names()) {
            ItemFacts facts = ItemFacts.ofName(spec.icon(), name);
            ItemClass cls = ItemClassifier.classify(facts);
            out.add(build(facts, cls, spec.icon(), io.theprisons.modules.qol.market.ItemDirectory.maskModel(name), tiers, variants, "DIRECTORY"));
        }
        return out;
    }
}
