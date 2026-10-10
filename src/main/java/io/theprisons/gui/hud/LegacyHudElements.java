package io.theprisons.gui.hud;

import io.theprisons.ThePrisonsClient;
import io.theprisons.bandit.ThePrisonsBanditManager;
import io.theprisons.config.ThePrisonsConfig;
import io.theprisons.ui.ThePrisonsHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** The v1 HUD widgets (pets & trinkets, satchels / session widgets, armour) as {@link HudElement}s for the editor. */
public final class LegacyHudElements {
    private LegacyHudElements() {
    }

    public static List<HudElement> all() {
        ThePrisonsConfig cfg = ThePrisonsClient.CONFIG.get();
        ThePrisonsConfig.GuiConfig def = new ThePrisonsConfig().gui;
        List<HudElement> list = new ArrayList<>();
        list.add(widget("PET_TRINKET", "Pets & Trinkets", List.of("pet_hud"), def.petHudX, def.petHudY, () -> cfg.gui.petHudX, v -> cfg.gui.petHudX = v,
                () -> cfg.gui.petHudY, v -> cfg.gui.petHudY = v, () -> cfg.gui.petHudScale, v -> cfg.gui.petHudScale = (float) v));
        list.add(widget("SESSION_XP", "Session XP", List.of("session_stats"), def.sessionXpHudX, def.sessionXpHudY, () -> cfg.gui.sessionXpHudX, v -> cfg.gui.sessionXpHudX = v,
                () -> cfg.gui.sessionXpHudY, v -> cfg.gui.sessionXpHudY = v, () -> cfg.gui.sessionXpHudScale, v -> cfg.gui.sessionXpHudScale = (float) v));
        list.add(widget("ENERGY", "Energy", List.of("session_stats"), def.energyHudX, def.energyHudY, () -> cfg.gui.energyHudX, v -> cfg.gui.energyHudX = v,
                () -> cfg.gui.energyHudY, v -> cfg.gui.energyHudY = v, () -> cfg.gui.energyHudScale, v -> cfg.gui.energyHudScale = (float) v));
        list.add(widget("MINING", "Cooldowns & Satchels", List.of("command_cooldowns", "satchel_hud"), def.miningHudX, def.miningHudY, () -> cfg.gui.miningHudX, v -> cfg.gui.miningHudX = v,
                () -> cfg.gui.miningHudY, v -> cfg.gui.miningHudY = v, () -> cfg.gui.miningHudScale, v -> cfg.gui.miningHudScale = (float) v));
        list.add(new HudElement() {
            @Override
            public String id() {
                return "ARMOR";
            }

            @Override
            public List<String> moduleIds() {
                return List.of("armor_hud");
            }

            @Override
            public String name() {
                return "Armor";
            }

            @Override
            public int[] bounds(int sw, int sh) {
                ThePrisonsHudRenderer.HudBounds b = ThePrisonsBanditManager.measureArmorHud(MinecraftClient.getInstance(), cfg);
                return new int[]{b.x, b.y, b.width, b.height};
            }

            @Override
            public void moveTo(int x, int y, int sw, int sh) {
                cfg.gui.armorHudX = Math.max(0, x);
                cfg.gui.armorHudY = Math.max(0, y);
                ThePrisonsClient.CONFIG.saveAsync();
            }

            @Override
            public double scale() {
                return cfg.hud.armorHudScale;
            }

            @Override
            public void setScale(double scale) {
                cfg.hud.armorHudScale = (float) Math.max(0.75D, Math.min(1.35D, scale));
                ThePrisonsClient.CONFIG.saveAsync();
            }

            @Override
            public void drawPreview(DrawContext context, int sw, int sh) {
                ThePrisonsBanditManager.drawArmorHudPreview(context, MinecraftClient.getInstance(), cfg);
            }

            @Override
            public void reset() {
                cfg.gui.armorHudX = -1;
                cfg.hud.armorHudScale = 1.0F;
                ThePrisonsClient.CONFIG.saveAsync();
            }
        });
        return list;
    }

    /** All v1 widget previews at once (they are drawn together by the v1 renderer). */
    public static void drawWidgetPreviews(DrawContext context) {
        ThePrisonsHudRenderer.drawWidgetPreviews(context, MinecraftClient.getInstance(), ThePrisonsClient.CONFIG.get());
    }

    private static HudElement widget(String id, String name, List<String> modules, int defX, int defY, IntSupplier gx, Consumer<Integer> sx, IntSupplier gy,
                                     Consumer<Integer> sy, DoubleSupplier gs, java.util.function.DoubleConsumer ss) {
        return new HudElement() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<String> moduleIds() {
                return modules;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public int[] bounds(int sw, int sh) {
                ThePrisonsHudRenderer.HudBounds b = ThePrisonsHudRenderer.widgetBounds(MinecraftClient.getInstance(),
                        ThePrisonsClient.CONFIG.get(), id);
                return new int[]{b.x, b.y, b.width, b.height};
            }

            @Override
            public void moveTo(int x, int y, int sw, int sh) {
                sx.accept(Math.max(0, x));
                sy.accept(Math.max(0, y));
                ThePrisonsClient.CONFIG.saveAsync();
            }

            @Override
            public double scale() {
                return gs.getAsDouble();
            }

            @Override
            public void setScale(double scale) {
                ss.accept(Math.max(0.5D, Math.min(2.5D, scale)));
                ThePrisonsClient.CONFIG.saveAsync();
            }

            @Override
            public void drawPreview(DrawContext context, int sw, int sh) {
                // drawn together, see drawWidgetPreviews
            }

            @Override
            public void reset() {
                sx.accept(defX);
                sy.accept(defY);
                ss.accept(1.0D);
                ThePrisonsClient.CONFIG.saveAsync();
            }
        };
    }
}
