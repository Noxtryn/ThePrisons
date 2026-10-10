package io.theprisons.items.client;

import io.theprisons.gui.kit.Panel;
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
        int[] at = Panel.corner(screen.width, screen.height, w, h, true, true, 8);
        int x = at[0];
        int y = at[1];
        int alpha = model.stale() ? 150 : 255;
        Panel.box(c, x, y, w, h);
        int ty = y + 7;
        for (String line : lines) {
            if (line.startsWith("█") || line.startsWith("░")) {
                int bx = x + 8;
                int bw = w - 16;
                Panel.bar(c, bx, ty + 1, bw, 5, model.progress(), model.stale());
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
