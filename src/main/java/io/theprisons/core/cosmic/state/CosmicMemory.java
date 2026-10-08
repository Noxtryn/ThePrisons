package io.theprisons.core.cosmic.state;

import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.parse.EventParser;
import io.theprisons.core.cosmic.parse.PrivacyFilter;
import io.theprisons.core.cosmic.parse.ZoneParser;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The little the model remembers between samples, fed by the server's messages: the zone, the running event, the last action bar
 * lines and a short list of system lines. Player chat is never stored, private messages are dropped, everything stored goes
 * through {@link PrivacyFilter}. Bounded: nothing here grows with the session. Client thread only (the zone is volatile for
 * readers elsewhere).
 */
public final class CosmicMemory {
    public static final int ACTION_BAR_LINES = 4;
    public static final int SYSTEM_LINES = 12;

    private volatile String zone = "";
    private volatile long zoneAtMs;
    private @Nullable String event;
    private long eventAtMs;
    private final ArrayDeque<Raw.Line> actionBar = new ArrayDeque<>();
    private final ArrayDeque<Raw.Line> system = new ArrayDeque<>();

    /** The zone named by the last zone message ("" = none yet). */
    public String zone() {
        return zone;
    }

    public long zoneAtMs() {
        return zoneAtMs;
    }

    /**
     * A message from the server.
     *
     * @param text       formatting already stripped
     * @param overlay    true for the action bar
     * @param fromPlayer true for player chat (ignored completely)
     */
    public void onMessage(String text, boolean overlay, boolean fromPlayer, long nowMs) {
        if (fromPlayer || text == null || text.isBlank()) {
            return;
        }
        if (overlay) {
            push(actionBar, new Raw.Line(nowMs, PrivacyFilter.redact(text)), ACTION_BAR_LINES);
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String next = ZoneParser.next(zone, lower);
        if (!next.equals(zone)) {
            zone = next;
            zoneAtMs = nowMs;
        }
        String found = EventParser.parse(text);
        if (found != null) {
            event = found;
            eventAtMs = nowMs;
        }
        String safe = PrivacyFilter.safeLine(text);
        if (safe != null) {
            push(system, new Raw.Line(nowMs, safe), SYSTEM_LINES);
        }
    }

    private static void push(ArrayDeque<Raw.Line> ring, Raw.Line line, int max) {
        ring.addLast(line);
        while (ring.size() > max) {
            ring.pollFirst();
        }
    }

    /** The running event, or {@code null} once it has expired. */
    public @Nullable String event(long nowMs) {
        String current = event;
        if (current != null && nowMs - eventAtMs > EventParser.activeMs(current)) {
            event = null;
            return null;
        }
        return current;
    }

    public long eventAtMs() {
        return eventAtMs;
    }

    public List<Raw.Line> actionBar() {
        return new ArrayList<>(actionBar);
    }

    public List<Raw.Line> systemLines() {
        return new ArrayList<>(system);
    }

    /**
     * The client world changed (Cosmic swaps worlds on zone changes and mine resets). The zone and the running event stay, exactly as
     * the old {@code ThePrisonsCore.lastZone()} did (it was only changed by messages); the message rings start empty.
     */
    public void onWorldChanged() {
        actionBar.clear();
        system.clear();
    }

    /** A full reset (tests, future use): nothing from before is true any more. */
    public void reset() {
        zone = "";
        zoneAtMs = 0L;
        event = null;
        eventAtMs = 0L;
        actionBar.clear();
        system.clear();
    }
}
