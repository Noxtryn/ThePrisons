package io.theprisons.modules.qol.bandit.nav;

import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.model.Entry;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.core.cosmic.value.GameValue;

/**
 * Reads {@link SpearAreaState} from the game model: the zone the chat last named, looked up in the zone registry, attribute
 * {@code spearUsable} (a boolean at least OBSERVED). The attribute is UNKNOWN for every zone today, so the answer is UNKNOWN until the owner
 * (or a capture) supplies the rule - and every capture carries the evidence (zone, sidebar, action bar, spear state) to learn it.
 */
public final class SpearAreaEvaluator {
    private SpearAreaEvaluator() {
    }

    public static SpearAreaState evaluate(CosmicGameModel model, String zone) {
        if (zone == null || zone.isBlank()) {
            return SpearAreaState.UNKNOWN;
        }
        Entry entry = model.zones().forZone(zone);
        GameValue<Boolean> usable = entry.attribute("spearUsable", Boolean.class);
        if (!usable.isKnown() || !usable.confidence().atLeast(Confidence.OBSERVED)) {
            return SpearAreaState.UNKNOWN;
        }
        return Boolean.TRUE.equals(usable.value()) ? SpearAreaState.VALID : SpearAreaState.INVALID;
    }
}
