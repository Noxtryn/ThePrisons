package io.theprisons.modules.qol.bandit.nav;

import io.theprisons.modules.qol.bandit.combat.Terrain;

import java.util.List;

/**
 * One tick of input for {@link LocalNavigator}: where the player is and moves, every nearby bandit, the ground, and the spear-area state.
 * Positions are world coordinates; velocities blocks per second.
 */
public record NavInputs(long nowMs, double x, double y, double z, double vx, double vz, double viewYaw, boolean onGround, List<NavBandit> bandits,
                          Terrain terrain, SpearAreaState area, double halfWidth) {
    /** Half of the vanilla player width (0.6). The module passes the real value from the player. */
    public static final double PLAYER_HALF_WIDTH = 0.3D;

    public NavInputs {
        bandits = List.copyOf(bandits);
    }

    public NavInputs(long nowMs, double x, double y, double z, double vx, double vz, double viewYaw, boolean onGround, List<NavBandit> bandits,
                       Terrain terrain, SpearAreaState area) {
        this(nowMs, x, y, z, vx, vz, viewYaw, onGround, bandits, terrain, area, PLAYER_HALF_WIDTH);
    }
}
