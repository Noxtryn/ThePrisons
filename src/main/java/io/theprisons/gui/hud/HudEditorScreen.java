package io.theprisons.gui.hud;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.gui.kit.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The HUD editor: the game stays visible, a grid (the size of {@code hud_layout.grid}) fades in and every HUD element
 * gets an outlined box with a name chip. Drag to move (snaps to the grid, the screen edges and the centre lines while
 * {@code hud_layout.snap} is on), scroll or +/- to scale, right-click or R to reset one element, H or the eye in the
 * element list to hide and show it, arrow keys to nudge. The toolbar changes the two layout settings.
 */
public final class HudEditorScreen extends Screen implements io.theprisons.gui.kit.HidesHud {
    private record Row(int x, int y, int w, int h, HudElement element, boolean eye) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int ROW_H = 12;
    private static final String[] BUTTONS = {"Snap", "Grid", "List", "Reset all", "Done"};

    private final @Nullable Screen parent;
    private final ThePrisonsCore core;
    private final java.util.function.Consumer<DrawContext> drawShared;
    private List<HudElement> elements = List.of();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final long openedMs = Util.getMeasuringTimeMs();
    private long lastFrameMs = openedMs;
    private @Nullable HudElement dragging;
    private @Nullable HudElement selected;
    private double grabX;
    private double grabY;
    private boolean showList = true;
    private int listRight;
    private int listBottom;
    private boolean guideX;
    private boolean guideY;
    private int[] toolbar = new int[4];
    private int hoveredButton = -1;
    private String toast = "";
    private long toastMs;

    /**
     * @param drawShared draws previews that are not per element (the v1 widgets draw together)
     */
    public HudEditorScreen(@Nullable Screen parent, ThePrisonsCore core, java.util.function.Consumer<DrawContext> drawShared) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
        this.core = core;
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
        elements = HudLayout.elements(core);
        float in = Ui.appear(openedMs, 0, 350.0F);
        int accent = Ui.theme().accent();
        int grid = HudLayout.grid();

        int gridAlpha = Math.round((HudLayout.snapEnabled() ? 30 : 12) * in);
        for (int x = 0; x < width; x += grid) {
            context.fill(x, 0, x + 1, height, Ui.argb(gridAlpha, 0xFFFFFF));
        }
        for (int y = 0; y < height; y += grid) {
            context.fill(0, y, width, y + 1, Ui.argb(gridAlpha, 0xFFFFFF));
        }
        if (dragging != null && guideX) {
            context.fill(width / 2, 0, width / 2 + 1, height, Ui.argb(200, accent));
        }
        if (dragging != null && guideY) {
            context.fill(0, height / 2, width, height / 2 + 1, Ui.argb(200, accent));
        }

        drawShared.accept(context);
        for (HudElement element : elements) {
            if (HudLayout.visible(core, element)) {
                element.drawPreview(context, width, height);
            }
        }

