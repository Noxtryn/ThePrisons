package io.theprisons.modules.qol.bandit.dodge;

/**
 * The tuning of the dodge planner. All of these are our choices (macro settings), none is a Cosmic fact: the real aggro range and the best
 * distance are UNKNOWN. {@link #minDistance} is checked against EVERY nearby bandit.
 */
public final class DodgeConfig {
    /** No bandit may be closer than this when an aim is allowed (and an aim in progress is cancelled the moment one is). */
    public double minDistance = 8.0D;
    /** An evade (breach) ends only once every bandit is this much beyond the minimum distance - no flipping in and out at the border. */
    public double evadeExitBuffer = 1.0D;
    /** Beyond the minimum distance the danger fades out over this band. */
    public double warningBand = 6.0D;
    /** How far ahead each direction is probed and scored. */
    public double lookahead = 10.0D;
    /** Points along a probe are scored every this many blocks. */
    public double sampleStep = 2.0D;
    /** Bandits are predicted at most this far ahead (seconds); beyond that their position stays where the prediction ends. */
    public double predictionSeconds = 0.6D;
    /** Running speed used to time the points of a probe (blocks per second, sprinting). */
    public double playerSpeed = 5.6D;
    /** Slowest bandit speed taken as real motion; above {@link #maxBanditSpeed} a velocity is a teleport / glitch and counts as 0. */
    public double maxBanditSpeed = 12.0D;
    /** Directions probed around the player. */
    public int directions = 16;
    /** A probe that stops sooner than this at a wall / drop / hazard is blocked. */
    public double minFree = 3.0D;
    /**
     * Wall comfort distance: a way that ends in a wall / drop / hazard sooner than this is increasingly penalised (sprint is 5.6 blocks per second, the
     * view needs a few ticks to turn), so the player bends away long before the wall instead of at it.
     */
    public double wallComfort = 7.0D;
    /** A way that ends this close is "unsafe" (the player must leave it now). */
    public double wallUrgent = 4.0D;
    public double wWall = 70.0D;
    /** Penalty per degree between the desired and the really executed direction. */
    public double wExecError = 0.15D;
    /** After a step up the player needs this much more floor, or the step is treated as a wall (no jump into a dead end). */
    public double jumpLanding = 2.0D;
    /** The body probes sit this far inside the full half width, so a player hugging a wall is not "inside" it. */
    public double bodyInset = 0.02D;
    /** A candidate is "safe" when no bandit gets closer than this fraction of the minimum distance on the way. */
    public double projectedMarginFraction = 1.0D;
    /** A new direction must beat the current one by this much (and by this share of its score) to be taken. */
    public double switchMargin = 8.0D;
    public double switchShare = 0.12D;

    // weights
    public double wFree = 3.0D;
    public double wSeparation = 4.0D;
    public double wThreat = 0.25D;
    public double wPeak = 0.1D;
    public double wGap = 2.0D;
    public double wForward = 25.0D;
    public double wTurn = 15.0D;
    public double wDeadEnd = 2.0D;
    public double wReversal = 25.0D;
    public double wJump = 3.0D;
    public double wFailed = 40.0D;
    public double wArea = 5.0D;
    /** Scraping a wall (less room than this beside the body) costs points: the player keeps a hand's width from walls when it can. */
    public double sideComfort = 0.4D;
    public double wScrape = 6.0D;
    /** The route that is simulated and swept (ticks, 20 per second): the view catches up, then the run is straight. */
    public int pathTicks = 38;

    // jumping
    public long jumpCooldownMs = 600L;
    /** A jump is taken when the step is this close (blocks). */
    public double jumpWithin = 2.0D;
    /** The jump key stays down this many ticks while the player is still on the ground (one tick can be lost); released one tick after lift-off. */
    public int jumpHoldTicks = 4;
    /** A jump that never lifted off may be tried again after this long. */
    public long jumpRetryMs = 250L;

    // aim window
    public int requiredStableTicks = 12;
    /** The threat at the player's own place (summed danger) must stay under this for the aim window. */
    public double aimThreatLimit = 40.0D;
    /** A bandit inside min + warning that closes in faster than this (blocks per second) closes the window. */
    public double closingSpeed = 4.0D;
    /** The heading may not change more than this (degrees) over the last ticks for the window. */
    public double stableHeadingDegrees = 20.0D;
    /** The way ahead must be at least this free for the window. */
    public double aimFreeAhead = 6.0D;

    // memory
    public long failedHeadingMs = 2_500L;
    public long stuckWindowMs = 1_500L;
    public double stuckMinMove = 1.2D;
    public long oscillationWindowMs = 4_000L;
    public long commitMs = 1_500L;

    public DodgeConfig copy() {
        DodgeConfig c = new DodgeConfig();
        for (var f : DodgeConfig.class.getFields()) {
            try {
                f.set(c, f.get(this));
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }
        return c;
    }
}
