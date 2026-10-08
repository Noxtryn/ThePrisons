package io.theprisons.hud;

/** How much the session dashboard shows: a few lines, the main panels, or everything useful. */
public enum DashboardStyle {
    MINIMAL("Minimal"), STANDARD("Standard"), DETAILED("Detailed");

    private final String label;

    DashboardStyle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
