package io.theprisons.gui;

import io.theprisons.ThePrisonsClient;
import io.theprisons.bandit.ThePrisonsBanditManager;
import io.theprisons.config.ThePrisonsConfig;
import io.theprisons.ui.ThePrisonsColors;
import io.theprisons.ui.ThePrisonsHudRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ThePrisonsHudLayoutScreen extends Screen {
    private final Screen parent;
    private final ThePrisonsConfig config;
    private DragTarget dragTarget;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean wasMouseDown;

    public ThePrisonsHudLayoutScreen(Screen parent, ThePrisonsConfig config) {
        super(Text.literal("HUD Layout"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> close()).dimensions(12, height - 28, 80, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateDrag(mouseX, mouseY);
        context.fill(0, 0, width, height, ThePrisonsColors.BG_OVERLAY);
        context.drawTextWithShadow(textRenderer, Text.literal("Drag HUD widgets. Alerts are fixed."), 12, 12, ThePrisonsColors.FG_PRIMARY);
        String hoveredWidget = insideHudWidget(mouseX, mouseY);
        String hoverScaleText = hoveredWidget == null
                ? "Hover scale -"
                : "Hover scale " + formatWidgetScale(hoveredWidget);
        context.drawTextWithShadow(textRenderer, Text.literal(String.format("%s  Armor %.2fx", hoverScaleText, config.hud.armorHudScale)), 12, 24, ThePrisonsColors.FG_MUTED);
        ThePrisonsHudRenderer.drawWidgetPreviews(context, client, config);
        ThePrisonsBanditManager.drawArmorHudPreview(context, client, config);
        ThePrisonsHudRenderer.drawNotificationPreview(context, client, config);
        super.render(context, mouseX, mouseY, delta);
    }

    private String insideHudWidget(int mouseX, int mouseY) {
        for (String id : new String[]{"PET_TRINKET", "SESSION_XP", "ENERGY", "MINING"}) {
            if (ThePrisonsHudRenderer.widgetBounds(client, config, id).contains(mouseX, mouseY)) {
                return id;
            }
        }
        return null;
    }

    private boolean insideArmorHud(int mouseX, int mouseY) {
        return ThePrisonsBanditManager.isInsideArmorHud(client, config, mouseX, mouseY);
    }

    private void updateDrag(int mouseX, int mouseY) {
        boolean mouseDown = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (mouseDown && !wasMouseDown) {
            if (insideArmorHud(mouseX, mouseY)) {
                ThePrisonsHudRenderer.HudBounds bounds = ThePrisonsBanditManager.measureArmorHud(client, config);
                dragTarget = DragTarget.ARMOR;
                dragOffsetX = mouseX - bounds.x;
                dragOffsetY = mouseY - bounds.y;
            } else {
                String widgetId = insideHudWidget(mouseX, mouseY);
                if (widgetId != null) {
                    dragTarget = DragTarget.fromWidget(widgetId);
                    switch (dragTarget) {
                        case PET_TRINKET_HUD -> {
                            dragOffsetX = mouseX - config.gui.petHudX;
                            dragOffsetY = mouseY - config.gui.petHudY;
                        }
                        case SESSION_XP_HUD -> {
                            dragOffsetX = mouseX - config.gui.sessionXpHudX;
                            dragOffsetY = mouseY - config.gui.sessionXpHudY;
                        }
                        case ENERGY_HUD -> {
                            dragOffsetX = mouseX - config.gui.energyHudX;
                            dragOffsetY = mouseY - config.gui.energyHudY;
                        }
                        case MINING_HUD -> {
                            dragOffsetX = mouseX - config.gui.miningHudX;
                            dragOffsetY = mouseY - config.gui.miningHudY;
                        }
                        default -> {
                        }
                    }
                }
            }
        }

        if (mouseDown && dragTarget != null) {
            ThePrisonsConfig.GuiConfig gui = config.gui;
            int grid = gui.snapToGrid ? gui.gridSize : 1;
            int newX = snap((int) (mouseX - dragOffsetX), grid);
            int newY = snap((int) (mouseY - dragOffsetY), grid);
            switch (dragTarget) {
                case PET_TRINKET_HUD -> {
                    gui.petHudX = Math.max(0, newX);
                    gui.petHudY = Math.max(0, newY);
                }
                case SESSION_XP_HUD -> {
                    gui.sessionXpHudX = Math.max(0, newX);
                    gui.sessionXpHudY = Math.max(0, newY);
                }
                case ENERGY_HUD -> {
                    gui.energyHudX = Math.max(0, newX);
                    gui.energyHudY = Math.max(0, newY);
                }
                case MINING_HUD -> {
                    gui.miningHudX = Math.max(0, newX);
                    gui.miningHudY = Math.max(0, newY);
                }
                case ARMOR -> {
                    gui.armorHudX = Math.max(0, newX);
                    gui.armorHudY = Math.max(0, newY);
                }
            }
            ThePrisonsClient.CONFIG.saveAsync();
        }

        if (!mouseDown) {
            dragTarget = null;
        }

        wasMouseDown = mouseDown;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        String widgetId = insideHudWidget((int) mouseX, (int) mouseY);
        if (widgetId != null) {
            float step = verticalAmount > 0 ? 0.05f : -0.05f;
            switch (widgetId) {
                case "PET_TRINKET" -> config.gui.petHudScale = clampScale(config.gui.petHudScale + step);
                case "SESSION_XP" -> config.gui.sessionXpHudScale = clampScale(config.gui.sessionXpHudScale + step);
                case "ENERGY" -> config.gui.energyHudScale = clampScale(config.gui.energyHudScale + step);
                case "MINING" -> config.gui.miningHudScale = clampScale(config.gui.miningHudScale + step);
                default -> {
                }
            }
            ThePrisonsClient.CONFIG.saveAsync();
            return true;
        }
        if (insideArmorHud((int) mouseX, (int) mouseY)) {
            float step = verticalAmount > 0 ? 0.05f : -0.05f;
            config.hud.armorHudScale = Math.max(0.75f, Math.min(1.35f, config.hud.armorHudScale + step));
            ThePrisonsClient.CONFIG.saveAsync();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private float clampScale(float value) {
        return Math.max(0.5f, Math.min(2.5f, value));
    }

    private String formatWidgetScale(String widgetId) {
        float scale = switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudScale;
            case "SESSION_XP" -> config.gui.sessionXpHudScale;
            case "ENERGY" -> config.gui.energyHudScale;
            case "MINING" -> config.gui.miningHudScale;
            default -> 1.0f;
        };
        return String.format("%.2fx", scale);
    }

    private int snap(int value, int grid) {
        if (grid <= 1) {
            return value;
        }
        return Math.round(value / (float) grid) * grid;
    }

    @Override
    public void close() {
        ThePrisonsClient.CONFIG.saveAsync();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private enum DragTarget {
        PET_TRINKET_HUD,
        SESSION_XP_HUD,
        ENERGY_HUD,
        MINING_HUD,
        ARMOR;

        private static DragTarget fromWidget(String widgetId) {
            return switch (widgetId) {
                case "PET_TRINKET" -> PET_TRINKET_HUD;
                case "SESSION_XP" -> SESSION_XP_HUD;
                case "ENERGY" -> ENERGY_HUD;
                case "MINING" -> MINING_HUD;
                default -> PET_TRINKET_HUD;
            };
        }
    }
}
