package io.theprisons.modules.qol.bandit.dodge;

/**
 * Whether the spear can be used where the player is. Nothing about this is known yet: the state is VALID / INVALID only when a verified
 * registry value says so, and UNKNOWN otherwise. No coordinates and no zone border are invented.
 */
public enum SpearAreaState {
    VALID, INVALID, UNKNOWN
}
