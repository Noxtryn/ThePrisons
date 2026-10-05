package io.theprisons.gui.hud;

import io.theprisons.gui.kit.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HUD editor in the dashboard's design: the game stays visible, a fine grid fades in, every HUD element gets an
 * outlined box with a name chip. Drag to move (snaps to the grid and to the screen centre lines, guides appear while
 * snapping), scroll to scale, right-click to reset one element. A toolbar at the bottom: snap on / off, reset all, done.
 */
public final class HudEditorScreen extends Screen implements io.theprisons.gui.kit.HidesHud {
    private static final int GRID = 8;
    private static final int SNAP = 4;

    private final @Nullable Screen parent;
    private final Supplier<List<HudElement>> source;
    private final java.util.function.Consumer<DrawContext> drawShared;
    private List<HudElement> elements = List.of();
    private final Map<String, Float> hover = new HashMap<>();
    private final long openedMs = Util.getMeasuringTimeMs();
    private long lastFrameMs = openedMs;
    private @Nullable HudElement dragging;
    private double grabX;
    private double grabY;
    private boolean snapping = true;
    private boolean guideX;
    private boolean guideY;
    private int[] toolbar = new int[4];
    private int hoveredButton = -1;
    private String toast = "";
    private long toastMs;

    /**
     * @param source     the elements (re-read every frame: modules may turn on / off)
     * @param drawShared draws previews that are not per element (the v1 widgets draw together)
     */
    public HudEditorScreen(@Nullable Screen parent, Supplier<List<HudElement>> source,
                           java.util.function.Consumer<DrawContext> drawShared) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
        this.source = source;
        this.drawShared = drawShared;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // the game stays visible
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        long now = Util.getMeasuringTimeMs();
        float dt = MathHelper.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;
        elements = source.get();
        float in = Ui.appear(openedMs, 0, 350.0F);
        int accent = Ui.theme().accent();

        // grid
        int gridAlpha = Math.round(26 * in);
        for (int x = 0; x < width; x += GRID * 2) {
            context.fill(x, 0, x + 1, height, Ui.argb(gridAlpha, 0xFFFFFF));
        }
        for (int y = 0; y < height; y += GRID * 2) {
            context.fill(0, y, width, y + 1, Ui.argb(gridAlpha, 0xFFFFFF));
        }
        if (dragging != null && guideX) {
            context.fill(width / 2, 0, width / 2 + 1, height, Ui.argb(200, accent));
        }
        if (dragging != null && guideY) {
            context.fill(0, height / 2, width, height / 2 + 1, Ui.argb(200, accent));
        }

        // previews
        drawShared.accept(context);
        for (HudElement element : elements) {
            element.drawPreview(context, width, height);
        }

