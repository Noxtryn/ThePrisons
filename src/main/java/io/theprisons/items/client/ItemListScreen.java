package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.ItemCategory;
import io.theprisons.items.ItemEntry;
import io.theprisons.items.ItemListModel;
import io.theprisons.items.ItemView;
import io.theprisons.items.ItemsService;
import io.theprisons.items.VirtualGrid;
import io.theprisons.modules.FeatureProfile;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cosmic item list: categories on the left, a search field and (only where a family has them) tier chips on top, large cards in the middle, the
 * details on the right. The screen only draws and forwards input; the data comes from {@link ItemListModel} (a derived view recomputed only when the
 * query, the category, the tiers or the registry changed) and only the cards in the visible rows are drawn ({@link VirtualGrid}). Per frame it builds no
 * stack, parses no lore and sorts nothing.
 */
public final class ItemListScreen extends Screen {
    private static final int CARD_W = 92;
    private static final int CARD_H = 84;
    private static final int GAP = 8;
    private static final int SIDEBAR_W = 132;
    private static final int DETAIL_W = 214;
    // ruhig: blue-black panels, thin borders, cyan for hover, pink only for the selection
    private static final int BG = 0xF2080C14;
    private static final int PANEL = 0xFF0C111C;
    private static final int CARD = 0xFF101828;
    private static final int CARD_HOVER = 0xFF16223A;
    private static final int BORDER = 0xFF1C2740;
    private static final int CYAN = 0xFF3CC4E8;
    private static final int PINK = 0xFFFF6EC7;

    private final ItemsService items = ItemsService.get();
    private final ItemListModel model = items.list();
    private final VirtualGrid grid = new VirtualGrid(CARD_W, CARD_H, GAP, 2);
    private TextFieldWidget search;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int gx;
    private int gy;
    private int gw;
    private int gh;
    private boolean detailShown;
    private float scroll;
    private int scrollTarget;
    private long lastFrameNs;
    private @Nullable String selectedKey;
    private final Map<String, List<String>> wrapped = new HashMap<>();
    private final List<int[]> chipRects = new ArrayList<>();
    private final List<String> chipTiers = new ArrayList<>();
    private final List<int[]> categoryRects = new ArrayList<>();
    private final List<@Nullable ItemCategory> categoryOrder = new ArrayList<>();
    private Map<ItemCategory, Integer> counts = new EnumMap<>(ItemCategory.class);
    private long countsRevision = -1;
    private int drawnCards;

    public ItemListScreen() {
        super(Text.literal("Items"));
    }

