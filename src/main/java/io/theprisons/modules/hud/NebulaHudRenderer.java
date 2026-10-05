package io.theprisons.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The session HUD in a modern "premium mod" look: a dark translucent card with soft corners, a thin animated chroma
 * line on top, a chroma title, slim sans-serif text (Noto Sans, {@code theprisons:nebula}), grey labels on the left,
 * pastel values on the right and hairline separators between the groups. Booster rows only exist while a booster is
 * active. Draws a ready {@link CosmicStats.Snapshot} - no parsing or allocation-heavy work per frame beyond the rows.
 */
public final class NebulaHudRenderer {
    static final Identifier FONT = Identifier.of("theprisons", "boxy");
    private static final int BG = 0xC80E1016;
    private static final int BORDER = 0x26FFFFFF;
    private static final int SEPARATOR = 0x1FFFFFFF;
    private static final int LABEL = 0xFF9AA3B5;
    private static final int MUTED = 0xFF6B7385;
    static final int MINT = 0xFF8CF5C2;
    static final int SKY = 0xFF8AD8FF;
    static final int LAVENDER = 0xFFC3A6FF;
    static final int PEACH = 0xFFFFC59E;
    static final int ROSE = 0xFFFF9EB5;
    static final int BUTTER = 0xFFFFE89E;
    static final int WARN = 0xFFFF5E6C;
    /** Below this durability share the pickaxe warning shows. */
    static final double DURABILITY_WARN = 0.10D;
    private static final int PAD = 7;
    private static final int ROW = 11;
    private static final int GAP = 5;
    private static final int MIN_WIDTH = 150;

    /** One label / value row, or a separator ({@code label == null}). */
    record Row(@Nullable String label, String value, int color, String hint) {
        static final Row SEPARATOR = new Row(null, "", 0, "");
    }

    private NebulaHudRenderer() {
    }

    /** Which optional rows are shown (Session HUD settings). */
    /**
     * @param title the card's title: the kind of session ("Ore Mining", "Bandit")
     * @param activity what is being done right now ("Mining Gold", the macro's step, "Idle"); empty = no row
     * @param bandit Bandit stats: kills instead of ores, OP/s and the pickaxe rows
     */
    public record Options(boolean botState, boolean levelUpEta, boolean enchantYield, String title, String activity,
                          boolean bandit, long kills, long playerKills) {
        public static final Options ALL = new Options(true, true, true, "SESSION STATS", "", false, 0L, 0L);
    }

    static List<Row> rows(CosmicStats.Snapshot s, long nowMs) {
        return rows(s, nowMs, Options.ALL);
    }

