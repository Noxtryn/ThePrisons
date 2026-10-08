package io.theprisons.modules.qol.bandit.dodge;

/**
 * One scored direction. {@code dirX/dirZ} is the DESIRED heading; everything that judges safety ({@code free}, {@code nearest}, {@code threat}, {@code gap})
 * was measured along the EXECUTED direction ({@code execX/execZ}, what the keys really walk along with the current view yaw).
 *
 * @param index         direction index (0 = +x, counting towards +z), -1 for the "keep the current heading" candidate
 * @param free          blocks the player BODY (centre + both sides) can walk along the executed direction (up to the lookahead)
 * @param centerFree    the same for the centre line only
 * @param leftFree      the same for the left body edge (left of the executed direction)
 * @param rightFree     the same for the right body edge
 * @param pressure      wall pressure penalty already included in {@code score} (0 = the wall is farther than the comfort distance)
 * @param nearest       the smallest distance to any bandit along the way (bandits predicted)
 * @param threat        average summed danger of all bandits along the way
 * @param peak          the worst summed danger at any point
 * @param gap           the narrowest side clearance to a bandit ahead (capped; the cap = nothing ahead)
 * @param blocked       why the direction is not usable ("" = usable)
 * @param safe          usable and nobody gets closer than the minimum distance on the way
 */
public record DodgeCandidate(int index, double dirX, double dirZ, double execX, double execZ, double errorDegrees, double free, double centerFree,
                             double leftFree, double rightFree, String stop, double jumpAt, double pressure, double nearest, double threat, double peak,
                             double gap, double score, String blocked, boolean safe) {
    public double headingDegrees() {
        return Math.toDegrees(Math.atan2(dirZ, dirX));
    }

    public double executedDegrees() {
        return Math.toDegrees(Math.atan2(execZ, execX));
    }
}
