package io.theprisons.modules.qol.bandit.combat;

import org.jspecify.annotations.Nullable;

/**
 * What the combat controller wants this tick: a state, a wish for the movement keys, a wish for the view, and whether the spear attack
 * may run. The module turns the wishes into {@code MovementIntent} / {@code RotationIntent}; nothing here touches the game.
 *
 * @param stopReason     non-null: the macro must end ({@code stopAfterRecall}: first bring the spear back)
 * @param detail         a short human line for the HUD and the log
 */
public record Decision(CombatState state, String reason, @Nullable String targetId, Move move, Look look, boolean attackAllowed,
                       @Nullable String stopReason, boolean stopAfterRecall, String detail) {
    /**
     * @param kind  NONE = stand; DIRECTION = walk along the world vector (dx, dz); PATH_TO = walk the planned path to (goalX, goalZ)
     */
    public record Move(Kind kind, double dx, double dz, boolean sprint, boolean jump, double goalX, double goalZ) {
        public enum Kind { NONE, DIRECTION, PATH_TO }

        public static final Move NONE = new Move(Kind.NONE, 0, 0, false, false, 0, 0);

        public static Move direction(double dx, double dz, boolean sprint, boolean jump) {
            return new Move(Kind.DIRECTION, dx, dz, sprint, jump, 0, 0);
        }

        public static Move pathTo(double goalX, double goalZ) {
            return new Move(Kind.PATH_TO, 0, 0, false, false, goalX, goalZ);
        }
    }

    /** What the view should do: face the target, face the way it moves, hold, or nothing (no view wish). */
    public enum Look { TARGET, MOVEMENT, HOLD, NONE }

    public boolean stopping() {
        return stopReason != null;
    }
}
