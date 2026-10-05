package com.freelocs.theprisons.core.client;

import com.freelocs.theprisons.mixin.ThePrisonsBossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Reads what the server shows the player: the sidebar, boss bars and item lore (formatting stripped). */
public final class ClientReadouts {
    private ClientReadouts() {
    }

    /** The sidebar's lines top to bottom, as shown ({@code null} = no sidebar). */
    public static @Nullable List<String> sidebar(MinecraftClient client, ClientWorld clientWorld) {
        net.minecraft.scoreboard.Scoreboard board = clientWorld.getScoreboard();
        net.minecraft.scoreboard.ScoreboardObjective objective = null;
        if (client.player != null) {
            net.minecraft.scoreboard.Team team = board.getScoreHolderTeam(client.player.getNameForScoreboard());
            if (team != null) {
                net.minecraft.scoreboard.ScoreboardDisplaySlot slot =
                        net.minecraft.scoreboard.ScoreboardDisplaySlot.fromFormatting(team.getColor());
                if (slot != null) {
                    objective = board.getObjectiveForSlot(slot);
                }
            }
        }
        if (objective == null) {
            objective = board.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        }
        if (objective == null) {
            return null;
        }
        List<net.minecraft.scoreboard.ScoreboardEntry> entries = new java.util.ArrayList<>(board.getScoreboardEntries(objective));
        entries.removeIf(net.minecraft.scoreboard.ScoreboardEntry::hidden);
        entries.sort(java.util.Comparator.comparingInt(net.minecraft.scoreboard.ScoreboardEntry::value).reversed()
                .thenComparing(net.minecraft.scoreboard.ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER));
        List<String> lines = new java.util.ArrayList<>();
        for (net.minecraft.scoreboard.ScoreboardEntry entry : entries) {
            net.minecraft.scoreboard.Team team = board.getScoreHolderTeam(entry.owner());
            lines.add(TextStrip.strip(net.minecraft.scoreboard.Team.decorateName(team, entry.name()).getString()));
        }
        return lines;
    }

    /** The boss bars' titles, top to bottom. */
    public static List<String> bossBarTitles(MinecraftClient client) {
        List<String> titles = new ArrayList<>();
        if (client.inGameHud == null) {
            return titles;
        }
        for (ClientBossBar bar : ((ThePrisonsBossBarHudAccessor) client.inGameHud.getBossBarHud()).theprisons$getBossBars().values()) {
            titles.add(TextStrip.strip(bar.getName().getString()));
        }
        return titles;
    }

    /** An item's lore lines. */
    public static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                lines.add(TextStrip.strip(line.getString()));
            }
        }
        return lines;
    }
}
