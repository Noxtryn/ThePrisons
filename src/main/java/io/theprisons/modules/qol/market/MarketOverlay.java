package io.theprisons.modules.qol.market;

import io.theprisons.gui.kit.Panel;
import io.theprisons.gui.kit.Ui;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;

/** Compact replacement-style information layer for Cosmic AH and /ee menus. */
public final class MarketOverlay {
    private MarketOverlay() {}

    public static void render(DrawContext c, HandledScreen<?> screen) {
        MarketModule m = MarketModule.get();
        if (m == null || !m.enabled() || screen instanceof MarketScreen) return;
        String title = screen.getTitle().getString();
        boolean ah = title.equals(MarketParser.MARKET) || title.equals(MarketParser.CATEGORIES) || title.equals(MarketParser.HISTORY);
        boolean ee = title.equals(MarketParser.ENERGY);
        if (!ah && !ee) return;
        int w = 154;
        int[] at = Panel.corner(screen.width, screen.height, w, ee ? 62 : 76, true, false, 8);
        int x = at[0];
        int y = at[1];
        Panel.box(c, x, y, w, ee ? 62 : 76);
        Ui.draw(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, ee ? "COSMIC ENERGY" : "COSMIC MARKET", x + 9, y + 8, Ui.theme().title(), 255);
        Panel.rule(c, x + 8, x + w - 8, y + 22);
        if (ee) {
            double rate = m.book().moneyPerEnergy();
            Ui.draw(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, "Rate", x + 9, y + 31, Ui.MUTED, 255);
            Ui.drawRight(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, rate > 0 ? "$" + Money.compact(rate * 1000) + " / 1k" : "unknown", x + w - 9, y + 31, Ui.GOOD, 255);
            Ui.draw(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, "Updated " + MarketSearch.age(System.currentTimeMillis() - m.book().rateSeenMs()), x + 9, y + 46, Ui.MUTED, 255);
        } else {
            Ui.draw(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, "Tracked", x + 9, y + 31, Ui.MUTED, 255);
            Ui.drawRight(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, Integer.toString(m.book().all().size()) + " items", x + w - 9, y + 31, Ui.VALUE, 255);
            Ui.draw(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, "Search in inventory", x + 9, y + 46, Ui.MUTED, 255);
            Ui.drawRight(c, net.minecraft.client.MinecraftClient.getInstance().textRenderer, "/price <item>", x + w - 9, y + 61, Ui.theme().accent(), 255);
        }
    }
}
