package io.theprisons.modules.hud.tab;

import io.theprisons.core.client.TextStrip;
import io.theprisons.gui.kit.Ui;
import io.theprisons.mixin.ThePrisonsPlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod's own tab list (used by the Tab key and, clickable, by Shift + Tab): players grouped by their rank in the
 * server's order, each group with a coloured header and its count, faces, names in the rank colour and ping; fake
 * entries (spacers, NPC lines) left out, unlisted players included; the server's header / footer as an info column
 * without the store / Discord lines.
 */
public final class TabList {
    public static final int ROW = 10;
    private static final int HEAD = 8;
    private static final int PAD = 6;
    private static final int GAP = 10;
    private static final int INFO_W = 120;
    /** A Minecraft account name: what real players have (spacers and NPC lines do not). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern RANK_TAG = Pattern.compile("<([A-Za-z+]{2,16})(?:●\\d+)?>");
    private static final Set<String> KNOWN_RANKS = Set.of("admin", "owner", "manager", "mod", "moderator", "helper",
            "banteam", "builder", "empereon", "divergent", "ulterior", "transcendent", "invoker", "arcanist", "wisp",
            "trainee", "vip", "vip+", "media", "youtube");

    private TabList() {
    }

    public record Group(String rank, int colour, List<PlayerListEntry> players) {
    }

    /** A clickable thing in the drawn list: a player (id set) or a rank header (rank set). */
    public record Slot(int x, int y, int w, int h, @Nullable UUID id, @Nullable String rank) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** The players in the server's order, without fakes, plus unlisted ones; grouped by rank. */
    public static List<Group> groups(MinecraftClient client, String filter, Set<String> hiddenRanks) {
        List<PlayerListEntry> ordered = new ArrayList<>(((ThePrisonsPlayerListHudAccessor) client.inGameHud.getPlayerListHud())
                .theprisons$collectPlayerEntries());
        Set<UUID> seen = new HashSet<>();
        for (PlayerListEntry e : ordered) {
            seen.add(e.getProfile().id());
        }
        if (client.getNetworkHandler() != null) {
            for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
                if (seen.add(e.getProfile().id())) {
                    ordered.add(e);
                }
            }
        }
        String f = filter.toLowerCase(Locale.ROOT);
        Map<String, Group> groups = new LinkedHashMap<>();
        for (PlayerListEntry e : ordered) {
            String name = e.getProfile().name();
            if (name == null || !NAME.matcher(name).matches()) {
                continue; // spacer / NPC line
            }
            Text shown = client.inGameHud.getPlayerListHud().getPlayerName(e);
            String plain = TextStrip.strip(shown.getString());
            if (!f.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(f) && !plain.toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            String rank = rankOf(plain);
            if (hiddenRanks.contains(rank.toLowerCase(Locale.ROOT))) {
                groups.computeIfAbsent(rank, r -> new Group(r, colourOf(shown, rank), new ArrayList<>()));
                continue; // header stays (to show it again), players hidden
            }
            groups.computeIfAbsent(rank, r -> new Group(r, colourOf(shown, rank), new ArrayList<>())).players().add(e);
        }
        return new ArrayList<>(groups.values());
    }

