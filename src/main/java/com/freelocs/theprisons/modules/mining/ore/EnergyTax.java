package com.freelocs.theprisons.modules.mining.ore;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The guard tax read from the energy one ore gives, not from the sidebar (pure logic, no Minecraft types).
 *
 * <ul>
 *     <li><b>Energy per ore:</b> the energy of one ore is the same for every ore (how many ores a moment brings changes,
 *     the energy per ore does not). Every rise of the pickaxe's energy becomes a pair (energy, ores): a rise of at most
 *     {@value #EXACT_ORES} ores that is a whole multiple of the reference counts exactly (the server credits ore by ore);
 *     otherwise the ores broken since the last rise ({@link #ore}) are used (the server credits several at once). The
 *     energy per ore now is the sum of the last pairs over at least {@value #MIN_ORES} ores. Rises with no ore broken
 *     (orbs, energy put in) are not used.</li>
 *     <li><b>Decision:</b> blocks of at least {@value #MIN_ORES} ores (not overlapping) against the reference (the value
 *     before); a change needs two blocks in a row, the second (wholly after the step) decides:
 *     {@value #TAX_MIN}..{@value #TAX_MAX} more per ore = the tax fell away (outside the guarded area);
 *     the same less = back inside. {@value #BOOST_UP} more or {@value #BOOST_DOWN} less = an energy booster started or
 *     ended: only a new reference, the state stays. Within {@value #DRIFT} the reference follows slowly (the tax
 *     changes a little with the distance to the guards while walking inside).</li>
 * </ul>
 */
public final class EnergyTax {
    static final int MIN_ORES = 8;
    static final int EXACT_ORES = 3;
    static final int MAX_ORES_PER_RISE = 40;
    static final double TAX_MIN = 0.02D;
    /** The guard tax is 4 % at the zone's edge up to 10 % near a guard (the user): a jump of 2..12 % is the tax. */
    static final double TAX_MAX = 0.12D;
    static final double BOOST_UP = 0.20D;
    static final double BOOST_DOWN = 0.17D;
    static final double DRIFT = 0.015D;
    /** Positions kept for the edge (one per pair). */
    static final int WINDOW = 48;
    private static final int RING = 64;

    /** A state change: {@code taxed} now, the energy per ore before and after. */
    /** {@code pairsAgo}: the step began this many measured rises ago (where the edge was crossed). */
    public record Change(boolean taxed, double before, double after, int pairsAgo) {
    }

    private String pickaxe = "";
    private long last = -1L;
    private int oresSince;
    private double reference = Double.NaN;
    /** A first block off the reference, waiting for the next one to agree. */
    private double candidate = Double.NaN;
    private int typeSince;
    private boolean mixed;
    private int referenceType;
    private long candidateFrom;
    /** Pairs in the last block. */
    private int blockPairs;
    private int blockOres;
    private long blocks;
    private double lastBlock = Double.NaN;
    private int lastBlockOres;
    private final long[] rise = new long[RING];
    private final int[] ores = new int[RING];
    private int next;
    /** Pairs since the last restart (only those decide). */
    private int fresh;
    private @Nullable Boolean taxed;
    private @Nullable String event;
    private long samples;
    private long rises;
    private long oresTotal;

    public void reset() {
        pickaxe = "";
        last = -1L;
        oresSince = 0;
        reference = Double.NaN;
        candidate = Double.NaN;
        typeSince = 0;
        mixed = false;
        referenceType = 0;
        next = 0;
        fresh = 0;
        taxed = null;
        event = null;
        samples = 0L;
        rises = 0L;
        oresTotal = 0L;
    }

    /** {@code true} = taxed (inside), {@code false} = outside, {@code null} = not known yet. */
    public @Nullable Boolean taxed() {
        return taxed;
    }

    /** Back at the last place with the tax: inside again at once, a new reference (no waiting for two blocks). */
    public void forceInside() {
        taxed = Boolean.TRUE;
        rebase();
    }

    /** The state before anything was measured (the macro starts in the guarded area). */
    public void assume(boolean inside) {
        if (taxed == null) {
            taxed = inside;
        }
    }

    /** {@code count} ores broken (the energy for them comes with the next rise). */
    public void ore(int count) {
        ore(count, 0);
    }

    /**
     * {@code count} ores of {@code type} broken. Every ore type gives its own energy (wiki: "the amount of energy will
     * vary based on what ore you are mining"): a rise over ores of two types is not used, another type starts a new
     * reference.
     */
    public void ore(int count, int type) {
        if (oresSince > 0 && type != typeSince) {
            mixed = true;
        }
        typeSince = type;
        oresSince += count;
        oresTotal += count;
    }

    /**
     * An energy booster started or ended (chat), a /comp booster gives only 1.1x-1.5x - which would look like the tax
     * falling away: a new reference, the tax state stays.
     */
    public void rebase() {
        reference = Double.NaN;
        candidate = Double.NaN;
        fresh = 0;
    }

    /** The energy per ore now (NaN = not known yet). */
    public double perOre() {
        double now = current();
        return Double.isNaN(now) ? reference : now;
    }

    public double reference() {
        return reference;
    }

    /** Pairs used. */
    public long samples() {
        return samples;
    }

    /** Energy rises seen (diagnostics). */
    public long rises() {
        return rises;
    }

    public long oresTotal() {
        return oresTotal;
    }

    public long energyNow() {
        return last;
    }

    /** Blocks measured so far (each {@link #lastBlock} over {@link #lastBlockOres} ores). */
    public long blocks() {
        return blocks;
    }

    public double lastBlock() {
        return lastBlock;
    }

    public int lastBlockOres() {
        return lastBlockOres;
    }

    /** A booster / ambiguous jump since the last call, for the log (once). */
    public @Nullable String takeEvent() {
        String e = event;
        event = null;
        return e;
    }

    /** The held pickaxe's energy ({@code key} = which pickaxe). Returns the change of state it caused, or null. */
    public @Nullable Change energy(String key, long value) {
        if (!key.equals(pickaxe) || last < 0L || value <= last) {
            // Another pickaxe, the first read, or energy taken out: only a new baseline.
            if (!key.equals(pickaxe)) {
                reference = Double.NaN;
                candidate = Double.NaN;
                fresh = 0;
            }
            pickaxe = key;
            last = value;
            oresSince = 0;
            return null;
        }
        long gain = value - last;
        last = value;
        rises++;
        int counted = oresSince;
        boolean wasMixed = mixed;
        int type = typeSince;
        oresSince = 0;
        mixed = false;
        if (wasMixed) {
            return null;
        }
        if (counted > 0 && type != referenceType) {
            rebase();
            referenceType = type;
        }
        int k;
        double multiple = Double.isNaN(reference) ? Double.NaN : gain / reference;
        if (!Double.isNaN(multiple) && multiple <= EXACT_ORES + 0.4D && multiple >= 0.6D
                && Math.abs(multiple - Math.round(multiple)) <= 0.25D) {
            k = (int) Math.round(multiple);
        } else if (counted >= 1 && counted <= MAX_ORES_PER_RISE) {
            k = counted;
        } else {
            return null;
        }
        rise[next] = gain;
        ores[next] = k;
        next = (next + 1) % RING;
        fresh = Math.min(RING, fresh + 1);
        samples++;
        double block = current();
        if (Double.isNaN(block)) {
            return null;
        }
        blocks++;
        lastBlock = block;
        lastBlockOres = blockOres;
        // Blocks of at least MIN_ORES ores that do not overlap: a step shows up whole in the block after the one it
        // falls into, never smeared over a sliding average.
        fresh = 0;
        if (Double.isNaN(reference)) {
            reference = block;
            return null;
        }
        double ratio = block / reference;
        if (Math.abs(ratio - 1.0D) <= DRIFT) {
            candidate = Double.NaN;
            reference += 0.2D * (block - reference);
            return null;
        }
        if (Double.isNaN(candidate) || Math.signum(candidate - reference) != Math.signum(block - reference)) {
            // Off for the first time: the next block must agree (the step may lie inside this one).
            candidate = block;
            candidateFrom = samples - blockPairs;
            return null;
        }
        candidate = Double.NaN;
        double before = reference;
        reference = block;
        if (ratio >= 1.0D + BOOST_UP || ratio <= 1.0D - BOOST_DOWN) {
            event = String.format(java.util.Locale.ROOT, "energy per ore %.2f -> %.2f (%+.0f%%): booster, tax state unchanged",
                    before, block, (ratio - 1.0D) * 100.0D);
            return null;
        }
        boolean up = ratio >= 1.0D + TAX_MIN && ratio <= 1.0D + TAX_MAX;
        boolean down = ratio <= 1.0D - TAX_MIN && ratio >= 1.0D - TAX_MAX;
        if (!up && !down) {
            event = String.format(java.util.Locale.ROOT, "energy per ore %.2f -> %.2f (%+.0f%%): unclear, new reference",
                    before, block, (ratio - 1.0D) * 100.0D);
            return null;
        }
        boolean inside = down;
        if (taxed != null && taxed == inside) {
            // Deeper in / further out of the zone than before: the same state.
            return null;
        }
        taxed = inside;
        return new Change(inside, before, block, (int) Math.min(WINDOW - 1, samples - candidateFrom));
    }

    /** Energy per ore over the newest fresh pairs with at least {@value #MIN_ORES} ores; NaN = not enough yet. */
    private double current() {
        long energy = 0L;
        int count = 0;
        for (int i = 1; i <= fresh; i++) {
            int at = Math.floorMod(next - i, RING);
            energy += rise[at];
            count += ores[at];
            if (count >= MIN_ORES) {
                blockPairs = i;
                blockOres = count;
                return energy / (double) count;
            }
        }
        return Double.NaN;
    }
}
