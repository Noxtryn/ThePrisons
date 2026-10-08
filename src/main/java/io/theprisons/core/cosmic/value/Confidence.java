package io.theprisons.core.cosmic.value;

/**
 * How far a Cosmic Prisons value can be trusted. Cosmic changes mechanics with updates, so nothing is a fact unless it
 * says so, and {@link #UNKNOWN} is a state of its own: it is never turned into 0 or "false" on the way.
 */
public enum Confidence {
    /** Stated by the server itself (rules, wiki, release notes) - the owner confirmed the source. */
    VERIFIED_OFFICIAL(3),
    /** Read from the live game right now (sidebar, lore, action bar, an entity in view). Correct as far as the parser is. */
    VERIFIED_LIVE(2),
    /** Seen while playing or in a log and plausible, but not guaranteed: heuristics, measurements, remembered values. */
    OBSERVED(1),
    /** Not known. Features must handle this (skip, ask, use their own default), never assume. */
    UNKNOWN(0);

    private final int rank;

    Confidence(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** True when this is at least as trustworthy as {@code minimum}. */
    public boolean atLeast(Confidence minimum) {
        return rank >= minimum.rank;
    }

    public boolean known() {
        return this != UNKNOWN;
    }

    /** Case-insensitive; throws {@link IllegalArgumentException} for an unrecognised name (data files must not guess). */
    public static Confidence parse(String name) {
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("unknown confidence: " + name);
        }
    }
}
