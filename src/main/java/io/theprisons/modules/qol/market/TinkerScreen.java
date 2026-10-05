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
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Tinkerer ({@code /tinker}) in the mod's design: the server's chest menu with a status card on top (what the
 * offer would turn into, with the accept button), the offer area as a 9 x 4 grid and your own inventory below - all of
 * it real slots of the server's menu moved into the layout, so offering (click / shift-click) and accepting are the
 * server's own.
 *
 * <p>Layout of the server's menu (recorded 2026-10-05): slot 4 = the status / accept button ("NOTHING OFFERED" or
 * "ACCEPT (Click)" with the lines "Tinkerer will transform offer into: * 2,500 Cosmic Energy"), slots 18-53 = the offer
 * area, the rest is panes.</p>
 */
public final class TinkerScreen extends HandledScreen<GenericContainerScreenHandler> implements HidesHud {
    static final String TITLE = "Tinkerer";
    private static final int CW = 20;
    private static final int PAD = 10;
    private static final int GRID_W = 9 * CW;
    private static final int PANEL_W = GRID_W + PAD * 2;
    private static final int PANEL_H = 272;
    private static final int STATUS_SLOT = 4;
    private static final int OFFER_FIRST = 18;
    private static final int HIDDEN = -10_000;
    private static final int SUB = MarketScreen.SUB;

    private final Map<Slot, int[]> original = new IdentityHashMap<>();
    private int top;
    private int left;

