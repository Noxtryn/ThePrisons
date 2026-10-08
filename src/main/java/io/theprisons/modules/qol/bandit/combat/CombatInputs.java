package io.theprisons.modules.qol.bandit.combat;

import java.util.List;

/**
 * Everything the combat controller looks at in one tick, as plain data (so scenarios and tests need no game). The module fills it from
 * the Cosmic state store, the live world (only for the target and a few candidates) and the path follower.
 *
 * @param bandits   the bandits within reach, from the state store; the module filters by kind
 * @param players   other real players (friends / gang removed by the module)
 * @param terrain   short ground probes around the player
 * @param pathFailures how many times the path to the current goal could not be planned
 */
public record CombatInputs(long nowMs, Me me, List<Foe> bandits, List<Foe> players, Terrain terrain, SpearStatus spear, AttackPhase attack,
                           PathStatus path, int pathFailures) {
    /** The player. {@code manualInput}: the human holds a movement key. */
    public record Me(double x, double y, double z, float yaw, double health, boolean onGround, boolean manualInput) {
    }

    /** What the path follower is doing for a {@link Decision.Move.Kind#PATH_TO} goal. */
    public enum PathStatus { NONE, PLANNING, FOLLOWING, ARRIVED, FAILED }

    public CombatInputs {
        bandits = List.copyOf(bandits);
        players = List.copyOf(players);
    }
}
