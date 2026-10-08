package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.market.SlotView;
import io.theprisons.modules.FeatureProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Draws the prepared {@link io.theprisons.items.market.AhSnapshot}: no parsing, no maths, no allocation per slot beyond the hover. */
public final class AhOverlayRender {
    private AhOverlayRender() {
    }

    /** After the server's slot is drawn: a one pixel border and a tiny percentage. */
    public static void drawSlot(DrawContext c, HandledScreen<?> screen, Slot slot) {
        AhOverlayModule m = AhOverlayModule.get();
        if (m == null || !m.activeFor(screen)) {
            return;
        }
        SlotView v = m.snapshot().slots().get(slot.id);
        if (v == null) {
            return;
        }
        int x = slot.x;
        int y = slot.y;
        if (v.borderArgb() != 0 && m.showBorders()) {
            Ui.outline(c, x, y, 16, 16, v.borderArgb());
        }
        if (v.badge() != null && m.showBadges() && v.borderArgb() != 0) {
            var tr = MinecraftClient.getInstance().textRenderer;
            var mat = c.getMatrices();
            mat.pushMatrix();
            mat.translate(x + 1, y + 10);
            mat.scale(0.6F, 0.6F);
            c.drawText(tr, v.badge(), 0, 0, 0xFF000000 | (v.borderArgb() & 0xFFFFFF), true);
            mat.popMatrix();
        }
    }

    /** The analysis below the server's tooltip of the hovered listing. */
    public static void extendTooltip(HandledScreen<?> screen, Slot focused, ItemStack stack, List<Text> lines, List<Text> out) {
        AhOverlayModule m = AhOverlayModule.get();
        if (m == null || focused == null || !m.activeFor(screen) || focused.getStack() != stack) {
            return;
        }
        SlotView v = m.snapshot().slots().get(focused.id);
        if (v == null) {
            return;
        }
        out.add(Text.empty());
        List<String> hover = v.hover(System.currentTimeMillis());
        for (int i = 0; i < hover.size(); i++) {
            String line = hover.get(i);
            Formatting color = i == 0 ? Formatting.AQUA : line.startsWith("Difference") ? (line.contains("-") ? Formatting.GREEN : Formatting.RED) : Formatting.GRAY;
            out.add(Text.literal(line).formatted(color));
        }
    }

    /** Dev profile: the overlay's own numbers in the corner. */
    public static void drawDev(DrawContext c, HandledScreen<?> screen) {
        AhOverlayModule m = AhOverlayModule.get();
        if (FeatureProfile.DEV && m != null && m.activeFor(screen)) {
            Ui.draw(c, MinecraftClient.getInstance().textRenderer, m.devLine(), 6, 6, Ui.MUTED, 255);
        }
    }

    static List<Text> copy(List<Text> in) {
        return new ArrayList<>(in);
    }
}
