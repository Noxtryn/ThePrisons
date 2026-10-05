package io.theprisons.modules.qol.market;

import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.client.TextStrip;
import io.theprisons.gui.kit.HidesHud;
import io.theprisons.gui.kit.Ui;
import io.theprisons.mixin.ThePrisonsSlotAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Ground Zero ({@code /gz}) and Prison Break ({@code /pb}) menus and their shops in the mod's design: the server's
 * chest menu with its real slots in a clean grid (the decoration panes are not drawn), a header with the points
 * balance and the shop's reset clock, and under it what the offer under the mouse is worth - in money, in energy and
 * per point - with the best offers outlined. Clicks and tooltips are the server's own.
 *
 * <p>The valuation ({@link ShopView}) runs on the background thread: it reads a snapshot of the price book and is
 * only redone when the menu or the book changed, so the screen never waits for it.</p>
 */
public final class ShopScreen extends HandledScreen<GenericContainerScreenHandler> implements HidesHud {
    private static final int CW = 20;
    private static final int PAD = 10;
    private static final int GRID_W = 9 * CW;
    private static final int PANEL_W = GRID_W + PAD * 2;
    private static final int HEADER_H = 34;
    private static final int INFO_H = 40;
    private static final int HIDDEN = -10_000;
    private static final int SUB = MarketScreen.SUB;

    private final MarketModule module;
    private final Text menuTitle;
    private final Map<Slot, int[]> original = new IdentityHashMap<>();
    private final int rows;
    private int top;
    private int left;

    private volatile ShopView.Model model = ShopView.Model.EMPTY;
    private long builtAt;
    private int builtSignature = Integer.MIN_VALUE;
    private int builtBook = Integer.MIN_VALUE;
    private boolean building;

    ShopScreen(MarketModule module, GenericContainerScreenHandler handler, Text title) {
        super(handler, MinecraftClient.getInstance().player.getInventory(), title);
        this.module = module;
        this.menuTitle = title;
        this.rows = handler.getRows();
        for (Slot slot : handler.slots) {
            original.put(slot, new int[]{slot.x, slot.y});
        }
    }

    @Override
    protected void init() {
        super.init();
        x = 0;
        y = 0;
        backgroundWidth = width;
        backgroundHeight = height;
    }

    private String title() {
        return TextStrip.strip(menuTitle.getString());
    }

    // ── Background valuation ─────────────────────────────────────────────────