    /** "(82) <Transcendent> Steve [Gang]" / "Transcendent Steve" -> "Transcendent"; "Players" when there is none. */
    static String rankOf(String plain) {
        Matcher m = RANK_TAG.matcher(plain);
        if (m.find()) {
            return m.group(1);
        }
        for (String word : plain.replaceAll("[\\[\\]()<>|]", " ").trim().split("\\s+")) {
            if (KNOWN_RANKS.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return "Players";
    }

    /** The colour of the rank (or else the name) as the server shows it. */
    private static int colourOf(Text shown, String rank) {
        int[] colour = {-1, -1};
        shown.visit((style, s) -> {
            TextColor c = style.getColor();
            if (c != null) {
                if (colour[0] < 0 && s.contains(rank)) {
                    colour[0] = c.getRgb();
                }
                if (colour[1] < 0 && !s.isBlank()) {
                    colour[1] = c.getRgb();
                }
            }
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return colour[0] >= 0 ? colour[0] : colour[1] >= 0 ? colour[1] : 0xE0E4EC;
    }

    /** The name's own colour in the server's tab name (white when it has none). */
    private static int nameColour(Text shown, String name) {
        int[] colour = {0xFFFFFF};
        shown.visit((style, s) -> {
            if (s.contains(name) && style.getColor() != null) {
                colour[0] = style.getColor().getRgb();
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return colour[0];
    }

    /** Laid out rows: a group header or a player, in columns. */
    private record Cell(int column, int row, Group group, @Nullable PlayerListEntry player) {
    }

    /**
     * Draws the tab list card centred at {@code centerX}, from {@code top}; {@code firstColumn} scrolls sideways.
     * Returns the clickable slots and, in {@code out[0]}, how many columns there are, in {@code out[1]} how many fit.
     */
    public static List<Slot> draw(DrawContext c, MinecraftClient client, int centerX, int top, int maxWidth, int maxHeight,
                                  float alpha, String filter, Set<String> hiddenRanks, @Nullable UUID selected,
                                  int mouseX, int mouseY, int firstColumn, int[] out) {
        TextRenderer tr = client.textRenderer;
        List<Group> groups = groups(client, filter, hiddenRanks);
        List<String> info = BetterTabModule.infoLines(client);
        int a = Math.round(255 * alpha);

        int nameW = 50;
        int total = 0;
        for (Group g : groups) {
            nameW = Math.max(nameW, Ui.width(tr, g.rank() + "  " + g.players().size()));
            for (PlayerListEntry e : g.players()) {
                nameW = Math.max(nameW, tr.getWidth(e.getProfile().name()));
                total++;
            }
        }
        int colW = HEAD + 3 + nameW + 4 + 14;
        int infoW = info.isEmpty() ? 0 : INFO_W + GAP;
        int rows = Math.max(6, Math.min(24, (maxHeight - 30) / ROW));

        // flow: a header row then its players; a group never starts on the last row of a column
        List<Cell> cells = new ArrayList<>();
        int col = 0;
        int row = 0;
        for (Group g : groups) {
            if (row >= rows - 1) {
                col++;
                row = 0;
            }
            cells.add(new Cell(col, row++, g, null));
            for (PlayerListEntry e : g.players()) {
                if (row >= rows) {
                    col++;
                    row = 0;
                }
                cells.add(new Cell(col, row++, g, e));
            }
        }
        int columns = cells.isEmpty() ? 1 : col + 1;
        int fit = Math.max(1, (maxWidth - PAD * 2 - infoW + GAP) / (colW + GAP));
        int first = Math.max(0, Math.min(firstColumn, Math.max(0, columns - fit)));
        int shownCols = Math.min(fit, columns);
        out[0] = columns;
        out[1] = fit;
        int usedRows = 0;
        for (Cell cell : cells) {
            if (cell.column() >= first && cell.column() < first + shownCols) {
                usedRows = Math.max(usedRows, cell.row() + 1);
            }
        }
        int gridW = shownCols * colW + (shownCols - 1) * GAP;
        int width = Math.max(PAD * 2 + gridW + infoW, Math.min(maxWidth, 220));
        int infoRows = Math.min(info.size(), rows);
        int height = 22 + Math.max(Math.max(1, usedRows) * ROW, infoRows * ROW) + PAD + (columns > shownCols ? ROW : 0);
        int x = centerX - width / 2;

        Ui.card(c, x, top, width, height, alpha);
        Ui.flowLine(c, x, x + width, top, alpha);
        Ui.shimmer(c, tr, "COSMIC PRISONS", x + PAD, top + 6, alpha);
        PlayerListEntry self = client.player != null && client.getNetworkHandler() != null
                ? client.getNetworkHandler().getPlayerListEntry(client.player.getUuid()) : null;
        String status = total + " online" + (self != null ? "  ·  " + self.getLatency() + " ms" : "");
        Ui.drawRight(c, tr, status, x + width - PAD, top + 6, Ui.LABEL, a);
        Ui.line(c, x + PAD, x + width - PAD, top + 17, 0.5F * alpha);

        List<Slot> slots = new ArrayList<>();
        int gy = top + 21;
        for (Cell cell : cells) {
            if (cell.column() < first || cell.column() >= first + shownCols) {
                continue;
            }
            int cx = x + PAD + (cell.column() - first) * (colW + GAP);
            int cy = gy + cell.row() * ROW;
            Group g = cell.group();
            PlayerListEntry e = cell.player();
            boolean over = mouseX >= cx - 2 && mouseX < cx + colW && mouseY >= cy - 1 && mouseY < cy + ROW - 1;
            if (e == null) {
                boolean hidden = hiddenRanks.contains(g.rank().toLowerCase(Locale.ROOT));
                c.fill(cx - 2, cy - 1, cx + colW, cy + ROW - 1, Ui.argb(Math.round((over ? 70 : 40) * alpha), g.colour()));
                c.fill(cx - 2, cy - 1, cx, cy + ROW - 1, Ui.argb(a, g.colour()));
                Ui.draw(c, tr, g.rank().toUpperCase(Locale.ROOT), cx + 2, cy, hidden ? Ui.MUTED : g.colour(), a);
                Ui.drawRight(c, tr, hidden ? "hidden" : String.valueOf(g.players().size()), cx + colW - 2, cy, Ui.LABEL, a);
                slots.add(new Slot(cx - 2, cy - 1, colW + 2, ROW, null, g.rank()));
                continue;
            }
            UUID id = e.getProfile().id();
            boolean isSelf = self != null && id.equals(self.getProfile().id());
            if (id.equals(selected)) {
                c.fill(cx - 2, cy - 1, cx + colW, cy + ROW - 1, Ui.argb(Math.round(70 * alpha), Ui.theme().accent()));
            } else if (over) {
                c.fill(cx - 2, cy - 1, cx + colW, cy + ROW - 1, Ui.argb(Math.round(30 * alpha), 0xFFFFFF));
            } else if (cell.row() % 2 == 0) {
                c.fill(cx - 2, cy - 1, cx + colW, cy + ROW - 1, Ui.argb(Math.round(12 * alpha), 0xFFFFFF));
            }
            Text shown = client.inGameHud.getPlayerListHud().getPlayerName(e);
            io.theprisons.modules.qol.players.FriendsModule friends =
                    io.theprisons.modules.qol.players.FriendsModule.get();
            int mate = friends == null || isSelf ? -1 : friends.tabColour(e.getProfile().name(), shown.getString());
            if (isSelf || id.equals(selected)) {
                Ui.outline(c, cx - 2, cy - 1, colW + 2, ROW, Ui.argb(Math.round(200 * alpha), Ui.theme().accent()));
            } else if (mate >= 0) {
                // Friend (blue) / gang mate (pink): framed in their colour.
                c.fill(cx - 2, cy - 1, cx + colW, cy + ROW - 1, Ui.argb(Math.round(28 * alpha), mate));
                Ui.outline(c, cx - 2, cy - 1, colW + 2, ROW, Ui.argb(Math.round(230 * alpha), mate));
            }
            PlayerSkinDrawer.draw(c, e.getSkinTextures(), cx, cy, HEAD);
            c.drawText(tr, e.getProfile().name(), cx + HEAD + 3, cy, Ui.argb(a, nameColour(shown, e.getProfile().name())), true);
            pingBars(c, cx + colW - 13, cy + 1, e.getLatency(), a);
            slots.add(new Slot(cx - 2, cy - 1, colW + 2, ROW, id, null));
        }
        if (cells.isEmpty()) {
            Ui.draw(c, tr, filter.isEmpty() ? "No players" : "No player matches \"" + filter + "\"", x + PAD, gy, Ui.MUTED, a);
        }
        if (columns > shownCols) {
            String more = "columns " + (first + 1) + "–" + (first + shownCols) + " of " + columns + "  ·  scroll for more";
            Ui.draw(c, tr, more, x + PAD, top + height - PAD - ROW + 1, Ui.MUTED, a);
        }
        if (!info.isEmpty()) {
            int ix = x + PAD + gridW + GAP;
            c.fill(ix - GAP / 2, gy, ix - GAP / 2 + 1, gy + Math.max(usedRows, infoRows) * ROW, Ui.argb(Math.round(70 * alpha),
                    Ui.theme().accent()));
            for (int i = 0; i < infoRows; i++) {
                Ui.draw(c, tr, BetterTabModule.fit(tr, info.get(i), INFO_W), ix + 2, gy + i * ROW,
                        i == 0 ? Ui.theme().title() : Ui.VALUE, a);
            }
        }
        out[2] = height;
        return slots;
    }

    /** Four bars, coloured by latency (green fast, amber medium, red slow, grey unknown). */
    static void pingBars(DrawContext context, int x, int y, int latency, int alpha) {
        int bars = latency <= 0 ? 0 : latency < 150 ? 4 : latency < 300 ? 3 : latency < 600 ? 2 : 1;
        int colour = bars >= 4 ? Ui.GOOD : bars == 3 ? Ui.WARN : bars == 2 ? 0xFFA040 : Ui.BAD;
        for (int i = 0; i < 4; i++) {
            int h = 2 + i * 2;
            int c = i < bars ? Ui.argb(alpha, colour) : Ui.argb(alpha / 4, 0xFFFFFF);
            context.fill(x + i * 3, y + 8 - h, x + i * 3 + 2, y + 8, c);
        }
    }
}
