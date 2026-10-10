package io.theprisons.gui.hud;

import net.minecraft.client.gui.DrawContext;

/** Something the HUD editor can move and scale. Positions are in scaled GUI pixels (top-left corner). */
public interface HudElement {
    String id();

    String name();

    /** Current bounds {x, y, width, height} on a screen of this size (empty width = hidden). */
    int[] bounds(int screenWidth, int screenHeight);

    void moveTo(int x, int y, int screenWidth, int screenHeight);

    double scale();

    void setScale(double scale);

    /** Draws the element with sample values (the editor shows it even when nothing is going on). */
    void drawPreview(DrawContext context, int screenWidth, int screenHeight);

    /** Back to the default position and scale. */
    void reset();

    /** The modules that show this element; it is on while any of them is on. A module that is its own element needs no override. */
    default java.util.List<String> moduleIds() {
        return java.util.List.of(id());
    }
}
