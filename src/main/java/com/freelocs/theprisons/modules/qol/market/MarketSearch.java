package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.gui.kit.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Inventory-only Cosmic market search bar and item browser. */
public final class MarketSearch {
    private static String query = "";
    private static MarketCategory group = null;
    private static int page;
    private static boolean focused;

    private MarketSearch() {}

    public static String query() { return query; }
    public static boolean focused() { return focused; }

    public static boolean charTyped(char c) {
        if (!focused || !isAllowed(c) || query.length() >= 28) return false;
        query += c;
        page = 0;
        focused = true;
        return true;
    }

    /** Key press in the inventory; true = the search used it (the screen must not see it). */
    public static boolean keyPressed(int key, int scancode) {
        if (key == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            page = 0;
            focused = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && focused) {
            focused = false;
            return true;
        }
        if (focused && (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z || key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9
                || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_PERIOD)) {
            // The search owns the letters: "e" must not close the inventory and digits must not swap the hotbar.
            String name = key == GLFW.GLFW_KEY_SPACE ? " " : GLFW.glfwGetKeyName(key, scancode);
            if (name != null && name.length() == 1) {
                charTyped(name.charAt(0));
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) { page = Math.max(0, page - 1); return focused; }
        if (key == GLFW.GLFW_KEY_RIGHT) { page++; return focused; }
        if (key == GLFW.GLFW_KEY_TAB) { focused = !focused; return true; }
        return false;
    }

    /** Mouse wheel over the inventory: pages of the result list while the search is open. */
    public static boolean scroll(double vertical) {
        if (!focused && query.isEmpty()) return false;
        page = Math.max(0, page + (vertical < 0 ? 1 : -1));
        return true;
    }

    public static boolean click(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        int x = screenWidth / 2 - 110;
        int y = screenHeight / 2 + 72;
        if (mouseY >= y && mouseY <= y + 18 && mouseX >= x && mouseX <= x + 220) {
            focused = true;
            return true;
        }
        int tabsY = y + 22;
        String[] tabs = {"ALL", "COS", "UPG", "MIN", "COM", "OTHER"};
        for (int i = 0; i < tabs.length; i++) {
            int tx = x + i * 36;
            if (mouseX >= tx && mouseX <= tx + 34 && mouseY >= tabsY && mouseY <= tabsY + 14) {
                group = switch (i) { case 1 -> MarketCategory.COSMETICS; case 2 -> MarketCategory.UPGRADES; case 3 -> MarketCategory.MINING; case 4 -> MarketCategory.COMBAT; case 5 -> MarketCategory.OTHER; default -> null; };
                page = 0;
                focused = true;
                return true;
            }
        }
        return false;
    }

    public static void render(DrawContext c, TextRenderer tr, int width, int height) {
        MarketModule module = MarketModule.get();
        if (module == null || !module.enabled()) return;
        PriceBook book = module.book();
        int x = width / 2 - 110;
        int y = height / 2 + 72;
        Ui.shadowCard(c, x, y, 220, 18, 0.95f);
        Ui.outline(c, x, y, 220, 18, Ui.argb(focused ? 210 : 120, Ui.theme().accent()));
        Ui.draw(c, tr, "⌕", x + 6, y + 4, Ui.theme().accent(), 255);
        String shown = query.isEmpty() ? "Search Cosmic items… (click or Tab)" : query;
        Ui.draw(c, tr, shown, x + 20, y + 4, query.isEmpty() ? Ui.MUTED : Ui.VALUE, 255);

        String[] tabs = {"ALL", "COS", "UPG", "MIN", "COM", "OTHER"};
        int tabsY = y + 22;
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == 0 && group == null) || (i == 1 && group == MarketCategory.COSMETICS)
                    || (i == 2 && group == MarketCategory.UPGRADES) || (i == 3 && group == MarketCategory.MINING)
                    || (i == 4 && group == MarketCategory.COMBAT) || (i == 5 && group == MarketCategory.OTHER);
            Ui.round(c, x + i * 36, tabsY, 34, 14, Ui.argb(active ? 150 : 65, active ? Ui.theme().accent() : 0x000000));
            Ui.draw(c, tr, tabs[i], x + i * 36 + 5, tabsY + 3, active ? Ui.VALUE : Ui.MUTED, 255);
        }