    /** The rows for a snapshot (groups split by separators; booster rows only when active). */
    static List<Row> rows(CosmicStats.Snapshot s, long nowMs, Options options) {
        List<Row> rows = new ArrayList<>();
        if (s.durability() >= 0.0D && s.durability() < DURABILITY_WARN) {
            // Blinks (on 600 ms / off 400 ms) in red until the pickaxe is repaired or put away.
            boolean on = nowMs % 1000L < 600L;
            rows.add(new Row("Pickaxe", "REPAIR REQUIRED", on ? WARN : 0x66FF5E6C,
                    String.format(Locale.ROOT, "%.0f %%", s.durability() * 100.0D)));
            rows.add(Row.SEPARATOR);
        }
        if (!options.activity().isEmpty()) {
            rows.add(new Row("Activity", options.activity(), options.activity().equals("Idle") ? MUTED : MINT, ""));
        }
        if (options.botState() && !s.botState().isEmpty()) {
            rows.add(new Row("Bot State", s.botState(), switch (s.botState()) {
                case "ESCAPING" -> ROSE;
                case "MINING" -> MINT;
                case "WAITING", "RECOVERING" -> BUTTER;
                default -> SKY;
            }, ""));
        }
        if (options.botState() && s.inventoryPercent() >= 0) {
            rows.add(new Row("Inventory", s.inventoryPercent() + " %",
                    s.inventoryPercent() >= 90 ? ROSE : s.inventoryPercent() >= 70 ? BUTTER : MINT, ""));
        }
        rows.add(new Row("UpTime", clock(s.uptimeMs()), BUTTER, ""));
        if (options.bandit()) {
            double hours = s.uptimeMs() / 3_600_000.0D;
            rows.add(new Row("Bandit Kills", compact(options.kills()), MINT, hours > 0.01D
                    ? String.format(Locale.ROOT, "%.0f/h", options.kills() / hours) : ""));
            rows.add(new Row("Player Kills", compact(options.playerKills()), ROSE, ""));
            rows.add(Row.SEPARATOR);
            rows.add(new Row("Energy/h", compact(Math.round(s.energyPerHour())), SKY, "now"));
            if (s.energyAvgPerHour() >= 0.0D) {
                rows.add(new Row("  avg", compact(Math.round(s.energyAvgPerHour())), MUTED, "5m"));
            }
            rows.add(new Row("XP/h", compact(Math.round(s.xpPerHour())), LAVENDER, "now"));
            if (s.xpAvgPerHour() >= 0.0D) {
                rows.add(new Row("  avg", compact(Math.round(s.xpAvgPerHour())), MUTED, "5m"));
            }
            if (options.levelUpEta()) {
                rows.add(new Row("Level-Up", s.levelUpEtaMs() < 0L ? "—" : eta(s.levelUpEtaMs()),
                        s.levelUpEtaMs() < 0L ? MUTED : LAVENDER, s.levelUpEtaMs() < 0L ? "" : "ETA"));
            }
            boosters(rows, s, nowMs);
            return rows;
        }
        rows.add(new Row("OP/s", String.format(Locale.ROOT, "%.2f", s.opsRecent()), MINT,
                String.format(Locale.ROOT, "avg %.2f", s.opsAverage())));
        rows.add(new Row("Ores", compact(s.ores()), MINT, options.enchantYield() && s.procShare() > 0.0D
                ? String.format(Locale.ROOT, "procs +%.0f%%", s.procShare() * 100.0D) : ""));
        if (options.enchantYield() && !s.loreProcs().isEmpty()) {
            rows.add(new Row("Enchants", s.loreProcs(), PEACH, ""));
        }
        if (options.enchantYield() && s.forecastOps() >= 0.0D) {
            rows.add(new Row("Forecast", String.format(Locale.ROOT, "%.2f OP/s", s.forecastOps()), MINT,
                    String.format(Locale.ROOT, "%.0f%% proc", s.loreChance() * 100.0D)));
        }
        rows.add(Row.SEPARATOR);
        rows.add(new Row("Energy/h", compact(Math.round(s.energyPerHour())), SKY, "now"));
        if (s.energyAvgPerHour() >= 0.0D) {
            rows.add(new Row("  avg", compact(Math.round(s.energyAvgPerHour())), MUTED, "5m"));
        }
        rows.add(new Row("XP/h", compact(Math.round(s.xpPerHour())), LAVENDER, "now"));
        if (s.xpAvgPerHour() >= 0.0D) {
            rows.add(new Row("  avg", compact(Math.round(s.xpAvgPerHour())), MUTED, "5m"));
        }
        if (options.levelUpEta()) {
            rows.add(new Row("Level-Up", s.levelUpEtaMs() < 0L ? "—" : eta(s.levelUpEtaMs()),
                    s.levelUpEtaMs() < 0L ? MUTED : LAVENDER, s.levelUpEtaMs() < 0L ? "" : "ETA"));
        }
        rows.add(Row.SEPARATOR);
        Double tax = s.taxPercent();
        rows.add(new Row("Current Tax", tax == null ? "—" : String.format(Locale.ROOT, "%.1f %%", tax),
                tax == null ? MUTED : tax <= 0.0D ? MINT : tax < 10.0D ? BUTTER : ROSE, s.taxNote()));
        boosters(rows, s, nowMs);
        if (io.theprisons.modules.FeatureProfile.DEV) {
            rows.add(new Row("Learned Routes", String.valueOf(s.learnedRoutes()), LAVENDER, ""));
        }
        return rows;
    }

    /** The active boosters, then a separator. */
    private static void boosters(List<Row> rows, CosmicStats.Snapshot s, long nowMs) {
        for (CosmicStats.Booster b : s.serverBoosters()) {
            rows.add(new Row("Server Booster", booster(b), PEACH, left(b, nowMs)));
        }
        for (CosmicStats.Booster b : s.personalBoosters()) {
            rows.add(new Row("Booster", booster(b), ROSE, left(b, nowMs)));
        }
        rows.add(Row.SEPARATOR);
    }

    /** Draws the card; returns its unscaled size {width, height}. */
    public static int[] draw(DrawContext context, CosmicStats.Snapshot s, int x, int y, float scale, boolean sleekFont,
                            Options options) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        long now = System.currentTimeMillis();
        List<Row> rows = rows(s, now, options);

