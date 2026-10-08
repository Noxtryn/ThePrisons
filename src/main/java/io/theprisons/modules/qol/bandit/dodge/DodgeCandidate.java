package io.theprisons.modules.qol.bandit.dodge;

/**
 * One scored direction.
 *
 * @param index         direction index (0 = +x, counting towards +z), -1 for the "keep the current heading" candidate
 * @param free          blocks that can be walked in that direction (up to the lookahead)
 * @param nearest       the smallest distance to any bandit along the way (bandits predicted)
 * @param threat        average summed danger of all bandits along the way
 * @param peak          the worst summed danger at any point
 * @param gap           the narrowest side clearance to a bandit ahead (capped; the cap = nothing ahead)
 * @param blocked       why the direction is not usable ("" = usable)
 * @param safe          usable and nobody gets closer than the minimum distance on the way
 */
public record DodgeCandidate(int index, double dirX, double dirZ, double free, String stop, double jumpAt, double nearest, double threat, double peak,
                             double gap, double score, String blocked, boolean safe) {
    public double headingDegrees() {
        return Math.toDegrees(Math.atan2(dirZ, dirX));
    }
}
