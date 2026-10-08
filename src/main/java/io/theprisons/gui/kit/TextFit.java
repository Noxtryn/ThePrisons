package io.theprisons.gui.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * Text that always fits: cut with an ellipsis, or wrapped to a few lines, measured with whatever width function the caller has (the game's font, or a plain
 * one in tests). Pure, so the layouts of the dashboards are testable and no text ever overlaps its neighbour.
 */
public final class TextFit {
    public static final String ELLIPSIS = "…";

    /** The width of a string in pixels. */
    @FunctionalInterface
    public interface Measure {
        int width(String s);
    }

    private TextFit() {
    }

    /** {@code s} itself when it fits in {@code maxWidth}, else the longest prefix that fits together with an ellipsis. */
    public static String ellipsize(String s, int maxWidth, Measure m) {
        if (maxWidth <= 0) {
            return "";
        }
        if (m.width(s) <= maxWidth) {
            return s;
        }
        int e = m.width(ELLIPSIS);
        if (e > maxWidth) {
            return "";
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(s.substring(0, mid)) + e <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        String cut = s.substring(0, lo).stripTrailing();
        return cut + ELLIPSIS;
    }

    /** Wraps at spaces into at most {@code maxLines} lines of at most {@code maxWidth}; the last line gets an ellipsis when the text does not fit. */
    public static List<String> wrap(String s, int maxWidth, int maxLines, Measure m) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0 || maxLines <= 0) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        String[] words = s.split(" ");
        for (int i = 0; i < words.length; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (m.width(next) <= maxWidth) {
                line = new StringBuilder(next);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                if (lines.size() == maxLines) {
                    return withEllipsis(lines, remaining(words, i), maxWidth, m);
                }
            }
            line = new StringBuilder(words[i]);
            if (m.width(line.toString()) > maxWidth) {
                lines.add(ellipsize(line.toString(), maxWidth, m));
                line = new StringBuilder();
                if (lines.size() == maxLines) {
                    return withEllipsis(lines, remaining(words, i + 1), maxWidth, m);
                }
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String remaining(String[] words, int from) {
        return from >= words.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(words, from, words.length));
    }

    private static List<String> withEllipsis(List<String> lines, String rest, int maxWidth, Measure m) {
        if (!rest.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, ellipsize(lines.get(last) + " " + rest, maxWidth, m));
        }
        return lines;
    }

    /** The width needed for two texts side by side (label left, value right) with a gap; false when they do not fit in {@code maxWidth}. */
    public static boolean fitsTogether(String left, String right, int gap, int maxWidth, Measure m) {
        return m.width(left) + gap + m.width(right) <= maxWidth;
    }

    /**
     * A label and a value on one row of {@code maxWidth}: the value keeps its full width when it can, the label is shortened first; when even the value alone
     * does not fit it is shortened too. Returns {label, value}.
     */
    public static String[] row(String label, String value, int gap, int maxWidth, Measure m) {
        int vw = m.width(value);
        if (vw >= maxWidth) {
            return new String[]{"", ellipsize(value, maxWidth, m)};
        }
        String l = ellipsize(label, Math.max(0, maxWidth - vw - gap), m);
        return new String[]{l, value};
    }
}
