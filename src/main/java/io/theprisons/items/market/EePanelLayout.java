package io.theprisons.items.market;

import io.theprisons.gui.kit.TextFit;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays the energy market panel out from numbers only: every text gets a position, a width and a font scale, shortened to fit its column, so nothing can overlap
 * and the panel height is known before anything is drawn. The renderer just draws the items; the same code is tested with a plain width function.
 */
public final class EePanelLayout {
    public static final int PAD = 8;
    public static final int LINE = 11;
    public static final double METRIC_SCALE = 1.5D;

    public enum Kind { TITLE, SECTION, METRIC_LABEL, METRIC_VALUE, ROW_LABEL, ROW_VALUE, NOTE, RULE }

    /** @param w the width the (already fitted) text takes at its scale; @param h its height; @param scale font scale */
    public record Item(Kind kind, String text, int x, int y, int w, int h, double scale, EeAnalysis.Tone tone) {
        public boolean overlaps(Item o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    public record Result(List<Item> items, int width, int height) {
        public Result {
            items = List.copyOf(items);
        }
    }

    private EePanelLayout() {
    }

    public static Result layout(EeAnalysis.Panel panel, int width, TextFit.Measure m) {
        List<Item> items = new ArrayList<>();
        int inner = Math.max(20, width - PAD * 2);
        int y = PAD - 1;
        items.add(text(Kind.TITLE, panel.title(), PAD, y, inner, 1.0D, EeAnalysis.Tone.NEUTRAL, m));
        y += LINE + 3;
        items.add(new Item(Kind.RULE, "", PAD, y, inner, 1, 1.0D, EeAnalysis.Tone.MUTED));
        y += 6;
        for (EeAnalysis.Section s : panel.sections()) {
            if (s.metrics()) {
                int gap = 8;
                int cols = Math.max(1, Math.min(2, s.rows().size()));
                int colW = (inner - gap * (cols - 1)) / cols;
                int bottom = y;
                for (int i = 0; i < s.rows().size() && i < 2; i++) {
                    EeAnalysis.Row r = s.rows().get(i);
                    int x = PAD + i * (colW + gap);
                    items.add(text(Kind.METRIC_LABEL, r.label(), x, y, colW, 1.0D, EeAnalysis.Tone.MUTED, m));
                    Item value = text(Kind.METRIC_VALUE, r.value(), x, y + LINE - 1, colW, METRIC_SCALE, r.tone(), m);
                    items.add(value);
                    bottom = Math.max(bottom, value.y + value.h);
                }
                y = bottom + 7;
            } else {
                items.add(text(Kind.SECTION, s.title(), PAD, y, inner, 1.0D, EeAnalysis.Tone.MUTED, m));
                y += LINE;
                for (EeAnalysis.Row r : s.rows()) {
                    String[] fitted = TextFit.row(r.label(), r.value(), 6, inner, m);
                    Item value = text(Kind.ROW_VALUE, fitted[1], 0, y, inner, 1.0D, r.tone(), m);
                    items.add(new Item(Kind.ROW_VALUE, value.text(), PAD + inner - value.w(), y, value.w(), value.h(), 1.0D, r.tone()));
                    if (!fitted[0].isEmpty()) {
                        items.add(text(Kind.ROW_LABEL, fitted[0], PAD, y, inner - value.w() - 6, 1.0D, EeAnalysis.Tone.NEUTRAL, m));
                    }
                    y += LINE;
                }
                y += 5;
            }
        }
        if (!panel.note().isEmpty()) {
            items.add(text(Kind.NOTE, panel.note(), PAD, y, inner, 1.0D, EeAnalysis.Tone.WARN, m));
            y += LINE;
        }
        return new Result(items, width, y + PAD - 2);
    }

    private static Item text(Kind kind, String text, int x, int y, int maxW, double scale, EeAnalysis.Tone tone, TextFit.Measure m) {
        TextFit.Measure scaled = s -> (int) Math.ceil(m.width(s) * scale);
        String fitted = TextFit.ellipsize(text, maxW, scaled);
        return new Item(kind, fitted, x, y, scaled.width(fitted), (int) Math.ceil(9 * scale), scale, tone);
    }
}
