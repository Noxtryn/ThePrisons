package io.theprisons.core.cosmic.state;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one authoritative local state: the latest {@link CosmicContextSnapshot} plus a short bounded history. HUD, macros,
 * statistics and notifications read it instead of sampling the client themselves.
 *
 * <p>Reading {@link #latest()} is a volatile read of an immutable object (any thread). Everything else runs on the client thread.
 * The history holds at most {@link #HISTORY} snapshots (about 24 s at the default 5 samples per second); a change of server,
 * dimension or season starts a new history - values of one map never mix with another's.
 */
public final class CosmicStateStore {
    public static final int HISTORY = 120;

    private volatile @Nullable CosmicContextSnapshot latest;
    private final ArrayDeque<CosmicContextSnapshot> history = new ArrayDeque<>(HISTORY + 1);
    private final List<Consumer<CosmicContextSnapshot>> listeners = new ArrayList<>();
    private long version;

    /** The newest snapshot, or {@code null} before the first sample (and after {@link #reset()}). */
    public @Nullable CosmicContextSnapshot latest() {
        return latest;
    }

    /** Counts up with every published snapshot; consumers can poll it to see whether something changed. */
    public synchronized long version() {
        return version;
    }

    public synchronized void publish(CosmicContextSnapshot snapshot) {
        CosmicContextSnapshot previous = latest;
        if (previous != null && !previous.key().equals(snapshot.key())) {
            history.clear(); // another server / dimension / season: nothing of the old context belongs here
        }
        history.addLast(snapshot);
        while (history.size() > HISTORY) {
            history.pollFirst();
        }
        latest = snapshot;
        version++;
        for (Consumer<CosmicContextSnapshot> listener : listeners) {
            listener.accept(snapshot);
        }
    }

    /** Oldest first. */
    public synchronized List<CosmicContextSnapshot> history() {
        return new ArrayList<>(history);
    }

    /** Joined / left a server or changed the world: everything is forgotten. */
    public synchronized void reset() {
        latest = null;
        history.clear();
        version++;
    }

    /** Called on the client thread for every published snapshot. Listeners must be fast and must not publish. */
    public synchronized void subscribe(Consumer<CosmicContextSnapshot> listener) {
        listeners.add(listener);
    }

    public synchronized int historySize() {
        return history.size();
    }
}