        String title = options.title().isEmpty() ? "SESSION STATS" : options.title().toUpperCase(Locale.ROOT);
        String mode = s.mode().isEmpty() ? "" : s.mode().toUpperCase(Locale.ROOT);
        int width = Math.max(MIN_WIDTH, width(tr, title, sleekFont) + (mode.isEmpty() ? 0 : width(tr, mode, sleekFont) + 14) + 2 * PAD);
        int height = PAD + ROW + 3;
        for (Row row : rows) {
            if (row.label() == null) {
                height += GAP;
                continue;
            }
            String value = row.hint().isEmpty() ? row.value() : row.value() + "  " + row.hint();
            width = Math.max(width, width(tr, row.label(), sleekFont) + width(tr, value, sleekFont) + 2 * PAD + 14);
            height += ROW;
        }
        height += PAD - 2;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);

        // Card in the mod's design: theme darkness, flowing accent line on top, shimmering title.
        io.theprisons.gui.kit.Ui.card(context, 0, 0, width, height, 1.0F);
        io.theprisons.gui.kit.Ui.flowLine(context, 0, width, 0, 1.0F);

        int cy = PAD;
        io.theprisons.gui.kit.Ui.shimmer(context, tr, title, PAD, cy, 1.0F);
        if (!mode.isEmpty()) {
            int pillW = width(tr, mode, sleekFont) + 8;
            int px = width - PAD - pillW;
            int pillColor = switch (s.mode()) {
                case "Flight" -> ROSE;
                case "Cave", "To tunnel" -> PEACH;
                case "Tunnel" -> MINT;
                default -> SKY;
            };
            roundRect(context, px, cy - 2, px + pillW, cy + 9, (pillColor & 0x00FFFFFF) | 0x33000000);
            context.drawText(tr, text(mode, sleekFont), px + 4, cy, pillColor, false);
        }
        cy += ROW + 3;
        io.theprisons.gui.kit.Ui.line(context, PAD, width - PAD, cy - 3, 0.8F);

        for (Row row : rows) {
            if (row.label() == null) {
                io.theprisons.gui.kit.Ui.line(context, PAD, width - PAD, cy + GAP / 2 - 1, 0.35F);
                cy += GAP;
                continue;
            }
            context.drawText(tr, text(row.label(), sleekFont), PAD, cy, LABEL, false);
            int vx = width - PAD;
            if (!row.hint().isEmpty()) {
                vx -= width(tr, row.hint(), sleekFont);
                context.drawText(tr, text(row.hint(), sleekFont), vx, cy, MUTED, false);
                vx -= 4;
            }
            vx -= width(tr, row.value(), sleekFont);
            context.drawText(tr, text(row.value(), sleekFont), vx, cy, row.color(), false);
            cy += ROW;
        }
        context.getMatrices().popMatrix();
        return new int[]{width, height};
    }

    private static Text text(String s, boolean sleek) {
        MutableText t = Text.literal(s);
        return sleek ? t.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(FONT))) : t;
    }

    private static int width(TextRenderer tr, String s, boolean sleek) {
        return tr.getWidth(text(s, sleek));
    }

    private static void roundRect(DrawContext c, int x0, int y0, int x1, int y1, int color) {
        c.fill(x0 + 1, y0, x1 - 1, y1, color);
        c.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        c.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    private static void outline(DrawContext c, int x0, int y0, int x1, int y1, int color) {
        c.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
        c.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        c.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** A pastel chroma colour moving along {@code offset} (0..1) over time. */
    static int chroma(long nowMs, float offset, float saturation, float value) {
        float hue = (nowMs % 6000L) / 6000.0F + offset;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue - (float) Math.floor(hue), saturation, value) & 0x00FFFFFF);
    }

    static String clock(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }

    /** Time to the next level, [HH:MM]. */
    static String eta(long ms) {
        long minutes = (ms + 59_999L) / 60_000L;
        return minutes > 99L * 60L ? ">99:00" : String.format(Locale.ROOT, "%02d:%02d", minutes / 60L, minutes % 60L);
    }

    static String compact(long v) {
        if (v >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB", v / 1.0E9D);
        }
        if (v >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", v / 1.0E6D);
        }
        if (v >= 10_000L) {
            return String.format(Locale.ROOT, "%.1fk", v / 1.0E3D);
        }
        return String.valueOf(v);
    }

    private static String booster(CosmicStats.Booster b) {
        String name = b.name().replaceFirst("(?i)\\s*booster$", "");
        return name.isEmpty() ? String.format(Locale.ROOT, "%.1fx", b.multiplier()) : name;
    }

    private static String left(CosmicStats.Booster b, long nowMs) {
        if (b.endsAtMs() <= 0L) {
            return "active";
        }
        long s = Math.max(0L, (b.endsAtMs() - nowMs) / 1000L);
        return s >= 3600L ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L)
                : String.format(Locale.ROOT, "%d:%02d", s / 60L, s % 60L);
    }
}
