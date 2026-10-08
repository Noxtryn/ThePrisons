package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.ItemCategory;
import io.theprisons.items.ItemEntry;
import io.theprisons.items.ItemListModel;
import io.theprisons.items.ItemView;
import io.theprisons.items.InventoryListLayout;
import io.theprisons.items.ItemsService;
import io.theprisons.items.SearchInput;
import io.theprisons.items.VirtualGrid;
import io.theprisons.modules.FeatureProfile;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The item list of the player inventory: a search bar above the hotbar (typing anywhere fills it), and - while something is searched or "all items" is on -
 * a panel beside the inventory with categories, tier chips (only where a family has tiers) and large cards. A double click on the bar shows or hides every
 * item. The layout and the typing are pure ({@link InventoryListLayout}, {@link SearchInput}); the data is the shared {@link ItemListModel}, recomputed only
 * when the query, the category, the tiers or the registry changed; only the cards in the visible rows are drawn ({@link VirtualGrid}).
 *
 * <p>Driven by the mixins of the inventory screen; there is no screen of its own and no key to open it.
 */
public final class InventoryItemList {
    private static final long DOUBLE_CLICK_MS = 350L;
    private static final int PANEL_BG = 0xF00A0F1A;
    private static final int CARD = 0xFF101828;
    private static final int CARD_HOVER = 0xFF16223A;
    private static final int BORDER = 0xFF1C2740;
    private static final int CYAN = 0xFF3CC4E8;
    private static final int PINK = 0xFFFF6EC7;

    private static final SearchInput INPUT = new SearchInput();
    private static float scroll;
    private static int scrollTarget;
    private static long lastBarClickMs;
    private static long lastFrameMs;
    private static @Nullable String selectedKey;
    private static final Map<String, List<String>> WRAPPED = new HashMap<>();
    private static int wrappedCardW = -1;
    private static final List<int[]> CHIP_RECTS = new ArrayList<>();
    private static final List<Object> CHIP_ACTIONS = new ArrayList<>();
    private static Map<ItemCategory, Integer> counts = new EnumMap<>(ItemCategory.class);
    private static long countsRevision = -1;
    private static int drawnCards;
    private static InventoryListLayout layout = InventoryListLayout.of(640, 360);

    private InventoryItemList() {
    }

    private static boolean enabled() {
        ItemListModule m = ItemListModule.get();
        return m != null && m.enabled() && ItemsService.get() != null;
    }

    public static boolean open() {
        return enabled() && INPUT.open();
    }

    public static String query() {
        return INPUT.text();
    }

    /** Closes the list and forgets the search (the inventory was closed). */
    public static void reset() {
        INPUT.reset();
        selectedKey = null;
        scrollTarget = 0;
        scroll = 0;
        sync();
    }

    /** Called every tick while no inventory is open: forgets the search once. */
    public static void onInventoryClosed() {
        if (INPUT.open() || selectedKey != null) {
            reset();
        }
    }

    private static void sync() {
        ItemsService s = ItemsService.get();
        if (s != null && INPUT.takeChanged()) {
            // "all items" shows the whole list; a query narrows it
            s.list().setQuery(INPUT.text());
            scrollTarget = 0;
        }
    }

    /** The HUD widgets step aside while the list is open over the inventory. */
    public static HudRenderCallback hudGuard(HudRenderCallback callback) {
        return (context, tickCounter) -> {
            if (!(open() && MinecraftClient.getInstance().currentScreen instanceof InventoryScreen)) {
                callback.onHudRender(context, tickCounter);
            }
        };
    }

    // ── Input ────────────────────────────────────────────────────────────────

    public static boolean charTyped(char c) {
        if (!enabled()) {
            return false;
        }
        boolean consumed = INPUT.charTyped(c);
        sync();
        return consumed;
    }

    public static boolean keyPressed(int key, int modifiers, boolean overSlot) {
        if (!enabled()) {
            return false;
        }
        boolean consumed = INPUT.keyPressed(key, modifiers, overSlot);
        sync();
        if (key == 256) {
            selectedKey = null;
        }
        return consumed;
    }

