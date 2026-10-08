package io.theprisons.items.energy;

/** Where an energy value was read: the lore of an item (verified format: "Cosmic Energy", bar, "(now / capacity)"), the amount stored on a Cosmic Energy item, or nowhere. */
public enum EnergySource {
    ITEM_LORE, ITEM_VALUE, UNKNOWN
}
