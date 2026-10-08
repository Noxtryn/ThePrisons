package io.theprisons.items.client;

import io.theprisons.gui.kit.Ui;
import io.theprisons.items.energy.EnergyOverlayModel;
import io.theprisons.modules.FeatureProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.List;

/** Draws the prepared {@link EnergyOverlayModel}: a calm dark panel, the bar only when the capacity is known. No business logic here. */
public final class EnergyOverlayRender {
    private EnergyOverlayRender() {
    }

    public static void draw(DrawContext c, HandledScreen<?> screen) {
        EnergyOverlayModule m = EnergyOverlayModule.get();
        if (m == null || !m.enabled()) {
            return;
        }
        EnergyOverlayModel model = m.visibleModel();
        if (model == null) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        boolean detailed = m.detailed();
        List<String> lines = detailed ? model.detailedLines() : model.compactLines();
        int w = detailed ? 176 : 132;
        int h = 14 + lines.size() * 11 + (model.progress() != null ? 5 : 0);       // the bar row is 16 px, the text rows 11
        int x = screen.width - w - 8;
        int y = screen.height - h - 8;
        int alpha = model.stale() ? 150 : 255;
        Ui.round(c, x, y, w, h, 0xF00A0F1A);
        Ui.outline(c, x, y, w, h, 0xFF1C2740);
        int ty = y + 7;
        for (String line : lines) {
            if (line.startsWith("█") || line.startsWith("░")) {
                int bx = x + 8;
                int bw = w - 16;
                c.fill(bx, ty + 1, bx + bw, ty + 6, 0xFF111A2C);
                if (model.progress() != null) {
                    c.fill(bx, ty + 1, bx + Math.round(bw * model.progress()), ty + 6, model.stale() ? 0xFF2B6B80 : 0xFF3CC4E8);
                }
                Ui.drawRight(c, tr, model.percent() == null ? "" : model.percent(), x + w - 8, ty + 8, Ui.VALUE, alpha);
                ty += 16;
                continue;
            }
            boolean title = line.equals(model.title());
            Ui.draw(c, tr, line, x + 8, ty, title ? Ui.theme().title() : line.startsWith("Status") && model.stale() ? Ui.WARN : Ui.LABEL, alpha);
            ty += 11;
        }
        if (FeatureProfile.DEV) {
            Ui.draw(c, tr, m.devLine(), 6, 16, Ui.MUTED, 255);
        }
    }
}
