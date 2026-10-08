package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.market.EeAnalysis;
import io.theprisons.items.market.EePanelLayout;
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

    /** The market dashboard beside the menu: laid out once per page ({@link EePanelLayout}), drawn from that. */
    public static void drawCard(DrawContext c, HandledScreen<?> screen) {
        EeOverlayModule m = EeOverlayModule.get();
        if (m == null || !m.activeFor(screen)) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int width = Math.max(120, Math.min(176, screen.width / 2 - 88 - 14));
        int x = screen.width / 2 + 88 + 10;
        if (x + width > screen.width - 4) {
            x = Math.max(4, screen.width / 2 - 88 - 10 - width);          // no room on the right: the left of the menu
        }
        EePanelLayout.Result layout = m.layout(width, s -> Ui.width(tr, s));
        int y = Math.max(6, Math.min(screen.height / 2 - 83, screen.height - layout.height() - 6));
        Ui.round(c, x, y, layout.width(), layout.height(), 0xF00A0F1A);
        Ui.outline(c, x, y, layout.width(), layout.height(), 0xFF1C2740);
        for (EePanelLayout.Item item : layout.items()) {
            int colour = switch (item.tone()) {
                case GOOD -> Ui.GOOD;
                case WARN -> Ui.WARN;
                case BAD -> Ui.BAD;
                case MUTED -> Ui.MUTED;
                default -> Ui.VALUE;
            };
            switch (item.kind()) {
                case RULE -> c.fill(x + item.x(), y + item.y(), x + item.x() + item.w(), y + item.y() + 1, 0xFF1C2740);
                case TITLE -> Ui.draw(c, tr, item.text(), x + item.x(), y + item.y(), Ui.theme().title(), 255);
                case SECTION, METRIC_LABEL -> Ui.draw(c, tr, item.text(), x + item.x(), y + item.y(), Ui.MUTED, 255);
                case ROW_LABEL -> Ui.draw(c, tr, item.text(), x + item.x(), y + item.y(), Ui.LABEL, 255);
                case METRIC_VALUE -> {
                    var mat = c.getMatrices();
                    mat.pushMatrix();
                    mat.translate(x + item.x(), y + item.y());
                    mat.scale((float) item.scale(), (float) item.scale());
                    Ui.draw(c, tr, item.text(), 0, 0, colour, 255);
                    mat.popMatrix();
                }
                default -> Ui.draw(c, tr, item.text(), x + item.x(), y + item.y(), colour, 255);
            }
        }
        if (FeatureProfile.DEV) {
            Ui.draw(c, tr, m.devLine(), 6, 26, Ui.MUTED, 255);
        }
    }
}
