package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.modules.qol.items.ItemLookModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Cosmic item list of the player inventory (built like the SkyBlock Item List: a paged item grid beside the
 * inventory, one search field - here in the mod's design).
 *
 * <p>The search field above the hotbar is always active: typing anything fills it, no click needed; a quick double click
 * on it shows every item without a search (and turns that off again). As soon as there is a search text (or the
 * show-all mode) the list opens on the right side - from the top to the bottom of the screen, with a margin to the
 * inventory - and the mod's other HUD overlays step aside.</p>
 *
 * <p>The list holds one cell per item family ("Shard", "Contraband") with its base texture (no rarity frame). A left
 * click opens a rounded dropdown right under the cell with every rarity that exists, each with its real texture and its
 * price; hovering one lifts it and shows the item exactly as in the game (name, colours, lore). A click anywhere else
 * closes it, a click on another cell opens that one instead.</p>
 */
public final class MarketSearch {
    private static final int BAR_W = 170;
    private static final int BAR_H = 18;
    private static final int GAP = 20;
    private static final int PAD = 8;
    private static final int CW = 26;
    private static final int CH = 30;
    private static final int HEADER = 44;
    private static final int FOOTER = 18;
    private static final int DROP_W = 28;
    private static final int DROP_H = 40;
    private static final long DOUBLE_CLICK_MS = 350L;
    private static final int SUB = MarketScreen.SUB;

    private static String query = "";
    private static boolean showAll;
    private static @Nullable MarketCategory group;
    private static int page;
    private static long lastBarClickMs;
    /** What the list showed in the last frame (for clicks / scrolling). */
    private static int shownPages = 1;
    private static @Nullable String openFamily;
    private static final List<Hit> hits = new ArrayList<>();
    private static int dropX;
    private static int dropY;
    private static int dropW;
    private static int dropH;
    private static final Map<String, Float> hoverAnim = new HashMap<>();
    private static long lastFrameMs;

    // The families, rebuilt when the price book changed.
    private static final java.util.Set<String> EXCLUDED_KINDS = java.util.Set.of("Armor", "Tools", "Weapons", "NPC Items");
    private static volatile List<Family> families = List.of();
    private static volatile int familiesVersion = -1;
    private static volatile boolean building;
    /** The list shown for the last (families, tab, text): the filter only runs when one of them changes. */
    private static List<Family> filtered = List.of();
    private static List<Family> filteredFrom = List.of();
    private static @Nullable MarketCategory filteredGroup;
    private static String filteredQuery = "";

    /** The cells of the last frame: where each family is. */
    private record Hit(int x, int y, Family family) {
    }

    /** One item family: its variants (rarities) from the lowest, the group and the finer kind. */
    private record Family(String key, String name, MarketCategory group, String kind, List<PriceBook.Entry> variants,
                           String hay, String kindText) {
        Family(String key, String name, MarketCategory group, String kind, List<PriceBook.Entry> variants) {
            this(key, name, group, kind, variants, haystack(name, variants),
                    (group.label + " " + kind).toLowerCase(Locale.ROOT));
        }

        double lowest() {
            double min = -1.0D;
            for (PriceBook.Entry e : variants) {
                if (e.price() > 0.0D && (min < 0.0D || e.price() < min)) {
                    min = e.price();
                }
            }
            return min;
        }
    }

    private static String haystack(String name, List<PriceBook.Entry> variants) {
        StringBuilder hay = new StringBuilder(name.toLowerCase(Locale.ROOT));
        for (PriceBook.Entry v : variants) {
            hay.append(' ').append(v.name().toLowerCase(Locale.ROOT));
        }
        return hay.toString();
    }

    private MarketSearch() {
    }

    /** Closes the list and forgets the search (the inventory was closed). */
    public static void reset() {
        query = "";
        showAll = false;
        page = 0;
        openFamily = null;
    }

    public static String query() {
        return query;
    }

    /** For the client game test: opens the dropdown of a family (lower-case name). */
    public static void debugOpen(@Nullable String familyKey) {
        openFamily = familyKey;
    }

    /** For the client game test: where a family's cell was drawn last frame (x, y in GUI pixels), null = not shown. */
    public static int @Nullable [] debugCell(String familyKey) {
        for (Hit h : hits) {
            if (h.family().key().equals(familyKey)) {
                return new int[]{h.x(), h.y()};
            }
        }
        return null;
    }

    /** For the client game test: where the dropdown is (x, y, w, h in GUI pixels). */
    public static int[] debugDrop() {
        return new int[]{dropX, dropY, dropW, dropH};
    }

    /** The list is open: a search text, or the show-all mode. */
    public static boolean open() {
        return showAll || !query.isEmpty();
    }

    // ── Families ─────────────────────────────────────────────────────────────

    /**
     * The families of the price book. They are built on the background thread whenever the book changed; until the new
     * ones are ready the last ones are shown (the render thread never waits).
     */
    private static List<Family> families(PriceBook book) {
        if (familiesVersion != book.version() && !building) {
            building = true;
            int version = book.version();
            PriceBook snapshot = book.snapshot();
            MarketWork.run("item list", () -> buildFamilies(snapshot), list -> {
                families = list;
                familiesVersion = version;
                building = false;
            });
        }
        return families;
    }

    /** True while the first list is being built (nothing to show yet). */
    private static boolean preparing() {
        return building && families.isEmpty();
    }

    private static List<Family> buildFamilies(PriceBook book) {
        Map<String, List<PriceBook.Entry>> byFamily = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> tieredFamilies = new java.util.HashSet<>();
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            if (!spec.rarities().isEmpty()) {
                tieredFamilies.add(spec.family().toLowerCase(Locale.ROOT));
            }
        }
        // 1. Every item the mod knows (rarities that exist), with the price book's price where there is one.
        for (ItemDirectory.Spec spec : ItemDirectory.all()) {
            for (String variant : spec.names()) {
                ItemIdentity.Id id = ItemIdentity.of(variant, null);
                if (id == null || !seen.add(id.key())) {
                    continue;
                }
                PriceBook.Entry known = book.get(id.key());
                MarketCategory.Sorted sorted = MarketCategory.of(id.name(), null);
                PriceBook.Entry entry = known != null ? known
                        : new PriceBook.Entry(id.key(), id.name(), sorted.group().label + "/" + sorted.kind(), -1.0D, 0L, "dir", spec.icon());
                String fk = id.family().toLowerCase(Locale.ROOT);
                byFamily.computeIfAbsent(fk, k -> new ArrayList<>()).add(entry);
                names.putIfAbsent(fk, id.family());
            }
        }
        // 2. Everything else the menus showed (not only a shop's loot list).
        for (PriceBook.Entry e : book.all()) {
            ItemIdentity.Id id = ItemIdentity.of(e.name(), null);
            if (id == null || seen.contains(id.key()) || (e.source().equals("shop") && e.price() <= 0.0D)) {
                continue;
            }
            // "Dust" without a rarity next to its rarities is the category heading of the market, not an item.
            if (id.rarity() == null && tieredFamilies.contains(id.family().toLowerCase(Locale.ROOT))) {
                continue;
            }
            seen.add(id.key());
            byFamily.computeIfAbsent(id.family().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
            names.putIfAbsent(id.family().toLowerCase(Locale.ROOT), id.family());
        }
        List<Family> out = new ArrayList<>();
        for (Map.Entry<String, List<PriceBook.Entry>> f : byFamily.entrySet()) {
            List<PriceBook.Entry> variants = f.getValue();
            variants.sort(Comparator.comparingInt((PriceBook.Entry e) -> {
                ItemIdentity.Id id = ItemIdentity.of(e.name(), null);
                return id == null ? 0 : id.rank();
            }));
            MarketCategory.Sorted sorted = MarketCategory.of(variants.get(0).name(), null);
            // No armour, pickaxes or weapons in the list.
            if (EXCLUDED_KINDS.contains(sorted.kind())) {
                continue;
            }
            out.add(new Family(f.getKey(), names.get(f.getKey()), sorted.group(), sorted.kind(), variants));
        }
        out.sort(Comparator.comparingInt((Family f) -> f.group().ordinal()).thenComparing(Family::kind)
                .thenComparing(Family::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static boolean matches(Family f, @Nullable MarketCategory tabGroup, String text) {
        if (tabGroup != null && f.group() != tabGroup) {
            return false;
        }
        String hay = f.hay();
        String kindText = f.kindText();
        for (String word : text.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.startsWith("@")) {
                // "@armor", "@shards", "@cosm": the group or the finer kind
                String want = word.substring(1);
                boolean ok = false;
                for (String part : kindText.split(" ")) {
                    ok |= part.startsWith(want) || (want.endsWith("s") && part.startsWith(want.substring(0, want.length() - 1)));
                }
                if (!ok) {
                    return false;
                }
            } else if (!hay.contains(word) && !kindText.contains(word)) {
                return false;
            }
        }
        return true;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    /** Wraps one of the mod's HUD callbacks: it is skipped while the item list is open. */
    public static net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback hudGuard(
            net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback callback) {
        return (context, tickCounter) -> {
            if (!(open() && MinecraftClient.getInstance().currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) {
                callback.onHudRender(context, tickCounter);
            }
        };
    }

    public static boolean charTyped(char c) {
        if (!allowed(c) || query.length() >= 40) {
            return true;
        }
        query += c;
        page = 0;
        openFamily = null;
        return true;
    }

    /**
     * Keys while the inventory is open. Everything that writes a character belongs to the field (so "E" does not close
     * the inventory) - except digits over a slot (the hotbar swap keeps working) and Ctrl / Alt shortcuts.
     *
     * @return true = consumed
     */
    public static boolean keyPressed(int key, int modifiers, boolean overSlot) {
        boolean shortcut = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0;
        if (key == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            query = shortcut ? "" : query.substring(0, query.length() - 1);
            page = 0;
            openFamily = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // One Esc: the list is reset and the inventory closes with it (not consumed - the game handles the key).
            reset();
            return false;
        }
        if (open() && key == GLFW.GLFW_KEY_LEFT) {
            page = Math.max(0, page - 1);
            return true;
        }
        if (open() && key == GLFW.GLFW_KEY_RIGHT) {
            page = Math.min(shownPages - 1, page + 1);
            return true;
        }
        if (shortcut) {
            return false;
        }
        boolean digit = key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9;
        if (digit && overSlot) {
            return false;
        }
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT;
    }

    public static boolean scrolled(double mouseX, double mouseY, double vertical, int screenWidth) {
        if (!open() || mouseX < panelX(screenWidth)) {
            return false;
        }
        page = Math.max(0, Math.min(shownPages - 1, page - (int) Math.signum(vertical)));
        openFamily = null;
        return true;
    }

    public static boolean click(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        // The search field: a quick double click shows / hides every item.
        int bx = screenWidth / 2 - BAR_W / 2;
        int by = barY(screenHeight);
        if (mouseX >= bx && mouseX < bx + BAR_W && mouseY >= by && mouseY < by + BAR_H) {
            long now = System.currentTimeMillis();
            if (now - lastBarClickMs <= DOUBLE_CLICK_MS) {
                showAll = !showAll;
                page = 0;
                openFamily = null;
                lastBarClickMs = 0L;
            } else {
                lastBarClickMs = now;
            }
            return true;
        }
        if (!open()) {
            return false;
        }
        int px = panelX(screenWidth);
        int pw = panelW(screenWidth);
        boolean inPanel = mouseX >= px && mouseX < px + pw && mouseY >= 6 && mouseY < screenHeight - 6;
        // Inside the open dropdown: nothing closes.
        if (openFamily != null && mouseX >= dropX && mouseX < dropX + dropW && mouseY >= dropY && mouseY < dropY + dropH) {
            return true;
        }
        if (!inPanel) {
            openFamily = null;
            return false;
        }
        int tabW = (pw - PAD * 2) / 6;
        if (mouseY >= 24 && mouseY < 42) {
            int i = (int) ((mouseX - px - PAD) / tabW);
            if (i >= 0 && i < 6) {
                group = i == 0 ? null : MarketCategory.values()[i - 1];
                page = 0;
            }
            openFamily = null;
            return true;
        }
        int footY = screenHeight - 6 - FOOTER + 3;
        if (mouseY >= footY - 3) {
            if (mouseX >= px + pw - 52 && mouseX < px + pw - 30) {
                page = Math.max(0, page - 1);
            } else if (mouseX >= px + pw - 22 && mouseX < px + pw - PAD + 4) {
                page = Math.min(shownPages - 1, page + 1);
            }
            openFamily = null;
            return true;
        }
        for (Hit h : hits) {
            if (mouseX >= h.x() && mouseX < h.x() + CW && mouseY >= h.y() && mouseY < h.y() + CH) {
                openFamily = h.family().key().equals(openFamily) ? null : h.family().key();
                return true;
            }
        }
        openFamily = null;
        return true;
    }

    private static boolean allowed(char c) {
        return Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' || c == '.' || c == '@' || c == '\'' || c == '%'
                || c == '(' || c == ')' || c == ':';
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    /** The inventory is 176 wide and centred. */
    private static int panelX(int w) {
        return w / 2 + 88 + GAP;
    }

    private static int panelW(int w) {
        return Math.max(CW * 3 + PAD * 2, w - 8 - panelX(w));
    }

    /** A little above the HUD hotbar. */
    private static int barY(int h) {
        return h - 22 - 4 - BAR_H - 8;
    }

    // ── Render ───────────────────────────────────────────────────────────────

    public static void render(DrawContext c, TextRenderer tr, int width, int height, int mouseX, int mouseY) {
        MarketModule module = MarketModule.get();
        if (module == null || !module.enabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        float dt = Math.min(0.1F, (nowMs - lastFrameMs) / 1000.0F);
        lastFrameMs = nowMs;
        PriceBook book = module.book();
        ItemCatalog catalog = module.catalog();
        drawBar(c, tr, width, height);
        hits.clear();
        if (!open()) {
            openFamily = null;
            return;
        }
        int accent = Ui.theme().accent();
        int px = panelX(width);
        int pw = panelW(width);
        int py = 6;
        int ph = height - 12;
        Ui.sprite(c, "market/panel_body", px + 3, py + 4, pw, ph, Ui.argb(60, 0x000000));
        Ui.sprite(c, "market/panel_body", px, py, pw, ph, Ui.argb(222, 0x0B0C14));
        Ui.sprite(c, "market/panel_frame", px, py, pw, ph, Ui.argb(210, accent));
        Ui.sprite(c, "market/cat_other", px + PAD, py + 5, 12, 12, Ui.argb(255, accent));
        Ui.shimmer(c, tr, "COSMIC ITEMS", px + PAD + 16, py + 8, 1f);

        int tabW = (pw - PAD * 2) / 6;
        for (int i = 0; i < 6; i++) {
            MarketCategory g = i == 0 ? null : MarketCategory.values()[i - 1];
            boolean active = group == g;
            int tx = px + PAD + i * tabW;
            if (active) {
                Ui.sprite(c, "market/glow", tx - 4, py + 13, tabW + 8, 28, Ui.argb(110, accent));
            }
            Ui.sprite(c, "market/tab", tx + 1, py + 18, tabW - 2, 18, active ? Ui.argb(200, accent) : Ui.argb(46, 0xFFFFFF));
            Ui.sprite(c, "market/" + MarketScreen.TAB_ICON[i], tx + (tabW - 12) / 2, py + 21, 12, 12, Ui.argb(255, active ? 0xFFFFFF : SUB));
        }

        List<Family> all = families(book);
        if (all != filteredFrom || group != filteredGroup || !query.equals(filteredQuery)) {
            List<Family> next = new ArrayList<>();
            for (Family f : all) {
                if (matches(f, group, query)) {
                    next.add(f);
                }
            }
            filtered = next;
            filteredFrom = all;
            filteredGroup = group;
            filteredQuery = query;
        }
        List<Family> shown = filtered;
        int cols = Math.max(3, (pw - PAD * 2) / CW);
        int rows = Math.max(2, (ph - HEADER - FOOTER) / CH);
        int per = cols * rows;
        shownPages = Math.max(1, (shown.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, shownPages - 1));
        int gx = px + (pw - cols * CW) / 2;
        int gy = py + HEADER;
        int start = page * per;
        Family hoveredBase = null;
        int hoverX = 0;
        int hoverY = 0;
        for (int k = 0; k < Math.min(per, shown.size() - start); k++) {
            Family f = shown.get(start + k);
            int cx = gx + (k % cols) * CW;
            int cy = gy + (k / cols) * CH;
            hits.add(new Hit(cx, cy, f));
            boolean over = mouseX >= cx && mouseX < cx + CW && mouseY >= cy && mouseY < cy + CH;
            boolean isOpen = f.key().equals(openFamily);
            Ui.sprite(c, "market/cell", cx + 1, cy, CW - 2, CH - 2, isOpen ? Ui.argb(170, accent) : over ? Ui.argb(110, accent) : Ui.argb(52, 0xFFFFFF));
            drawBase(c, catalog, f, cx + (CW - 16) / 2, cy + 2);
            double lowest = f.lowest();
            Ui.drawCentered(c, tr, lowest > 0 ? MarketScreen.shortMoney(lowest) : "–", cx + CW / 2, cy + 19,
                    lowest > 0 ? Ui.GOOD : SUB, 255);
            if (over) {
                hoveredBase = f;
                hoverX = cx;
                hoverY = cy;
            }
        }
        if (shown.isEmpty()) {
            Ui.drawCentered(c, tr, preparing() ? "Preparing the item list…" : "No Cosmic item found", px + pw / 2, py + HEADER + 20, SUB, 255);
        }
        int footY = py + ph - FOOTER + 6;
        double rate = book.moneyPerEnergy();
        String foot = shown.size() + " items" + (rate > 0 ? " · $" + Money.compact(rate * 1000) + "/1k" : "");
        if (Ui.width(tr, foot) > pw - PAD - 62) {
            foot = shown.size() + " items";
        }
        Ui.draw(c, tr, foot, px + PAD, footY, SUB, 255);
        Ui.draw(c, tr, "‹", px + pw - 48, footY, page > 0 ? Ui.VALUE : SUB, 255);
        Ui.drawCentered(c, tr, (page + 1) + "/" + shownPages, px + pw - 38, footY, SUB, 255);
        Ui.draw(c, tr, "›", px + pw - 18, footY, page < shownPages - 1 ? Ui.VALUE : SUB, 255);

        // The dropdown: a rounded layer over the overlay, directly under its item.
        Tooltip tip = null;
        Family open = null;
        Hit openHit = null;
        for (Hit h : hits) {
            if (h.family().key().equals(openFamily)) {
                open = h.family();
                openHit = h;
            }
        }
        if (open != null) {
            tip = drawDropdown(c, tr, catalog, open, openHit, px, pw, py, ph, mouseX, mouseY, dt);
        } else {
            dropW = 0;
            dropH = 0;
        }
        boolean overDrop = open != null && mouseX >= dropX && mouseX < dropX + dropW && mouseY >= dropY && mouseY < dropY + dropH;
        if (tip == null && hoveredBase != null && !overDrop) {
            tip = new Tooltip(null, hoveredBase, hoveredBase.variants().size() > 1 ? "Click for its rarities" : "Click for its price");
        }
        if (tip != null) {
            drawItemTooltip(c, tr, book, catalog, tip, mouseX, mouseY);
        }
    }

    private record Tooltip(PriceBook.@Nullable Entry entry, @Nullable Family family, String hint) {
    }

    private static @Nullable Tooltip drawDropdown(DrawContext c, TextRenderer tr, ItemCatalog catalog, Family f, Hit at,
                                                  int px, int pw, int py, int ph, int mouseX, int mouseY, float dt) {
        int accent = Ui.theme().accent();
        int n = f.variants().size();
        // As many as fit in a row of the panel, the rest in further rows.
        int perRow = Math.max(1, (pw - 16) / DROP_W);
        int rowsNeeded = (n + perRow - 1) / perRow;
        dropW = Math.min(n, perRow) * DROP_W + 8;
        dropH = rowsNeeded * (DROP_H - 6) + 8;
        dropX = Math.max(px + 4, Math.min(at.x() + CW / 2 - dropW / 2, px + pw - 4 - dropW));
        dropY = at.y() + CH;
        if (dropY + dropH > py + ph - FOOTER) {
            dropY = at.y() - dropH;
        }
        Ui.sprite(c, "market/panel_body", dropX + 2, dropY + 3, dropW, dropH, Ui.argb(70, 0x000000));
        Ui.sprite(c, "market/panel_body", dropX, dropY, dropW, dropH, Ui.argb(250, 0x10121C));
        Ui.sprite(c, "market/panel_frame", dropX, dropY, dropW, dropH, Ui.argb(235, accent));
        Tooltip tip = null;
        for (int i = 0; i < n; i++) {
            PriceBook.Entry e = f.variants().get(i);
            int vx = dropX + 4 + (i % perRow) * DROP_W;
            int vy = dropY + 4 + (i / perRow) * (DROP_H - 6);
            boolean over = mouseX >= vx && mouseX < vx + DROP_W && mouseY >= vy && mouseY < vy + DROP_H - 8;
            // (one variant per cell; rows are DROP_H - 6 apart)
            float a = hoverAnim.getOrDefault(e.key(), 0.0F);
            a += ((over ? 1.0F : 0.0F) - a) * Math.min(1.0F, dt * 14.0F);
            hoverAnim.put(e.key(), a);
            int lift = Math.round(a * 2.0F);
            if (a > 0.02F) {
                Ui.sprite(c, "market/glow", vx - 3, vy - 3 - lift, DROP_W + 6, 30, Ui.argb(Math.round(130 * a), accent));
            }
            Ui.sprite(c, "market/cell", vx + 1, vy - lift, DROP_W - 2, DROP_H - 10, over ? Ui.argb(150, accent) : Ui.argb(52, 0xFFFFFF));
            ItemStack stack = stackFor(catalog, e);
            c.getMatrices().pushMatrix();
            float scale = 1.0F + 0.18F * a;
            c.getMatrices().translate(vx + DROP_W / 2.0F, vy + 11 - lift);
            c.getMatrices().scale(scale, scale);
            c.drawItem(stack, -8, -8);
            c.getMatrices().popMatrix();
            double price = e.price();
            String label = price > 0 ? MarketScreen.shortMoney(price) : "–";
            boolean guess = e.source().equals("kind");
            Ui.drawCentered(c, tr, label, vx + DROP_W / 2, vy + 20 - lift, price > 0 ? (guess ? Ui.WARN : Ui.GOOD) : SUB, 255);
            if (over) {
                tip = new Tooltip(e, null, "");
            }
        }
        return tip;
    }

    /** The collapsed cell's texture: the lowest variant's real item, without a rarity frame. */
    private static void drawBase(DrawContext c, ItemCatalog catalog, Family f, int x, int y) {
        ItemStack stack = stackFor(catalog, f.variants().get(0));
        ItemLookModule.noFrame = true;
        try {
            c.drawItem(stack, x, y);
        } finally {
            ItemLookModule.noFrame = false;
        }
    }

    /** The real item of an entry (as the server sent it), else its plain icon with the name. */
    private static ItemStack stackFor(ItemCatalog catalog, PriceBook.Entry e) {
        // One stack object per entry: the item look caches by the identity of the stack's data, a new stack every frame
        // would redo the (regex heavy) name lookup for every cell, every frame.
        if (stackCacheCatalog != catalog.size()) {
            stackCache.clear();
            stackCacheCatalog = catalog.size();
        }
        ItemStack cached = stackCache.get(e.key());
        if (cached != null) {
            return cached;
        }
        ItemStack built = buildStack(catalog, e);
        stackCache.put(e.key(), built);
        return built;
    }

    private static final Map<String, ItemStack> stackCache = new HashMap<>();
    private static int stackCacheCatalog = -1;

    private static ItemStack buildStack(ItemCatalog catalog, PriceBook.Entry e) {
        ItemStack real = catalog.stack(e.key());
        if (real != null) {
            return real;
        }
        try {
            Identifier id = Identifier.of(e.icon().isBlank() ? "minecraft:paper" : e.icon());
            net.minecraft.item.Item item = Registries.ITEM.get(id);
            ItemStack stack = new ItemStack(item == net.minecraft.item.Items.AIR ? net.minecraft.item.Items.PAPER : item);
            ItemIdentity.Id sid = ItemIdentity.of(e.name(), null);
            Formatting colour = sid == null || sid.rarity() == null ? Formatting.WHITE : rarityColour(sid.rarity());
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(e.name()).setStyle(
                    net.minecraft.text.Style.EMPTY.withColor(colour).withItalic(false)));
            java.util.List<Text> lore = new java.util.ArrayList<>();
            lore.add(Text.empty());
            if (sid != null && sid.rarity() != null) {
                lore.add(Text.literal(sid.rarity().toUpperCase(Locale.ROOT) + " " + sid.family().toUpperCase(Locale.ROOT))
                        .setStyle(net.minecraft.text.Style.EMPTY.withColor(colour).withBold(true).withItalic(false)));
            }
            lore.add(Text.literal(e.category().replace("/", " · ")).setStyle(net.minecraft.text.Style.EMPTY.withColor(Formatting.GRAY).withItalic(false)));
            stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            String maskModel = ItemDirectory.maskModel(ItemIdentity.of(e.name(), null) != null ? e.name().replaceAll("^(?:Simple|Uncommon|Elite|Ultimate|Legendary|Godly) Mask$", "$0") : e.name());
            if (maskModel != null) {
                stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("theprisons", maskModel));
            }
            return stack;
        } catch (RuntimeException ex) {
            return new ItemStack(net.minecraft.item.Items.PAPER);
        }
    }

    /** Cosmic's tier colours (the name colour of a tiered item). */
    private static Formatting rarityColour(String rarity) {
        return switch (rarity) {
            case "Uncommon", "Unique" -> Formatting.GREEN;
            case "Elite" -> Formatting.AQUA;
            case "Ultimate" -> Formatting.YELLOW;
            case "Legendary" -> Formatting.GOLD;
            case "Godly" -> Formatting.RED;
            case "Mystic" -> Formatting.DARK_PURPLE;
            case "Heroic" -> Formatting.DARK_AQUA;
            case "Executive" -> Formatting.LIGHT_PURPLE;
            case "Simple" -> Formatting.WHITE;
            default -> Formatting.AQUA; // kits
        };
    }

    /** The item as in the game (name, colours, lore, the mod's tooltip frame) with the market lines added. */
    private static void drawItemTooltip(DrawContext c, TextRenderer tr, PriceBook book, ItemCatalog catalog, Tooltip tip, int mouseX, int mouseY) {
        if (tip.entry() == null) {
            drawFamilyTooltip(c, tr, tip, mouseX, mouseY);
            return;
        }
        PriceBook.Entry e = tip.entry();
        ItemStack stack = stackFor(catalog, e);
        MinecraftClient client = MinecraftClient.getInstance();
        List<Text> lines = new ArrayList<>(Screen.getTooltipFromItem(client, stack));
        lines.add(Text.empty());
        double price = e.price() > 0 ? e.price() : ShopValue.item(e.name(), book);
        boolean guess = e.price() <= 0 || e.source().equals("kind");
        if (price > 0) {
            String how = e.price() > 0 ? "  " + switch (e.source()) {
                case "sold" -> "last sale ";
                case "kind" -> "lowest of its kind ";
                default -> "lowest BIN ";
            } + age(System.currentTimeMillis() - e.seenMs()) : "  average of its kind";
            lines.add(Text.literal((guess ? "~$" : "$") + Money.compact(price)).formatted(guess ? Formatting.YELLOW : Formatting.GREEN)
                    .append(Text.literal(how).formatted(Formatting.DARK_GRAY)));
            double energy = book.inEnergy(price);
            if (energy > 0) {
                lines.add(Text.literal("≈ " + Money.compact(energy) + " energy").formatted(Formatting.AQUA));
            }
        } else {
            lines.add(Text.literal("no price yet").formatted(Formatting.GRAY));
        }
        if (!tip.hint().isEmpty()) {
            lines.add(Text.literal(tip.hint()).formatted(Formatting.DARK_GRAY));
        }
        c.drawTooltip(tr, lines, Optional.empty(), mouseX, mouseY,
                ItemLookModule.tooltipStyle(stack, stack.get(DataComponentTypes.TOOLTIP_STYLE)));
    }

    /** The base cell: just the family ("Shard"), how many rarities and the cheapest price. */
    private static void drawFamilyTooltip(DrawContext c, TextRenderer tr, Tooltip tip, int mouseX, int mouseY) {
        Family f = tip.family();
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(f.name()).formatted(Formatting.WHITE, Formatting.BOLD));
        int n = f.variants().size();
        ItemIdentity.Id first = ItemIdentity.of(f.variants().get(0).name(), null);
        ItemIdentity.Id last = ItemIdentity.of(f.variants().get(n - 1).name(), null);
        if (n > 1 && first != null && last != null && first.rarity() != null && last.rarity() != null) {
            lines.add(Text.literal(first.rarity() + " – " + last.rarity() + " · " + n + " rarities").formatted(Formatting.GRAY));
        }
        lines.add(Text.literal(f.group().label + " · " + f.kind()).formatted(Formatting.DARK_GRAY));
        double lowest = f.lowest();
        if (lowest > 0.0D) {
            lines.add(Text.literal("from $" + Money.compact(lowest)).formatted(Formatting.GREEN));
        }
        if (!tip.hint().isEmpty()) {
            lines.add(Text.literal(tip.hint()).formatted(Formatting.DARK_GRAY));
        }
        c.drawTooltip(tr, lines, Optional.empty(), mouseX, mouseY);
    }

    private static void drawBar(DrawContext c, TextRenderer tr, int width, int height) {
        int accent = Ui.theme().accent();
        int x = width / 2 - BAR_W / 2;
        int y = barY(height);
        Ui.sprite(c, "market/tab", x - 1, y - 1, BAR_W + 2, BAR_H + 2, Ui.argb(open() ? 230 : 140, accent));
        Ui.sprite(c, "market/tab", x, y, BAR_W, BAR_H, Ui.argb(240, 0x0B0C14));
        Ui.sprite(c, "market/nav_search", x + 6, y + 4, 10, 10, Ui.argb(255, accent));
        boolean blink = (System.currentTimeMillis() / 450L) % 2 == 0;
        String text;
        int colour;
        if (query.isEmpty()) {
            text = (blink ? "|" : " ") + (showAll ? " All items · double-click: off" : " Search Cosmic items…");
            colour = SUB;
        } else {
            String shown = query;
            while (shown.length() > 3 && Ui.width(tr, shown) > BAR_W - 34) {
                shown = shown.substring(1);
            }
            text = shown + (blink ? "|" : "");
            colour = Ui.VALUE;
        }
        Ui.draw(c, tr, text, x + 21, y + 5, colour, 255);
    }

    public static String age(long ms) {
        if (ms < 0) return "now";
        Duration d = Duration.ofMillis(ms);
        if (d.toDays() > 0) return d.toDays() + "d ago";
        if (d.toHours() > 0) return d.toHours() + "h ago";
        if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
        return Math.max(1, d.toSeconds()) + "s ago";
    }
}
