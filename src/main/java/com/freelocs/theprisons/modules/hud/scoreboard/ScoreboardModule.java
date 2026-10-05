package com.freelocs.theprisons.modules.hud.scoreboard;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.client.ClientReadouts;
import com.freelocs.theprisons.core.event.CoreEvents;
import com.freelocs.theprisons.core.event.EventBus;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.gui.hud.HudElement;
import com.freelocs.theprisons.gui.kit.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom scoreboard for Cosmic Prisons (in the spirit of Hypixel SkyBlock's custom scoreboards): replaces the
 * server's sidebar with a card in the mod's design whose rows never move - player, economy, world and session - so
 * the important values are always in the same place. Unknown values show "–". Movable and scalable in the HUD editor.
 */
public final class ScoreboardModule extends Module implements HudElement {
    private static final int WIDTH = 150;
    private static final int PAD = 7;
    private static final int ROW = 10;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd.MM.yy  ·  HH:mm", Locale.ROOT);
    private static @Nullable ScoreboardModule instance;

    private final Settings.IntSetting x;
    private final Settings.IntSetting y;
    private final Settings.DoubleSetting scale;
    private long joinedMs = System.currentTimeMillis();
    /** "(!) Your mining level is at the daily /levelcap" seen (until the level changes). */
    private boolean levelCap;
    private int capLevel = -1;
    private final java.util.Set<String> loggedBoards = new java.util.HashSet<>();
    private ScoreboardData.Values values = ScoreboardData.Values.EMPTY;
    private int ticks;


    public ScoreboardModule() {
        super("scoreboard", "Scoreboard", Category.HUD, "Widgets",
                "Cosmic Prisons scoreboard with fixed rows: player, economy, world and session.", Settings.KeybindSetting.NONE);
        x = integer("x", "X", -1, -1, 4000, 1).group("Position");
        y = integer("y", "Y", -1, -1, 4000, 1).group("Position");
        scale = decimal("scale", "Scale", 1.0D, 0.5D, 2.0D, 0.05D).group("Position");
        instance = this;
    }

    /** The balance as Cosmic's sidebar shows it ("$2,223,016.2"), empty when unknown. */
    public String balance() {
        return values.balance();
    }

    public static @Nullable ScoreboardModule get() {
        return instance;
    }

    /** The vanilla sidebar is hidden while this scoreboard is shown. */
    public static boolean replacesSidebar() {
        ScoreboardModule s = instance;
        return s != null && s.enabled();
    }

    public void register(EventBus bus) {
        bus.subscribe(CoreEvents.ChatReceived.class, this, event -> {
            String line = event.message().getString().toLowerCase(java.util.Locale.ROOT);
            if (line.contains("levelcap") || line.contains("level cap")) {
                levelCap = true;
                MinecraftClient client = MinecraftClient.getInstance();
                capLevel = client.player != null ? client.player.experienceLevel : -1;
            }
        });
        bus.subscribe(CoreEvents.WorldChanged.class, this, event -> {
            if (event.previous() == null && event.current() != null) {
                joinedMs = System.currentTimeMillis();
            }
        });
        bus.subscribe(CoreEvents.TickEnd.class, this, event -> {
            if (!enabled() || ++ticks % 10 != 0) {
                return;
            }
            MinecraftClient client = event.client();
            if (client.world == null) {
                return;
            }
            List<String> sidebar = ClientReadouts.sidebar(client, client.world);
            // The sidebar switches between boards (player / planets): keep what the other board showed.
            values = ScoreboardData.parse(sidebar).over(values);
            if (sidebar != null && !sidebar.isEmpty()) {
                // Every kind of board once per session (the words without numbers identify it), to tune the rules.
                String shape = String.join("|", sidebar).replaceAll("[0-9.,%$()/+-]", "").replaceAll("\\s+", " ");
                if (loggedBoards.size() < 30 && loggedBoards.add(shape)) {
                    ThePrisonsClient.LOGGER.info("[scoreboard] sidebar: {}", String.join(" | ", sidebar));
                }
            }
            if (levelCap && client.player != null && client.player.experienceLevel != capLevel) {
                levelCap = false;
            }
        });
    }

    // ── content ──────────────────────────────────────────────────────────────

    /** Section colours: player pink, economy green, world aqua, session orange, server purple. */
    private static final int PLAYER = 0xFF7AC8;
    private static final int ECONOMY = 0x5DE86B;
    private static final int WORLD = 0x4FD8F0;
    private static final int SESSION = 0xFF9A2E;
    private static final int SERVER = 0xA66CFF;

    /** A drawn row: a section header ({@code value == null}) or label / value, in its section's colour. */
    private record Row(String label, @Nullable String value, int colour, double bar) {
    }

    private final java.util.Map<String, String> lastValues = new java.util.HashMap<>();
    private final java.util.Map<String, Long> changedAt = new java.util.HashMap<>();
    private long shownMs = -1L;
    private long lastFrameMs;

    private List<Row> rows(MinecraftClient client, boolean preview) {
        ScoreboardData.Values v = preview ? sample() : values;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("PLAYER", null, PLAYER, -1));
        rows.add(new Row("Name", client.player != null ? client.player.getName().getString() : "Player", PLAYER, -1));
        rows.add(new Row("Day", or(v.day()), PLAYER, -1));
        // Mining level = the XP bar (Cosmic's player level), not the sidebar's "Level" (that one is the pickaxe's)
        int miningLevel = preview ? 61 : client.player != null ? client.player.experienceLevel : 0;
        float miningProgress = preview ? 0.42F : client.player != null ? client.player.experienceProgress : 0.0F;
        rows.add(new Row("Mining Level", miningLevel + (levelCap ? "  ·  cap" : "  →  " + (miningLevel + 1)), PLAYER, -1));
        rows.add(new Row("Progress", levelCap ? "level cap reached" : String.format(java.util.Locale.ROOT, "%.1f%%",
                miningProgress * 100), PLAYER, levelCap ? 1.0D : miningProgress));
        rows.add(new Row("Record", or(v.record()), PLAYER, -1));
        net.minecraft.item.ItemStack held = client.player != null ? client.player.getMainHandStack() : net.minecraft.item.ItemStack.EMPTY;
        boolean pickaxe = preview || held.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
        if (pickaxe && !v.level().isEmpty()) {
            rows.add(new Row("PICKAXE", null, WORLD, -1));
            String name = preview ? "Stone Pickaxe 12" : com.freelocs.theprisons.core.client.TextStrip.strip(held.getName().getString());
            rows.add(new Row("Pickaxe", name, WORLD, -1));
            rows.add(new Row("Level", v.nextLevel().isEmpty() ? v.level() : v.level() + "  →  " + v.nextLevel(), WORLD, -1));
            rows.add(new Row("Progress", v.progress() < 0 ? "–" : String.format(java.util.Locale.ROOT, "%.1f%%", v.progress() * 100)
                    + (v.toGo().isEmpty() ? "" : "  ·  " + v.toGo() + " left"), WORLD, v.progress()));
            rows.add(new Row("Total XP", v.xpTotal() < 0 ? "–" : ScoreboardData.compact(v.xpTotal()), WORLD, -1));
        }
        rows.add(new Row("ECONOMY", null, ECONOMY, -1));
        rows.add(new Row("Balance", v.balance().isEmpty() ? "–" : ScoreboardData.money(v.balance()), ECONOMY, -1));
        rows.add(new Row("Cosmic Coins", or(v.coinsLifetime()) + (v.coinsSeasonal().isEmpty() ? "" : "  ·  S " + v.coinsSeasonal()),
                ECONOMY, -1));
        rows.add(new Row("Credits", or(v.credits()), ECONOMY, -1));
        rows.add(new Row("WORLD", null, WORLD, -1));
        rows.add(new Row("Zone", or(v.zone()), WORLD, -1));
        if (!v.gang().isEmpty()) {
            rows.add(new Row("Gang", v.gang(), WORLD, -1));
        }
        BlockPos pos = client.player != null ? client.player.getBlockPos() : BlockPos.ORIGIN;
        rows.add(new Row("Coords", pos.getX() + "  " + pos.getY() + "  " + pos.getZ(), WORLD, -1));
        if (!v.planets().isEmpty()) {
            rows.add(new Row("PLANETS", null, SERVER, -1));
            for (ScoreboardData.Planet planet : v.planets()) {
                double f = planet.max() > 0 ? Math.min(1.0D, planet.value() / (double) planet.max()) : -1;
                rows.add(new Row(planet.name(), planet.value() + " / " + planet.max(), SERVER, f));
            }
        }
        rows.add(new Row("SESSION", null, SESSION, -1));
        rows.add(new Row("Online", ScoreboardData.duration(preview ? 3_720_000L : System.currentTimeMillis() - joinedMs), SESSION, -1));
        rows.add(new Row("Ping", ping(client) + " ms", SESSION, -1));
        rows.add(new Row("FPS", Integer.toString(client.getCurrentFps()), SESSION, -1));
        if (!v.extra().isEmpty()) {
            rows.add(new Row("SERVER", null, SERVER, -1));
            for (String line : v.extra()) {
                int colon = line.indexOf(':');
                if (colon > 0 && colon < line.length() - 1) {
                    rows.add(new Row(line.substring(0, colon).trim(), line.substring(colon + 1).trim(), SERVER, -1));
                } else {
                    rows.add(new Row(line, "", SERVER, -1));
                }
            }
        }
        return rows;
    }

    private static ScoreboardData.Values sample() {
        return new ScoreboardData.Values("9", "Neutral", "$2,223,016.2", "1,100", "0", "0", "Safezone", "81", 16_321_009L,
                0.731D, "308,993", "82", "", List.of(new ScoreboardData.Planet("Celestial", 85, 500),
                new ScoreboardData.Planet("Aether", 131, 500), new ScoreboardData.Planet("Spaceship", 36, 1500)),
                List.of("Guarded W"));
    }

    private static String or(String value) {
        return value == null || value.isEmpty() ? "–" : value;
    }

    private static int ping(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return 0;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    private static int height(List<Row> rows) {
        int h = 30;
        for (Row row : rows) {
            h += row.value() == null ? ROW + 4 : ROW;
            if (row.bar() >= 0) {
                h += 6;
            }
        }
        return h + 16;
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    public void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || client.player == null || client.options.hudHidden) {
            return;
        }
        draw(context, client, false);
    }

    private void draw(DrawContext context, MinecraftClient client, boolean preview) {
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        if (shownMs < 0 || now - lastFrameMs > 1_000L) {
            shownMs = now; // first frame after a while (join, HUD back on): rows slide in again
        }
        lastFrameMs = now;
        List<Row> rows = rows(client, preview);
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int[] b = bounds(sw, sh);
        float s = (float) effectiveScale(rows, sh);
        TextRenderer tr = client.textRenderer;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(b[0], b[1]);
        context.getMatrices().scale(s, s);
        int h = height(rows);
        float in = Ui.appear(shownMs, 0, 300.0F);
        Ui.shadowCard(context, 0, 0, WIDTH, h, in);
        // rainbow header strip through all section colours
        int[] strip = {PLAYER, ECONOMY, WORLD, SESSION, SERVER};
        double t = now / 1500.0D;
        for (int x = 3; x < WIDTH - 3; x++) {
            double f = ((x / (double) WIDTH) + t * 0.15D) % 1.0D * strip.length;
            int i = (int) f;
            int colour = Ui.mix(strip[i % strip.length], strip[(i + 1) % strip.length], (float) (f - i));
            context.fill(x, 0, x + 1, 2, Ui.argb(Math.round(255 * in), colour));
        }
        Ui.shimmer(context, tr, "COSMIC PRISONS", PAD, 7, in);
        Ui.draw(context, tr, LocalDateTime.now().format(CLOCK), PAD, 18, Ui.MUTED, Math.round(255 * in));
        int yy = 31;
        int index = 0;
        int sectionColour = PLAYER;
        for (Row row : rows) {
            float a = Ui.appear(shownMs, 60L + index * 35L, 260.0F);
            int slide = Math.round((1.0F - a) * 12.0F);
            int alpha = Math.round(255 * a);
            if (row.value() == null) {
                sectionColour = row.colour();
                yy += 2;
                Ui.round(context, PAD - 3 + slide, yy - 1, WIDTH - PAD * 2 + 6, ROW + 1, Ui.argb(Math.round(48 * a), sectionColour));
                context.fill(PAD - 1 + slide, yy + 2, PAD + 2 + slide, yy + 5, Ui.argb(alpha, sectionColour));
                Ui.draw(context, tr, row.label(), PAD + 6 + slide, yy + 1, sectionColour, alpha);
                yy += ROW + 2;
            } else {
                context.fill(PAD - 3, yy, PAD - 2, yy + ROW - 1, Ui.argb(Math.round(150 * a), sectionColour));
                Ui.draw(context, tr, row.label(), PAD + 1 + slide, yy + 1, Ui.LABEL, alpha);
                String value = fit(tr, row.value(), WIDTH - PAD * 2 - 52);
                String key = row.label();
                if (!value.equals(lastValues.put(key, value)) && !preview) {
                    changedAt.put(key, now);
                }
                float flash = Ui.animationsOn() ? Math.max(0.0F, 1.0F - (now - changedAt.getOrDefault(key, 0L)) / 700.0F) : 0.0F;
                int colour = Ui.mix(Ui.mix(Ui.VALUE, row.colour(), 0.35F), 0xFFFFFF, flash);
                Ui.drawRight(context, tr, value, WIDTH - PAD + slide, yy + 1, colour, alpha);
                yy += ROW;
                if (row.bar() >= 0) {
                    int bw = WIDTH - PAD * 2;
                    Ui.round(context, PAD, yy + 1, bw, 4, Ui.argb(Math.round(60 * a), 0xFFFFFF));
                    int fill = (int) Math.round(bw * row.bar() * a);
                    if (fill > 3) {
                        context.fillGradient(PAD + 1, yy + 2, PAD + fill - 1, yy + 4, Ui.argb(alpha, row.colour()),
                                Ui.argb(alpha, Ui.mix(row.colour(), 0xFFFFFF, 0.45F)));
                    }
                    yy += 6;
                }
            }
            index++;
        }
        ServerInfo server = client.getCurrentServerEntry();
        String footer = server != null ? server.address : "cosmicprisons.com";
        Ui.drawCentered(context, tr, footer, WIDTH / 2, h - 11, Ui.mix(PLAYER, SERVER, Ui.pulse(3000L)), Math.round(255 * in));
        context.getMatrices().popMatrix();
    }

    private static String fit(TextRenderer tr, String text, int maxWidth) {
        if (Ui.width(tr, text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 1 && Ui.width(tr, s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** The set scale, smaller when the scoreboard would not fit on the screen. */
    private double effectiveScale(List<Row> rows, int screenHeight) {
        return Math.min(scale.get(), (screenHeight - 8.0D) / height(rows));
    }

    // ── HudElement ───────────────────────────────────────────────────────────


    @Override
    public int[] bounds(int screenWidth, int screenHeight) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Row> rows = rows(client, !enabled() || values == ScoreboardData.Values.EMPTY);
        double s = effectiveScale(rows, screenHeight);
        int w = (int) Math.round(WIDTH * s);
        int h = (int) Math.round(height(rows) * s);
        int bx = x.get() < 0 ? screenWidth - w - 4 : Math.min(x.get(), Math.max(0, screenWidth - w));
        int by = y.get() < 0 ? (screenHeight - h) / 2 : Math.min(y.get(), Math.max(0, screenHeight - h));
        return new int[]{bx, by, w, h};
    }

    @Override
    public void moveTo(int nx, int ny, int screenWidth, int screenHeight) {
        x.set(Math.max(0, nx));
        y.set(Math.max(0, ny));
    }

    @Override
    public double scale() {
        return scale.get();
    }

    @Override
    public void setScale(double value) {
        scale.set(Math.max(0.5D, Math.min(2.0D, value)));
    }

    @Override
    public void drawPreview(DrawContext context, int screenWidth, int screenHeight) {
        draw(context, MinecraftClient.getInstance(), true);
    }

    @Override
    public void reset() {
        x.set(-1);
        y.set(-1);
        scale.set(1.0D);
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }
}
