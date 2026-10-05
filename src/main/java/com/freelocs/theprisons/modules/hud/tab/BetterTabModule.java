package com.freelocs.theprisons.modules.hud.tab;

import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.mixin.ThePrisonsPlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Better Tab for Cosmic Prisons (the idea of Skysoft's Better TAB, written for this mod): the tab list as a card in
 * the mod's design - a title row with players online and your ping, players in tidy columns with their heads, rank
 * colours and ping bars (you highlighted), and the server's header / footer lines in an info column, without the
 * store / website / Discord lines. Slides in when Tab is pressed. Fixed layout, nothing to configure.
 */
public final class BetterTabModule extends Module {
    private static final int ROWS = 20;
    private static final int ROW = 10;
    private static final int PAD = 6;
    private static final int HEAD = 8;
    private static final int GAP = 8;
    private static final int INFO_W = 130;
    private static @Nullable BetterTabModule instance;

    private long lastFrameMs;
    private final java.util.Set<String> loggedHeaders = new java.util.HashSet<>();
    private long openedMs;
    private final Settings.TextSetting hiddenRanks;

    public BetterTabModule() {
        super("better_tab", "Better Tab", Category.HUD, "Widgets",
                "The mod's own tab list: players grouped by rank with faces, rank colours and ping, server info, no ads.",
                Settings.KeybindSetting.NONE);
        hiddenRanks = text("hidden_ranks", "Hidden ranks", "", 256).group("General");
        instance = this;
    }

    public static @Nullable BetterTabModule get() {
        return instance;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    /** Rank groups the player hid in the Shift + Tab list (lower case, comma separated). */
    public java.util.Set<String> hiddenRanks() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String r : hiddenRanks.get().split(",")) {
            if (!r.isBlank()) {
                out.add(r.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public void toggleRank(String rank) {
        java.util.Set<String> set = hiddenRanks();
        String r = rank.toLowerCase(Locale.ROOT);
        if (!set.remove(r)) {
            set.add(r);
        }
        hiddenRanks.set(String.join(",", set));
    }

    public void render(DrawContext context, int screenWidth, PlayerListHud hud) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        long now = Util.getMeasuringTimeMs();
        if (now - lastFrameMs > 250L) {
            openedMs = now; // Tab was pressed again: play the entrance
        }
        lastFrameMs = now;
        float in = Ui.appear(openedMs, 0, 220.0F);
        ThePrisonsPlayerListHudAccessor access = (ThePrisonsPlayerListHudAccessor) hud;
        String raw = (access.theprisons$header() != null ? access.theprisons$header().getString() : "") + " || "
                + (access.theprisons$footer() != null ? access.theprisons$footer().getString() : "");
        if (loggedHeaders.size() < 10 && loggedHeaders.add(raw.replaceAll("[0-9]", ""))) {
            com.freelocs.theprisons.ThePrisonsClient.LOGGER.info("[better_tab] header/footer: {}", raw.replace("\n", " | "));
        }
        int sh = client.getWindow().getScaledHeight();
        TabList.draw(context, client, screenWidth / 2, 8 - Math.round((1.0F - in) * 12.0F), screenWidth - 20, sh - 30, in,
                "", hiddenRanks(), null, -1, -1, 0, new int[3]);
    }

    /** The server's header and footer as lines, without blank lines, the address, store, vote and Discord lines. */
    /** The info lines of the current tab list. */
    static List<String> infoLines(MinecraftClient client) {
        ThePrisonsPlayerListHudAccessor access = (ThePrisonsPlayerListHudAccessor) client.inGameHud.getPlayerListHud();
        return infoLines(access.theprisons$header(), access.theprisons$footer());
    }

    static List<String> infoLines(@Nullable Text header, @Nullable Text footer) {
        List<String> lines = new ArrayList<>();
        for (Text text : new Text[]{header, footer}) {
            if (text == null) {
                continue;
            }
            for (String raw : text.getString().split("\n")) {
                String line = TextStrip.strip(raw);
                String lower = line.toLowerCase(Locale.ROOT);
                if (line.isBlank() || line.chars().allMatch(c -> "-=_▬━─ ".indexOf(c) >= 0)) {
                    continue;
                }
                if (lower.contains("store") || lower.contains("discord") || lower.contains("vote") || lower.contains("www.")
                        || lower.contains(".com") || lower.contains(".net") || lower.contains("playing on")
                        || lower.contains("buy ")) {
                    continue;
                }
                lines.add(line);
            }
        }
        return lines;
    }

    static String fit(TextRenderer tr, String text, int maxWidth) {
        if (Ui.width(tr, text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 1 && Ui.width(tr, s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }
}
