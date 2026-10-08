package io.theprisons.hud;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the session dashboard shows, as plain prepared text: built when the statistics snapshot changes (a few times a second), never per frame. It holds
 * only values that are KNOWN - a metric the macro does not provide is simply not in the lists.
 *
 * @param stats  the basic status rows of the STANDARD view (at most about four)
 * @param detail the analytics of the DETAILED view
 * @param debug  internal data: only with developer mode / debug on, never in the normal dashboard
 */
public record SessionView(Kind kind, String title, String clock, String stateLine, List<Kpi> kpis, @Nullable Activity activity, List<Row> stats, List<Row> detail,
                          List<BoosterRow> boosters, List<Row> debug) {
    public enum Kind { ORE, BANDIT }

    /** The colour role of a value (the renderer maps it to the theme). */
    public enum Tone { NEUTRAL, PRIMARY, ENERGY, XP, TIME, GOOD, WARN, BAD, MUTED }

    /** A major number: a big value over a small label ("4.86" / "OP/s"), with an optional small sub line (an average). */
    public record Kpi(String value, String label, @Nullable String sub, Tone tone) {
    }

    /** What is being done now, and how full the inventory is (-1 = unknown: no bar). */
    public record Activity(String text, int inventoryPercent) {
    }

    public record Row(String label, String value, Tone tone) {
    }

    public record BoosterRow(String name, String time, Tone tone) {
    }

    public SessionView {
        kpis = List.copyOf(kpis);
        stats = List.copyOf(stats);
        detail = List.copyOf(detail);
        boosters = List.copyOf(boosters);
        debug = List.copyOf(debug);
    }
}
