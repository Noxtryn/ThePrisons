package com.freelocs.theprisons.modules.qol.players;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.modules.hud.CosmicStats;
import com.freelocs.theprisons.modules.hud.SessionHudModule;
import com.freelocs.theprisons.modules.hud.scoreboard.ScoreboardModule;
import com.freelocs.theprisons.modules.qol.storage.StorageOverlayModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player viewer of the Shift + Tab list: a header (face, tab name, ping) and pages to pick - Overview (Cosmic's
 * /playerstats for anyone: blocks mined, mining level, balance, bandit kills, playtime, gang), Gear (while nearby) and,
 * for yourself, Inventory, Vaults (every scanned PV with the energy and money in them), Skills (skill points, Ground
 * Zero points) and Session (what you mined / fought last, per activity). Cosmic shares no inventory or vaults of other
 * players, so those pages are yours only.
 */
public final class PlayerViewer {
    public enum Page {
        OVERVIEW("Overview", "session_hud"), GEAR("Gear", "armor_hud"), INVENTORY("Inventory", "satchel_hud"),
        VAULTS("Vaults", "storage_overlay"), SKILLS("Skills", "item_look"), SESSION("Session", "category_mining");

        final String label;
        final String icon;

        Page(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        boolean ownOnly() {
            return this == INVENTORY || this == VAULTS || this == SKILLS || this == SESSION;
        }
    }

    public static final int WIDTH = 230;
    private static final int PAD = 7;
    private static final int ROW = 11;
    private static final Pattern ENERGY = Pattern.compile("(?i)([0-9][0-9,]*)\\s*cosmic energy");
    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");

    /** A clickable page tab or button. */
    public record Hit(int x, int y, int w, int h, @Nullable Page page, @Nullable String action) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private PlayerViewer() {
    }

    /** Draws the viewer; returns its clickable parts. */
    public static List<Hit> draw(DrawContext c, TextRenderer tr, int x, int y, int height, PlayerCard.Subject s, Page page,
                                 int mx, int my, float alpha) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Hit> hits = new ArrayList<>();
        int a = Math.round(255 * alpha);
        boolean self = client.player != null && s.entry().getProfile().id().equals(client.player.getUuid());
        Ui.shadowCard(c, x, y, WIDTH, height, alpha);
        Ui.flowLine(c, x, x + WIDTH, y, alpha);

        // header
        PlayerSkinDrawer.draw(c, s.entry().getSkinTextures(), x + PAD, y + 7, 18);
        Text shown = client.inGameHud.getPlayerListHud().getPlayerName(s.entry());
        c.drawText(tr, shown, x + PAD + 23, y + 7, Ui.argb(a, 0xFFFFFF), true);
        PlayerCard.Facts f = PlayerCard.Facts.of(shown.getString());
        String sub = (f.rank().isEmpty() ? "" : f.rank() + "  ·  ") + s.entry().getLatency() + " ms"
                + (s.player() != null && client.player != null
                ? String.format(Locale.ROOT, "  ·  %.0f m", Math.sqrt(s.player().squaredDistanceTo(client.player))) : "");
        Ui.draw(c, tr, sub, x + PAD + 23, y + 17, Ui.LABEL, a);

        // page tabs (icons; your own pages are greyed for other players)
        int ty = y + 30;
        int tw = (WIDTH - PAD * 2) / Page.values().length;
        for (Page p : Page.values()) {
            int tx = x + PAD + p.ordinal() * tw;
            boolean on = p == page;
            boolean usable = self || !p.ownOnly();
            boolean over = mx >= tx && mx < tx + tw && my >= ty && my < ty + 14;
            c.fill(tx + 1, ty, tx + tw - 1, ty + 14, Ui.argb(Math.round((on ? 90 : over ? 45 : 20) * alpha),
                    on ? Ui.theme().accent() : 0xFFFFFF));
            Ui.icon(c, p.icon, tx + (tw - 10) / 2, ty + 2, 10, alpha * (usable ? 1.0F : 0.3F));
            hits.add(new Hit(tx, ty, tw, 14, p, null));
            if (over) {
                Ui.drawCentered(c, tr, p.label + (usable ? "" : " (only yours)"), x + WIDTH / 2, y + height + 4, Ui.VALUE, a);
            }
        }
        Ui.draw(c, tr, page.label.toUpperCase(Locale.ROOT), x + PAD, ty + 19, Ui.theme().title(), a);
        int cy = ty + 31;
        int bottom = y + height - PAD;
        if (page.ownOnly() && !self) {
            Ui.draw(c, tr, "Cosmic shares this only for", x + PAD, cy, Ui.MUTED, a);
            Ui.draw(c, tr, "your own account.", x + PAD, cy + ROW, Ui.MUTED, a);
            return hits;
        }
        switch (page) {
            case OVERVIEW -> overview(c, tr, x, cy, s, a, hits);
            case GEAR -> gear(c, tr, x, cy, bottom, s, a);
            case INVENTORY -> inventory(c, tr, x, cy, client, mx, my, a);
            case VAULTS -> vaults(c, tr, x, cy, bottom, client, mx, my, a);
            case SKILLS -> skills(c, tr, x, cy, a);
            case SESSION -> session(c, tr, x, cy, bottom, a);
        }
        return hits;
    }