    TinkerScreen(GenericContainerScreenHandler handler, Text title) {
        super(handler, MinecraftClient.getInstance().player.getInventory(), title);
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

    // ── Layout ───────────────────────────────────────────────────────────────

    private int statusY() {
        return top + 26;
    }

    private int offerY() {
        return top + 82;
    }

    private int inventoryY() {
        return offerY() + 4 * CW + 16;
    }

    private int hotbarY() {
        return inventoryY() + 3 * CW + 5;
    }

    private void layout() {
        left = (width - PANEL_W) / 2;
        top = Math.max(2, (height - PANEL_H) / 2);
        int gx = left + PAD;
        for (Slot slot : handler.slots) {
            int sx = HIDDEN;
            int sy = HIDDEN;
            int i = slot.getIndex();
            if (slot.inventory == handler.getInventory()) {
                if (i == STATUS_SLOT) {
                    sx = gx + 7;
                    sy = statusY() + 15;
                } else if (i >= OFFER_FIRST) {
                    int k = i - OFFER_FIRST;
                    sx = gx + (k % 9) * CW + 2;
                    sy = offerY() + (k / 9) * CW + 2;
                }
            } else if (slot.inventory instanceof PlayerInventory) {
                if (i >= 9 && i < 36) {
                    sx = gx + ((i - 9) % 9) * CW + 2;
                    sy = inventoryY() + ((i - 9) / 9) * CW + 2;
                } else if (i < 9) {
                    sx = gx + i * CW + 2;
                    sy = hotbarY() + 2;
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

    private ItemStack statusStack() {
        return handler.slots.size() > STATUS_SLOT ? handler.slots.get(STATUS_SLOT).getStack() : ItemStack.EMPTY;
    }

    private boolean acceptReady() {
        String name = TextStrip.strip(statusStack().getName().getString()).toUpperCase(Locale.ROOT);
        return name.startsWith("ACCEPT");
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float deltaTicks) {
        layout();
        TextRenderer tr = textRenderer;
        int accent = Ui.theme().accent();
        Ui.sprite(c, "market/panel_body", left + 3, top + 4, PANEL_W, PANEL_H, Ui.argb(60, 0x000000));
        Ui.sprite(c, "market/panel_body", left, top, PANEL_W, PANEL_H, Ui.argb(232, 0x0B0C14));
        Ui.sprite(c, "market/panel_frame", left, top, PANEL_W, PANEL_H, Ui.argb(210, accent));
        MarketScreen.drawMenuIcon(c, "tinker", left + PAD - 1, top + 6);
        Ui.shimmer(c, tr, "TINKERER", left + PAD + 18, top + 11, 1.0F);
        drawStatus(c, tr, mouseX, mouseY);
        Ui.draw(c, tr, "OFFER", left + PAD + 2, offerY() - 10, SUB, 255);
        drawCells(c, offerY(), 4);
        Ui.draw(c, tr, "YOUR ITEMS", left + PAD + 2, inventoryY() - 10, SUB, 255);
        drawCells(c, inventoryY(), 3);
        drawCells(c, hotbarY(), 1);
    }

    private void drawCells(DrawContext c, int y, int rows) {
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < 9; col++) {
                Ui.sprite(c, "market/cell", left + PAD + col * CW + 1, y + r * CW + 1, CW - 2, CW - 2, Ui.argb(48, 0xFFFFFF));
            }
        }
    }

    /** What the offer turns into, and the accept button (the whole card is it while there is an offer). */
    private void drawStatus(DrawContext c, TextRenderer tr, int mouseX, int mouseY) {
        int x = left + PAD;
        int y = statusY();
        int h = 44;
        boolean ready = acceptReady();
        int accent = Ui.theme().accent();
        boolean over = ready && mouseX >= x && mouseX < x + GRID_W && mouseY >= y && mouseY < y + h;
        Ui.sprite(c, "market/button", x, y, GRID_W, h, over ? Ui.argb(160, accent) : Ui.argb(ready ? 70 : 40, ready ? accent : 0xFFFFFF));
        ItemStack status = statusStack();
        String name = TextStrip.strip(status.getName().getString());
        Ui.draw(c, tr, name, x + 8, y + 4, ready ? Ui.GOOD : Ui.BAD, 255);
        int lineY = y + 14;
        List<String> lines = new ArrayList<>();
        for (String raw : ClientReadouts.lore(status)) {
            String line = raw.strip();
            if (line.isEmpty() || line.matches("^-+$")) {
                continue;
            }
            lines.add(line);
        }
        for (String line : lines) {
            if (lineY > y + h - 8) {
                break;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("WARNING")) {
                continue;
            }
            // the server's long sentences, short
            if (upper.startsWith("TINKERER WILL TRANSFORM")) {
                line = "Offer becomes:";
            } else if (upper.contains("LOST FOREVER")) {
                line = "Offered items are lost!";
            }
            boolean gain = line.startsWith("*");
            boolean warn = upper.contains("LOST");
            int colour = gain ? Ui.WARN : warn ? Ui.BAD : SUB;
            // Under the invisible slot of the (not drawn) button item: start right of it
            String shown = line;
            while (shown.length() > 3 && Ui.width(tr, shown) > GRID_W - 36) {
                shown = shown.substring(0, shown.length() - 1);
            }
            Ui.draw(c, tr, shown, x + 28, lineY, colour, 255);
            lineY += 9;
        }
        if (ready) {
            Ui.sprite(c, "market/nav_energy", x + 8, y + 17, 14, 14, Ui.argb(255, 0xFFFFFF));
        } else {
            Ui.sprite(c, "market/nav_back", x + 8, y + 17, 14, 14, Ui.argb(160, SUB));
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float deltaTicks, int mouseX, int mouseY) {
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
    }

    /** The status item is not drawn (the card shows it); everything else is a normal slot. */
    @Override
    protected void drawSlots(DrawContext context, int mouseX, int mouseY) {
        for (Slot slot : handler.slots) {
            boolean status = slot.inventory == handler.getInventory() && slot.getIndex() == STATUS_SLOT;
            if (slot.isEnabled() && slot.x != HIDDEN && !status) {
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
        int x = left + PAD;
        if (click.button() == 0 && acceptReady() && mx >= x && mx < x + GRID_W && my >= statusY() && my < statusY() + 44
                && handler.getCursorStack().isEmpty()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.interactionManager != null && client.player != null) {
                client.interactionManager.clickSlot(handler.syncId, STATUS_SLOT, 0, SlotActionType.PICKUP, client.player);
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
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
