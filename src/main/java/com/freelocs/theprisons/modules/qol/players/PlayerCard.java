package com.freelocs.theprisons.modules.qol.players;

import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.gui.kit.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A player's card in the scoreboard look: head, tab name and ping, then what the tab list says (level, rank, gang) and,
 * while the player is nearby, health, distance and the gear (armour with its mask, both hands) with item icons.
 * Drawn by the right-click player HUD and by the Shift + Tab player list.
 */
public final class PlayerCard {
    public static final int WIDTH = 184;
    private static final int PAD = 7;
    private static final int ROW = 10;
    private static final int GEAR_ROW = 17;
    /** Cosmic's tab / chat names: "(82) <Transcendent> M4cL4ren [Gang]". */
    private static final Pattern LEVEL = Pattern.compile("^\\(?(\\d{1,3})\\)");
    private static final Pattern RANK = Pattern.compile("<([^>]{2,24})>");
    private static final Pattern GANG = Pattern.compile("\\[([^\\]]{1,20})]\\s*$");
    private static final Pattern MASK = Pattern.compile("(?i)mask\\s*\\(([^)]+)\\)");
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private PlayerCard() {
    }

    /** What the card shows: the tab list's entry and the player entity while it is loaded nearby. */
    public record Subject(PlayerListEntry entry, @Nullable PlayerEntity player) {
        public String name() {
            return entry.getProfile().name();
        }
    }