    @Override
    protected void init() {
        pw = Math.min(width - 24, 1000);
        ph = Math.min(height - 24, 620);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        detailShown = pw >= 760;
        gx = px + SIDEBAR_W + 14;
        gy = py + 78;
        gw = pw - SIDEBAR_W - 28 - (detailShown ? DETAIL_W + 10 : 0);
        gh = ph - 78 - 22;
        String old = search == null ? "" : search.getText();
        search = new TextFieldWidget(textRenderer, gx + 8, py + 18, gw - 16, 14, Text.literal("Search"));
        search.setDrawsBackground(false);
        search.setMaxLength(48);
        search.setPlaceholder(Text.literal("Search items, categories, tiers ..."));
        search.setText(old);
        search.setChangedListener(q -> {
            model.setQuery(q);
            scrollTarget = 0;
        });
        addDrawableChild(search);
        setInitialFocus(search);
        wrapped.clear();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fill(0, 0, width, height, 0xB0040710);       // no blur, just dimmed
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016F : Math.min(0.1F, (now - lastFrameNs) / 1e9F);
        lastFrameNs = now;
        ItemView view = model.view();                   // two comparisons unless something changed
        scrollTarget = grid.clampScroll(scrollTarget, view.entries().size(), gw, gh);
        scroll = Ui.approach(scroll, scrollTarget, dt, 16.0F);
        int scrollPx = Math.round(scroll);

        Ui.round(c, px, py, pw, ph, BG);
        Ui.outline(c, px, py, pw, ph, BORDER);
        drawSidebar(c, mouseX, mouseY);
        drawHeader(c, view, mouseX, mouseY);

        int hover = -1;
        c.enableScissor(gx, gy, gx + gw, gy + gh);
        VirtualGrid.Range range = grid.visible(view.entries().size(), gw, gh, scrollPx);
        drawnCards = range.count();
        if (mouseX >= gx && mouseX < gx + gw && mouseY >= gy && mouseY < gy + gh) {
            hover = grid.indexAt(mouseX - gx, mouseY - gy, view.entries().size(), gw, scrollPx);
        }
        for (int i = range.first(); i >= 0 && i <= range.last(); i++) {
            int cx = gx + grid.x(i, gw);
            int cy = gy + grid.y(i, gw) - scrollPx;
            if (cy + CARD_H < gy || cy > gy + gh) {
                continue;                               // overscan rows are laid out but not drawn
            }
            drawCard(c, view.entries().get(i), cx, cy, i == hover);
        }
        c.disableScissor();
        if (view.entries().isEmpty()) {
            Ui.drawCentered(c, textRenderer, "No items match", gx + gw / 2, gy + 40, Ui.MUTED, 255);
        }
        drawScrollbar(c, view, scrollPx);

        ItemEntry shown = hover >= 0 ? view.entries().get(hover) : selected(view);
        if (detailShown) {
            drawDetail(c, shown, px + pw - DETAIL_W - 12, py + 14, DETAIL_W, ph - 28);
        } else if (hover >= 0) {
            c.drawTooltip(textRenderer, detailLines(shown), mouseX, mouseY);
        }
        if (FeatureProfile.DEV) {
            Ui.draw(c, textRenderer, "cards " + drawnCards + " / " + view.entries().size() + " (registry " + view.totalItems() + ")  search "
                    + view.searchNanos() / 1000 + " us  filter " + view.filterNanos() / 1000 + " us  computed " + model.computations() + "x  stacks " + ItemStacks.cached(),
                    gx, py + ph - 14, Ui.MUTED, 255);
        }
        super.render(c, mouseX, mouseY, delta);          // the search field
    }

    private @Nullable ItemEntry selected(ItemView view) {
        if (selectedKey == null) {
            return null;
        }
        for (ItemEntry e : view.entries()) {
            if (e.key().equals(selectedKey)) {
                return e;
            }
        }
        return items.registry().get(selectedKey);
    }

    private void drawSidebar(DrawContext c, int mouseX, int mouseY) {
        int x = px + 12;
        int y = py + 16;
        Ui.draw(c, textRenderer, "ITEMS", x + 6, y, Ui.theme().title(), 255);
        Ui.line(c, x, px + SIDEBAR_W, y + 14, 0.5F);
        if (countsRevision != items.registry().revision()) {
            counts = new EnumMap<>(ItemCategory.class);
            for (ItemEntry e : items.registry().all()) {
                counts.merge(e.category(), 1, Integer::sum);
            }
            countsRevision = items.registry().revision();
        }
        categoryRects.clear();
        categoryOrder.clear();
        y += 24;
        List<@Nullable ItemCategory> rows = new ArrayList<>();
        rows.add(null);
        for (ItemCategory cat : ItemCategory.values()) {
            rows.add(cat);
        }
        for (ItemCategory cat : rows) {
            int w = SIDEBAR_W - 12;
            boolean on = model.filter().category() == cat;
            boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
            if (on) {
                Ui.round(c, x, y, w, 20, 0xFF121B2E);
                c.fill(x, y + 3, x + 2, y + 17, CYAN);
            } else if (over) {
                Ui.round(c, x, y, w, 20, 0xFF0F1626);
            }
            String label = cat == null ? "All" : cat.label();
            int count = cat == null ? items.registry().size() : counts.getOrDefault(cat, 0);
            Ui.draw(c, textRenderer, label, x + 10, y + 6, on ? Ui.VALUE : Ui.LABEL, 255);
            Ui.drawRight(c, textRenderer, Integer.toString(count), x + w - 8, y + 6, Ui.MUTED, 255);
            categoryRects.add(new int[]{x, y, w, 20});
            categoryOrder.add(cat);
            y += 22;
        }
    }

