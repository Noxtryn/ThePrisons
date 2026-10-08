package io.theprisons.core.cosmic.parse;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Server-wide events announced in chat. Only what the repository has actually seen is recognised; the rest stays unknown. */
public final class EventParser {
    public static final String METEOR = "meteor";
    /** The session HUD treats a meteor as active this long after the announcement. */
    public static final long METEOR_ACTIVE_MS = 10L * 60_000L;

    private EventParser() {
    }

    /** The event a system chat line announces ("meteor"), or {@code null}. */
    public static @Nullable String parse(String strippedLine) {
        String lower = strippedLine.toLowerCase(Locale.ROOT);
        if (lower.contains("meteor") && (lower.contains("has crashed") || lower.contains("is falling"))) {
            return METEOR;
        }
        return null;
    }

    /** How long an event stays "current" after its announcement; 0 for an unknown event. */
    public static long activeMs(@Nullable String event) {
        return METEOR.equals(event) ? METEOR_ACTIVE_MS : 0L;
    }
}
