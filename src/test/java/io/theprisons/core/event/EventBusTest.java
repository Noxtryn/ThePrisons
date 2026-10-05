package io.theprisons.core.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {
    record Ping(int value) {
    }

    record Other() {
    }

    @Test
    void dispatchesByTypeInPriorityThenRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        EventBus bus = new EventBus((owner, type, error) -> {
            throw new AssertionError(error);
        });
        Object a = new Object();
        Object b = new Object();
        bus.subscribe(Ping.class, a, 0, p -> calls.add("a0"));
        bus.subscribe(Ping.class, b, 10, p -> calls.add("b10"));
        bus.subscribe(Ping.class, a, 0, p -> calls.add("a0-second"));
        bus.subscribe(Ping.class, b, -5, p -> calls.add("b-5"));
        bus.subscribe(Other.class, a, p -> calls.add("other"));
        bus.post(new Ping(1));
        assertEquals(List.of("b10", "a0", "a0-second", "b-5"), calls);
    }

    @Test
    void unsubscribeAllRemovesOnlyThatOwner() {
        List<String> calls = new ArrayList<>();
        EventBus bus = new EventBus((owner, type, error) -> {
        });
        Object a = new Object();
        Object b = new Object();
        bus.subscribe(Ping.class, a, p -> calls.add("a"));
        bus.subscribe(Other.class, a, p -> calls.add("a-other"));
        bus.subscribe(Ping.class, b, p -> calls.add("b"));
        bus.unsubscribeAll(a);
        bus.post(new Ping(1));
        bus.post(new Other());
        assertEquals(List.of("b"), calls);
        assertEquals(1, bus.listenerCount());
        assertFalse(bus.hasListeners(Other.class));
    }

    @Test
    void failingListenerIsReportedAndDoesNotStopDispatch() {
        List<Object> failedOwners = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        EventBus bus = new EventBus((owner, type, error) -> failedOwners.add(owner));
        Object bad = new Object();
        bus.subscribe(Ping.class, bad, 5, p -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(Ping.class, this, 0, p -> calls.add("still called"));
        bus.post(new Ping(1));
        assertEquals(List.of("still called"), calls);
        assertEquals(1, failedOwners.size());
        assertSame(bad, failedOwners.get(0));
    }

    @Test
    void subscribingDuringDispatchAppliesToNextPost() {
        List<String> calls = new ArrayList<>();
        EventBus bus = new EventBus((owner, type, error) -> {
        });
        bus.subscribe(Ping.class, this, p -> {
            calls.add("first");
            if (p.value() == 1) {
                bus.subscribe(Ping.class, this, q -> calls.add("late"));
                bus.unsubscribeAll(new Object());
            }
        });
        bus.post(new Ping(1));
        assertEquals(List.of("first"), calls);
        bus.post(new Ping(2));
        assertEquals(List.of("first", "first", "late"), calls);
        assertTrue(bus.hasListeners(Ping.class));
    }
}