    private void drawHeader(DrawContext c, ItemView view, int mouseX, int mouseY) {
        Ui.round(c, gx, py + 12, gw, 26, PANEL);
        Ui.outline(c, gx, py + 12, gw, 26, search.isFocused() ? CYAN : BORDER);
        search.setY(py + 18);
        chipRects.clear();
        chipTiers.clear();
        if (!view.tiers().isEmpty()) {          // a rarity row only where this category really has tiers
            int cx = gx;
            int cy = py + 46;
            for (String tier : view.tiers()) {
                int w = Ui.width(textRenderer, tier) + 16;
                boolean on = model.filter().tiers().contains(tier);
                int rgb = TierColors.rgb(tier);
                Ui.round(c, cx, cy, w, 18, on ? 0xFF000000 | Ui.mix(0x0F1626, rgb, 0.28F) : 0xFF0F1626);
                Ui.outline(c, cx, cy, w, 18, on ? 0xFF000000 | rgb : BORDER);
                Ui.draw(c, textRenderer, tier, cx + 8, cy + 5, on ? rgb : Ui.LABEL, 255);
                chipRects.add(new int[]{cx, cy, w, 18});
                chipTiers.add(tier);
                cx += w + 6;
            }
        }
        Ui.drawRight(c, textRenderer, view.entries().size() + " items", gx + gw, py + 50, Ui.MUTED, 255);
    }

