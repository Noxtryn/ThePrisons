package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.gui.kit.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cosmic's /ah and /ee in the mod's own design: a card beside the menu that sorts what the page shows into Cosmetics,
 * Upgrades, Mining, Combat and Other (the AH: listings, item kinds and sales) and turns every price into energy at
 * the cheapest /ee offer. The /ee card shows the rate, what money buys in energy and the cheapest offers.
 */
public final class MarketOverlay {
    private static final int W = 176;
    private static final int PAD = 8;
    private static final String[] CHIPS = {"ALL", "COS", "UPG", "MIN", "COM", "OTH"};
    private static final MarketCategory[] CHIP_GROUPS = {null, MarketCategory.COSMETICS, MarketCategory.UPGRADES,
            MarketCategory.MINING, MarketCategory.COMBAT, MarketCategory.OTHER};
    private static final int[] GROUP_COLOURS = {0xFF6EC7, 0x8AD8FF, 0x4FE8E0, 0xFF9A2E, 0xA0A0B8};

    /** One line of the AH card: a listing, an item kind or a sale. */
    private record Row(MarketCategory group, String name, double price, String note) {
    }

    private static @Nullable MarketCategory filter;
    private static int panelX;
    private static int panelY;
    private static boolean ahShown;

    // Rows are rebuilt only when the page changes (the module hands out a new list per page).
    private static @Nullable List<MarketParser.Item> rowsFor;
    private static String rowsTitle = "";
    private static List<Row> rows = List.of();

    private MarketOverlay() {
    }

    public static void render(DrawContext c, HandledScreen<?> screen) {
        MarketModule m = MarketModule.get();
        ahShown = false;
        if (m == null || !m.enabled()) {
            return;
        }
        String title = TextStrip.strip(screen.getTitle().getString());
        boolean ee = title.equals(MarketParser.ENERGY);
        boolean ah = title.equals(MarketParser.MARKET) || title.equals(MarketParser.CATEGORIES) || title.equals(MarketParser.HISTORY);
        if (!ah && !ee) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        // The chest menu is 176 wide and centred: the card goes to its left, or right when there is no room.
        int menuLeft = (screen.width - 176) / 2;
        panelX = menuLeft - W - 6 >= 4 ? menuLeft - W - 6 : menuLeft + 176 + 6;
        panelY = 8;
        int maxH = screen.height - 16;
        if (ee) {
            drawEnergy(c, tr, m, panelX, panelY, maxH);
        } else {
            ahShown = true;
            drawMarket(c, tr, m, title, panelX, panelY, maxH);
        }
    }

    /** Clicks on the group chips of the AH card. */
    public static boolean click(double mouseX, double mouseY) {
        if (!ahShown) {
            return false;
        }
        int y = panelY + 26;
        if (mouseY < y || mouseY > y + 13) {
            return false;
        }
        int cw = (W - PAD * 2 - 5 * 2) / 6;
        for (int i = 0; i < CHIPS.length; i++) {
            int x = panelX + PAD + i * (cw + 2);
            if (mouseX >= x && mouseX <= x + cw) {
                filter = CHIP_GROUPS[i];
                return true;
            }
        }
        return false;
    }

    // ── AH ───────────────────────────────────────────────────────────────────