    /** The subject for a player name / uuid, or null when the player is not in the tab list. */
    public static @Nullable Subject of(@Nullable PlayerListEntry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entry == null) {
            return null;
        }
        PlayerEntity player = client.world != null ? client.world.getPlayerByUuid(entry.getProfile().id()) : null;
        return new Subject(entry, player);
    }

    /** The tab list's facts parsed from the shown name (formatting stripped). */
    record Facts(String level, String rank, String gang) {
        static Facts of(String shown) {
            String s = TextStrip.strip(shown).trim();
            Matcher l = LEVEL.matcher(s);
            Matcher r = RANK.matcher(s);
            Matcher g = GANG.matcher(s);
            return new Facts(l.find() ? l.group(1) : "", r.find() ? r.group(1).replaceAll("[^A-Za-z0-9+ ]", "").trim() : "",
                    g.find() ? g.group(1).trim() : "");
        }
    }

    public static int height(Subject s) {
        int rows = factRows(s).size();
        int h = 31 + rows * ROW;
        if (s.player() != null) {
            h += 6 + ROW + gear(s.player()).size() * GEAR_ROW;
        } else {
            h += 4 + ROW;
        }
        return h + PAD - 2;
    }

    /** Draws the card at x / y; returns its height. */
    public static int draw(DrawContext c, TextRenderer tr, int x, int y, Subject s, float alpha) {
        MinecraftClient client = MinecraftClient.getInstance();
        int h = height(s);
        int a = Math.round(255 * alpha);
        Ui.shadowCard(c, x, y, WIDTH, h, alpha);
        Ui.flowLine(c, x, x + WIDTH, y, alpha);

        // head, tab name, account name and ping
        PlayerSkinDrawer.draw(c, s.entry().getSkinTextures(), x + PAD, y + 7, 16);
        Text shown = client.inGameHud.getPlayerListHud().getPlayerName(s.entry());
        c.drawText(tr, fit(tr, shown, WIDTH - PAD * 2 - 44), x + PAD + 21, y + 7, Ui.argb(a, 0xFFFFFF), true);
        if (!TextStrip.strip(shown.getString()).trim().equals(s.name())) {
            Ui.draw(c, tr, s.name(), x + PAD + 21, y + 17, Ui.LABEL, a);
        }
        int latency = s.entry().getLatency();
        Ui.drawRight(c, tr, latency > 0 ? latency + " ms" : "– ms", x + WIDTH - PAD, y + 7, latencyColour(latency), a);
        Ui.line(c, x + PAD, x + WIDTH - PAD, y + 27, 0.5F * alpha);

        int ry = y + 31;
        for (String[] row : factRows(s)) {
            Ui.draw(c, tr, row[0], x + PAD, ry, Ui.LABEL, a);
            Ui.drawRight(c, tr, row[1], x + WIDTH - PAD, ry, Integer.parseInt(row[2]), a);
            ry += ROW;
        }

        PlayerEntity p = s.player();
        if (p == null) {
            ry += 4;
            Ui.drawCentered(c, tr, "Not nearby  ·  gear unknown", x + WIDTH / 2, ry, Ui.MUTED, a);
            return h;
        }
        // health bar
        ry += 4;
        float health = p.getHealth();
        float max = Math.max(1.0F, p.getMaxHealth());
        float absorb = p.getAbsorptionAmount();
        Ui.draw(c, tr, "Health", x + PAD, ry, Ui.LABEL, a);
        int barX = x + PAD + 40;
        int barW = WIDTH - PAD * 2 - 40 - 44;
        c.fill(barX, ry + 2, barX + barW, ry + 6, Ui.argb(a / 4, 0xFFFFFF));
        float share = health > 0.0F ? Math.min(1.0F, health / max) : 0.0F;
        int hc = share > 0.6F ? Ui.GOOD : share > 0.3F ? Ui.WARN : Ui.BAD;
        c.fill(barX, ry + 2, barX + Math.round(barW * share), ry + 6, Ui.argb(a, hc));
        String hp = health > 0.0F ? String.format(Locale.ROOT, "%.0f/%.0f", health, max) + (absorb > 0.0F
                ? String.format(Locale.ROOT, " +%.0f", absorb) : "") : "?";
        Ui.drawRight(c, tr, hp, x + WIDTH - PAD, ry, hc, a);
        ry += ROW + 2;
        // gear with item icons
        for (Gear g : gear(p)) {
            c.drawItem(g.stack(), x + PAD, ry);
            Text name = g.stack().getName();
            c.drawText(tr, fit(tr, name, WIDTH - PAD * 2 - 22), x + PAD + 20, ry + (g.note().isEmpty() ? 4 : 0),
                    Ui.argb(a, 0xFFFFFF), true);
            if (!g.note().isEmpty()) {
                Ui.draw(c, tr, g.note(), x + PAD + 20, ry + 9, Ui.theme().accent(), a);
            }
            ry += GEAR_ROW;
        }
        return h;
    }

    record Gear(ItemStack stack, String note) {
    }

    /** Armour (with the mask attached to the helmet) and both hands, empty slots left out. */
    static List<Gear> gear(PlayerEntity p) {
        List<Gear> out = new ArrayList<>();
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack stack = p.getEquippedStack(slot);
            if (!stack.isEmpty()) {
                out.add(new Gear(stack, slot == EquipmentSlot.HEAD ? maskOf(stack) : ""));
            }
        }
        ItemStack main = p.getMainHandStack();
        if (!main.isEmpty()) {
            out.add(new Gear(main, "Main hand"));
        }
        ItemStack off = p.getOffHandStack();
        if (!off.isEmpty()) {
            out.add(new Gear(off, "Off hand"));
        }
        return out;
    }

    /** "Mask (Turkey Mask)" in a helmet's lore -> "Turkey Mask". */
    static String maskOf(ItemStack helmet) {
        LoreComponent lore = helmet.get(DataComponentTypes.LORE);
        if (lore == null) {
            return "";
        }
        for (Text line : lore.lines()) {
            Matcher m = MASK.matcher(TextStrip.strip(line.getString()));
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    /** {label, value, rgb} rows: level, rank and gang from the tab name, the distance while nearby. */
    private static List<String[]> factRows(Subject s) {
        List<String[]> rows = new ArrayList<>();
        Text shown = MinecraftClient.getInstance().inGameHud.getPlayerListHud().getPlayerName(s.entry());
        Facts f = Facts.of(shown.getString());
        if (!f.level().isEmpty()) {
            rows.add(new String[]{"Level", f.level(), String.valueOf(Ui.theme().title())});
        }
        if (!f.rank().isEmpty()) {
            rows.add(new String[]{"Rank", f.rank(), String.valueOf(Ui.VALUE)});
        }
        if (!f.gang().isEmpty()) {
            rows.add(new String[]{"Gang", f.gang(), String.valueOf(Ui.theme().accent())});
        }
        PlayerStats.Stats stats = PlayerStats.cached(s.name());
        if (stats != null) {
            for (String key : new String[]{"Balance", "Mining", "Bandit Kills", "Playtime"}) {
                String v = stats.rows().get(key);
                if (v != null) {
                    rows.add(new String[]{key, v, String.valueOf(key.equals("Balance") ? Ui.GOOD : Ui.VALUE)});
                }
            }
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (s.player() != null && client.player != null) {
            double d = Math.sqrt(s.player().squaredDistanceTo(client.player));
            rows.add(new String[]{"Distance", String.format(Locale.ROOT, "%.1f m", d), String.valueOf(Ui.VALUE)});
        }
        return rows;
    }

    private static int latencyColour(int latency) {
        return latency <= 0 ? Ui.MUTED : latency < 150 ? Ui.GOOD : latency < 300 ? Ui.WARN : Ui.BAD;
    }

    private static Text fit(TextRenderer tr, Text text, int maxWidth) {
        if (tr.getWidth(text) <= maxWidth) {
            return text;
        }
        String s = TextStrip.strip(text.getString());
        while (s.length() > 1 && tr.getWidth(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return Text.literal(s + "…").setStyle(text.getStyle());
    }
}
