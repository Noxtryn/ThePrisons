package io.theprisons.gui.kit;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * What every overlay panel shares: the dark panel and its edge, rules, progress bars, the hover and tone colours,
 * the placement beside a container menu or in a screen corner, and the block that is appended to an item tooltip.
 * Item List, Energy, Auction House and Energy Exchange overlays all draw from here, so a change of look is one edit.
 */
public final class Panel {
    public static final int BG = 0xF00A0F1A;
    public static final int EDGE = 0xFF1C2740;
    public static final int CARD = 0xFF101828;
    public static final int CARD_HOVER = 0xFF16223A;
    public static final int TRACK = 0xFF111A2C;
    public static final int CHIP = 0xFF0F1626;

    private Panel() {
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /** The overlay panel: dark body with a thin edge. */
    public static void box(DrawContext c, int x, int y, int w, int h) {
        Ui.round(c, x, y, w, h, BG);
        Ui.outline(c, x, y, w, h, EDGE);
    }

    /** A thin separator between two blocks of a panel. */
    public static void rule(DrawContext c, int x0, int x1, int y) {
        c.fill(x0, y, x1, y + 1, EDGE);
    }

    /** A progress bar; {@code progress} is 0..1, or {@code null} for an empty track (capacity unknown). Stale data is dimmed. */
    public static void bar(DrawContext c, int x, int y, int w, int h, @Nullable Float progress, boolean stale) {
        c.fill(x, y, x + w, y + h, TRACK);
        if (progress != null) {
            int fill = Math.round(w * Math.max(0.0F, Math.min(1.0F, progress)));
            c.fill(x, y, x + fill, y + h, Ui.argb(stale ? 130 : 255, Ui.theme().accent()));
        }
    }

    /** Edge colour of a highlighted slot: the tone colour at the overlay's usual strength. */
    public static int slotEdge(int toneRgb) {
        return Ui.argb(0xE0, toneRgb);
    }

    // ── Placement (pure) ─────────────────────────────────────────────────────

    /**
     * Top-left corner of a panel next to a centred container menu: on its right when there is room, else on its
     * left, else as far right as the screen allows. Vertically it follows the menu's top and stays on screen.
     */
    public static int[] besideMenu(int screenW, int screenH, int menuW, int menuH, int panelW, int panelH, int gap, int margin) {
        int right = screenW / 2 + menuW / 2 + gap;
        int x = right + panelW <= screenW - margin ? right : Math.max(margin, screenW / 2 - menuW / 2 - gap - panelW);
        int top = screenH / 2 - menuH / 2;
        int y = Math.max(margin, Math.min(top, screenH - panelH - margin));
        return new int[]{x, y};
    }

    /** Top-left corner of a panel in a screen corner. */
    public static int[] corner(int screenW, int screenH, int panelW, int panelH, boolean right, boolean bottom, int margin) {
        int x = right ? screenW - panelW - margin : margin;
        int y = bottom ? screenH - panelH - margin : margin;
        return new int[]{Math.max(0, x), Math.max(0, y)};
    }

    // ── Tooltip block ────────────────────────────────────────────────────────

    /**
     * The lines an overlay appends to the tooltip of the hovered slot, below the game's own lines: a blank line, the
     * heading, then the facts. {@code bad} lines are red, {@code good} lines green, the rest grey.
     */
    public static void appendTooltip(java.util.List<Text> out, java.util.List<String> lines, Predicate<String> good, Predicate<String> bad) {
        out.add(Text.empty());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Formatting color = i == 0 ? Formatting.AQUA : bad.test(line) ? Formatting.RED : good.test(line) ? Formatting.GREEN : Formatting.GRAY;
            out.add(Text.literal(line).formatted(color));
        }
    }
}