    public static boolean scrolled(double mouseX, double mouseY, double vertical, int screenWidth, int screenHeight) {
        if (!open()) {
            return false;
        }
        InventoryListLayout l = InventoryListLayout.of(screenWidth, screenHeight);
        if (!l.overPanel(mouseX, mouseY)) {
            return false;
        }
        VirtualGrid grid = new VirtualGrid(l.cardW(), l.cardH(), InventoryListLayout.CARD_GAP, 2);
        ItemView view = ItemsService.get().list().view();
        scrollTarget = grid.clampScroll(scrollTarget - (int) Math.round(vertical * (l.cardH() + InventoryListLayout.CARD_GAP)), view.entries().size(),
                l.gridW(), gridHeight(l));
        return true;
    }

    public static boolean click(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        if (!enabled()) {
            return false;
        }
        InventoryListLayout l = InventoryListLayout.of(screenWidth, screenHeight);
        if (l.overBar(mouseX, mouseY)) {
            long now = System.currentTimeMillis();
            if (now - lastBarClickMs <= DOUBLE_CLICK_MS) {
                INPUT.toggleShowAll();
                sync();
                lastBarClickMs = 0L;
            } else {
                lastBarClickMs = now;
            }
            return true;
        }
        if (!open() || !l.overPanel(mouseX, mouseY)) {
            return false;
        }
        ItemListModel model = ItemsService.get().list();
        for (int i = 0; i < CHIP_RECTS.size(); i++) {
            int[] r = CHIP_RECTS.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                Object action = CHIP_ACTIONS.get(i);
                if (action instanceof ItemCategory cat) {
                    model.setCategory(cat);
                } else if (action instanceof String tier) {
                    model.toggleTier(tier);
                } else {
                    model.setCategory(null);
                }
                scrollTarget = 0;
                return true;
            }
        }
        VirtualGrid grid = new VirtualGrid(l.cardW(), l.cardH(), InventoryListLayout.CARD_GAP, 2);
        ItemView view = model.view();
        int top = gridTop(l);
        if (mouseY >= top && mouseY < top + gridHeight(l)) {
            int idx = grid.indexAt((int) mouseX - l.gridX(), (int) mouseY - top, view.entries().size(), l.gridW(), Math.round(scroll));
            selectedKey = idx >= 0 ? view.entries().get(idx).key() : null;
        }
        return true;
    }

    // ── Layout helpers ───────────────────────────────────────────────────────

    private static final int HEADER = 22;
    private static final int FOOTER = 40;

    /** Top of the grid: below the header, the category chips (set while drawing) and the tier row. */
    private static int gridTopOffset = 60;

    private static int gridTop(InventoryListLayout l) {
        return l.panelY() + gridTopOffset;
    }

    private static int gridHeight(InventoryListLayout l) {
        return Math.max(0, l.panelH() - gridTopOffset - FOOTER);
    }

    // ── Render ───────────────────────────────────────────────────────────────

    public static void render(DrawContext c, TextRenderer tr, int width, int height, int mouseX, int mouseY) {
        if (!enabled()) {
            return;
        }
        sync();
        layout = InventoryListLayout.of(width, height);
        long nowMs = System.currentTimeMillis();
        float dt = Math.min(0.1F, Math.max(0.0F, (nowMs - lastFrameMs) / 1000.0F));
        lastFrameMs = nowMs;
        drawBar(c, tr);
        if (!INPUT.open() || !layout.panelFits()) {
            return;
        }
        ItemListModel model = ItemsService.get().list();
        ItemView view = model.view();
        InventoryListLayout l = layout;
        int px = l.panelX();
        int py = l.panelY();
        Ui.round(c, px, py, l.panelW(), l.panelH(), PANEL_BG);
        Ui.outline(c, px, py, l.panelW(), l.panelH(), BORDER);
        Ui.draw(c, tr, "ITEMS", px + InventoryListLayout.PAD, py + 7, Ui.theme().title(), 255);
        Ui.drawRight(c, tr, view.entries().size() + " items", px + l.panelW() - InventoryListLayout.PAD, py + 7, Ui.MUTED, 255);
        Ui.line(c, px + InventoryListLayout.PAD, px + l.panelW() - InventoryListLayout.PAD, py + 18, 0.4F);

        int y = drawChips(c, tr, model, view, l, py + HEADER + 2);
        gridTopOffset = y - py + 2;
        int top = gridTop(l);
        int gh = gridHeight(l);
        VirtualGrid grid = new VirtualGrid(l.cardW(), l.cardH(), InventoryListLayout.CARD_GAP, 2);
        int count = view.entries().size();
        scrollTarget = grid.clampScroll(scrollTarget, count, l.gridW(), gh);
        scroll = Ui.approach(scroll, scrollTarget, dt, 18.0F);
        int scrollPx = Math.round(scroll);
        if (l.cardW() != wrappedCardW) {
            WRAPPED.clear();
            wrappedCardW = l.cardW();
        }
        int hover = -1;
        if (mouseX >= l.gridX() && mouseX < l.gridX() + l.gridW() && mouseY >= top && mouseY < top + gh) {
            hover = grid.indexAt(mouseX - l.gridX(), mouseY - top, count, l.gridW(), scrollPx);
        }
        c.enableScissor(l.gridX(), top, l.gridX() + l.gridW(), top + gh);
        VirtualGrid.Range range = grid.visible(count, l.gridW(), gh, scrollPx);
        drawnCards = 0;
        for (int i = range.first(); i >= 0 && i <= range.last(); i++) {
            int cx = l.gridX() + grid.x(i, l.gridW());
            int cy = top + grid.y(i, l.gridW()) - scrollPx;
            if (cy + l.cardH() < top || cy > top + gh) {
                continue;
            }
            drawCard(c, tr, view.entries().get(i), cx, cy, l.cardW(), l.cardH(), i == hover);
            drawnCards++;
        }
        c.disableScissor();
        if (count == 0) {
            Ui.drawCentered(c, tr, "No items match", px + l.panelW() / 2, top + 20, Ui.MUTED, 255);
        }
        drawScrollbar(c, grid, count, l, top, gh, scrollPx);

        ItemEntry shown = hover >= 0 ? view.entries().get(hover) : selected(view);
        drawFooter(c, tr, shown, l);
        if (FeatureProfile.DEV) {
            Ui.draw(c, tr, "cards " + drawnCards + "/" + count + " · search " + view.searchNanos() / 1000 + "us filter " + view.filterNanos() / 1000
                    + "us · computed " + model.computations() + "x", px + 4, py + l.panelH() + 1, Ui.MUTED, 200);
        }
        if (hover >= 0) {
            c.drawTooltip(tr, tooltip(view.entries().get(hover)), Optional.empty(), mouseX, mouseY);
        }
    }

    private static @Nullable ItemEntry selected(ItemView view) {
        if (selectedKey == null) {
            return null;
        }
        return ItemsService.get().registry().get(selectedKey);
    }

    private static void drawBar(DrawContext c, TextRenderer tr) {
        InventoryListLayout l = layout;
        int accent = Ui.theme().accent();
        boolean open = INPUT.open();
        Ui.round(c, l.barX() - 1, l.barY() - 1, l.barW() + 2, l.barH() + 2, Ui.argb(open ? 230 : 140, accent));
        Ui.round(c, l.barX(), l.barY(), l.barW(), l.barH(), 0xF00A0F1A);
        Ui.sprite(c, "market/nav_search", l.barX() + 6, l.barY() + 4, 10, 10, Ui.argb(255, accent));
        boolean blink = (System.currentTimeMillis() / 450L) % 2 == 0;
        String text;
        int colour;
        if (INPUT.text().isEmpty()) {
            text = (blink ? "|" : " ") + (INPUT.showAll() ? " All items · double-click: off" : " Search Cosmic items…");
            colour = Ui.MUTED;
        } else {
            String shown = INPUT.text();
            while (shown.length() > 3 && Ui.width(tr, shown) > l.barW() - 34) {
                shown = shown.substring(1);
            }
            text = shown + (blink ? "|" : "");
            colour = Ui.VALUE;
        }
        Ui.draw(c, tr, text, l.barX() + 21, l.barY() + 5, colour, 255);
    }

    /** Category chips (wrapped) and, when the category has tiers, the tier row. Returns the y below them. */
    private static int drawChips(DrawContext c, TextRenderer tr, ItemListModel model, ItemView view, InventoryListLayout l, int startY) {
        CHIP_RECTS.clear();
        CHIP_ACTIONS.clear();
        if (countsRevision != ItemsService.get().registry().revision()) {
            counts = new EnumMap<>(ItemCategory.class);
            for (ItemEntry e : ItemsService.get().registry().all()) {
                counts.merge(e.category(), 1, Integer::sum);
            }
            countsRevision = ItemsService.get().registry().revision();
        }
        int x = l.panelX() + InventoryListLayout.PAD;
        int max = l.panelX() + l.panelW() - InventoryListLayout.PAD;
        int y = startY;
        List<@Nullable ItemCategory> cats = new ArrayList<>();
        cats.add(null);
        for (ItemCategory cat : ItemCategory.values()) {
            if (counts.getOrDefault(cat, 0) > 0) {
                cats.add(cat);
            }
        }
        for (ItemCategory cat : cats) {
            String label = cat == null ? "All" : cat.label();
            int w = Ui.width(tr, label) + 10;
            if (x + w > max) {
                x = l.panelX() + InventoryListLayout.PAD;
                y += 15;
            }
            boolean on = model.filter().category() == cat;
            Ui.round(c, x, y, w, 13, on ? 0xFF16223A : 0xFF0F1626);
            if (on) {
                c.fill(x + 2, y + 11, x + w - 2, y + 12, CYAN);
            }
            Ui.draw(c, tr, label, x + 5, y + 3, on ? Ui.VALUE : Ui.LABEL, 255);
            CHIP_RECTS.add(new int[]{x, y, w, 13});
            CHIP_ACTIONS.add(cat == null ? "ALL" : cat);
            x += w + 3;
        }
        y += 16;
        if (!view.tiers().isEmpty()) {
            x = l.panelX() + InventoryListLayout.PAD;
            for (String tier : view.tiers()) {
                int w = Ui.width(tr, tier) + 10;
                if (x + w > max) {
                    x = l.panelX() + InventoryListLayout.PAD;
                    y += 15;
                }
                boolean on = model.filter().tiers().contains(tier);
                int rgb = TierColors.rgb(tier);
                Ui.round(c, x, y, w, 13, on ? 0xFF000000 | Ui.mix(0x0F1626, rgb, 0.28F) : 0xFF0F1626);
                Ui.outline(c, x, y, w, 13, on ? 0xFF000000 | rgb : BORDER);
                Ui.draw(c, tr, tier, x + 5, y + 3, on ? rgb : Ui.LABEL, 255);
                CHIP_RECTS.add(new int[]{x, y, w, 13});
                CHIP_ACTIONS.add(tier);
                x += w + 3;
            }
            y += 16;
        }
        return y;
    }

    private static void drawCard(DrawContext c, TextRenderer tr, ItemEntry e, int x, int y, int w, int h, boolean hover) {
        boolean sel = e.key().equals(selectedKey);
        Ui.round(c, x, y, w, h, hover ? CARD_HOVER : CARD);
        Ui.outline(c, x, y, w, h, sel ? PINK : hover ? CYAN : BORDER);
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x + w / 2.0F - 16, y + 4);
        m.scale(2.0F, 2.0F);
        c.drawItem(ItemStacks.of(e), 0, 0);
        m.popMatrix();
        List<String> lines = wrap(tr, e, w - 6);
        int ty = y + 38;
        for (int i = 0; i < lines.size() && i < 2; i++) {
            Ui.drawCentered(c, tr, lines.get(i), x + w / 2, ty, Ui.VALUE, 255);
            ty += 9;
        }
        io.theprisons.items.market.ItemPrices.Card price = ItemsService.get().prices().card(e.key());
        if (price != null) {
            // the price from the shared market memory; the word of the confidence only where the card is wide enough
            int colour = switch (price.confidence()) {
                case HIGH -> 0x7CF0A0;
                case MEDIUM -> 0xDCE6F5;
                default -> 0x8A93A6;
            };
            String text = price.price();
            String word = price.confidence().name();
            if (Ui.width(tr, text + " " + word) <= w - 6) {
                text = text + " " + word;
            }
            Ui.drawCentered(c, tr, text, x + w / 2, y + h - 13, colour, 255);
        }
        if (e.tier() != null) {
            c.fill(x + 6, y + h - 3, x + w - 6, y + h - 2, 0xFF000000 | TierColors.rgb(e.tier()));     // a thin tier line, not a neon card
        }
    }

    private static List<String> wrap(TextRenderer tr, ItemEntry e, int maxW) {
        return WRAPPED.computeIfAbsent(e.key(), k -> {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : e.displayName().split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (Ui.width(tr, next) > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
            return out;
        });
    }

    private static void drawScrollbar(DrawContext c, VirtualGrid grid, int count, InventoryListLayout l, int top, int gh, int scrollPx) {
        int content = grid.contentHeight(count, l.gridW());
        if (content <= gh) {
            return;
        }
        int barH = Math.max(18, gh * gh / content);
        int barY = top + (int) ((long) (gh - barH) * scrollPx / Math.max(1, content - gh));
        int bx = l.panelX() + l.panelW() - 4;
        c.fill(bx, top, bx + 2, top + gh, 0xFF0F1626);
        c.fill(bx, barY, bx + 2, barY + barH, 0xFF2D4466);
    }

    /** The selected / hovered item at the bottom of the panel: name, category, tier, market. */
    private static void drawFooter(DrawContext c, TextRenderer tr, @Nullable ItemEntry e, InventoryListLayout l) {
        int x = l.panelX() + InventoryListLayout.PAD;
        int y = l.panelY() + l.panelH() - FOOTER + 4;
        Ui.line(c, x, l.panelX() + l.panelW() - InventoryListLayout.PAD, y - 3, 0.3F);
        if (e == null) {
            Ui.draw(c, tr, "Click an item for details", x, y + 8, Ui.MUTED, 255);
            return;
        }
        Ui.draw(c, tr, e.displayName(), x, y, Ui.VALUE, 255);
        String sub = e.category().label() + (e.tier() != null ? " · " + e.tier() : "");
        Ui.draw(c, tr, sub, x, y + 10, e.tier() != null ? TierColors.rgb(e.tier()) : Ui.LABEL, 255);
        List<String> market = ItemsService.get().marketDetail(e.key());
        Ui.draw(c, tr, market.isEmpty() ? "No market data yet" : market.get(0) + (market.size() > 2 ? " · " + market.get(2) : ""), x, y + 20, Ui.MUTED, 255);
    }

    private static List<Text> tooltip(ItemEntry e) {
        List<Text> out = new ArrayList<>();
        out.add(Text.literal(e.displayName()));
        out.add(Text.literal(e.category().label() + " · " + e.subcategory() + (e.tier() != null ? " · " + e.tier() : "")));
        if (e.meta().hasTiers()) {
            out.add(Text.literal("Tiers: " + String.join(", ", e.meta().tiers())));
        }
        for (String line : ItemsService.get().marketDetail(e.key())) {
            out.add(Text.literal(line));
        }
        if (FeatureProfile.DEV) {
            out.add(Text.literal(e.internalName() + " · " + e.identity().confidence()));
        }
        return out;
    }
}
