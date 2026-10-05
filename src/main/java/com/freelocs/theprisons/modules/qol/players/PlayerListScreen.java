package com.freelocs.theprisons.modules.qol.players;

import com.freelocs.theprisons.gui.kit.HidesHud;
import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.modules.hud.tab.BetterTabModule;
import com.freelocs.theprisons.modules.hud.tab.TabList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shift + Tab: the mod's tab list as a screen - click a player for the player viewer, click a rank header to hide /
 * show that rank, type to search, scroll for more columns. Esc or Tab closes it.
 */
public final class PlayerListScreen extends Screen implements HidesHud {
    private final long openedMs = Util.getMeasuringTimeMs();
    private String filter = "";
    private @Nullable UUID selected;
    private PlayerViewer.Page page = PlayerViewer.Page.OVERVIEW;
    private int firstColumn;
    private int columns;
    private int fit;
    private List<TabList.Slot> slots = List.of();
    private List<PlayerViewer.Hit> viewerHits = List.of();

    public PlayerListScreen() {
        this(null);
    }

    /** With a player already selected (their viewer shown). */
    public PlayerListScreen(@Nullable UUID preselected) {
        super(Text.literal("Players"));
        selected = preselected;
    }

    public void showPage(PlayerViewer.Page p) {
        page = p;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        float in = Ui.appear(openedMs, 0, 250.0F);
        context.fillGradient(0, 0, width, height, Ui.argb(Math.round(150 * in), 0x07060C), Ui.argb(Math.round(190 * in), 0x0C0A16));
    }

    private static Set<String> hidden() {
        BetterTabModule tab = BetterTabModule.get();
        return tab != null ? tab.hiddenRanks() : new HashSet<>();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) {
            return;
        }
        float in = Ui.appear(openedMs, 0, 300.0F);
        int alpha = Math.round(255 * in);
        PlayerCard.Subject subject = selected != null ? PlayerCard.of(mc.getNetworkHandler().getPlayerListEntry(selected)) : null;
        int viewerW = subject != null ? PlayerViewer.WIDTH + 12 : 0;
        int listW = width - 16 - viewerW;
        int top = 26;

        String search = filter.isEmpty() ? "Type to search  ·  click a rank to hide it  ·  click a player to view"
                : "Search: " + filter + (Util.getMeasuringTimeMs() / 500 % 2 == 0 ? "_" : "");
        Ui.drawCentered(context, textRenderer, search, 8 + listW / 2, 10, filter.isEmpty() ? Ui.MUTED : Ui.VALUE, alpha);

        int[] out = new int[3];
        slots = TabList.draw(context, mc, 8 + listW / 2, top, listW, height - top - 8, in, filter, hidden(), selected,
                mouseX, mouseY, firstColumn, out);
        columns = out[0];
        fit = out[1];

        if (subject != null) {
            int vx = width - 8 - PlayerViewer.WIDTH;
            int vh = Math.min(height - top - 20, 300);
            viewerHits = PlayerViewer.draw(context, textRenderer, vx, top, vh, subject, page, mouseX, mouseY, in);
        } else {
            viewerHits = List.of();
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            for (PlayerViewer.Hit hit : viewerHits) {
                if (hit.contains(click.x(), click.y())) {
                    if (hit.page() != null) {
                        page = hit.page();
                    } else if ("refresh".equals(hit.action()) && selected != null) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(selected) : null;
                        if (entry != null) {
                            PlayerStats.ask(entry.getProfile().name());
                        }
                    }
                    return true;
                }
            }
            for (TabList.Slot slot : slots) {
                if (slot.contains(click.x(), click.y())) {
                    if (slot.rank() != null) {
                        BetterTabModule tab = BetterTabModule.get();
                        if (tab != null) {
                            tab.toggleRank(slot.rank());
                        }
                    } else if (slot.id() != null) {
                        selected = slot.id().equals(selected) ? null : slot.id();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        firstColumn = Math.max(0, Math.min(Math.max(0, columns - fit), firstColumn - (int) Math.signum(verticalAmount)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_TAB) {
            close();
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_BACKSPACE && !filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            firstColumn = 0;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar() && filter.length() < 16) {
            String c = input.asString();
            if (c.matches("[A-Za-z0-9_ ]")) {
                filter += c;
                firstColumn = 0;
                return true;
            }
        }
        return super.charTyped(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