    private static void row(DrawContext c, TextRenderer tr, int x, int y, String label, String value, int colour, int a) {
        Ui.draw(c, tr, label, x + PAD, y, Ui.LABEL, a);
        Ui.drawRight(c, tr, value, x + WIDTH - PAD, y, colour, a);
    }

    private static void overview(DrawContext c, TextRenderer tr, int x, int y, PlayerCard.Subject s, int a, List<Hit> hits) {
        PlayerStats.Stats stats = PlayerStats.get(s.name());
        if (stats == null) {
            Ui.draw(c, tr, PlayerStats.waiting(s.name()) ? "Asking the server (/playerstats)…" : "No stats yet", x + PAD, y,
                    Ui.MUTED, a);
        } else {
            int ry = y;
            for (Map.Entry<String, String> e : stats.rows().entrySet()) {
                row(c, tr, x, ry, e.getKey(), e.getValue(), Ui.VALUE, a);
                ry += ROW;
            }
            long age = (System.currentTimeMillis() - stats.receivedMs()) / 1000L;
            Ui.draw(c, tr, "from /playerstats · " + age + " s ago", x + PAD, ry + 3, Ui.MUTED, a);
            y = ry + 3;
        }
        int by = y + 16;
        Ui.round(c, x + PAD, by, 70, 13, Ui.argb(Math.round(a * 0.35F), Ui.theme().accent()));
        Ui.drawCentered(c, tr, "Refresh", x + PAD + 35, by + 3, Ui.VALUE, a);
        hits.add(new Hit(x + PAD, by, 70, 13, null, "refresh"));
        PlayerEntity p = s.player();
        if (p != null) {
            row(c, tr, x, by + 20, "Health", String.format(Locale.ROOT, "%.0f / %.0f", p.getHealth(), p.getMaxHealth()),
                    p.getHealth() > p.getMaxHealth() * 0.5F ? Ui.GOOD : Ui.BAD, a);
        }
    }

    private static void gear(DrawContext c, TextRenderer tr, int x, int y, int bottom, PlayerCard.Subject s, int a) {
        PlayerEntity p = s.player();
        if (p == null) {
            Ui.draw(c, tr, "Not nearby - gear is only known", x + PAD, y, Ui.MUTED, a);
            Ui.draw(c, tr, "while the player is loaded.", x + PAD, y + ROW, Ui.MUTED, a);
            return;
        }
        int ry = y;
        for (PlayerCard.Gear g : PlayerCard.gear(p)) {
            if (ry + 17 > bottom) {
                break;
            }
            c.drawItem(g.stack(), x + PAD, ry);
            c.drawText(tr, g.stack().getName(), x + PAD + 20, ry + (g.note().isEmpty() ? 4 : 0), Ui.argb(a, 0xFFFFFF), true);
            if (!g.note().isEmpty()) {
                Ui.draw(c, tr, g.note(), x + PAD + 20, ry + 9, Ui.theme().accent(), a);
            }
            ry += 18;
        }
    }

    private static void inventory(DrawContext c, TextRenderer tr, int x, int y, MinecraftClient client, int mx, int my, int a) {
        if (client.player == null) {
            return;
        }
        List<ItemStack> main = client.player.getInventory().getMainStacks();
        ItemStack hovered = grid(c, tr, x + PAD + 4, y, main, 9, mx, my, a);
        long[] totals = totals(main);
        int ty = y + 4 * 18 + 6;
        row(c, tr, x, ty, "Energy (items + pickaxe)", compact(totals[0]), Ui.theme().accent(), a);
        row(c, tr, x, ty + ROW, "Money notes", "$" + compact(totals[1]), Ui.GOOD, a);
        if (hovered != null) {
            c.drawItemTooltip(tr, hovered, mx, my);
        }
    }

