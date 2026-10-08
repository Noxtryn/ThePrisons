package io.theprisons.hud;

import io.theprisons.gui.kit.TextFit;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays the session dashboard out from numbers only: every text gets a position, a width and a scale, fitted to its column (ellipsis, never overflow), and the
 * height follows from the content. Pure and tested with a plain width function, so it holds at every GUI scale: the renderer only multiplies the finished
 * layout by the widget scale, which cannot make two texts meet.
 *
 * <p>Four KPI cards side by side when each has room for its text (about 56 px), otherwise a 2 x 2 grid. MINIMAL is a short list, STANDARD adds the KPI cards,
 * the activity and the basic status, DETAILED the analytics, the boosters and (only when the view carries any) the debug rows.
 */
public final class DashboardLayout {
    public static final int PAD = 8;
    public static final int LINE = 11;
    public static final int GAP = 4;
    public static final int MIN_CARD = 56;
    public static final double KPI_SCALE = 1.5D;
    public static final int MINIMAL_WIDTH = 170;

    public enum Kind { TITLE, CLOCK, STATE, CARD, KPI_VALUE, KPI_LABEL, KPI_SUB, SECTION, ROW_LABEL, ROW_VALUE, ACTIVITY, BAR, BOOSTER_NAME, BOOSTER_TIME, MINI_VALUE, MINI_LABEL, RULE }

    /** @param fraction for BAR: how full (0..1); @param w / h the space the item takes (text: its fitted width) */
    public record Item(Kind kind, String text, int x, int y, int w, int h, double scale, SessionView.Tone tone, double fraction) {
        public boolean overlaps(Item o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        public boolean isText() {
            return kind != Kind.CARD && kind != Kind.BAR && kind != Kind.RULE;
        }
    }

    public record Result(List<Item> items, int width, int height, int kpiColumns) {
        public Result {
            items = List.copyOf(items);
        }
    }

    private DashboardLayout() {
    }

