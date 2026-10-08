package io.theprisons.core.control;

import io.theprisons.core.control.InputController.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentArbitrationTest {
    private static final Keys W = new Keys(true, false, false, false, false, false, false);
    private static final Keys S = new Keys(false, true, false, false, false, false, false);

    @Test
    void oneMovementIntentWinsPerTick() {
        ControlService control = new ControlService();
        assertTrue(control.submit(new MovementIntent(IntentPriority.PATHFINDING, "path", W)));
        assertTrue(control.submit(new MovementIntent(IntentPriority.COMBAT_EVADE, "evade", S)));
        assertFalse(control.submit(new MovementIntent(IntentPriority.TARGET_LOOK, "look", W)), "lower priority loses");
        assertEquals(S, control.input().wanted());
        assertEquals(1, control.input().rejectedRequests());
    }

    @Test
    void theHighestRotationIntentWins() {
        ControlService control = new ControlService();
        assertTrue(control.submit(RotationIntent.aimed(IntentPriority.TARGET_LOOK, "aim", RotationMode.MINING, 10.0F, 5.0F, 8.0F)));
        assertTrue(control.submit(RotationIntent.following(IntentPriority.COMBAT_EVADE, "evade", 90.0F, 0.0F, 5.0F, 5.0F)), "evade outranks target look");
        assertFalse(control.submit(RotationIntent.aimed(IntentPriority.PATHFINDING, "path", RotationMode.NAVIGATION, 0.0F, 0.0F, 8.0F)));
        assertFalse(control.submit(RotationIntent.aimed(IntentPriority.TARGET_LOOK, "aim2", RotationMode.MINING, 20.0F, 5.0F, 8.0F)));
        assertEquals("evade", control.pendingRotation().source());
        assertEquals(90.0F, control.pendingRotation().yaw());
    }

    @Test
    void amongEqualIntentsTheHigherModeThenTheLaterWins() {
        ControlService control = new ControlService();
        assertTrue(control.submit(RotationIntent.aimed(IntentPriority.PATHFINDING, "a", RotationMode.NAVIGATION, 1.0F, 0.0F, 8.0F)));
        assertTrue(control.submit(RotationIntent.aimed(IntentPriority.PATHFINDING, "b", RotationMode.TURNING, 2.0F, 0.0F, 8.0F)), "TURNING outranks NAVIGATION");
        assertFalse(control.submit(RotationIntent.aimed(IntentPriority.PATHFINDING, "c", RotationMode.NAVIGATION, 3.0F, 0.0F, 8.0F)));
        assertTrue(control.submit(RotationIntent.aimed(IntentPriority.PATHFINDING, "d", RotationMode.TURNING, 4.0F, 0.0F, 8.0F)), "later among equals");
    }

    @Test
    void manualAndEmergencyOutrankEverything() {
        ControlService control = new ControlService();
        control.submit(new MovementIntent(IntentPriority.EMERGENCY, "safety", Keys.NONE));
        assertFalse(control.submit(new MovementIntent(IntentPriority.COMBAT_EVADE, "evade", W)));
        assertTrue(control.submit(new MovementIntent(IntentPriority.MANUAL, "human", S)));
        assertEquals(S, control.input().wanted());
    }

    @Test
    void legacyRequestsStillBehaveAsBefore() {
        ControlService control = new ControlService();
        control.input().set(W);
        control.input().set(S);
        assertEquals(S, control.input().wanted(), "last set wins as ever");
        control.rotation().request(RotationMode.NAVIGATION, 10.0F, 0.0F);
        control.rotation().request(RotationMode.MINING, 20.0F, 0.0F);
        control.rotation().request(RotationMode.TURNING, 30.0F, 0.0F);
        assertEquals(RotationMode.MINING.priority() > RotationMode.TURNING.priority(), true);
    }
}