        // boxes + chips
        int i = 0;
        for (HudElement element : elements) {
            int[] b = element.bounds(width, height);
            if (b[2] <= 0 || b[3] <= 0) {
                continue;
            }
            boolean over = dragging == element || (dragging == null && inside(b, mouseX, mouseY));
            float h = Ui.approach(hover.getOrDefault(element.id(), 0.0F), over ? 1.0F : 0.0F, dt, 12.0F);
            hover.put(element.id(), h);
            float pop = Ui.appear(openedMs, 120L + i * 60L, 300.0F);
            context.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], Ui.argb(Math.round((18 + 40 * h) * pop), accent));
            dashed(context, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, Ui.argb(Math.round((120 + 135 * h) * pop), accent), now);
            String chip = element.name() + "  " + String.format(java.util.Locale.ROOT, "%.2fx", element.scale());
            int cw = Ui.width(textRenderer, chip) + 8;
            int cy = b[1] - 12 - Math.round((1.0F - pop) * 6.0F) - Math.round(h * 2.0F);
            if (cy < 2) {
                cy = b[1] + b[3] + 2;
            }
            context.fill(b[0], cy, b[0] + cw, cy + 11, Ui.argb(Math.round(210 * pop), 0x101018));
            context.fill(b[0], cy + 10, b[0] + cw, cy + 11, Ui.argb(Math.round(255 * pop), accent));
            Ui.draw(context, textRenderer, chip, b[0] + 4, cy + 2, h > 0.5F ? Ui.theme().title() : Ui.VALUE, Math.round(255 * pop));
            i++;
        }

        drawHeader(context, in);
        drawToolbar(context, mouseX, mouseY, in);
        super.render(context, mouseX, mouseY, deltaTicks);
    }

    private void drawHeader(DrawContext context, float in) {
        String title = "HUD EDITOR";
        int y = 8 - Math.round((1.0F - in) * 10.0F);
        Ui.shimmer(context, textRenderer, title, width / 2 - Ui.width(textRenderer, title) / 2, y, in);
        Ui.drawCentered(context, textRenderer, "Drag to move  ·  scroll to scale  ·  right-click to reset", width / 2, y + 11,
                Ui.LABEL, Math.round(255 * in));
        if (!toast.isEmpty() && Util.getMeasuringTimeMs() - toastMs < 1600L) {
            float t = 1.0F - (Util.getMeasuringTimeMs() - toastMs) / 1600.0F;
            Ui.drawCentered(context, textRenderer, toast, width / 2, y + 23, Ui.theme().title(), Math.round(255 * t));
        }
    }

    private static final String[] BUTTONS = {"Snap", "Reset all", "Done"};

    private void drawToolbar(DrawContext context, int mouseX, int mouseY, float in) {
        int bw = 70;
        int gap = 6;
        int total = BUTTONS.length * bw + (BUTTONS.length - 1) * gap + 12;
        int x = (width - total) / 2;
        int y = height - 30 + Math.round((1.0F - in) * 30.0F);
        toolbar = new int[]{x, y, total, 24};
        Ui.card(context, x, y, total, 24, in);
        Ui.flowLine(context, x, x + total, y, in);
        hoveredButton = -1;
        for (int b = 0; b < BUTTONS.length; b++) {
            int bx = x + 6 + b * (bw + gap);
            boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= y + 4 && mouseY < y + 20;
            if (over) {
                hoveredButton = b;
            }
            boolean active = b == 0 && snapping;
            int fill = over ? 70 : active ? 45 : 20;
            context.fill(bx, y + 4, bx + bw, y + 20, Ui.argb(fill, Ui.theme().accent()));
            String label = b == 0 ? (snapping ? "Snap  ✔" : "Snap  ✕") : BUTTONS[b];
            Ui.drawCentered(context, textRenderer, label, bx + bw / 2, y + 8, over ? Ui.theme().title() : Ui.VALUE, 255);
        }
    }

    private static void dashed(DrawContext c, int x, int y, int w, int h, int argb, long now) {
        int phase = (int) (now / 120L % 6L);
        for (int i = -phase; i < w; i += 6) {
            int a = Math.max(0, i);
            int b = Math.min(w, i + 3);
            if (b > a) {
                c.fill(x + a, y, x + b, y + 1, argb);
                c.fill(x + w - b, y + h - 1, x + w - a, y + h, argb);
            }
        }
        for (int i = -phase; i < h; i += 6) {
            int a = Math.max(0, i);
            int b = Math.min(h, i + 3);
            if (b > a) {
                c.fill(x, y + h - b, x + 1, y + h - a, argb);
                c.fill(x + w - 1, y + a, x + w, y + b, argb);
            }
        }
    }

    private static boolean inside(int[] b, double x, double y) {
        return x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3];
    }

    private @Nullable HudElement at(double x, double y) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            int[] b = e.bounds(width, height);
            if (b[2] > 0 && inside(b, x, y)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && hoveredButton >= 0) {
            switch (hoveredButton) {
                case 0 -> snapping = !snapping;
                case 1 -> {
                    for (HudElement e : elements) {
                        e.reset();
                    }
                    toast("All HUD elements reset");
                }
                default -> close();
            }
            return true;
        }
        HudElement e = at(click.x(), click.y());
        if (e != null && click.button() == 1) {
            e.reset();
            toast(e.name() + " reset");
            return true;
        }
        if (e != null && click.button() == 0) {
            int[] b = e.bounds(width, height);
            dragging = e;
            grabX = click.x() - b[0];
            grabY = click.y() - b[1];
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        HudElement e = dragging;
        if (e == null) {
            return super.mouseDragged(click, offsetX, offsetY);
        }
        int[] b = e.bounds(width, height);
        int nx = (int) Math.round(click.x() - grabX);
        int ny = (int) Math.round(click.y() - grabY);
        guideX = false;
        guideY = false;
        if (snapping) {
            if (Math.abs(nx + b[2] / 2 - width / 2) <= SNAP) {
                nx = width / 2 - b[2] / 2;
                guideX = true;
            } else {
                nx = Math.round(nx / (float) GRID) * GRID;
            }
            if (Math.abs(ny + b[3] / 2 - height / 2) <= SNAP) {
                ny = height / 2 - b[3] / 2;
                guideY = true;
            } else {
                ny = Math.round(ny / (float) GRID) * GRID;
            }
        }
        nx = MathHelper.clamp(nx, 0, Math.max(0, width - b[2]));
        ny = MathHelper.clamp(ny, 0, Math.max(0, height - b[3]));
        e.moveTo(nx, ny, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            guideX = false;
            guideY = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        HudElement e = at(mouseX, mouseY);
        if (e != null) {
            e.setScale(Math.round((e.scale() + (verticalAmount > 0 ? 0.05D : -0.05D)) * 100.0D) / 100.0D);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void toast(String text) {
        toast = text;
        toastMs = Util.getMeasuringTimeMs();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
