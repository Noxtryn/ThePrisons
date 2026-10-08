package io.theprisons.items.energy;

/** What the energy is doing, derived only from OBSERVED changes of the value (never from a server rule): UNKNOWN until two readings exist. */
public enum EnergyOperation {
    UNKNOWN, IDLE, GAINING, DRAINING
}
