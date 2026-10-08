package io.theprisons.modules.qol.bandit.combat;

import org.jspecify.annotations.Nullable;

/**
 * The tuning of the combat controller. Every number here is a macro setting or a pacing choice of ours - none of them is a Cosmic
 * fact. What the game really does (aggro range, best fighting distance, spear cooldown) is UNKNOWN until verified; when it becomes
 * known the module passes it in ({@link #knownPreferredDistance}, {@link #knownAggroRange}) and it replaces the matching default.
 */
public final class CombatConfig {
    // ── distances (blocks) ───────────────────────────────────────────────────
    /** Closer than this is too close (evade). */
    public double minSafe = 6.0D;
    /** Farther than this is too far (approach). */
    public double maxCombat = 34.0D;
    /** Bandits farther than this are not targets. */
    public double targetRange = 60.0D;
    /** Preferred orbit radius; 0 = AUTO (40 % into the band, or the verified value when known). */
    public double preferredDistance = 0.0D;
    /** Dead band at every distance limit. */
    public double hysteresis = 2.0D;
    /** The orbit radius may differ this much before the controller corrects it. */
    public double ringTolerance = 2.5D;
    /** Other bandits within this distance of the player count as threats. */
    public double threatRadius = 12.0D;
    /** A real player within this distance of a bandit makes it a bad target. */
    public double crowdRadius = 6.0D;
    /** Other real players: caution inside this radius. */
    public double playerSafetyRadius = 30.0D;
    /** Verified values (null = unknown, the defaults above apply). */
    public @Nullable Double knownPreferredDistance;
    public @Nullable Double knownAggroRange;

    // ── safety ───────────────────────────────────────────────────────────────
    /** Below this health the macro breaks off the fight (a macro option, not a game threshold). */
    public double retreatHealth = 8.0D;
    /** The macro resumes when health is back to retreatHealth + this. */
    public double resumeHealthMargin = 4.0D;
    /** At or below this the macro stops (after the spear is back). */
    public double criticalHealth = 6.0D;
    /** More bandits than this around the player: break off. */
    public int maxNearbyThreats = 3;
    /** This many retreats within {@link #retreatWindowMs} stop the macro. */
    public int maxRetreats = 3;
    public long retreatWindowMs = 90_000L;
    public long healthWaitMaxMs = 120_000L;

    // ── target choice ────────────────────────────────────────────────────────
    public double lockBonus = 25.0D;
    public long lockBonusMs = 6_000L;
    /** A challenger must beat the locked target by this much (and the lock must be this old) to take over. */
    public double switchMargin = 10.0D;
    public long lockMinMs = 1_500L;
    public long lostGraceMs = 1_500L;
    public long sightLostMs = 4_000L;
    public long blacklistMs = 20_000L;

    // ── state timing ─────────────────────────────────────────────────────────
    public long approachTimeoutMs = 14_000L;
    public long evadeMaxMs = 1_500L;
    public long repositionMaxMs = 3_000L;
    public long engageMaxMs = 3_000L;
    public long recoverStepMs = 450L;
    public int maxRecoveries = 3;
    public long recoveryWindowMs = 30_000L;
    public long retreatMinMs = 1_500L;
    public long retreatMaxMs = 10_000L;
    public long cooldownMs = 400L;
    public long stabiliseMs = 800L;
    public int maxRepositions = 3;
    public long repositionWindowMs = 20_000L;
    /** The orbit direction may flip at most this often (unless fully blocked). */
    public long flipMinMs = 1_200L;
    public double orbitStep = 3.0D;
    /** An orbit probe shorter than this on a wall / drop is blocked. */
    public double orbitBlockedFree = 1.5D;

    // ── loops ────────────────────────────────────────────────────────────────
    public long loopRepeatWindowMs = 60_000L;

    public CombatConfig copy() {
        CombatConfig c = new CombatConfig();
        c.minSafe = minSafe;
        c.maxCombat = maxCombat;
        c.targetRange = targetRange;
        c.preferredDistance = preferredDistance;
        c.hysteresis = hysteresis;
        c.ringTolerance = ringTolerance;
        c.threatRadius = threatRadius;
        c.crowdRadius = crowdRadius;
        c.playerSafetyRadius = playerSafetyRadius;
        c.knownPreferredDistance = knownPreferredDistance;
        c.knownAggroRange = knownAggroRange;
        c.retreatHealth = retreatHealth;
        c.resumeHealthMargin = resumeHealthMargin;
        c.criticalHealth = criticalHealth;
        c.maxNearbyThreats = maxNearbyThreats;
        c.maxRetreats = maxRetreats;
        c.retreatWindowMs = retreatWindowMs;
        c.healthWaitMaxMs = healthWaitMaxMs;
        c.lockBonus = lockBonus;
        c.lockBonusMs = lockBonusMs;
        c.switchMargin = switchMargin;
        c.lockMinMs = lockMinMs;
        c.lostGraceMs = lostGraceMs;
        c.sightLostMs = sightLostMs;
        c.blacklistMs = blacklistMs;
        c.approachTimeoutMs = approachTimeoutMs;
        c.evadeMaxMs = evadeMaxMs;
        c.repositionMaxMs = repositionMaxMs;
        c.engageMaxMs = engageMaxMs;
        c.recoverStepMs = recoverStepMs;
        c.maxRecoveries = maxRecoveries;
        c.recoveryWindowMs = recoveryWindowMs;
        c.retreatMinMs = retreatMinMs;
        c.retreatMaxMs = retreatMaxMs;
        c.cooldownMs = cooldownMs;
        c.stabiliseMs = stabiliseMs;
        c.maxRepositions = maxRepositions;
        c.repositionWindowMs = repositionWindowMs;
        c.flipMinMs = flipMinMs;
        c.orbitStep = orbitStep;
        c.orbitBlockedFree = orbitBlockedFree;
        c.loopRepeatWindowMs = loopRepeatWindowMs;
        return c;
    }

    /** The orbit radius in use: verified value, else the setting, else AUTO. */
    public double ring(DistanceBand band) {
        if (knownPreferredDistance != null) {
            return band.ring(knownPreferredDistance);
        }
        return band.ring(preferredDistance);
    }

    /** Where the source of the orbit radius is documented in the logs. */
    public String ringSource() {
        return knownPreferredDistance != null ? "verified" : preferredDistance > 0.0D ? "setting" : "AUTO(unknown): 40% into the band";
    }

    /** The radius in which other bandits count as threats: the verified aggro range when known, else the setting. */
    public double effectiveThreatRadius() {
        return knownAggroRange != null && knownAggroRange > 0.0D ? knownAggroRange : threatRadius;
    }
}
