package io.theprisons.items.client;

import io.theprisons.gui.kit.Panel;
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
            Ui.outline(c, slot.x, slot.y, 16, 16, Panel.slotEdge(Ui.BAD));
        } else if (info.cheapest()) {
            Ui.outline(c, slot.x, slot.y, 16, 16, Panel.slotEdge(Ui.GOOD));
        } else if (!Double.isNaN(info.premium()) && info.premium() >= 0.05D) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            var mat = c.getMatrices();
            mat.pushMatrix();
            mat.translate(slot.x + 1, slot.y + 10);
            mat.scale(0.6F, 0.6F);
            c.drawText(tr, String.format(Locale.ROOT, "+%.0f%%", info.premium() * 100.0D), 0, 0, Ui.argb(255, Ui.WARN), true);
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
        Panel.appendTooltip(out, hover, line -> false, line -> line.contains("ignored"));
    }

    /** The market dashboard beside the menu: laid out once per page ({@link EePanelLayout}), drawn from that. */
    public static void drawCard(DrawContext c, HandledScreen<?> screen) {
        EeOverlayModule m = EeOverlayModule.get();
        if (m == null || !m.activeFor(screen)) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int width = Math.max(120, Math.min(176, screen.width / 2 - 88 - 14));
        EePanelLayout.Result layout = m.layout(width, s -> Ui.width(tr, s));
        int[] at = Panel.besideMenu(screen.width, screen.height, 176, 166, layout.width(), layout.height(), 10, 6);
        int x = at[0];
        int y = at[1];
        Panel.box(c, x, y, layout.width(), layout.height());
        for (EePanelLayout.Item item : layout.items()) {
            int colour = switch (item.tone()) {
                case GOOD -> Ui.GOOD;
                case WARN -> Ui.WARN;
                case BAD -> Ui.BAD;
                case MUTED -> Ui.MUTED;
                default -> Ui.VALUE;
            };
            switch (item.kind()) {
                case RULE -> Panel.rule(c, x + item.x(), x + item.x() + item.w(), y + item.y());
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
