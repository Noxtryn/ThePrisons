package io.theprisons.modules.qol.market;

import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.client.TextStrip;
import io.theprisons.gui.kit.HidesHud;
import io.theprisons.gui.kit.Ui;
import io.theprisons.mixin.ThePrisonsSlotAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's auction house menus and /ee as one overlay in the mod's design (64x sprites, own icons). It is the
 * server's real chest screen - its slots are moved into our layout, so every click, buy and page switch is the server's
 * own - drawn as a compact card (a little larger than the vanilla chest):
 * <ul>
 *     <li>market / category view / history: a tab per group (All, Cosmetics, Upgrades, Mining, Combat, Other), a search
 *     field, the page's items re-flowed into pages of 6 columns x 4 rows (only items, no empty cells; the arrows go through
 *     our pages first, then to the server's next page), the server's buttons as an icon bar and, for the item under the
 *     mouse, its price in energy and the last price the book knows;</li>
 *     <li>/ee: market info (available, cheapest, sales average) and the offers as rows, five per page.</li>
 * </ul>
 * Items that do not match the tab / search are not clickable (their slot is parked off-screen).
 */
public final class MarketScreen extends HandledScreen<GenericContainerScreenHandler> implements HidesHud {
    private static final int COLS = 6;
    private static final int ROWS = 4;
    private static final int PER_PAGE = COLS * ROWS;
    private static final int CW = 29;
    private static final int CH = 30;
    private static final int PAD = 10;
    private static final int GRID_W = COLS * CW;
    private static final int PANEL_W = GRID_W + PAD * 2;
    private static final int PANEL_H = 234;
    private static final int LISTING_SLOTS = 45;
    private static final int HIDDEN = -10_000;
    private static final int OFFER_H = 24;
    private static final int OFFERS_PER_PAGE = 5;
    private static final int BTN = 18;
    /** Secondary text: brighter than the HUD's muted grey, the panel can sit over a bright sky. */
    static final int SUB = 0xB4BCCC;
    private static final Pattern AMOUNT = Pattern.compile("^Amount:\\s*(.+)$");
    private static final Pattern OFFER_PRICE = Pattern.compile("^Price:\\s*\\$([\\d,.]+)\\s*\\(([\\d,.]+)\\s*/\\s*1k\\)");
    static final String[] TAB_ICON = {"cat_all", "cat_cosmetics", "cat_upgrades", "cat_mining", "cat_combat", "cat_other"};

    // Filter state survives page switches (every page of the server is a new screen).
    private static @Nullable MarketCategory tab;
    private static String query = "";
    private static boolean focused;
    /** The finer kind chosen under the tabs ("Satchels"), null = all kinds of the tab. */
    private static @Nullable String kindFilter;

    private final MarketModule module;
    private final String kind;
    private final Map<Slot, int[]> original = new IdentityHashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<Offer> offers = new ArrayList<>();
    private final List<Nav> navs = new ArrayList<>();
    private List<String> infoLines = List.of();
    private int ticks;
    private int clientPage;
    private int top;
    private int left;

    /** One listing slot of the market / category / history page. */
    private record Cell(int slot, String name, MarketCategory group, String kind, double unit, @Nullable String customId) {
    }

    /** One offer of /ee. */
    private record Offer(int slot, String seller, String amount, double price, String rate) {
    }

    /** One server button of the bottom row: its slot, name and our icon. */
    private record Nav(int slot, String name, String icon, boolean prev, boolean next) {
    }

    public static boolean handles(String title) {
        return title.equals(MarketParser.MARKET) || title.equals(MarketParser.CATEGORIES) || title.equals(MarketParser.HISTORY)
                || title.equals(MarketParser.ENERGY) || plainTitle(title);
    }

    /** Menus that hold items without a market price per piece: your listings, the collection bin. */
    private static boolean plainTitle(String title) {
        return title.startsWith("Your ") || title.contains("Collection");
    }

    MarketScreen(MarketModule module, GenericContainerScreenHandler handler, Text title) {
        super(handler, MinecraftClient.getInstance().player.getInventory(), title);
        this.module = module;
        this.kind = TextStrip.strip(title.getString());
        for (Slot slot : handler.slots) {
            original.put(slot, new int[]{slot.x, slot.y});
        }
    }

