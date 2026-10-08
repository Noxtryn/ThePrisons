package io.theprisons.modules.qol.bandit.dodge;

/** A bandit as the dodge planner sees it: position in blocks, velocity in blocks per second (0 when it cannot be observed reliably). */
public record DodgeBandit(String id, double x, double z, double vx, double vz) {
    public double distanceTo(double px, double pz) {
        return Math.hypot(x - px, z - pz);
    }
}