        int i = 0;
        for (HudElement element : elements) {
            int[] b = element.bounds(width, height);
            if (!HudLayout.visible(core, element) || b[2] <= 0 || b[3] <= 0) {
                continue;
            }
            boolean chosen = element == selected;
            boolean over = dragging == element || (dragging == null && inside(b, mouseX, mouseY));
            float h = Ui.approach(hover.getOrDefault(element.id(), 0.0F), over || chosen ? 1.0F : 0.0F, dt, 12.0F);
            hover.put(element.id(), h);
            float pop = Ui.appear(openedMs, 120L + i * 60L, 300.0F);
            context.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], Ui.argb(Math.round((18 + 40 * h) * pop), accent));
            dashed(context, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, Ui.argb(Math.round((120 + 135 * h) * pop), accent), now);
            String chip = element.name() + "  " + String.format(Locale.ROOT, "%.2fx", element.scale());
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
        drawList(context, mouseX, mouseY, in);
        drawToolbar(context, mouseX, mouseY, in);
        super.render(context, mouseX, mouseY, deltaTicks);
    }

    private void drawHeader(DrawContext context, float in) {
        String title = "HUD EDITOR";
        int y = 8 - Math.round((1.0F - in) * 10.0F);
        Ui.shimmer(context, textRenderer, title, width / 2 - Ui.width(textRenderer, title) / 2, y, in);
        String help = width < 340 ? "Drag  ·  scroll or +/-  ·  H hides" : "Drag to move  ·  scroll or +/- to scale  ·  right-click resets  ·  H hides";
        Ui.drawCentered(context, textRenderer, help, width / 2, y + 11, Ui.LABEL, Math.round(255 * in));
        if (!toast.isEmpty() && Util.getMeasuringTimeMs() - toastMs < 1600L) {
            float t = 1.0F - (Util.getMeasuringTimeMs() - toastMs) / 1600.0F;
            Ui.drawCentered(context, textRenderer, toast, width / 2, y + 23, Ui.theme().title(), Math.round(255 * t));
        }
    }

    /** All elements with an eye (show / hide); hidden ones stay here so they can come back. */
    private void drawList(DrawContext context, int mouseX, int mouseY, float in) {
        rows.clear();
        if (!showList || elements.isEmpty()) {
            return;
        }
        int panelW = width < 340 ? 104 : 136;
        int x = 6;
        int y = 36;
        int panelH = 16 + elements.size() * ROW_H + 4;
        // never taller than the room above the toolbar
        panelH = Math.min(panelH, height - y - 36);
        int visibleRows = Math.max(0, (panelH - 20) / ROW_H);
        listRight = x + panelW;
        listBottom = y + panelH;
        Ui.card(context, x, y, panelW, panelH, in);
        Ui.flowLine(context, x, x + panelW, y, in);
        Ui.draw(context, textRenderer, "Elements", x + 5, y + 4, Ui.theme().title(), Math.round(255 * in));
        int ry = y + 16;
        for (int n = 0; n < Math.min(visibleRows, elements.size()); n++) {
            HudElement element = elements.get(n);
            boolean on = HudLayout.visible(core, element);
            boolean rowOver = mouseX >= x && mouseX < x + panelW && mouseY >= ry && mouseY < ry + ROW_H;
            if (element == selected) {
                context.fill(x + 1, ry, x + panelW - 1, ry + ROW_H, Ui.argb(50, Ui.theme().accent()));
            } else if (rowOver) {
                context.fill(x + 1, ry, x + panelW - 1, ry + ROW_H, Ui.argb(24, 0xFFFFFF));
            }
            int boxX = x + 5;
            int boxY = ry + 2;
            Ui.outline(context, boxX, boxY, 8, 8, Ui.argb(220, on ? Ui.theme().accent() : Ui.MUTED));
            if (on) {
                context.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, Ui.argb(255, Ui.theme().accent()));
            }
            String name = element.name();
            while (name.length() > 3 && Ui.width(textRenderer, name) > panelW - 24) {
                name = name.substring(0, name.length() - 2) + "…";
            }
            Ui.draw(context, textRenderer, name, x + 18, ry + 2, on ? Ui.VALUE : Ui.MUTED, 255);
            rows.add(new Row(boxX - 2, ry, 14, ROW_H, element, true));
            rows.add(new Row(x + 16, ry, panelW - 17, ROW_H, element, false));
            ry += ROW_H;
        }
    }

    private void drawToolbar(DrawContext context, int mouseX, int mouseY, float in) {
        int bw = width < 340 ? 46 : 60;
        int gap = 4;
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
            boolean active = b == 0 && HudLayout.snapEnabled();
            int fill = over ? 70 : active ? 45 : 20;
            context.fill(bx, y + 4, bx + bw, y + 20, Ui.argb(fill, Ui.theme().accent()));
            String label = switch (b) {
                case 0 -> HudLayout.snapEnabled() ? "Snap  ✔" : "Snap  ✕";
                case 1 -> "Grid " + HudLayout.grid();
                case 2 -> showList ? "List  ✔" : "List  ✕";
                default -> BUTTONS[b];
            };
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

    /** The element under the pointer: the selected one first (so it can be grabbed where elements overlap), else the topmost. */
    private @Nullable HudElement at(double x, double y) {
        HudElement chosen = selected;
        if (chosen != null && HudLayout.visible(core, chosen)) {
            int[] b = chosen.bounds(width, height);
            if (b[2] > 0 && inside(b, x, y)) {
                return chosen;
            }
        }
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            int[] b = e.bounds(width, height);
            if (HudLayout.visible(core, e) && b[2] > 0 && inside(b, x, y)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && hoveredButton >= 0) {
            switch (hoveredButton) {
                case 0 -> HudLayout.setSnapEnabled(!HudLayout.snapEnabled());
                case 1 -> {
                    int next = HudLayout.grid() + 2;
                    HudLayout.setGrid(next > HudLayout.MAX_GRID ? HudLayout.MIN_GRID : next);
                }
                case 2 -> showList = !showList;
                case 3 -> {
                    for (HudElement e : elements) {
                        e.reset();
                    }
                    toast("All HUD elements reset");
                }
                default -> close();
            }
            return true;
        }
        if (click.button() == 0 && showList && click.x() < listRight && click.y() >= 36 && click.y() < listBottom) {
            for (Row row : rows) {
                if (row.contains(click.x(), click.y())) {
                    if (row.eye()) {
                        toggleVisible(row.element());
                    } else {
                        selected = row.element();
                    }
                    return true;
                }
            }
            return true; // the panel is in front of the elements
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
            selected = e;
            grabX = click.x() - b[0];
            grabY = click.y() - b[1];
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void toggleVisible(HudElement element) {
        boolean show = !HudLayout.visible(core, element);
        HudLayout.setVisible(core, element, show);
        toast(element.name() + (show ? " shown" : " hidden"));
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
        place(e, b, nx, ny);
        return true;
    }

    /** Moves the element to the wanted position: snapped while snapping is on, always kept on screen. */
    private void place(HudElement e, int[] b, int nx, int ny) {
        guideX = false;
        guideY = false;
        if (HudLayout.snapEnabled()) {
            boolean[] guide = new boolean[1];
            nx = HudLayout.snapAxis(nx, b[2], width, HudLayout.grid(), HudLayout.SNAP_THRESHOLD, guide);
            guideX = guide[0];
            ny = HudLayout.snapAxis(ny, b[3], height, HudLayout.grid(), HudLayout.SNAP_THRESHOLD, guide);
            guideY = guide[0];
        }
        nx = MathHelper.clamp(nx, 0, Math.max(0, width - b[2]));
        ny = MathHelper.clamp(ny, 0, Math.max(0, height - b[3]));
        e.moveTo(nx, ny, width, height);
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
            selected = e;
            rescale(e, verticalAmount > 0 ? 0.05D : -0.05D);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static void rescale(HudElement e, double delta) {
        e.setScale(Math.round((e.scale() + delta) * 100.0D) / 100.0D);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        HudElement e = selected;
        if (e != null) {
            int key = input.key();
            int step = input.hasShift() ? HudLayout.grid() : 1;
            int[] b = e.bounds(width, height);
            switch (key) {
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> rescale(e, 0.05D);
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> rescale(e, -0.05D);
                case GLFW.GLFW_KEY_R -> {
                    e.reset();
                    toast(e.name() + " reset");
                }
                case GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_DELETE -> toggleVisible(e);
                case GLFW.GLFW_KEY_LEFT -> nudge(e, b, -step, 0);
                case GLFW.GLFW_KEY_RIGHT -> nudge(e, b, step, 0);
                case GLFW.GLFW_KEY_UP -> nudge(e, b, 0, -step);
                case GLFW.GLFW_KEY_DOWN -> nudge(e, b, 0, step);
                default -> {
                    return super.keyPressed(input);
                }
            }
            return true;
        }
        return super.keyPressed(input);
    }

    private void nudge(HudElement e, int[] b, int dx, int dy) {
        e.moveTo(MathHelper.clamp(b[0] + dx, 0, Math.max(0, width - b[2])), MathHelper.clamp(b[1] + dy, 0, Math.max(0, height - b[3])), width, height);
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
