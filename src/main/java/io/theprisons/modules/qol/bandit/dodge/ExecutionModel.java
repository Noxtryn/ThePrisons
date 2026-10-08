package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.modules.qol.bandit.dodge.DodgeDrive.ExecutedMove;

import java.util.ArrayList;
import java.util.List;

/**
 * What Minecraft will really do for a desired heading: every tick the keys are chosen relative to the current view, the player moves along the key
 * vector, then the view turns towards the heading ({@link DodgeDrive#nextYaw}). Simulating that for a second or two gives the route that
 * collision, wall pressure and bandit distance must be judged on - not the ideal ray, which the keys do not walk.
 */
public final class ExecutionModel {
    public static final double TICK = 0.05D;
    public static final double SPRINT_SPEED = 5.6D;
    public static final double WALK_SPEED = 4.3D;

    private ExecutionModel() {
    }

    /** @param viewYaw the current view yaw; @param ticks how long to simulate (20 per second) */
    public static ExecutedPath simulate(double x, double z, double viewYaw, double wantX, double wantZ, int ticks) {
        List<ExecutedPath.Leg> legs = new ArrayList<>();
        double cx = x;
        double cz = z;
        float yaw = (float) viewYaw;
        double total = 0.0D;
        double firstSpeed = SPRINT_SPEED;
        double legX = x;
        double legZ = z;
        double legDx = 0.0D;
        double legDz = 0.0D;
        double legLen = 0.0D;
        int legStart = 0;
        boolean legSprint = true;
        boolean open = false;
        for (int t = 0; t < ticks; t++) {
            ExecutedMove move = DodgeDrive.resolve(wantX, wantZ, yaw);
            boolean sprint = move.keys().forward();
            double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
            if (t == 0) {
                firstSpeed = speed;
            }
            double step = speed * TICK;
            if (open && Math.abs(move.dirX() - legDx) < 1e-6 && Math.abs(move.dirZ() - legDz) < 1e-6 && sprint == legSprint) {
                legLen += step;
            } else {
                if (open) {
                    legs.add(new ExecutedPath.Leg(legX, legZ, legDx, legDz, legLen, legStart, legSprint));
                }
                legX = cx;
                legZ = cz;
                legDx = move.dirX();
                legDz = move.dirZ();
                legLen = step;
                legStart = t;
                legSprint = sprint;
                open = true;
            }
            cx += move.dirX() * step;
            cz += move.dirZ() * step;
            total += step;
            yaw = DodgeDrive.nextYaw(yaw, wantX, wantZ);
        }
        if (open) {
            legs.add(new ExecutedPath.Leg(legX, legZ, legDx, legDz, legLen, legStart, legSprint));
        }
        return new ExecutedPath(legs, total, ticks, firstSpeed);
    }
}