    private static void vaults(DrawContext c, TextRenderer tr, int x, int y, int bottom, MinecraftClient client, int mx, int my, int a) {
        Map<Integer, List<ItemStack>> vaults = storage() != null ? storage().knownVaults() : Map.of();
        long energy = 0L;
        long money = 0L;
        for (List<ItemStack> items : vaults.values()) {
            long[] t = totals(items);
            energy += t[0];
            money += t[1];
        }
        if (client.player != null) {
            long[] inv = totals(client.player.getInventory().getMainStacks());
            energy += inv[0];
            money += inv[1];
        }
        ScoreboardModule board = ScoreboardModule.get();
        String balance = board != null ? board.balance() : "";
        row(c, tr, x, y, "Scanned vaults", String.valueOf(vaults.size()), Ui.VALUE, a);
        row(c, tr, x, y + ROW, "Energy (vaults + inventory)", compact(energy), Ui.theme().accent(), a);
        row(c, tr, x, y + ROW * 2, "Money notes", "$" + compact(money), Ui.GOOD, a);
        row(c, tr, x, y + ROW * 3, "Balance", balance.isEmpty() ? "–" : balance, Ui.GOOD, a);
        int vy = y + ROW * 4 + 4;
        ItemStack hovered = null;
        for (Map.Entry<Integer, List<ItemStack>> e : vaults.entrySet()) {
            int rows = (e.getValue().size() + 8) / 9;
            int h = 10 + rows * 9;
            if (vy + h > bottom) {
                Ui.draw(c, tr, "+" + (vaults.size()) + " vaults in total - /pv for all", x + PAD, vy, Ui.MUTED, a);
                break;
            }
            Ui.draw(c, tr, "PV " + e.getKey(), x + PAD, vy, Ui.theme().title(), a);
            c.getMatrices().pushMatrix();
            c.getMatrices().translate(x + PAD + 30, vy);
            c.getMatrices().scale(0.5F, 0.5F);
            List<ItemStack> items = e.getValue();
            for (int i = 0; i < items.size(); i++) {
                ItemStack st = items.get(i);
                if (!st.isEmpty()) {
                    int ix = (i % 9) * 18;
                    int iy = (i / 9) * 18;
                    c.drawItem(st, ix, iy);
                    int sx = x + PAD + 30 + ix / 2;
                    int sy = vy + iy / 2;
                    if (mx >= sx && mx < sx + 9 && my >= sy && my < sy + 9) {
                        hovered = st;
                    }
                }
            }
            c.getMatrices().popMatrix();
            vy += h;
        }
        if (vaults.isEmpty()) {
            Ui.draw(c, tr, "Open /pv once to scan your vaults.", x + PAD, vy, Ui.MUTED, a);
        }
        if (hovered != null) {
            c.drawItemTooltip(tr, hovered, mx, my);
        }
    }

    private static void skills(DrawContext c, TextRenderer tr, int x, int y, int a) {
        int available = PlayerStats.skillPointsAvailable();
        row(c, tr, x, y, "Skill points available", available < 0 ? "–" : String.valueOf(available),
                Ui.theme().title(), a);
        row(c, tr, x, y + ROW, "Earned this session", String.valueOf(PlayerStats.skillPointsEarned()), Ui.VALUE, a);
        row(c, tr, x, y + ROW * 2, "Ground Zero points", String.valueOf(PlayerStats.groundZeroPoints()), Ui.WARN, a);
        long reset = PlayerStats.groundZeroResetMinutes();
        row(c, tr, x, y + ROW * 3, "Ground Zero reset", reset < 0 ? "–" : "in " + reset + " min", Ui.VALUE, a);
        Ui.draw(c, tr, "A point every few levels; the skill tree", x + PAD, y + ROW * 5, Ui.MUTED, a);
        Ui.draw(c, tr, "applies them to your player.", x + PAD, y + ROW * 6, Ui.MUTED, a);
    }

