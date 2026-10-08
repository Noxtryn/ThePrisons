package io.theprisons.hud;

import io.theprisons.gui.kit.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Draws a finished {@link DashboardLayout.Result}: a dark card, an accent line, KPI cards, rows, a bar. No measuring and no decisions here. */
public final class DashboardRenderer {
    private static final int LABEL = 0x9AA3B5;
    private static final int MUTED = 0x6B7385;
    private static final int MINT = 0x8CF5C2;
    private static final int SKY = 0x8AD8FF;
    private static final int LAVENDER = 0xC3A6FF;
    private static final int BUTTER = 0xFFE89E;
    private static final int PEACH = 0xFFC59E;
    private static final int ROSE = 0xFF9EB5;
    private static final int CARD = 0xFF101828;

    private DashboardRenderer() {
    }

    public static int color(SessionView.Tone tone) {
        return switch (tone) {
            case PRIMARY, GOOD -> MINT;
            case ENERGY -> SKY;
            case XP -> LAVENDER;
            case TIME -> BUTTER;
            case WARN -> PEACH;
            case BAD -> ROSE;
            case MUTED -> MUTED;
            default -> LABEL;
        };
    }

    /** The width function the layout needs, in the font the dashboard is drawn with. */
    public static io.theprisons.gui.kit.TextFit.Measure measure() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        return s -> Ui.width(tr, s);
    }

    public static void draw(DrawContext c, DashboardLayout.Result layout, int x, int y, float scale) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        Ui.card(c, 0, 0, layout.width(), layout.height(), 1.0F);
        Ui.flowLine(c, 0, layout.width(), 0, 1.0F);
        for (DashboardLayout.Item item : layout.items()) {
            int colour = color(item.tone());
            switch (item.kind()) {
                case RULE -> Ui.line(c, item.x(), item.x() + item.w(), item.y(), 0.35F);
                case CARD -> {
                    Ui.round(c, item.x(), item.y(), item.w(), item.h(), CARD);
                    c.fill(item.x() + 2, item.y(), item.x() + item.w() - 2, item.y() + 1, 0xFF000000 | colour);
                }
                case BAR -> {
                    c.fill(item.x(), item.y(), item.x() + item.w(), item.y() + item.h(), 0xFF0F1626);
                    c.fill(item.x(), item.y(), item.x() + (int) Math.round(item.w() * item.fraction()), item.y() + item.h(), 0xFF000000 | colour);
                }
                case TITLE -> Ui.shimmer(c, tr, item.text(), item.x(), item.y(), 1.0F);
                case KPI_VALUE -> {
                    m.pushMatrix();
                    m.translate(item.x(), item.y());
                    m.scale((float) item.scale(), (float) item.scale());
                    Ui.draw(c, tr, item.text(), 0, 0, colour, 255);
                    m.popMatrix();
                }
                case KPI_LABEL, KPI_SUB, SECTION, MINI_LABEL, ROW_LABEL, BOOSTER_NAME, STATE -> Ui.draw(c, tr, item.text(), item.x(), item.y(),
                        item.kind() == DashboardLayout.Kind.ROW_LABEL || item.kind() == DashboardLayout.Kind.BOOSTER_NAME ? LABEL
                                : item.kind() == DashboardLayout.Kind.STATE && item.tone() == SessionView.Tone.GOOD ? MINT : MUTED, 255);
                default -> Ui.draw(c, tr, item.text(), item.x(), item.y(), colour, 255);
            }
        }
        m.popMatrix();
    }
}