    private void drawCard(DrawContext c, ItemEntry e, int x, int y, boolean hover) {
        boolean sel = e.key().equals(selectedKey);
        Ui.round(c, x, y, CARD_W, CARD_H, hover ? CARD_HOVER : CARD);
        Ui.outline(c, x, y, CARD_W, CARD_H, sel ? PINK : hover ? CYAN : BORDER);
        // a large icon: the texture is the focus of the card
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x + CARD_W / 2.0F - 16, y + 8);
        m.scale(2.0F, 2.0F);
        c.drawItem(ItemStacks.of(e), 0, 0);
        m.popMatrix();
        List<String> lines = wrap(e);
        int ty = y + 46;
        for (int i = 0; i < lines.size() && i < 2; i++) {
            Ui.drawCentered(c, textRenderer, lines.get(i), x + CARD_W / 2, ty, Ui.VALUE, 255);
            ty += 10;
        }
        String sub = e.tier() != null ? e.tier() : e.variant() != null ? e.variant() : e.category().label();
        Ui.drawCentered(c, textRenderer, sub, x + CARD_W / 2, y + CARD_H - 12, e.tier() != null ? TierColors.rgb(e.tier()) : Ui.MUTED, 255);
    }

    /** The card title in at most two lines, wrapped once per entry and layout. */
    private List<String> wrap(ItemEntry e) {
        return wrapped.computeIfAbsent(e.key(), k -> {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : e.displayName().split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (Ui.width(textRenderer, next) > CARD_W - 10 && line.length() > 0) {
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

    private void drawScrollbar(DrawContext c, ItemView view, int scrollPx) {
        int content = grid.contentHeight(view.entries().size(), gw);
        if (content <= gh) {
            return;
        }
        int barH = Math.max(24, gh * gh / content);
        int barY = gy + (int) ((long) (gh - barH) * scrollPx / Math.max(1, content - gh));
        c.fill(gx + gw + 3, gy, gx + gw + 5, gy + gh, 0xFF0F1626);
        c.fill(gx + gw + 3, barY, gx + gw + 5, barY + barH, 0xFF2D4466);
    }

    private void drawDetail(DrawContext c, @Nullable ItemEntry e, int x, int y, int w, int h) {
        Ui.round(c, x, y, w, h, PANEL);
        Ui.outline(c, x, y, w, h, BORDER);
        if (e == null) {
            Ui.drawCentered(c, textRenderer, "Select an item", x + w / 2, y + h / 2 - 4, Ui.MUTED, 255);
            return;
        }
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x + w / 2.0F - 24, y + 12);
        m.scale(3.0F, 3.0F);
        c.drawItem(ItemStacks.of(e), 0, 0);
        m.popMatrix();
        int ty = y + 68;
        Ui.drawCentered(c, textRenderer, e.displayName(), x + w / 2, ty, Ui.VALUE, 255);
        ty += 14;
        Ui.drawCentered(c, textRenderer, e.category().label() + " · " + e.subcategory(), x + w / 2, ty, Ui.LABEL, 255);
        ty += 16;
        if (e.tier() != null) {
            Ui.drawCentered(c, textRenderer, e.tier(), x + w / 2, ty, TierColors.rgb(e.tier()), 255);
            ty += 14;
        }
        if (e.meta().hasTiers()) {
            Ui.draw(c, textRenderer, "Tiers", x + 12, ty, Ui.MUTED, 255);
            ty += 12;
            int cx = x + 12;
            for (String t : e.meta().tiers()) {
                int tw = Ui.width(textRenderer, t) + 8;
                if (cx + tw > x + w - 12) {
                    cx = x + 12;
                    ty += 11;
                }
                Ui.draw(c, textRenderer, t, cx, ty, TierColors.rgb(t), t.equals(e.tier()) ? 255 : 150);
                cx += tw;
            }
            ty += 16;
        }
        if (!e.meta().variants().isEmpty()) {
            Ui.draw(c, textRenderer, "Variants: " + String.join(", ", e.meta().variants()), x + 12, ty, Ui.LABEL, 255);
            ty += 14;
        }
        List<String> market = items.marketDetail(e.key());
        Ui.line(c, x + 10, x + w - 10, ty, 0.35F);
        ty += 8;
        if (market.isEmpty()) {
            Ui.draw(c, textRenderer, "No market data yet", x + 12, ty, Ui.MUTED, 255);
        } else {
            for (String line : market) {
                Ui.draw(c, textRenderer, line, x + 12, ty, Ui.LABEL, 255);
                ty += 11;
            }
        }
        if (FeatureProfile.DEV) {
            Ui.draw(c, textRenderer, e.internalName() + " · " + e.identity().confidence(), x + 12, y + h - 24, Ui.MUTED, 200);
            Ui.draw(c, textRenderer, e.key(), x + 12, y + h - 13, Ui.MUTED, 150);
        }
    }

    private List<Text> detailLines(@Nullable ItemEntry e) {
        List<Text> out = new ArrayList<>();
        if (e != null) {
            out.add(Text.literal(e.displayName()));
            out.add(Text.literal(e.category().label() + " · " + e.subcategory()));
            if (e.tier() != null) {
                out.add(Text.literal(e.tier()));
            }
            for (String l : items.marketDetail(e.key())) {
                out.add(Text.literal(l));
            }
        }
        return out;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        for (int i = 0; i < categoryRects.size(); i++) {
            int[] r = categoryRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                model.setCategory(categoryOrder.get(i));
                scrollTarget = 0;
                return true;
            }
        }
        for (int i = 0; i < chipRects.size(); i++) {
            int[] r = chipRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                model.toggleTier(chipTiers.get(i));
                scrollTarget = 0;
                return true;
            }
        }
        if (mx >= gx && mx < gx + gw && my >= gy && my < gy + gh) {
            ItemView view = model.view();
            int idx = grid.indexAt((int) mx - gx, (int) my - gy, view.entries().size(), gw, Math.round(scroll));
            if (idx >= 0) {
                selectedKey = view.entries().get(idx).key();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollTarget = grid.clampScroll(scrollTarget - (int) Math.round(verticalAmount * 54), model.view().entries().size(), gw, gh);
        return true;
    }
}
