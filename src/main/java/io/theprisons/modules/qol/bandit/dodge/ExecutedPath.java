package io.theprisons.modules.qol.bandit.dodge;

import java.util.List;

/**
 * The route the player will really walk for one desired heading: the keys follow the view, the view catches up with the heading, so the route is
 * a few straight legs (the first ones bend, then a straight run once the view has arrived). All safety checks run along THIS route.
 *
 * @param legs      consecutive straight legs
 * @param length    total length in blocks
 * @param ticks     how many ticks it covers
 * @param speed     the speed (blocks per second) of the first tick
 */
public record ExecutedPath(List<Leg> legs, double length, int ticks, double speed) {
    /** A straight stretch: where it starts, the world direction really walked, how long it is and which tick it begins at. */
    public record Leg(double x, double z, double dirX, double dirZ, double length, int startTick, boolean sprint) {
        public double endX() {
            return x + dirX * length;
        }

        public double endZ() {
            return z + dirZ * length;
        }
    }

    public Leg first() {
        return legs.get(0);
    }

    /** The point at {@code distance} blocks along the route (clamped to its end) and the tick it is reached at. */
    public double[] at(double distance) {
        double left = Math.max(0.0D, distance);
        double tickBase = 0.0D;
        for (Leg leg : legs) {
            if (left <= leg.length() + 1e-9) {
                double legSpeed = leg.sprint() ? ExecutionModel.SPRINT_SPEED : ExecutionModel.WALK_SPEED;
                return new double[]{leg.x() + leg.dirX() * left, leg.z() + leg.dirZ() * left, tickBase + left / legSpeed};
            }
            left -= leg.length();
            tickBase += leg.length() / (leg.sprint() ? ExecutionModel.SPRINT_SPEED : ExecutionModel.WALK_SPEED);
        }
        Leg last = legs.get(legs.size() - 1);
        double legSpeed = last.sprint() ? ExecutionModel.SPRINT_SPEED : ExecutionModel.WALK_SPEED;
        return new double[]{last.endX(), last.endZ(), tickBase};
    }
}
