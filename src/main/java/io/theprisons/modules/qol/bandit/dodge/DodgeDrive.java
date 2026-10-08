package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.core.control.InputController;
import io.theprisons.modules.qol.bandit.combat.Geo;

/**
 * Turns the planner's world direction into keys and decides where the view goes. Pure, so the simulator runs the very same code.
 *
 * <p>Two things made the live movement choppy: {@link Geo#keysFor} only sprints on pure forward (a diagonal or strafe key set dropped the sprint,
 * speed 5.6 to 4.3 blocks per second), and the view followed the heading at 3 degrees per tick, so after every heading change the keys stayed
 * diagonal / strafe for a long time. Here a forward-leaning key set keeps the sprint, and the view catches up quickly (but with a dead zone, so a
 * steady heading never moves the camera).
 */
public final class DodgeDrive {
    /** The view only turns when the heading is further off than this (degrees). */
    public static final double DEAD_ZONE = 6.0D;
    /** Degrees per tick the view may turn towards the heading. */
    public static final double MAX_TURN = 9.0D;

    private DodgeDrive() {
    }

    /** Keys for the world direction given the current view yaw; sprint stays on whenever W is pressed (also with A / D). */
    public static InputController.Keys keys(double dirX, double dirZ, double viewYaw, boolean jump) {
        InputController.Keys k = Geo.keysFor(dirX, dirZ, viewYaw, true, jump);
        return new InputController.Keys(k.forward(), k.back(), k.left(), k.right(), k.jump(), k.forward(), false);
    }

    /**
     * What Minecraft will really do for a desired world direction: the key set, the world direction those keys walk along (W / A / S / D relative to the
     * view yaw, 8 directions) and how far that is from the desired direction. The planner judges THIS direction, never the ideal one.
     */
    public record ExecutedMove(InputController.Keys keys, double dirX, double dirZ, double errorDegrees) {
    }

    public static ExecutedMove resolve(double dirX, double dirZ, double viewYaw) {
        InputController.Keys k = keys(dirX, dirZ, viewYaw, false);
        double[] f = Geo.forward(viewYaw);
        double[] r = Geo.right(viewYaw);
        double kf = (k.forward() ? 1.0D : 0.0D) - (k.back() ? 1.0D : 0.0D);
        double kr = (k.right() ? 1.0D : 0.0D) - (k.left() ? 1.0D : 0.0D);
        double[] u = Geo.unit(f[0] * kf + r[0] * kr, f[1] * kf + r[1] * kr);
        if (u[0] == 0.0D && u[1] == 0.0D) {
            return new ExecutedMove(k, 0.0D, 0.0D, 180.0D);
        }
        return new ExecutedMove(k, u[0], u[1], Geo.angleBetween(u[0], u[1], dirX, dirZ));
    }

    /** The yaw the view should have next tick (stays when close enough to the heading). */
    public static float nextYaw(float viewYaw, double dirX, double dirZ) {
        double target = Geo.yawOf(dirX, dirZ);
        double diff = wrap(target - viewYaw);
        if (Math.abs(diff) <= DEAD_ZONE) {
            return viewYaw;
        }
        return (float) (viewYaw + Math.max(-MAX_TURN, Math.min(MAX_TURN, diff)));
    }

    private static double wrap(double d) {
        double v = d % 360.0D;
        return v > 180.0D ? v - 360.0D : v <= -180.0D ? v + 360.0D : v;
    }
}
