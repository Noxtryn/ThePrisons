package io.theprisons.modules.qol.storage;

import io.theprisons.mixin.ThePrisonsSlotAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The storage overlay: one card per private vault (black, 60 % by default, light blue "PV n" title, pink line between
 * title and page), three per row in the middle of the screen, the inventory and the hotbar below, nothing else - no blur, no
 * dimming, the game stays fully visible around the cards.
 *
 * <p>The screen is a real {@link HandledScreen} of the open vault (or of the player inventory while no vault is
 * open): the vault's slots are moved into its card and the hotbar slots into the bottom card, every other slot is
 * parked off-screen. Picking up, placing, stacking, shift-clicking, dragging, double-clicking and number keys are
 * therefore exactly vanilla. Cards of other pages show what the vault held when last seen; clicking one opens it.
 */
public final class StorageOverlayScreen extends HandledScreen<ScreenHandler> implements io.theprisons.gui.kit.HidesHud {
    private static final Identifier NEBULA = Identifier.of("theprisons", "boxy");
    private static final Identifier UNIFORM = Identifier.ofVanilla("uniform");
    private static final int SLOT = 18;
    private static final int PAD = 7;
    private static final int HEADER = 17;
    private static final int GRID_TOP = HEADER + 5;
    private static final int CARD_W = PAD * 2 + 9 * SLOT;
    private static final int GAP = 10;
    private static final int TOP = 36;
    private static final int HOTBAR_H = SLOT + 10;
    private static final int INVENTORY_H = GRID_TOP + 3 * SLOT + PAD - 1;
    private static final int HIDDEN = -10_000;

    private static final int MUTED = 0xAEB6C6;
    private static final int CELL = 0x1CFFFFFF;

    private final StorageOverlayModule module;
    private final int page;
    private final Map<Slot, int[]> originalPositions = new IdentityHashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private boolean replaced;
    private boolean consumedPress;
    private int ticksOpen;
    private long lastFrameMs;
    private int viewTop;
    private int viewBottom;
    private int gridX;
    private int gridW;
    private int hotbarX;
    private int hotbarY;
    private int inventoryY;
    private boolean showInventory;
    private int cardBg;
    private int cardBgHover;
    private int rescanX;
    private int rescanW;
    private @Nullable ItemStack hoveredCached;
    private String hint = "";
    private long hintUntilMs;

    private record Card(int page, int x, int y, int h, int rows, float appear, float hover) {
        int gridY() {
            return y + GRID_TOP;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + CARD_W && my >= y && my < y + h;
        }
    }

    StorageOverlayScreen(StorageOverlayModule module, ScreenHandler handler, int page) {
        super(handler, MinecraftClient.getInstance().player.getInventory(), Text.literal("Private Vaults"));
        this.module = module;
        this.page = page;
        for (Slot slot : handler.slots) {
            originalPositions.put(slot, new int[]{slot.x, slot.y});
        }
    }

    /** The open vault page, 0 when only the cards are shown. */
    int page() {
        return page;
    }

    /** The screen is swapped for the next page (the overlay stays open). */
    void markReplaced() {
        replaced = true;
    }