    public static Result layout(SessionView v, DashboardStyle style, int requestedWidth, TextFit.Measure m) {
        int width = style == DashboardStyle.MINIMAL ? Math.min(requestedWidth, MINIMAL_WIDTH) : requestedWidth;
        width = Math.max(120, width);
        int inner = width - PAD * 2;
        List<Item> items = new ArrayList<>();
        int y = PAD - 1;

        // header: title left, clock right; the title gives way to the clock
        Item clock = text(Kind.CLOCK, v.clock(), 0, y, inner, 1.0D, SessionView.Tone.TIME, m);
        items.add(new Item(Kind.CLOCK, clock.text(), PAD + inner - clock.w(), y, clock.w(), clock.h(), 1.0D, SessionView.Tone.TIME, 0));
        String title = style == DashboardStyle.MINIMAL ? "THEPRISONS" : v.title();
        items.add(text(Kind.TITLE, title, PAD, y, Math.max(0, inner - clock.w() - 8), 1.0D, SessionView.Tone.NEUTRAL, m));
        y += LINE;
        if (style != DashboardStyle.MINIMAL) {
            items.add(text(Kind.STATE, v.stateLine(), PAD, y, inner, 1.0D, SessionView.Tone.MUTED, m));
            y += LINE;
        }
        items.add(new Item(Kind.RULE, "", PAD, y + 1, inner, 1, 1.0D, SessionView.Tone.MUTED, 0));
        y += 5;

        int columns = 0;
        if (style == DashboardStyle.MINIMAL) {
            for (int i = 0; i < v.kpis().size() && i < 3; i++) {
                if (i == 1 && v.kpis().size() > 2) {
                    continue;                               // OP/s (or kills), energy, then the state: three lines
                }
                SessionView.Kpi k = v.kpis().get(i == 2 ? 2 : i);
                Item value = text(Kind.MINI_VALUE, k.value(), PAD, y, inner, 1.0D, k.tone(), m);
                items.add(value);
                items.add(text(Kind.MINI_LABEL, k.label(), PAD + value.w() + 5, y, Math.max(0, inner - value.w() - 5), 1.0D, SessionView.Tone.MUTED, m));
                y += LINE;
            }
            items.add(text(Kind.STATE, v.activity() == null ? v.stateLine() : v.activity().text(), PAD, y, inner, 1.0D, SessionView.Tone.GOOD, m));
            y += LINE;
            return new Result(items, width, y + PAD - 4, 0);
        }

        // KPI cards: four in a row when each has room, else a 2 x 2 grid
        int n = v.kpis().size();
        if (n > 0) {
            columns = n >= 4 && (inner - 3 * GAP) / 4 >= MIN_CARD ? 4 : Math.min(2, n);
            int cardW = (inner - (columns - 1) * GAP) / columns;
            boolean subs = style == DashboardStyle.DETAILED && v.kpis().stream().anyMatch(k -> k.sub() != null);
            int cardH = 4 + (int) Math.ceil(9 * KPI_SCALE) + 2 + 9 + (subs ? 9 : 0) + 4;
            int rows = (n + columns - 1) / columns;
            for (int i = 0; i < n; i++) {
                SessionView.Kpi k = v.kpis().get(i);
                int cx = PAD + (i % columns) * (cardW + GAP);
                int cy = y + (i / columns) * (cardH + GAP);
                items.add(new Item(Kind.CARD, "", cx, cy, cardW, cardH, 1.0D, k.tone(), 0));
                int textW = cardW - 10;
                items.add(text(Kind.KPI_VALUE, k.value(), cx + 5, cy + 4, textW, KPI_SCALE, k.tone(), m));
                int ly = cy + 4 + (int) Math.ceil(9 * KPI_SCALE) + 2;
                items.add(text(Kind.KPI_LABEL, k.label(), cx + 5, ly, textW, 1.0D, SessionView.Tone.MUTED, m));
                if (subs && k.sub() != null) {
                    items.add(text(Kind.KPI_SUB, k.sub(), cx + 5, ly + 9, textW, 1.0D, SessionView.Tone.MUTED, m));
                }
            }
            y += rows * (cardH + GAP) + 1;
        }

        // activity and the inventory bar
        SessionView.Activity a = v.activity();
        if (a != null) {
            items.add(text(Kind.SECTION, "ACTIVITY", PAD, y, inner, 1.0D, SessionView.Tone.MUTED, m));
            y += LINE - 1;
            items.add(text(Kind.ACTIVITY, a.text(), PAD, y, inner, 1.0D, a.text().equals("Idle") ? SessionView.Tone.MUTED : SessionView.Tone.GOOD, m));
            y += LINE;
            if (a.inventoryPercent() >= 0) {
                int pct = Math.min(100, a.inventoryPercent());
                SessionView.Tone tone = pct >= 90 ? SessionView.Tone.BAD : pct >= 70 ? SessionView.Tone.WARN : SessionView.Tone.GOOD;
                Item label = text(Kind.ROW_LABEL, "Inventory", PAD, y, inner, 1.0D, SessionView.Tone.NEUTRAL, m);
                Item value = text(Kind.ROW_VALUE, pct + "%", 0, y, inner, 1.0D, tone, m);
                items.add(label);
                items.add(new Item(Kind.ROW_VALUE, value.text(), PAD + inner - value.w(), y, value.w(), value.h(), 1.0D, tone, 0));
                int barX = PAD + label.w() + 6;
                int barW = PAD + inner - value.w() - 6 - barX;
                if (barW >= 16) {
                    items.add(new Item(Kind.BAR, "", barX, y + 2, barW, 5, 1.0D, tone, pct / 100.0D));
                }
                y += LINE;
            }
            y += 3;
        }

        // basic status (and in DETAILED the analytics): two columns of label / value
        List<SessionView.Row> rows = new ArrayList<>(v.stats());
        if (style == DashboardStyle.DETAILED) {
            rows.addAll(v.detail());
        }
        if (!rows.isEmpty()) {
            items.add(new Item(Kind.RULE, "", PAD, y, inner, 1, 1.0D, SessionView.Tone.MUTED, 0));
            y += 4;
            int colW = (inner - GAP * 2) / 2;
            boolean twoCols = colW >= 70;
            int perRow = twoCols ? 2 : 1;
            int cellW = twoCols ? colW : inner;
            for (int i = 0; i < rows.size(); i += perRow) {
                for (int j = 0; j < perRow && i + j < rows.size(); j++) {
                    SessionView.Row r = rows.get(i + j);
                    int x0 = PAD + j * (cellW + GAP * 2);
                    String[] fitted = TextFit.row(r.label(), r.value(), 5, cellW, m);
                    Item value = text(Kind.ROW_VALUE, fitted[1], 0, y, cellW, 1.0D, r.tone(), m);
                    items.add(new Item(Kind.ROW_VALUE, value.text(), x0 + cellW - value.w(), y, value.w(), value.h(), 1.0D, r.tone(), 0));
                    if (!fitted[0].isEmpty()) {
                        items.add(text(Kind.ROW_LABEL, fitted[0], x0, y, cellW - value.w() - 5, 1.0D, SessionView.Tone.NEUTRAL, m));
                    }
                }
                y += LINE;
            }
            y += 2;
        }

        // boosters
        if (style == DashboardStyle.DETAILED && !v.boosters().isEmpty()) {
            items.add(new Item(Kind.RULE, "", PAD, y, inner, 1, 1.0D, SessionView.Tone.MUTED, 0));
            y += 4;
            items.add(text(Kind.SECTION, "ACTIVE BOOSTERS", PAD, y, inner, 1.0D, SessionView.Tone.MUTED, m));
            y += LINE;
            for (SessionView.BoosterRow b : v.boosters()) {
                Item time = text(Kind.BOOSTER_TIME, b.time(), 0, y, inner, 1.0D, b.tone(), m);
                items.add(new Item(Kind.BOOSTER_TIME, time.text(), PAD + inner - time.w(), y, time.w(), time.h(), 1.0D, b.tone(), 0));
                items.add(text(Kind.BOOSTER_NAME, b.name(), PAD, y, inner - time.w() - 6, 1.0D, SessionView.Tone.NEUTRAL, m));
                y += LINE;
            }
            y += 2;
        }

        // debug rows: only a view that carries them (developer mode) in the detailed style
        if (style == DashboardStyle.DETAILED && !v.debug().isEmpty()) {
            items.add(new Item(Kind.RULE, "", PAD, y, inner, 1, 1.0D, SessionView.Tone.MUTED, 0));
            y += 4;
            items.add(text(Kind.SECTION, "DEBUG", PAD, y, inner, 1.0D, SessionView.Tone.MUTED, m));
            y += LINE;
            for (SessionView.Row r : v.debug()) {
                if (r.label().isEmpty()) {
                    items.add(text(Kind.ROW_LABEL, r.value(), PAD, y, inner, 1.0D, SessionView.Tone.MUTED, m));
                } else {
                    String[] fitted = TextFit.row(r.label(), r.value(), 5, inner, m);
                    Item value = text(Kind.ROW_VALUE, fitted[1], 0, y, inner, 1.0D, r.tone(), m);
                    items.add(new Item(Kind.ROW_VALUE, value.text(), PAD + inner - value.w(), y, value.w(), value.h(), 1.0D, r.tone(), 0));
                    items.add(text(Kind.ROW_LABEL, fitted[0], PAD, y, inner - value.w() - 5, 1.0D, SessionView.Tone.MUTED, m));
                }
                y += LINE;
            }
        }
        return new Result(items, width, y + PAD - 3, columns);
    }

    private static Item text(Kind kind, String text, int x, int y, int maxW, double scale, SessionView.Tone tone, TextFit.Measure m) {
        TextFit.Measure scaled = s -> (int) Math.ceil(m.width(s) * scale);
        String fitted = TextFit.ellipsize(text, maxW, scaled);
        return new Item(kind, fitted, x, y, scaled.width(fitted), (int) Math.ceil(9 * scale), scale, tone, 0);
    }
}
