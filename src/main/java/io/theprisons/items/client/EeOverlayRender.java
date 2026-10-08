package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.market.EeAnalysis;
import io.theprisons.modules.FeatureProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;

/** Draws the prepared {@link EeAnalysis}: the card beside the menu, a border per listing and the hover text. Nothing is calculated here. */
public final class EeOverlayRender {
    private EeOverlayRender() {
    }

    public static void drawSlot(DrawContext c, HandledScreen<?> screen, Slot slot) {
        EeOverlayModule m = EeOverlayModule.get();
        if (m == null || !m.activeFor(screen) || !m.showBorders()) {
            return;
        }
        EeAnalysis.SlotInfo info = m.analysis().slots().get(slot.id);
        if (info == null) {
            return;
        }
        if (info.outlier()) {
            Ui.outline(c, slot.x, slot.y, 16, 16, 0xE0FF6B6B);
        } else if (info.cheapest()) {
            Ui.outline(c, slot.x, slot.y, 16, 16, 0xE03CE0A0);
        } else if (!Double.isNaN(info.premium()) && info.premium() >= 0.05D) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            var mat = c.getMatrices();
            mat.pushMatrix();
            mat.translate(slot.x + 1, slot.y + 10);
            mat.scale(0.6F, 0.6F);
            c.drawText(tr, String.format(Locale.ROOT, "+%.0f%%", info.premium() * 100.0D), 0, 0, 0xFFFFC14D, true);
            mat.popMatrix();
        }
    }

    public static void extendTooltip(HandledScreen<?> screen, Slot focused, List<Text> out) {
        EeOverlayModule m = EeOverlayModule.get();
        if (m == null || focused == null || !m.activeFor(screen)) {
            return;
        }
        List<String> hover = m.analysis().hover(focused.id);
        if (hover == null) {
            return;
        }
        out.add(Text.empty());
        for (int i = 0; i < hover.size(); i++) {
            out.add(Text.literal(hover.get(i)).formatted(i == 0 ? Formatting.AQUA : hover.get(i).contains("ignored") ? Formatting.RED : Formatting.GRAY));
        }
    }

    public static void drawCard(DrawContext c, HandledScreen<?> screen) {
        EeOverlayModule m = EeOverlayModule.get();
        if (m == null || !m.activeFor(screen)) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        List<String> lines = m.analysis().lines();
        int w = 168;
        int h = 12 + lines.size() * 11;
        int x = screen.width / 2 + 88 + 10;
        if (x + w > screen.width - 4) {
            x = Math.max(4, screen.width / 2 - 88 - 10 - w);          // no room on the right: the left of the menu
        }
        int y = Math.max(6, screen.height / 2 - 83);
        Ui.round(c, x, y, w, h, 0xF00A0F1A);
        Ui.outline(c, x, y, w, h, 0xFF1C2740);
        int ty = y + 6;
        for (int i = 0; i < lines.size(); i++) {
            Ui.draw(c, tr, lines.get(i), x + 7, ty, i == 0 ? Ui.theme().title() : lines.get(i).contains("ignored") ? Ui.WARN : Ui.LABEL, 255);
            ty += 11;
        }
        if (FeatureProfile.DEV) {
            Ui.draw(c, tr, m.devLine(), 6, 26, Ui.MUTED, 255);
        }
    }
}
