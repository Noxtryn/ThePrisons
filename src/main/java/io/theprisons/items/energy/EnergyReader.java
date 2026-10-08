package io.theprisons.items.energy;

import io.theprisons.core.cosmic.parse.PickaxeLore;
import io.theprisons.items.ItemFacts;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Reads energy from an item - only the forms that are known from real items: the lore section of a pickaxe or satchel ("Cosmic Energy", a bar, then
 * "(0 / 13,200)", parsed by {@link PickaxeLore}) and the {@code amount} stored on a Cosmic Energy item. Anything else is not energy: null.
 */
public final class EnergyReader {
    private EnergyReader() {
    }

    public static @Nullable EnergyReading read(ItemFacts facts) {
        String subject = facts.values().getOrDefault("custom_item_uuid", facts.customId() != null ? facts.customId() : facts.name());
        PickaxeLore.Energy lore = PickaxeLore.read(facts.lore());
        if (lore != null) {
            return new EnergyReading(subject, lore.now(), lore.capacity() > 0 ? lore.capacity() : null, EnergySource.ITEM_LORE);
        }
        if ("cosmic_energy".equals(facts.customId())) {
            String amount = facts.values().get("amount");
            if (amount != null) {
                try {
                    return new EnergyReading(subject, Math.round(Double.parseDouble(amount)), null, EnergySource.ITEM_VALUE);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Whether an item or a screen title names an extractor. This is a WORD heuristic: no real extractor menu has been captured yet
     * ({@code /prisons capture} in the game would give one), so it only decides whether the overlay may show, never what it shows.
     */
    public static boolean mentionsExtractor(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("extractor");
    }
}