    @Override
    protected void init() {
        super.init();
        x = 0;
        y = 0;
        backgroundWidth = width;
        backgroundHeight = height;
        lastFrameMs = Util.getMeasuringTimeMs();
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private int rowsOf(int vault) {
        if (vault == page && handler instanceof GenericContainerScreenHandler generic) {
            return generic.getRows();
        }
        VaultCache.Page cached = module.cachedPage(vault);
        return cached != null ? cached.rows() : module.typicalRows();
    }

    private static int cardHeight(int rows) {
        return GRID_TOP + rows * SLOT + PAD - 1;
    }

    private void layout(int mouseX, int mouseY) {
        long now = Util.getMeasuringTimeMs();
        float dt = MathHelper.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;
        StorageOverlayModule.OverlayState state = module.state();
        boolean animate = module.animations();

        int count = module.vaultCount();
        int fit = Math.max(1, (width - 32 + GAP) / (CARD_W + GAP));
        int cols = Math.max(1, Math.min(module.columns(), fit));
        gridW = cols * CARD_W + (cols - 1) * GAP;
        gridX = (width - gridW) / 2;
        hotbarY = height - HOTBAR_H - 6;
        hotbarX = (width - CARD_W) / 2;
        showInventory = module.showInventory();
        inventoryY = hotbarY - INVENTORY_H - 6;
        cardBg = module.backgroundAlpha();
        cardBgHover = Math.min(255, cardBg + 0x1C);
        viewTop = TOP;
        viewBottom = (showInventory ? inventoryY : hotbarY) - 8;

        int contentH = 0;
        for (int first = 1; first <= count; first += cols) {
            int rowH = 0;
            for (int c = 0; c < cols && first + c <= count; c++) {
                rowH = Math.max(rowH, cardHeight(rowsOf(first + c)));
            }
            contentH += rowH + GAP;
        }
        contentH = Math.max(0, contentH - GAP);
        double maxScroll = Math.max(0, contentH - (viewBottom - viewTop));
        state.scrollTarget = MathHelper.clamp(state.scrollTarget, 0.0D, maxScroll);
        state.scroll = animate ? state.scroll + (state.scrollTarget - state.scroll) * Math.min(1.0F, dt * 14.0F) : state.scrollTarget;
        if (Math.abs(state.scroll - state.scrollTarget) < 0.3D) {
            state.scroll = state.scrollTarget;
        }
        // Centre the grid vertically while it fits.
        int offsetY = contentH < viewBottom - viewTop ? (viewBottom - viewTop - contentH) / 3 : 0;

        cards.clear();
        int rowY = viewTop + offsetY - (int) Math.round(state.scroll);
        int index = 0;
        for (int first = 1; first <= count; first += cols) {
            int rowH = 0;
            for (int c = 0; c < cols && first + c <= count; c++) {
                int vault = first + c;
                int rows = rowsOf(vault);
                int h = cardHeight(rows);
                rowH = Math.max(rowH, h);
                float appear = 1.0F;
                if (animate) {
                    long delay = Math.min(index, 15) * 35L;
                    float p = MathHelper.clamp((now - state.openedMs - delay) / 280.0F, 0.0F, 1.0F);
                    appear = 1.0F - (1.0F - p) * (1.0F - p) * (1.0F - p);
                }
                int cx = gridX + c * (CARD_W + GAP);
                boolean over = mouseX >= cx && mouseX < cx + CARD_W && mouseY >= rowY && mouseY < rowY + h
                        && mouseY >= viewTop && mouseY < viewBottom;
                float hover = state.hover.getOrDefault(vault, 0.0F);
                float target = over ? 1.0F : 0.0F;
                hover = animate ? hover + (target - hover) * Math.min(1.0F, dt * 12.0F) : target;
                state.hover.put(vault, hover);
                int lift = animate ? Math.round(hover * 2.0F) : 0;
                int cy = rowY + Math.round((1.0F - appear) * 14.0F) - lift;
                cards.add(new Card(vault, cx, cy, h, rows, appear, hover));
                index++;
            }
            rowY += rowH + GAP;
        }
        placeSlots();
    }

    private @Nullable Card activeCard() {
        for (Card card : cards) {
            if (card.page() == page) {
                return card;
            }
        }
        return null;
    }

    private boolean isVaultSlot(Slot slot) {
        return handler instanceof GenericContainerScreenHandler generic && slot.inventory == generic.getInventory();
    }

    /** The 27 slots of the main inventory (above the hotbar). */
    private static boolean isMainInventorySlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory && slot.getIndex() >= 9 && slot.getIndex() < 36;
    }

