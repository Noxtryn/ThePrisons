package io.theprisons.modules.qol.bandit.combat;

import io.theprisons.core.cosmic.value.GameValue;

/**
 * What the combat controller knows about the spear's cooldown. Nothing is assumed: a cooldown the client cannot read is
 * {@link #UNKNOWN}, and UNKNOWN is handled conservatively (attacks are paced by the macro, never by an invented game value).
 */
public enum SpearStatus {
    KNOWN_READY, KNOWN_COOLDOWN, UNKNOWN;

    /**
     * @param recognised whether a spear is held
     * @param cooldown   the item cooldown progress 0..1 (1 = just used) when the client can read it
     */
    public static SpearStatus of(GameValue<Boolean> recognised, GameValue<Double> cooldown) {
        if (!Boolean.TRUE.equals(recognised.value()) || !cooldown.isKnown()) {
            return UNKNOWN;
        }
        return cooldown.value() > 0.0D ? KNOWN_COOLDOWN : KNOWN_READY;
    }
}
