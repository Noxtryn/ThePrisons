package io.theprisons.items.energy;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The ready-to-draw view of an {@link EnergyExtractorState}: only what is KNOWN. No capacity -> no progress and no percent; no rate -> no rate line; no
 * rate or no capacity -> no ETA. Built once per state revision (and per time bucket for the stale flag), never per frame.
 *
 * @param progress 0..1, null when the capacity is unknown
 */
public record EnergyOverlayModel(long revision, String title, @Nullable Float progress, String stored, @Nullable String capacity, @Nullable String percent,
                                 @Nullable String rate, @Nullable String eta, String status, boolean stale, List<String> unknown) {
    public EnergyOverlayModel {
        unknown = List.copyOf(unknown);
    }

    public static EnergyOverlayModel of(EnergyExtractorState s, long now) {
        boolean stale = s.stale(now);
        Float progress = null;
        String capacity = null;
        String percent = null;
        List<String> unknown = new ArrayList<>();
        if (s.capacity() != null && s.capacity() > 0L) {
            progress = (float) Math.max(0.0D, Math.min(1.0D, (double) s.current() / s.capacity()));
            capacity = EnergyFormat.compact(s.capacity());
            percent = Math.round(progress * 100.0F) + "%";
        } else {
            unknown.add("capacity");
        }
        String rate = null;
        String eta = null;
        if (!stale && s.rateMin() != null && s.operation() != EnergyOperation.UNKNOWN) {
            rate = EnergyFormat.rate(s.rateMin());
            if (s.capacity() != null && s.rateMin() > 0.0D && s.current() < s.capacity()) {
                eta = EnergyFormat.duration(Math.round((s.capacity() - s.current()) / s.rateMin() * 60.0D));
            }
        } else {
            unknown.add("rate");
        }
        if (eta == null) {
            unknown.add("eta");
        }
        String status = stale ? "STALE" : switch (s.operation()) {
            case GAINING -> "GAINING";
            case DRAINING -> "DRAINING";
            case IDLE -> "IDLE";
            case UNKNOWN -> "READING";
        };
        return new EnergyOverlayModel(s.revision(), "ENERGY", progress, EnergyFormat.compact(s.current()), capacity, percent, rate, eta, status, stale, unknown);
    }

    /** The bar as text: "██████████░░░░░░" (16 cells). */
    public static String bar(float progress) {
        int full = Math.round(Math.max(0.0F, Math.min(1.0F, progress)) * 16.0F);
        return "█".repeat(full) + "░".repeat(16 - full);
    }

    /** The compact lines: "⚡ 7.42M / 10M", the bar with the percent, the rate - only what is known. */
    public List<String> compactLines() {
        List<String> out = new ArrayList<>();
        out.add("⚡ " + stored + (capacity != null ? " / " + shorten(capacity) : ""));
        if (progress != null) {
            out.add(bar(progress) + " " + percent);
        }
        if (rate != null) {
            out.add(rate);
        }
        return out;
    }

    /** The detailed lines in the order of the spec; labels padded; only known values. */
    public List<String> detailedLines() {
        List<String> out = new ArrayList<>();
        out.add(title);
        if (progress != null) {
            out.add(bar(progress) + " " + percent);
        }
        out.add(pad("Stored") + stored);
        if (capacity != null) {
            out.add(pad("Capacity") + capacity);
        }
        if (rate != null) {
            out.add(pad("Rate") + rate);
        }
        if (eta != null) {
            out.add(pad("ETA") + eta);
        }
        out.add(pad("Status") + status);
        return out;
    }

    private static String pad(String label) {
        return String.format(java.util.Locale.ROOT, "%-9s ", label);
    }

    private static String shorten(String compact) {
        return compact.replaceAll("\\.0+([KMB])$", "$1").replaceAll("(\\.\\d*?)0+([KMB])$", "$1$2");
    }
}