    private static boolean isHotbarSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory && slot.getIndex() < 9;
    }

    /** Moves the vault slots into the active card, the hotbar into the bottom card and parks everything else. */
    private void placeSlots() {
        Card active = page > 0 ? activeCard() : null;
        for (Slot slot : handler.slots) {
            int sx = HIDDEN;
            int sy = HIDDEN;
            if (isVaultSlot(slot)) {
                int index = slot.getIndex();
                if (active != null && active.appear() > 0.3F) {
                    int cx = active.x() + PAD + (index % 9) * SLOT + 1;
                    int cy = active.gridY() + (index / 9) * SLOT + 1;
                    if (cy + 16 > viewTop && cy < viewBottom) {
                        sx = cx;
                        sy = cy;
                    }
                }
            } else if (showInventory && isMainInventorySlot(slot)) {
                int index = slot.getIndex() - 9;
                sx = hotbarX + PAD + (index % 9) * SLOT + 1;
                sy = inventoryY + GRID_TOP + (index / 9) * SLOT + 1;
            } else if (isHotbarSlot(slot)) {
                sx = hotbarX + PAD + slot.getIndex() * SLOT + 1;
                sy = hotbarY + 6;
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
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // No blur and no darkening: only the cards are drawn over the game.
        layout(mouseX, mouseY);
        hoveredCached = null;
        drawHeader(context, mouseX, mouseY);
        context.enableScissor(0, viewTop, width, viewBottom);
        for (Card card : cards) {
            if (card.y() + card.h() > viewTop && card.y() < viewBottom) {
                drawCard(context, card, mouseX, mouseY);
            }
        }
        context.disableScissor();
        if (cards.isEmpty()) {
            String text = module.scanning() ? "Scanning your vaults…" : "No private vaults found";
            drawCentered(context, sleek(text), width / 2, (viewTop + viewBottom) / 2 - 4, argb(255, MUTED));
        }
        if (showInventory) {
            drawInventoryCard(context);
        }
        drawHotbarCard(context);
    }

    @Override
    protected void drawBackground(DrawContext context, float deltaTicks, int mouseX, int mouseY) {
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        drawMouseoverTooltip(context, mouseX, mouseY);
        ItemStack cached = hoveredCached;
        if (cached != null && handler.getCursorStack().isEmpty() && focusedSlot == null) {
            context.drawTooltip(textRenderer, getTooltipFromItem(cached), cached.getTooltipData(), mouseX, mouseY,
                    io.theprisons.modules.qol.items.ItemLookModule.tooltipStyle(cached,
                            cached.get(net.minecraft.component.DataComponentTypes.TOOLTIP_STYLE)));
        }
    }

    @Override
    protected void drawSlots(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(0, viewTop, width, viewBottom);
        for (Slot slot : handler.slots) {
            if (slot.isEnabled() && slot.x != HIDDEN && isVaultSlot(slot)) {
                drawSlot(context, slot, mouseX, mouseY);
            }
        }
        context.disableScissor();
        for (Slot slot : handler.slots) {
            if (slot.isEnabled() && slot.x != HIDDEN && !isVaultSlot(slot)) {
                drawSlot(context, slot, mouseX, mouseY);
            }
        }
    }

    private void drawHeader(DrawContext context, int mouseX, int mouseY) {
        int count = module.vaultCount();
        int top = TOP - 22;
        io.theprisons.gui.kit.Ui.icon(context, "storage_overlay", gridX, top - 2, 12, 1.0F);
        context.drawText(textRenderer, sleek("PRIVATE VAULTS"), gridX + 15, top, argb(255, io.theprisons.gui.kit.Ui.theme().title()), true);
        String right = module.statusText();
        if (right.isEmpty()) {
            right = module.scanning() ? "Scanning…" : count == 1 ? "1 vault" : count + " vaults";
        }
        String rescan = "⟳ Rescan";
        rescanW = textRenderer.getWidth(sleek(rescan));
        rescanX = gridX + gridW - rescanW;
        boolean overRescan = mouseX >= rescanX && mouseX < rescanX + rescanW && mouseY >= top - 2 && mouseY < top + 10;
        context.drawText(textRenderer, sleek(rescan), rescanX, top, argb(255, overRescan ? io.theprisons.gui.kit.Ui.theme().accent() : MUTED), true);
        int rightW = textRenderer.getWidth(sleek(right));
        context.drawText(textRenderer, sleek(right), rescanX - 10 - rightW, top, argb(255, MUTED), true);
        String hintNow = Util.getMeasuringTimeMs() < hintUntilMs ? hint : "";
        if (!hintNow.isEmpty()) {
            drawCentered(context, sleek(hintNow), width / 2, top + 11, argb(255, io.theprisons.gui.kit.Ui.theme().accent()));
        }
        context.fill(gridX, TOP - 8, gridX + gridW, TOP - 7, argb(110, io.theprisons.gui.kit.Ui.theme().accent()));
    }

    private void drawCard(DrawContext context, Card card, int mouseX, int mouseY) {
        float a = card.appear();
        if (a <= 0.01F) {
            return;
        }
        boolean live = card.page() == page;
        boolean loading = card.page() == module.pendingPage();
        int x = card.x();
        int y = card.y();
        int bg = Math.round(cardBg + (cardBgHover - cardBg) * Math.max(card.hover(), live ? 1.0F : 0.0F));
        context.fill(x, y, x + CARD_W, y + card.h(), Math.round(bg * a) << 24);
        if (live) {
            outline(context, x, y, CARD_W, card.h(), argb(Math.round(220 * a), io.theprisons.gui.kit.Ui.theme().accent()));
        } else if (card.hover() > 0.02F) {
            outline(context, x, y, CARD_W, card.h(), argb(Math.round(90 * card.hover() * a), io.theprisons.gui.kit.Ui.theme().accent()));
        }

        int textAlpha = Math.max(8, Math.round(255 * a));
        context.drawText(textRenderer, sleek("PV " + card.page()), x + PAD, y + 5, argb(textAlpha, io.theprisons.gui.kit.Ui.theme().title()), false);
        VaultCache.Page cached = module.cachedPage(card.page());
        String label;
        int labelColor = MUTED;
        if (live) {
            label = "● LIVE";
            labelColor = io.theprisons.gui.kit.Ui.theme().accent();
        } else if (loading) {
            label = "Loading…";
        } else if (cached != null) {
            label = cached.filled() + "/" + cached.items().size();
        } else {
            label = "";
        }
        if (!label.isEmpty()) {
            Text t = sleek(label);
            context.drawText(textRenderer, t, x + CARD_W - PAD - textRenderer.getWidth(t), y + 5, argb(textAlpha, labelColor), false);
        }
        // The pink line between the title and the page.
        context.fill(x + PAD - 2, y + HEADER, x + CARD_W - PAD + 2, y + HEADER + 1, argb(Math.round(200 * a), io.theprisons.gui.kit.Ui.theme().accent()));

        int gy = card.gridY();
        boolean showGrid = live || cached != null;
        if (showGrid) {
            for (int r = 0; r < card.rows(); r++) {
                for (int c = 0; c < 9; c++) {
                    int cx = x + PAD + c * SLOT;
                    int cy = gy + r * SLOT;
                    context.fill(cx + 1, cy + 1, cx + 17, cy + 17, scaleAlpha(CELL, a));
                }
            }
        }
        if (!live && cached != null && a > 0.3F) {
            List<ItemStack> items = cached.items();
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                int ix = x + PAD + (i % 9) * SLOT + 1;
                int iy = gy + (i / 9) * SLOT + 1;
                context.drawItem(stack, ix, iy);
                context.drawStackOverlay(textRenderer, stack, ix, iy);
                if (mouseX >= ix - 1 && mouseX < ix + 17 && mouseY >= iy - 1 && mouseY < iy + 17
                        && mouseY >= viewTop && mouseY < viewBottom) {
                    hoveredCached = stack;
                }
            }
        }
        int gridH = card.rows() * SLOT;
        if (!showGrid && !loading) {
            drawPlaceholder(context, x + CARD_W / 2, gy + gridH / 2, a);
        }
        if (loading) {
            float pulse = 0.5F + 0.5F * MathHelper.sin(Util.getMeasuringTimeMs() / 160.0F);
            context.fill(x + 1, gy, x + CARD_W - 1, gy + gridH, argb(Math.round(40 + 30 * pulse), 0));
            drawCentered(context, sleek("Opening PV " + card.page() + "…"), x + CARD_W / 2, gy + gridH / 2 - 4,
                    argb(Math.round(150 + 105 * pulse), io.theprisons.gui.kit.Ui.theme().title()));
        }
    }

    /** "(Click on this Page to view Items)" - small, monospace, in a code-style pill. */
    private void drawPlaceholder(DrawContext context, int centerX, int centerY, float alpha) {
        Text text = Text.literal("(Click on this Page to view Items)")
                .setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(UNIFORM)));
        float scale = 0.75F;
        int w = textRenderer.getWidth(text);
        int scaledW = Math.round(w * scale);
        int left = centerX - scaledW / 2;
        int top = centerY - 4;
        context.fill(left - 4, top - 3, left + scaledW + 4, top + 9, argb(Math.round(90 * alpha), 0));
        context.fill(left - 4, top - 3, left - 3, top + 9, argb(Math.round(200 * alpha), io.theprisons.gui.kit.Ui.theme().accent()));
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(left, top);
        context.getMatrices().scale(scale, scale);
        context.drawText(textRenderer, text, 0, 0, argb(Math.max(8, Math.round(230 * alpha)), MUTED), false);
        context.getMatrices().popMatrix();
    }

    /** The main inventory in the cards' look, centred between the vault cards and the hotbar. */
    private void drawInventoryCard(DrawContext context) {
        int x = hotbarX;
        int y = inventoryY;
        context.fill(x, y, x + CARD_W, y + INVENTORY_H, cardBg << 24);
        io.theprisons.gui.kit.Ui.icon(context, "satchel_hud", x + PAD, y + 3, 10, 1.0F);
        context.drawText(textRenderer, sleek("INVENTORY"), x + PAD + 13, y + 5, argb(255, io.theprisons.gui.kit.Ui.theme().title()), false);
        context.fill(x + PAD - 2, y + HEADER, x + CARD_W - PAD + 2, y + HEADER + 1, argb(200, io.theprisons.gui.kit.Ui.theme().accent()));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                int cx = x + PAD + c * SLOT;
                int cy = y + GRID_TOP + r * SLOT;
                context.fill(cx + 1, cy + 1, cx + 17, cy + 17, CELL);
            }
        }
    }

    private void drawHotbarCard(DrawContext context) {
        int x = hotbarX;
        int y = hotbarY;
        context.fill(x, y, x + CARD_W, y + HOTBAR_H, cardBg << 24);
        context.fill(x + PAD - 2, y, x + CARD_W - PAD + 2, y + 1, argb(200, io.theprisons.gui.kit.Ui.theme().accent()));
        int selected = client.player != null ? client.player.getInventory().getSelectedSlot() : -1;
        for (int c = 0; c < 9; c++) {
            int cx = x + PAD + c * SLOT;
            int cy = y + 5;
            context.fill(cx + 1, cy + 1, cx + 17, cy + 17, CELL);
            if (c == selected) {
                outline(context, cx, cy, 18, 18, argb(170, io.theprisons.gui.kit.Ui.theme().title()));
            }
        }
    }

    private void drawCentered(DrawContext context, Text text, int centerX, int y, int color) {
        context.drawText(textRenderer, text, centerX - textRenderer.getWidth(text) / 2, y, color, true);
    }

    private static void outline(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static Text sleek(String text) {
        Text plain = Text.literal(text);
        // the Design setting decides: no boxy font -> the game's own font
        return io.theprisons.modules.general.DesignModule.sleekFont()
                ? Text.literal(text).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(NEBULA))) : plain;
    }

    private static int argb(int alpha, int rgb) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int scaleAlpha(int color, float factor) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * factor);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    private @Nullable Card cardAt(double mx, double my) {
        if (my < viewTop || my >= viewBottom) {
            return null;
        }
        for (Card card : cards) {
            if (card.contains(mx, my)) {
                return card;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        consumedPress = false;
        double mx = click.x();
        double my = click.y();
        int headerTop = TOP - 24;
        if (click.button() == 0 && mx >= rescanX && mx < rescanX + rescanW && my >= headerTop && my < headerTop + 12) {
            module.requestRescan();
            consumedPress = true;
            return true;
        }
        Card card = cardAt(mx, my);
        if (card != null && card.page() != page) {
            consumedPress = true;
            if (!handler.getCursorStack().isEmpty()) {
                hint = "Put the held item down before switching pages";
                hintUntilMs = Util.getMeasuringTimeMs() + 2_500L;
                return true;
            }
            if (click.button() == 0 && card.page() != module.pendingPage()) {
                module.openPage(card.page());
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (consumedPress) {
            consumedPress = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        module.state().scrollTarget -= verticalAmount * 28.0D;
        return true;
    }

    /** Outside every card and the hotbar: a click there with an item on the cursor drops it, like vanilla. */
    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top) {
        if (mouseY < TOP) {
            return false;
        }
        if (showInventory && mouseX >= hotbarX && mouseX < hotbarX + CARD_W && mouseY >= inventoryY
                && mouseY < inventoryY + INVENTORY_H) {
            return false;
        }
        if (mouseX >= hotbarX && mouseX < hotbarX + CARD_W && mouseY >= hotbarY && mouseY < hotbarY + HOTBAR_H) {
            return false;
        }
        return cardAt(mouseX, mouseY) == null;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void handledScreenTick() {
        ticksOpen++;
        // The vault contents arrive right after the screen opens; do not store the empty first frame.
        if (page > 0 && ticksOpen > 3 && ticksOpen % 5 == 0) {
            module.snapshot(page, handler);
        }
    }

    @Override
    public void removed() {
        if (page > 0 && ticksOpen > 3) {
            module.snapshot(page, handler);
        }
        for (Map.Entry<Slot, int[]> entry : originalPositions.entrySet()) {
            move(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
        super.removed();
        if (!replaced) {
            module.onOverlayClosed();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