    private static void drawMarket(DrawContext c, TextRenderer tr, MarketModule m, String title, int x, int y, int maxH) {
        PriceBook book = m.book();
        List<Row> all = rows(m, title);
        Ui.shadowCard(c, x, y, W, maxH, 0.96F);
        Ui.shimmer(c, tr, "COSMIC MARKET", x + PAD, y + 7, 1.0F);
        String sub = title.equals(MarketParser.CATEGORIES) ? "Item kinds" : title.equals(MarketParser.HISTORY) ? "Recent sales" : "This page";
        Ui.drawRight(c, tr, sub, x + W - PAD, y + 7, Ui.MUTED, 255);
        Ui.line(c, x + PAD, x + W - PAD, y + 20, 1.0F);

        int cw = (W - PAD * 2 - 5 * 2) / 6;
        for (int i = 0; i < CHIPS.length; i++) {
            boolean on = CHIP_GROUPS[i] == filter;
            int cx = x + PAD + i * (cw + 2);
            Ui.round(c, cx, y + 26, cw, 13, Ui.argb(on ? 160 : 60, on ? Ui.theme().accent() : 0x000000));
            Ui.drawCentered(c, tr, CHIPS[i], cx + cw / 2, y + 29, on ? Ui.VALUE : Ui.MUTED, 255);
        }

        int ry = y + 46;
        int bottom = y + maxH - 18;
        if (all.isEmpty()) {
            Ui.draw(c, tr, "Waiting for the page to load…", x + PAD, ry + 4, Ui.MUTED, 255);
            return;
        }
        MarketCategory last = null;
        int shown = 0;
        int hidden = 0;
        for (Row r : all) {
            if (filter != null && r.group() != filter) {
                continue;
            }
            int need = (r.group() != last ? 12 : 0) + 20;
            if (ry + need > bottom) {
                hidden++;
                continue;
            }
            if (r.group() != last) {
                last = r.group();
                int col = GROUP_COLOURS[r.group().ordinal()];
                c.fill(x + PAD, ry + 2, x + PAD + 2, ry + 9, Ui.argb(255, col));
                Ui.draw(c, tr, r.group().label.toUpperCase(java.util.Locale.ROOT), x + PAD + 6, ry + 2, col, 255);
                ry += 12;
            }
            Ui.draw(c, tr, fit(tr, r.name(), W - PAD * 2 - 52), x + PAD, ry, Ui.VALUE, 255);
            Ui.drawRight(c, tr, "$" + Money.compact(r.price()), x + W - PAD, ry, Ui.GOOD, 255);
            double energy = book.inEnergy(r.price());
            Ui.draw(c, tr, energy > 0 ? Money.compact(energy) + " energy" : "no /ee rate yet", x + PAD, ry + 10, Ui.MUTED, 255);
            if (!r.note().isEmpty()) {
                Ui.drawRight(c, tr, r.note(), x + W - PAD, ry + 10, Ui.MUTED, 255);
            }
            ry += 20;
            shown++;
        }
        if (shown == 0) {
            Ui.draw(c, tr, "Nothing of this group here", x + PAD, ry + 4, Ui.MUTED, 255);
        }
        String foot = hidden > 0 ? "+" + hidden + " more" : shown + " shown";
        Ui.draw(c, tr, foot, x + PAD, y + maxH - 13, Ui.MUTED, 255);
        double rate = book.moneyPerEnergy();
        Ui.drawRight(c, tr, rate > 0 ? "$" + Money.compact(rate * 1000.0D) + " / 1k energy" : "/ee unknown", x + W - PAD,
                y + maxH - 13, Ui.theme().accent(), 255);
    }

    private static List<Row> rows(MarketModule m, String title) {
        List<MarketParser.Item> items = m.lastItems();
        if (items == rowsFor && title.equals(rowsTitle)) {
            return rows;
        }
        if (!title.equals(m.lastTitle())) {
            return List.of(); // the page on screen is not read yet
        }
        List<Row> out = new ArrayList<>();
        switch (title) {
            case MarketParser.MARKET -> {
                for (MarketParser.Listing l : MarketParser.listings(items)) {
                    out.add(new Row(MarketCategory.of(l.name(), l.customId()).group(), l.name(), l.unitPrice(),
                            l.count() > 1 ? l.count() + "x" : ""));
                }
            }
            case MarketParser.CATEGORIES -> {
                for (MarketParser.Kind k : MarketParser.kinds(items)) {
                    out.add(new Row(MarketCategory.of(k.name(), k.customId()).group(), k.name(), k.lowest(),
                            k.listings() + " listed"));
                }
            }
            default -> {
                for (MarketParser.Sale s : MarketParser.sales(items)) {
                    out.add(new Row(MarketCategory.of(s.name(), s.customId()).group(), s.name(), s.unitPrice(),
                            MarketSearch.age(s.agoMs())));
                }
            }
        }
        out.sort(Comparator.comparing((Row r) -> r.group().ordinal()).thenComparingDouble(Row::price));
        rowsFor = items;
        rowsTitle = title;
        rows = out;
        return out;
    }

