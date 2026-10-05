package io.theprisons.gui.theme;

/**
 * Colour themes of the mod's screens (dashboard, storage overlay, HUD editor, scoreboard): an accent for lines and
 * highlights and a title colour. "Cosmic" is the original pink / light blue look.
 */
public enum Theme {
    COSMIC("Cosmic", 0xFF6EC7, 0x8AD8FF),
    AQUA("Aqua", 0x4FE8E0, 0xB8F6FF),
    SUNSET("Sunset", 0xFF9A2E, 0xFFE04A),
    EMERALD("Emerald", 0x5DE86B, 0xB8FFCC),
    ROYAL("Royal", 0xA66CFF, 0xFFC93C),
    CRIMSON("Crimson", 0xFF3D6E, 0xFFB0C0);

    private final String label;
    private final int accent;
    private final int title;

    Theme(String label, int accent, int title) {
        this.label = label;
        this.accent = accent;
        this.title = title;
    }

    public String label() {
        return label;
    }

    /** Lines, active borders, highlights (RGB). */
    public int accent() {
        return accent;
    }

    /** Titles and headings (RGB). */
    public int title() {
        return title;
    }
}