    @Override
    protected void handledScreenTick() {
        int signature = 0;
        int menu = rows * 9;
        for (int i = 0; i < menu && i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            signature = signature * 31 + System.identityHashCode(stack) + stack.getCount();
        }
        PriceBook book = module.book();
        if (building || (signature == builtSignature && book.version() == builtBook)) {
            return;
        }
        // The slots are read here (client thread); the valuation itself runs in the background.
        List<MarketParser.Item> items = new ArrayList<>();
        for (int i = 0; i < menu && i < handler.slots.size(); i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            items.add(new MarketParser.Item(i, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                    TextStrip.strip(stack.getName().getString()), ClientReadouts.lore(stack), MarketModule.customId(stack)));
        }
        builtSignature = signature;
        builtBook = book.version();
        building = true;
        PriceBook snapshot = book.snapshot();
        String title = title();
        MarketWork.run("shop " + title, () -> ShopView.build(title, items, snapshot), result -> {
            model = result;
            builtAt = System.currentTimeMillis();
            building = false;
        });
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private int panelH() {
        return HEADER_H + rows * CW + INFO_H + PAD;
    }

    private int gridY() {
        return top + HEADER_H;
    }

    private void layout() {
        left = (width - PANEL_W) / 2;
        top = Math.max(2, (height - panelH()) / 2);
        for (Slot slot : handler.slots) {
            int sx = HIDDEN;
            int sy = HIDDEN;
            if (slot.inventory == handler.getInventory()) {
                int i = slot.getIndex();
                ItemStack stack = slot.getStack();
                boolean pane = !stack.isEmpty() && ShopView.decoration(Registries.ITEM.getId(stack.getItem()).toString(),
                        TextStrip.strip(stack.getName().getString()));
                if (i < rows * 9 && !pane) {
                    sx = left + PAD + (i % 9) * CW + 2;
                    sy = gridY() + (i / 9) * CW + 2;
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
        int ph = panelH();
        Ui.sprite(c, "market/panel_body", left + 3, top + 4, PANEL_W, ph, Ui.argb(60, 0x000000));
        Ui.sprite(c, "market/panel_body", left, top, PANEL_W, ph, Ui.argb(232, 0x0B0C14));
        Ui.sprite(c, "market/panel_frame", left, top, PANEL_W, ph, Ui.argb(210, accent));
        ShopView.Model m = model;
        Ui.shimmer(c, tr, title().toUpperCase(java.util.Locale.ROOT), left + PAD, top + 9, 1.0F);
        if (!m.balance().isEmpty()) {
            Ui.drawRight(c, tr, m.balance(), left + PANEL_W - PAD, top + 9, Ui.GOOD, 255);
        }
        if (m.resetMs() >= 0L) {
            long remaining = Math.max(0L, m.resetMs() - (System.currentTimeMillis() - builtAt));
            Ui.draw(c, tr, "Shop resets in " + ShopView.clock(remaining), left + PAD, top + 21, SUB, 255);
        }
        for (Slot slot : handler.slots) {
            if (slot.inventory != handler.getInventory() || slot.x == HIDDEN || slot.getIndex() >= rows * 9) {
                continue;
            }
            int cx = slot.x - 2;
            int cy = slot.y - 2;
            ShopView.Entry e = m.bySlot().get(slot.getIndex());
            boolean best = e != null && e.best();
            Ui.sprite(c, "market/cell", cx + 1, cy + 1, CW - 2, CW - 2,
                    best ? Ui.argb(120, Ui.GOOD) : Ui.argb(48, 0xFFFFFF));
        }
        drawInfo(c, tr, m);
    }

    /** What the offer under the mouse is worth. */
    private void drawInfo(DrawContext c, TextRenderer tr, ShopView.Model m) {
        int x = left + PAD;
        int y = gridY() + rows * CW + 6;
        Slot hovered = focusedSlot;
        ShopView.Entry e = hovered == null ? null : m.bySlot().get(hovered.getIndex());
        if (e == null || hovered.inventory != handler.getInventory()) {
            if (m.shop() && !m.bestName().isEmpty()) {
                Ui.draw(c, tr, "Best value:", x, y, SUB, 255);
                Ui.draw(c, tr, m.bestName(), x + Ui.width(tr, "Best value: "), y, Ui.GOOD, 255);
                Ui.draw(c, tr, "Outlined offers give the most money per point.", x, y + 12, SUB, 255);
            } else if (m.shop()) {
                Ui.draw(c, tr, "Hover an offer to see what it is worth.", x, y, SUB, 255);
            } else {
                Ui.draw(c, tr, "Open the shop to see what the offers are worth.", x, y, SUB, 255);
            }
            return;
        }
        Ui.draw(c, tr, e.name(), x, y, Ui.theme().title(), 255);
        String stock = e.stock().isEmpty() ? "" : "  ·  " + e.stock();
        Ui.draw(c, tr, String.format(java.util.Locale.ROOT, "%,d points", e.points()) + stock, x, y + 11, SUB, 255);
        String worth = e.worth() < 0.0D ? "Worth: unknown yet" : "Worth ≈ " + module.worth(e.worth());
        Ui.draw(c, tr, worth, x, y + 22, e.worth() < 0.0D ? SUB : Ui.GOOD, 255);
        if (e.perPoint() > 0.0D) {
            Ui.drawRight(c, tr, "$" + Money.compact(e.perPoint()) + " / point", left + PANEL_W - PAD, y + 22,
                    e.best() ? Ui.GOOD : Ui.WARN, 255);
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float deltaTicks, int mouseX, int mouseY) {
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
    }

    @Override
    protected void drawSlots(DrawContext context, int mouseX, int mouseY) {
        for (Slot slot : handler.slots) {
            if (slot.isEnabled() && slot.x != HIDDEN) {
                drawSlot(context, slot, mouseX, mouseY);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        drawMouseoverTooltip(context, mouseX, mouseY);
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
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