    // ── /ee ──────────────────────────────────────────────────────────────────

    private static void drawEnergy(DrawContext c, TextRenderer tr, MarketModule m, int x, int y, int maxH) {
        PriceBook book = m.book();
        double rate = book.moneyPerEnergy();
        Ui.shadowCard(c, x, y, W, Math.min(maxH, 190), 0.96F);
        Ui.shimmer(c, tr, "COSMIC ENERGY", x + PAD, y + 7, 1.0F);
        Ui.line(c, x + PAD, x + W - PAD, y + 20, 1.0F);

        Ui.draw(c, tr, "Cheapest rate", x + PAD, y + 27, Ui.MUTED, 255);
        Ui.drawRight(c, tr, rate > 0 ? "$" + Money.compact(rate * 1000.0D) + " / 1k" : "unknown", x + W - PAD, y + 27, Ui.GOOD, 255);
        Ui.draw(c, tr, "Updated", x + PAD, y + 38, Ui.MUTED, 255);
        Ui.drawRight(c, tr, book.rateSeenMs() > 0L ? MarketSearch.age(System.currentTimeMillis() - book.rateSeenMs()) : "never",
                x + W - PAD, y + 38, Ui.VALUE, 255);

        Ui.draw(c, tr, "MONEY IN ENERGY", x + PAD, y + 54, Ui.theme().title(), 255);
        double[] money = {1e6, 10e6, 100e6, 1e9};
        for (int i = 0; i < money.length; i++) {
            int ry = y + 66 + i * 11;
            Ui.draw(c, tr, "$" + Money.compact(money[i]), x + PAD, ry, Ui.MUTED, 255);
            double energy = book.inEnergy(money[i]);
            Ui.drawRight(c, tr, energy > 0 ? Money.compact(energy) + " energy" : "—", x + W - PAD, ry, Ui.VALUE, 255);
        }

        Ui.draw(c, tr, "CHEAPEST OFFERS", x + PAD, y + 116, Ui.theme().title(), 255);
        if (!MarketParser.ENERGY.equals(m.lastTitle())) {
            Ui.draw(c, tr, "Waiting for the page to load…", x + PAD, y + 129, Ui.MUTED, 255);
            return;
        }
        List<MarketParser.EnergyOffer> offers = new ArrayList<>(MarketParser.energyOffers(m.lastItems()));
        offers.removeIf(o -> o.amount() < 1000.0D); // dust offers (0.7 energy) are no real option
        offers.sort(Comparator.comparingDouble(MarketParser.EnergyOffer::perK));
        for (int i = 0; i < Math.min(4, offers.size()); i++) {
            MarketParser.EnergyOffer o = offers.get(i);
            int ry = y + 129 + i * 14;
            Ui.draw(c, tr, fit(tr, o.seller(), 70), x + PAD, ry, Ui.VALUE, 255);
            Ui.draw(c, tr, Money.compact(o.amount()), x + PAD + 76, ry, Ui.MUTED, 255);
            Ui.drawRight(c, tr, "$" + Money.compact(o.perK()) + " / 1k", x + W - PAD, ry, Ui.GOOD, 255);
        }
        if (offers.isEmpty()) {
            Ui.draw(c, tr, "No offers on this page", x + PAD, y + 129, Ui.MUTED, 255);
        }
    }

    private static String fit(TextRenderer tr, String text, int maxWidth) {
        if (tr.getWidth(text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 3 && tr.getWidth(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }
}
