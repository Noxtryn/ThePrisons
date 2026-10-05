package com.freelocs.theprisons.ui;

public final class ThePrisonsColors {
    private ThePrisonsColors() {
    }

    // Backgrounds (deep space)
    public static final int BG_OVERLAY = 0x8C000000;
    public static final int BG_PANEL = 0xD0060608;
    public static final int BG_SIDE = 0x70000000;
    public static final int BG_SECTION = 0x55000000;
    public static final int BG_CARD = 0x70141418;
    public static final int BG_CARD_HOVER = 0x90222228;
    public static final int BG_CARD_INNER = 0xF20C0C0F;
    public static final int BG_INPUT = 0xA0000000;

    // Foregrounds
    public static final int FG_PRIMARY = 0xFFF2EEFF;
    public static final int FG_SECONDARY = 0xA6F2EEFF;
    public static final int FG_MUTED = 0x80C9C2E8;
    public static final int FG_DISABLED = 0x59C9C2E8;

    // Borders
    public static final int BORDER = 0x22FFFFFF;
    public static final int BORDER_HI = 0x36FFFFFF;

    // Accents
    public static final int ACCENT_CYAN = 0xFF00E5FF;
    public static final int ACCENT_BLUE = 0xFF4D8DFF;
    public static final int ACCENT_PURPLE = 0xFFC084FC;
    public static final int ACCENT_VIOLET = 0xFF7B3FFF;
    public static final int ACCENT_LIME = 0xFF65F59B;
    public static final int ACCENT_AMBER = 0xFFFFC14D;
    public static final int ACCENT_RED = 0xFFFF5E6C;
    public static final int GRID = 0x14FFFFFF;
    /** Menu / chat style: headings pink, the mod name light blue. */
    public static final int HEADING_PINK = 0xFFFF6EC7;
    public static final int MOD_BLUE = 0xFF7FD8FF;

    // Toggles
    public static final int TOGGLE_OFF = 0x26FFFFFF;
    public static final int TOGGLE_ON_L = ACCENT_VIOLET;
    public static final int TOGGLE_ON_R = ACCENT_CYAN;
    public static final int TOGGLE_KNOB = 0xFFFFFFFF;

    // Sidebar
    public static final int SIDEBAR_ACTIVE = 0x30FF6EC7;
    public static final int SIDEBAR_HOVER = 0x12FFFFFF;

    public static int lerp(int from, int to, float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        int a = Math.round(((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * c));
        int r = Math.round(((from >>> 16) & 0xFF) + ((((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * c));
        int g = Math.round(((from >>> 8) & 0xFF) + ((((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * c));
        int b = Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * c));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

}
