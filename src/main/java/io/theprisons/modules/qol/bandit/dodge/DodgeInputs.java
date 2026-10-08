package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.modules.qol.bandit.combat.Terrain;

import java.util.List;

/**
 * One tick of input for {@link BanditDodgePlanner}: where the player is and moves, every nearby bandit, the ground, and the spear-area state.
 * Positions are world coordinates; velocities blocks per second.
 */
public record DodgeInputs(long nowMs, double x, double y, double z, double vx, double vz, double viewYaw, boolean onGround, List<DodgeBandit> bandits,
                          Terrain terrain, SpearAreaState area) {
    public DodgeInputs {
        bandits = List.copyOf(bandits);
    }
}