    private static void session(DrawContext c, TextRenderer tr, int x, int y, int bottom, int a) {
        SessionHudModule hud = ThePrisonsCore.getOrNull() != null
                && ThePrisonsCore.getOrNull().modules().get("session_hud") instanceof SessionHudModule h ? h : null;
        if (hud == null) {
            return;
        }
        row(c, tr, x, y, "XP this session", compact(hud.sessionXp()), Ui.theme().title(), a);
        row(c, tr, x, y + ROW, "Energy this session", compact(hud.sessionEnergy()), Ui.theme().accent(), a);
        int ry = y + ROW * 2 + 6;
        Ui.draw(c, tr, "ACTIVITY", x + PAD, ry, Ui.LABEL, a);
        Ui.drawRight(c, tr, "TIME      ORES", x + WIDTH - PAD, ry, Ui.LABEL, a);
        ry += ROW;
        List<Object[]> list = hud.activitySummary();
        list.sort((p, q) -> Long.compare((Long) q[1], (Long) p[1]));
        for (Object[] row : list) {
            if (ry + ROW > bottom) {
                break;
            }
            long ms = (Long) row[1];
            String time = ms >= 3_600_000L ? String.format(Locale.ROOT, "%dh %02dm", ms / 3_600_000L, ms / 60_000L % 60L)
                    : String.format(Locale.ROOT, "%dm %02ds", ms / 60_000L, ms / 1000L % 60L);
            Ui.draw(c, tr, (String) row[0], x + PAD, ry, ((String) row[0]).toLowerCase(Locale.ROOT).contains("bandit")
                    ? Ui.BAD : Ui.VALUE, a);
            Ui.drawRight(c, tr, time + "    " + row[2], x + WIDTH - PAD, ry, Ui.VALUE, a);
            ry += ROW;
        }
        if (list.isEmpty()) {
            Ui.draw(c, tr, "Mine or fight bandits to start one.", x + PAD, ry, Ui.MUTED, a);
        }
    }

    /** A grid of item slots; returns the hovered stack. */
    private static @Nullable ItemStack grid(DrawContext c, TextRenderer tr, int x, int y, List<ItemStack> items, int columns,
                                            int mx, int my, int a) {
        ItemStack hovered = null;
        for (int i = 0; i < items.size(); i++) {
            int sx = x + (i % columns) * 18;
            int sy = y + (i / columns) * 18;
            c.fill(sx, sy, sx + 17, sy + 17, Ui.argb(a / 6, 0xFFFFFF));
            ItemStack st = items.get(i);
            if (!st.isEmpty()) {
                c.drawItem(st, sx, sy);
                c.drawStackOverlay(tr, st, sx, sy);
                if (mx >= sx && mx < sx + 17 && my >= sy && my < sy + 17) {
                    hovered = st;
                }
            }
        }
        return hovered;
    }

    /** {energy, money}: Cosmic Energy items (name), pickaxe energy (lore) and money notes ($ in the name). */
    static long[] totals(List<ItemStack> items) {
        long energy = 0L;
        long money = 0L;
        for (ItemStack st : items) {
            if (st.isEmpty()) {
                continue;
            }
            String name = TextStrip.strip(st.getName().getString());
            Matcher e = ENERGY.matcher(name);
            if (e.find()) {
                energy += Long.parseLong(e.group(1).replace(",", "")) * st.getCount();
            } else if (st.isIn(ItemTags.PICKAXES)) {
                LoreComponent lore = st.get(DataComponentTypes.LORE);
                if (lore != null) {
                    List<String> lines = new ArrayList<>();
                    lore.lines().forEach(l -> lines.add(TextStrip.strip(l.getString())));
                    energy += Math.max(0L, CosmicStats.loreEnergy(lines));
                }
            }
            if (name.toLowerCase(Locale.ROOT).contains("note")) {
                Matcher m = MONEY.matcher(name);
                if (m.find()) {
                    double v = Double.parseDouble(m.group(1).replace(",", ""));
                    String u = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
                    v *= u.equals("k") ? 1e3 : u.equals("m") ? 1e6 : u.equals("b") ? 1e9 : 1.0;
                    money += Math.round(v) * st.getCount();
                }
            }
        }
        return new long[]{energy, money};
    }

    private static @Nullable StorageOverlayModule storage() {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        return core != null && core.modules().get("storage_overlay") instanceof StorageOverlayModule s ? s : null;
    }

    static String compact(long v) {
        if (v >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        }
        if (v >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        }
        if (v >= 10_000L) {
            return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        }
        return String.format(Locale.ROOT, "%,d", v);
    }
}