        if (query.isEmpty() && !focused) return;
        List<PriceBook.Entry> entries = new ArrayList<>();
        for (PriceBook.Entry e : book.all()) {
            if (group != null && !e.category().startsWith(group.label + "/")) continue;
            if (!matches(e, query)) continue;
            entries.add(e);
        }
        entries.sort(Comparator.comparing(PriceBook.Entry::name, String.CASE_INSENSITIVE_ORDER));
        int perPage = 6;
        int maxPage = Math.max(0, (entries.size() - 1) / perPage);
        page = Math.min(page, maxPage);
        int start = page * perPage;
        int cardY = tabsY + 18;
        int cardH = 28;
        int shownCount = Math.min(perPage, entries.size() - start);
        if (shownCount == 0) {
            Ui.shadowCard(c, x, cardY, 220, 28, 0.95f);
            Ui.draw(c, tr, "No Cosmic item known yet", x + 9, cardY + 10, Ui.MUTED, 255);
            return;
        }
        for (int i = 0; i < shownCount; i++) {
            PriceBook.Entry e = entries.get(start + i);
            int cy = cardY + i * cardH;
            Ui.card(c, x, cy, 220, cardH, 0.92f);
            drawIcon(c, e, x + 7, cy + 6);
            String name = e.name();
            int maxName = 110;
            if (tr.getWidth(name) > maxName) {
                while (name.length() > 3 && tr.getWidth(name + "…") > maxName) name = name.substring(0, name.length() - 1);
                name += "…";
            }
            Ui.draw(c, tr, name, x + 29, cy + 4, Ui.VALUE, 255);
            String price = e.price() > 0 ? "$" + Money.compact(e.price()) : "NO BIN";
            double energy = e.price() > 0 ? book.inEnergy(e.price()) : -1;
            Ui.drawRight(c, tr, price, x + 214, cy + 4, Ui.GOOD, 255);
            String en = energy > 0 ? Money.compact(energy) + " E" : "— E";
            Ui.draw(c, tr, en, x + 29, cy + 15, Ui.MUTED, 255);
            Ui.drawRight(c, tr, age(System.currentTimeMillis() - e.seenMs()), x + 214, cy + 15, Ui.MUTED, 255);
        }
        if (maxPage > 0) Ui.drawRight(c, tr, (page + 1) + "/" + (maxPage + 1), x + 214, cardY + shownCount * cardH + 4, Ui.MUTED, 255);
    }

    private static boolean matches(PriceBook.Entry e, String q) {
        if (q.isBlank()) return focused;
        String hay = (e.name() + " " + e.category()).toLowerCase(Locale.ROOT);
        for (String word : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!hay.contains(word)) return false;
        return true;
    }

    private static void drawIcon(DrawContext c, PriceBook.Entry e, int x, int y) {
        try {
            Identifier id = Identifier.of(e.icon().isBlank() ? "minecraft:paper" : e.icon());
            ItemStack stack = new ItemStack(Registries.ITEM.get(id));
            c.drawItem(stack, x, y);
        } catch (RuntimeException ignored) {}
    }

    private static boolean isAllowed(char c) { return Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' || c == '.'; }

    public static String age(long ms) {
        if (ms < 0) return "now";
        Duration d = Duration.ofMillis(ms);
        if (d.toDays() > 0) return d.toDays() + "d ago";
        if (d.toHours() > 0) return d.toHours() + "h ago";
        if (d.toMinutes() > 0) return d.toMinutes() + "m ago";
        return Math.max(1, d.toSeconds()) + "s ago";
    }
}
