package io.theprisons.modules.qol.bandit.dodge;

import java.util.List;

/**
 * What the movement wants this tick (world coordinates; the module turns it into keys).
 *
 * @param nearestBandit            distance to the closest bandit now ({@code Double.NaN} = none around)
 * @param projectedNearestBandit   the closest approach along the chosen way
 * @param threatScore              summed danger of all bandits at the player's own place
 * @param freeDistance             free walk along the chosen direction
 * @param aimWindowSafe            safe for aiming THIS tick (nobody under the minimum distance, threat low, way open, nobody closing fast, steady)
 * @param aimWindowOpen            safe for the required number of ticks in a row
 * @param breach                   a bandit is under the minimum distance right now: any aim must be cancelled at once
 * @param chosen                   the chosen candidate (desired + executed direction, body probes, wall pressure)
 * @param terrainChange            the heading was changed because of the ground (wall, drop, wall pressure), not because of bandits
 * @param jumpPhase                NONE, HOLD (key held, still on the ground) or AIR (lifted off, course locked until the landing)
 * @param candidates               every scored direction (bounded: directions + the current heading)
 */
public record DodgeDecision(double dirX, double dirZ, boolean sprint, boolean jump, double score, double nearestBandit, double projectedNearestBandit,
                            double threatScore, double freeDistance, boolean aimWindowSafe, boolean aimWindowOpen, int aimWindowTicks, boolean breach,
                            int nearbyCount, DodgeAction action, double headingDegrees, SpearAreaState area, String reason, List<DodgeCandidate> candidates,
                            int oscillations, boolean stuck, DodgeCandidate chosen, boolean terrainChange, JumpPhase jumpPhase, int jumpTicksHeld,
                            long jumpCooldownLeftMs) {
    public DodgeDecision {
        candidates = List.copyOf(candidates);
    }
}