    private boolean energy() {
        return kind.equals(MarketParser.ENERGY);
    }

    private boolean plain() {
        return plainTitle(kind);
    }

    @Override
    protected void init() {
        super.init();
        x = 0;
        y = 0;
        backgroundWidth = width;
        backgroundHeight = height;
        refresh();
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    @Override
    protected void handledScreenTick() {
        if (++ticks % 3 == 0) {
            refresh();
        }
    }

    /** What the slots held when the cells were last read: unchanged slots are not parsed again. */
    private int refreshed = Integer.MIN_VALUE;

    private void refresh() {
        int signature = 0;
        int count = Math.min(handler.slots.size() - 36, 54);
        for (int i = 0; i < count; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            signature = signature * 31 + System.identityHashCode(stack) + stack.getCount();
        }
        if (signature == refreshed && !cells.isEmpty() || signature == refreshed && !offers.isEmpty()) {
            return;
        }
        refreshed = signature;
        cells.clear();
        offers.clear();
        navs.clear();
        List<String> info = new ArrayList<>();
        int total = Math.min(handler.slots.size() - 36, 54);
        for (int i = 0; i < total; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = TextStrip.strip(stack.getName().getString());
            if (i >= LISTING_SLOTS) {
                if (!name.isBlank()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    navs.add(new Nav(i, name, iconFor(lower), lower.contains("previous"), lower.contains("next page")));
                }
                continue;
            }
            List<String> lore = ClientReadouts.lore(stack);
            if (energy()) {
                if (i == 4) {
                    info.addAll(lore);
                } else if (i >= 9) {
                    offers.add(offer(i, name, lore));
                }
                continue;
            }
            if (name.isBlank()) {
                continue;
            }
            String customId = MarketModule.customId(stack);
            double unit;
            try {
                unit = MarketParser.unitPrice(new MarketParser.Item(i, Registries.ITEM.getId(stack.getItem()).toString(),
                        stack.getCount(), name, lore, customId));
            } catch (NumberFormatException e) {
                continue;
            }
            if (unit <= 0.0D && !plain()) {
                continue;
            }
            MarketCategory.Sorted sorted = MarketCategory.of(name, customId);
            cells.add(new Cell(i, name, sorted.group(), sorted.kind(), unit, customId));
        }
        infoLines = info;
        offers.removeIf(o -> o.price() < 0.0D);
        // By kind: every satchel with the other satchels, every mask with the masks (the server's order within a kind).
        cells.sort(java.util.Comparator.comparingInt((Cell c) -> c.group().ordinal()).thenComparing(Cell::kind));
    }

    /** Our own icon for a server button, by what it says. */
    static String iconFor(String lower) {
        if (lower.contains("previous")) return "nav_prev";
        if (lower.contains("next")) return "nav_next";
        if (lower.contains("refresh")) return "nav_refresh";
        if (lower.contains("history")) return "nav_history";
        if (lower.contains("collection") || lower.contains("bin")) return "nav_bin";
        if (lower.contains("sell")) return "cat_upgrades";
        if (lower.contains("listing")) return "nav_listings";
        if (lower.contains("categor")) return "nav_categories";
        if (lower.contains("filter") || lower.contains("search")) return "nav_filter";
        if (lower.contains("guide") || lower.contains("info")) return "nav_guide";
        if (lower.contains("analytic")) return "nav_analytics";
        if (lower.contains("buy") || lower.contains("energy")) return "nav_energy";
        if (lower.contains("return") || lower.contains("main") || lower.contains("back")) return "nav_back";
        return "cat_other";
    }

    private static Offer offer(int slot, String seller, List<String> lore) {
        String amount = "";
        double price = -1.0D;
        String rate = "";
        for (String raw : lore) {
            String line = raw.strip();
            Matcher a = AMOUNT.matcher(line);
            if (a.find()) {
                amount = Money.compact(Math.max(0.0D, safeParse(a.group(1))));
                continue;
            }
            Matcher p = OFFER_PRICE.matcher(line);
            if (p.find()) {
                try {
                    price = Double.parseDouble(p.group(1).replace(",", ""));
                    rate = p.group(2);
                } catch (NumberFormatException ignored) {
                    // an unreadable price: the offer is not shown
                }
            }
        }
        return new Offer(slot, seller, amount, price, rate);
    }

    private static double safeParse(String text) {
        try {
            return Money.parse(text);
        } catch (NumberFormatException e) {
            return 0.0D;
        }
    }

    private boolean matches(Cell c) {
        if (tab != null && c.group() != tab) {
            return false;
        }
        if (kindFilter != null && !c.kind().equals(kindFilter)) {
            return false;
        }
        String hay = (c.name() + " " + c.kind() + " " + c.group().label).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (!word.isEmpty() && !hay.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private List<Cell> visible() {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells) {
            if (matches(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private int pages() {
        int n = energy() ? offers.size() : visible().size();
        int per = energy() ? OFFERS_PER_PAGE : PER_PAGE;
        return Math.max(1, (n + per - 1) / per);
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private int gridX() {
        return left + PAD;
    }

    private int gridY() {
        return top + 64;
    }

    private int footerY() {
        return top + 188;
    }

    private int navY() {
        return top + 208;
    }

    /** The kinds of the chosen tab (with the items of the page), most items first - the chips under the tabs. */
    private List<String> kinds() {
        java.util.Map<String, Integer> count = new java.util.LinkedHashMap<>();
        for (Cell c : cells) {
            if (tab == null || c.group() == tab) {
                count.merge(c.kind(), 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>(count.keySet());
        out.sort((a, b) -> count.get(b) - count.get(a));
        return out;
    }

    private int chipWidth(TextRenderer tr, String text) {
        return Ui.width(tr, text) + 10;
    }

    private int navX(int index) {
        int n = navs.size();
        int gap = n <= 1 ? 0 : Math.min(4, (GRID_W - n * BTN) / (n - 1));
        int total = n * BTN + Math.max(0, n - 1) * gap;
        return gridX() + (GRID_W - total) / 2 + index * (BTN + gap);
    }

    private void layout() {
        left = (width - PANEL_W) / 2;
        top = Math.max(2, (height - PANEL_H) / 2);
        clientPage = Math.max(0, Math.min(clientPage, pages() - 1));
        List<Cell> shown = visible();
        for (Slot slot : handler.slots) {
            int sx = HIDDEN;
            int sy = HIDDEN;
            int i = slot.getIndex();
            if (slot.inventory == handler.getInventory()) {
                if (i >= LISTING_SLOTS) {
                    for (int k = 0; k < navs.size(); k++) {
                        if (navs.get(k).slot() == i) {
                            sx = navX(k) + 1;
                            sy = navY() + 1;
                        }
                    }
                } else if (energy()) {
                    for (int k = clientPage * OFFERS_PER_PAGE; k < Math.min(offers.size(), (clientPage + 1) * OFFERS_PER_PAGE); k++) {
                        if (offers.get(k).slot() == i) {
                            sx = gridX() + 4;
                            sy = gridY() + (k % OFFERS_PER_PAGE) * OFFER_H + 4;
                        }
                    }
                } else {
                    for (int k = clientPage * PER_PAGE; k < Math.min(shown.size(), (clientPage + 1) * PER_PAGE); k++) {
                        if (shown.get(k).slot() == i) {
                            int at = k % PER_PAGE;
                            sx = gridX() + (at % COLS) * CW + (CW - 16) / 2;
                            sy = gridY() + (at / COLS) * CH + 2;
                        }
                    }
                }
            }
            move(slot, sx, sy);
        }
    }

    private static void move(Slot slot, int sx, int sy) {
        if (slot.x != sx || slot.y != sy) {
            ThePrisonsSlotAccessor accessor = (ThePrisonsSlotAccessor) slot;
            accessor.theprisons$setX(sx);
            accessor.theprisons$setY(sy);
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float deltaTicks) {
        layout();
        TextRenderer tr = textRenderer;
        int accent = Ui.theme().accent();
        Ui.sprite(c, "market/panel_body", left + 3, top + 4, PANEL_W, PANEL_H, Ui.argb(60, 0x000000));
        Ui.sprite(c, "market/panel_body", left, top, PANEL_W, PANEL_H, Ui.argb(232, 0x0B0C14));
        Ui.sprite(c, "market/panel_frame", left, top, PANEL_W, PANEL_H, Ui.argb(210, accent));
        drawMenuIcon(c, energy() ? "ee" : "ah", left + PAD - 1, top + 6);
        Ui.shimmer(c, tr, titleText(), left + PAD + 18, top + 11, 1.0F);
        PriceBook book = module.book();
        if (energy()) {
            drawEnergyInfo(c, tr, book);
            drawOffers(c, tr);
        } else {
            drawSearch(c, tr);
            drawTabs(c);
            drawChips(c, tr);
            drawCells(c, tr, book);
        }
        drawFooter(c, tr, book);
        drawNav(c, mouseX, mouseY);
    }

    /** The menu's icon item (minecraft:ah / minecraft:ee ...), without a rarity frame. */
    static void drawMenuIcon(DrawContext c, String name, int x, int y) {
        net.minecraft.item.Item item = Registries.ITEM.get(net.minecraft.util.Identifier.ofVanilla(name));
        if (item == net.minecraft.item.Items.AIR) {
            return;
        }
        io.theprisons.modules.qol.items.ItemLookModule.noFrame = true;
        try {
            c.drawItem(new ItemStack(item), x, y);
        } finally {
            io.theprisons.modules.qol.items.ItemLookModule.noFrame = false;
        }
    }

    private String titleText() {
        return switch (kind) {
            case MarketParser.CATEGORIES -> "CATEGORIES";
            case MarketParser.HISTORY -> "SALES";
            case MarketParser.ENERGY -> "ENERGY";
            case MarketParser.MARKET -> "MARKET";
            default -> kind.toUpperCase(Locale.ROOT);
        };
    }

    private void drawTabs(DrawContext c) {
        int y = top + 28;
        int w = GRID_W / 6;
        int accent = Ui.theme().accent();
        for (int i = 0; i < 6; i++) {
            boolean active = (i == 0 && tab == null) || (i > 0 && tab == MarketCategory.values()[i - 1]);
            int x = gridX() + i * w;
            if (active) {
                Ui.sprite(c, "market/glow", x - 4, y - 5, w + 8, 28, Ui.argb(110, accent));
            }
            Ui.sprite(c, "market/tab", x + 1, y, w - 2, 18, active ? Ui.argb(200, accent) : Ui.argb(46, 0xFFFFFF));
            Ui.sprite(c, "market/" + TAB_ICON[i], x + (w - 12) / 2, y + 3, 12, 12, Ui.argb(255, active ? 0xFFFFFF : SUB));
        }
    }

    private void drawChips(DrawContext c, TextRenderer tr) {
        List<String> kinds = kinds();
        if (kinds.size() < 2) {
            return;
        }
        int accent = Ui.theme().accent();
        int x = gridX();
        int y = top + 49;
        for (String k : kinds) {
            int w = chipWidth(tr, k);
            if (x + w > gridX() + GRID_W) {
                break;
            }
            boolean active = k.equals(kindFilter);
            Ui.sprite(c, "market/tab", x, y, w, 12, active ? Ui.argb(190, accent) : Ui.argb(40, 0xFFFFFF));
            Ui.draw(c, tr, k, x + 5, y + 2, active ? 0xFFFFFF : SUB, 255);
            x += w + 3;
        }
    }

    private void drawSearch(DrawContext c, TextRenderer tr) {
        int w = 92;
        int x = left + PANEL_W - PAD - w;
        int y = top + 8;
        int accent = Ui.theme().accent();
        Ui.sprite(c, "market/tab", x, y, w, 15, Ui.argb(focused ? 90 : 46, focused ? accent : 0xFFFFFF));
        Ui.sprite(c, "market/nav_search", x + 4, y + 3, 9, 9, Ui.argb(255, accent));
        boolean blink = focused && (System.currentTimeMillis() / 450L) % 2 == 0;
        String shown = query.isEmpty() ? (focused ? "" : "Search…") : query;
        String text = fit(tr, shown, w - 22);
        Ui.draw(c, tr, text + (blink ? "|" : ""), x + 16, y + 4, query.isEmpty() ? SUB : Ui.VALUE, 255);
    }

    private void drawCells(DrawContext c, TextRenderer tr, PriceBook book) {
        List<Cell> shown = visible();
        int gx = gridX();
        int gy = gridY();
        int accent = Ui.theme().accent();
        for (int k = clientPage * PER_PAGE; k < Math.min(shown.size(), (clientPage + 1) * PER_PAGE); k++) {
            Cell cell = shown.get(k);
            int at = k % PER_PAGE;
            int cx = gx + (at % COLS) * CW;
            int cy = gy + (at / COLS) * CH;
            Slot slot = handler.slots.get(cell.slot());
            boolean over = slot == focusedSlot;
            Ui.sprite(c, "market/cell", cx + 1, cy, CW - 2, CH - 2, over ? Ui.argb(120, accent) : Ui.argb(52, 0xFFFFFF));
            PriceBook.Entry known = book.get(PriceBook.key(cell.customId(), cell.name()));
            boolean deal = known != null && known.price() > 0.0D && cell.unit() < known.price() * 0.9D
                    && !kind.equals(MarketParser.HISTORY);
            if (cell.unit() > 0.0D) {
                Ui.drawCentered(c, tr, shortMoney(cell.unit()), cx + CW / 2, cy + 19, deal ? Ui.WARN : Ui.GOOD, 255);
            }
        }
        if (shown.isEmpty()) {
            Ui.drawCentered(c, tr, cells.isEmpty() ? "Waiting for the server…" : "Nothing here", left + PANEL_W / 2,
                    gy + ROWS * CH / 2 - 4, SUB, 255);
        }
    }

    private void drawEnergyInfo(DrawContext c, TextRenderer tr, PriceBook book) {
        int x = gridX();
        int y = top + 28;
        Ui.sprite(c, "market/tab", x, y, GRID_W, 30, Ui.argb(46, 0xFFFFFF));
        String available = field("Available:");
        String from = field("From:");
        String sellers = field("Sellers:");
        String rise = field("Price increases in:");
        Ui.draw(c, tr, "Available " + (available.isEmpty() ? "?" : available), x + 6, y + 4, Ui.VALUE, 255);
        Ui.drawRight(c, tr, sellers.isEmpty() ? "" : sellers + " sellers", x + GRID_W - 6, y + 4, SUB, 255);
        String low = "Low " + (from.isEmpty() ? "?" : from.replace(" /1k", "/1k"));
        Ui.draw(c, tr, low, x + 6, y + 17, Ui.GOOD, 255);
        String right = book.avgWeekPerK() > 0.0D ? "7d avg $" + Money.compact(book.avgWeekPerK()) : rise.isEmpty() ? "" : "+" + rise;
        if (Ui.width(tr, low) + Ui.width(tr, right) + 16 <= GRID_W) {
            Ui.drawRight(c, tr, right, x + GRID_W - 6, y + 17, Ui.WARN, 255);
        }
    }

    private String field(String prefix) {
        for (String line : infoLines) {
            String s = line.strip();
            if (s.startsWith(prefix)) {
                return s.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private void drawOffers(DrawContext c, TextRenderer tr) {
        int gx = gridX();
        int gy = gridY();
        int accent = Ui.theme().accent();
        for (int k = clientPage * OFFERS_PER_PAGE; k < Math.min(offers.size(), (clientPage + 1) * OFFERS_PER_PAGE); k++) {
            Offer o = offers.get(k);
            int cy = gy + (k % OFFERS_PER_PAGE) * OFFER_H;
            boolean over = handler.slots.get(o.slot()) == focusedSlot;
            Ui.sprite(c, "market/cell", gx + 1, cy, GRID_W - 2, OFFER_H - 2, over ? Ui.argb(120, accent) : Ui.argb(52, 0xFFFFFF));
            String priceText = "$" + Money.compact(o.price());
            Ui.draw(c, tr, fit(tr, o.seller(), GRID_W - 28 - Ui.width(tr, priceText) - 14), gx + 26, cy + 3, Ui.VALUE, 255);
            Ui.drawRight(c, tr, priceText, gx + GRID_W - 7, cy + 3, Ui.GOOD, 255);
            Ui.draw(c, tr, o.amount() + " energy", gx + 26, cy + 12, SUB, 255);
            Ui.drawRight(c, tr, o.rate() + " /1k", gx + GRID_W - 7, cy + 12, SUB, 255);
        }
        if (offers.isEmpty()) {
            Ui.drawCentered(c, tr, "Waiting for the server…", left + PANEL_W / 2, gy + OFFERS_PER_PAGE * OFFER_H / 2 - 4, SUB, 255);
        }
    }

    private void drawNav(DrawContext c, int mouseX, int mouseY) {
        int accent = Ui.theme().accent();
        for (int k = 0; k < navs.size(); k++) {
            Nav nav = navs.get(k);
            int bx = navX(k);
            boolean over = mouseX >= bx && mouseX < bx + BTN && mouseY >= navY() && mouseY < navY() + BTN;
            boolean live = (nav.prev() && clientPage > 0) || (nav.next() && clientPage < pages() - 1);
            Ui.sprite(c, "market/button", bx, navY(), BTN, BTN, over ? Ui.argb(170, accent) : Ui.argb(live ? 80 : 46, live ? accent : 0xFFFFFF));
            Ui.sprite(c, "market/" + nav.icon(), bx + 3, navY() + 3, 12, 12, Ui.argb(255, over || live ? 0xFFFFFF : SUB));
        }
    }

    private void drawFooter(DrawContext c, TextRenderer tr, PriceBook book) {
        int x = gridX();
        int y = footerY();
        Ui.sprite(c, "market/tab", x, y, GRID_W, 16, Ui.argb(34, 0xFFFFFF));
        Slot slot = focusedSlot;
        if (slot != null && slot.inventory == handler.getInventory() && slot.hasStack()) {
            ItemStack stack = slot.getStack();
            String name = TextStrip.strip(stack.getName().getString());
            int i = slot.getIndex();
            Cell cell = null;
            for (Cell cc : cells) {
                if (cc.slot() == i) {
                    cell = cc;
                }
            }
            if (cell != null && cell.unit() <= 0.0D) {
                Ui.draw(c, tr, fit(tr, name, GRID_W - 12), x + 6, y + 4, Ui.VALUE, 255);
                return;
            }
            if (cell != null) {
                String price = "$" + Money.compact(cell.unit());
                Ui.drawRight(c, tr, price, x + GRID_W - 6, y + 4, Ui.GOOD, 255);
                Ui.draw(c, tr, fit(tr, name, GRID_W - 20 - Ui.width(tr, price)), x + 6, y + 1, Ui.VALUE, 255);
                double e = book.inEnergy(cell.unit());
                Ui.draw(c, tr, e > 0.0D ? "≈ " + Money.compact(e) + " energy" : "energy rate unknown", x + 6, y + 9, Ui.theme().accent(), 255);
                return;
            }
            for (Offer o : offers) {
                if (o.slot() == i) {
                    Ui.drawRight(c, tr, "$" + Money.compact(o.price()), x + GRID_W - 6, y + 4, Ui.GOOD, 255);
                    Ui.draw(c, tr, fit(tr, o.seller(), GRID_W - 60), x + 6, y + 1, Ui.VALUE, 255);
                    Ui.draw(c, tr, o.amount() + " energy at " + o.rate() + " per 1k", x + 6, y + 9, Ui.theme().accent(), 255);
                    return;
                }
            }
            Ui.draw(c, tr, fit(tr, name, GRID_W - 12), x + 6, y + 4, Ui.VALUE, 255);
            return;
        }
        double rate = book.moneyPerEnergy();
        Ui.draw(c, tr, rate > 0.0D ? "$" + Money.compact(rate * 1000.0D) + " per 1k energy" : "energy rate unknown", x + 6, y + 4, Ui.theme().accent(), 255);
        Ui.drawRight(c, tr, "page " + (clientPage + 1) + "/" + pages(), x + GRID_W - 6, y + 4, SUB, 255);
    }

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (Ui.width(tr, s) <= maxWidth) {
            return s;
        }
        String out = s;
        while (out.length() > 2 && Ui.width(tr, out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    /** 1 250 000 → "1.3M", 125 000 → "125K", 8 000 000 → "8M". */
    static String shortMoney(double v) {
        String s;
        if (v >= 1e9) {
            s = String.format(Locale.ROOT, "%.1fB", v / 1e9);
        } else if (v >= 1e6) {
            s = String.format(Locale.ROOT, v >= 1e7 ? "%.0fM" : "%.1fM", v / 1e6);
        } else if (v >= 1e3) {
            s = String.format(Locale.ROOT, v >= 1e4 ? "%.0fK" : "%.1fK", v / 1e3);
        } else {
            s = String.format(Locale.ROOT, "%.0f", v);
        }
        return s.replace(".0", "");
    }

    @Override
    protected void drawBackground(DrawContext context, float deltaTicks, int mouseX, int mouseY) {
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
    }

    /** The server's button items are not drawn: the bar shows the mod's own icons (the slots stay for clicks / tooltips). */
    @Override
    protected void drawSlots(DrawContext context, int mouseX, int mouseY) {
        for (Slot slot : handler.slots) {
            if (slot.isEnabled() && slot.x != HIDDEN && !(slot.inventory == handler.getInventory() && slot.getIndex() >= LISTING_SLOTS)) {
                drawSlot(context, slot, mouseX, mouseY);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            // Our pages first: the arrows turn them, only the last / first page goes on to the server.
            for (int k = 0; k < navs.size(); k++) {
                Nav nav = navs.get(k);
                int bx = navX(k);
                if (mx >= bx && mx < bx + BTN && my >= navY() && my < navY() + BTN) {
                    if (nav.next() && clientPage < pages() - 1) {
                        clientPage++;
                        return true;
                    }
                    if (nav.prev() && clientPage > 0) {
                        clientPage--;
                        return true;
                    }
                }
            }
            if (!energy()) {
                int ty = top + 28;
                int w = GRID_W / 6;
                if (my >= ty && my < ty + 18 && mx >= gridX() && mx < gridX() + GRID_W) {
                    int i = (int) ((mx - gridX()) / w);
                    tab = i <= 0 ? null : MarketCategory.values()[Math.min(i, 5) - 1];
                    kindFilter = null;
                    clientPage = 0;
                    return true;
                }
                if (my >= top + 49 && my < top + 61) {
                    int cx = gridX();
                    for (String k : kinds()) {
                        int cw = chipWidth(textRenderer, k);
                        if (cx + cw > gridX() + GRID_W) {
                            break;
                        }
                        if (mx >= cx && mx < cx + cw) {
                            kindFilter = k.equals(kindFilter) ? null : k;
                            clientPage = 0;
                            return true;
                        }
                        cx += cw + 3;
                    }
                }
                int sw = 92;
                int sx = left + PANEL_W - PAD - sw;
                int sy = top + 8;
                if (my >= sy && my < sy + 15 && mx >= sx && mx < sx + sw) {
                    focused = true;
                    return true;
                }
                focused = false;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (pages() > 1) {
            clientPage = Math.max(0, Math.min(pages() - 1, clientPage - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (focused && !energy()) {
            int key = input.key();
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                focused = false;
            } else if (key == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
                clientPage = 0;
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focused && !energy()) {
            if (input.isValidChar() && query.length() < 28) {
                char ch = input.asString().charAt(0);
                if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '\'' || ch == '.') {
                    query += ch;
                    clientPage = 0;
                }
            }
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top) {
        return false;
    }

    @Override
    public void removed() {
        for (Map.Entry<Slot, int[]> entry : original.entrySet()) {
            move(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
        focused = false;
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
