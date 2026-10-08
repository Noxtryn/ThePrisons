package io.theprisons.items.market;

/** How far a price estimate can be trusted. */
public enum MarketConfidence {
    NONE, LOW, MEDIUM, HIGH;

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
