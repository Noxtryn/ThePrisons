package com.freelocs.theprisons.core.event;

import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Typed, synchronous event bus of the core. Every Fabric callback the mod needs is registered exactly once (in
 * {@code FabricBridge}) and re-published here, so modules never register Fabric events themselves.
 *
 * <p>Threading: {@link #post} is meant for the client thread. Listener lists are copy-on-write arrays, so posting
 * allocates nothing and (un)subscribing while an event is being dispatched is safe; the change applies to the next
 * post. Dispatch is by exact event class, sorted by descending priority, then registration order.
 *
 * <p>Isolation: a throwing listener never stops the dispatch. The exception goes to the {@link ErrorHandler} with
 * the listener's owner, which lets the module manager disable exactly the module that failed.
 */
public final class EventBus {
    /** Receives listener failures; {@code owner} is what the listener was registered with. */
    public interface ErrorHandler {
        void onListenerError(Object owner, Class<?> eventType, Throwable error);
    }

    private record Listener(Object owner, int priority, long order, Consumer<Object> handler) {
    }

    private static final Listener[] NONE = new Listener[0];
    private static final Comparator<Listener> ORDER = Comparator.comparingInt(Listener::priority).reversed()
            .thenComparingLong(Listener::order);

    private final Map<Class<?>, Listener[]> listeners = new IdentityHashMap<>();
    private final ErrorHandler errorHandler;
    private long counter;

    public EventBus(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    /**
     * @param owner    identity used by {@link #unsubscribeAll}; typically the module
     * @param priority higher runs first (0 = normal)
     */
    @SuppressWarnings("unchecked")
    public synchronized <E> void subscribe(Class<E> type, Object owner, int priority, Consumer<? super E> handler) {
        Listener listener = new Listener(owner, priority, counter++, (Consumer<Object>) handler);
        Listener[] current = listeners.getOrDefault(type, NONE);
        Listener[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = listener;
        Arrays.sort(next, ORDER);
        listeners.put(type, next);
    }

    public <E> void subscribe(Class<E> type, Object owner, Consumer<? super E> handler) {
        subscribe(type, owner, 0, handler);
    }

    /** Removes every listener registered with this owner. */
    public synchronized void unsubscribeAll(Object owner) {
        for (Map.Entry<Class<?>, Listener[]> entry : listeners.entrySet()) {
            Listener[] current = entry.getValue();
            int kept = 0;
            for (Listener listener : current) {
                if (listener.owner != owner) {
                    kept++;
                }
            }
            if (kept == current.length) {
                continue;
            }
            Listener[] next = new Listener[kept];
            int i = 0;
            for (Listener listener : current) {
                if (listener.owner != owner) {
                    next[i++] = listener;
                }
            }
            entry.setValue(next);
        }
    }

    /** True when at least one listener wants this event type; lets bridges skip building expensive events. */
    public boolean hasListeners(Class<?> type) {
        Listener[] current;
        synchronized (this) {
            current = listeners.get(type);
        }
        return current != null && current.length > 0;
    }

    public void post(Object event) {
        Listener[] current;
        synchronized (this) {
            current = listeners.get(event.getClass());
        }
        if (current == null) {
            return;
        }
        for (Listener listener : current) {
            try {
                listener.handler.accept(event);
            } catch (Throwable error) {
                errorHandler.onListenerError(listener.owner, event.getClass(), error);
            }
        }
    }

    public synchronized int listenerCount() {
        int count = 0;
        for (Listener[] current : listeners.values()) {
            count += current.length;
        }
        return count;
    }
}
