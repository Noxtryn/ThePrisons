package com.freelocs.theprisons.modules.hud;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.client.ClientReadouts;
import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.core.event.CoreEvents;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.nav.Pos;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.core.world.WorldCache;
import com.freelocs.theprisons.modules.mining.ore.OreMacroModule;
import com.freelocs.theprisons.modules.mining.ore.route.RouteStore;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The session HUD ("Nebula" look, see {@link NebulaHudRenderer}) with live Cosmic Prisons numbers from
 * {@link CosmicStats}. Work is spread over ticks: pending ore checks every tick (a small map), sidebar / boss bars /
 * pickaxe lore / XP every {@value #READ_TICKS} ticks, the snapshot every {@value #SNAPSHOT_TICKS}; the frame only draws.
 *
 * <p>Ores: the broken block, plus every ore within {@value #PROC_RADIUS} blocks around it (1 up / down) that turns into
 * something else within {@value #PROC_TICKS} ticks - the blocks Fractured / Shatter (and the pickaxe's left / right
 * blocks) take with it. Learned routes: recorded routes plus the guarded routes in the
 * route memory ({@link com.freelocs.theprisons.modules.mining.ore.RouteMemory}).</p>
 */
public final class SessionHudModule extends Module implements com.freelocs.theprisons.gui.hud.HudElement {
    static final int PROC_RADIUS = 3;
    static final int PROC_TICKS = 10;
    private static final int READ_TICKS = 10;
    private static final int SNAPSHOT_TICKS = 5;

    private final WorldCache world;
    private final RouteStore routes;
    private final OreMacroModule macro;
    private final Settings.IntSetting x;
    private final Settings.IntSetting y;
    private final Settings.DoubleSetting scale;
    private final Settings.BoolSetting sleekFont;
    private final Settings.BoolSetting logUnparsed;
    private final Settings.BoolSetting showState;
    private final Settings.BoolSetting showEta;
    private final Settings.BoolSetting showYield;

    /** Chat effects, boosters, tax, pickaxe lore: everything that is not tied to one activity (wall clock). */
    private CosmicStats stats = newStats();
    /** Uptime, ores and rates per activity ("Iron Ore", "Gold Bandits", "Meteorites"), see {@link ActivityLog}. */
    private final ActivityLog activities = new ActivityLog(name -> new CosmicStats(0L, line -> {
    }));
    /** A meteor / meteorite shower crashed: blocks that are not ores count as "Meteorites" until then. */
    private long meteorUntilMs;
    private @Nullable String activityKey;
    private boolean attackHooked;
    private volatile CosmicStats.@Nullable Snapshot snapshot;
    /** Ores around a broken block that may still go with it: pos → last tick to count it. */
    private final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();
    /** Which hit a pending ore belongs to, and the hits that already took a proc block along (for blocks per proc). */
    private final Long2IntOpenHashMap pendingHit = new Long2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet procHits = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    private int hitId;
    private int ticks;
    private boolean inWorld;
    private int recordedRoutes;

    public SessionHudModule(WorldCache world, RouteStore routes, OreMacroModule macro) {
        super("session_hud", "Session HUD", Category.HUD, "Widgets",
                "UpTime, ores per second (with Fractured / Shatter blocks), energy and XP per hour, guard tax, boosters "
                        + "and learned routes in a slim card.", Settings.KeybindSetting.NONE);
        this.world = world;
        this.routes = routes;
        this.macro = macro;
        x = integer("x", "X", 6, 0, 4000, 1).group("Position");
        y = integer("y", "Y", 6, 0, 4000, 1).group("Position");
        scale = decimal("scale", "Scale", 1.0D, 0.5D, 2.5D, 0.05D).group("Position");
        sleekFont = bool("sleek_font", "Boxy font", true).group("Look");
        showState = bool("show_state", "Show activity", true)
                .description("What you are doing right now (mining which ore, fighting bandits, idle; the ore macro's "
                        + "current step) and the inventory fill.")
                .group("Rows");
        showEta = bool("show_eta", "Show ETA level-up", true)
                .description("Time to the next level from the XP per hour of the last 5 minutes.").group("Rows");
        showYield = bool("show_yield", "Show enchant yield", true)
                .description("Share of ores taken along by Fractured / Shatter procs, the enchant lines and the forecast.")
                .group("Rows");
        logUnparsed = bool("log_unparsed", "Log unknown booster / energy / XP / tax lines", true).group("Debug");

    }

    /** Subscriptions live while the module is enabled (they can only be made once it is registered). */
    @Override
    protected void onEnable() {
        on(CoreEvents.PlayerBrokeBlock.class, this::onBroken);
        on(CoreEvents.ChatReceived.class, e -> {
            if (!e.fromPlayer()) {
                String line = TextStrip.strip(e.message().getString());
                long now = System.currentTimeMillis();
                stats.message(line, e.overlay(), now);
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                ClientPlayerEntity me = MinecraftClient.getInstance().player;
                String victim = me == null ? null : SessionMode.ownKill(line, me.getName().getString());
                if (victim != null) {
                    ActivityLog.Activity bandit = fight("Player " + victim, now);
                    bandit.playerKills++;
                    ThePrisonsClient.LOGGER.info("[session_hud] player kill: {}", victim);
                }
                if (lower.contains("meteor") && (lower.contains("has crashed") || lower.contains("is falling"))) {
                    meteorUntilMs = now + 10L * 60_000L;
                }
            }
        });
        on(CoreEvents.WorldChanged.class, e -> {
            if (e.current() == null || e.previous() != null) {
                ActivityStore.save(activityKey, activities);
            }
            if (e.previous() == null && e.current() != null) {
                // Joined: a new session; the activities of this server come back from disk.
                stats = newStats();
                xpForwarded = 0L;
                energyForwarded = 0L;
                pending.clear();
                pendingHit.clear();
                activities.clear();
                banditsKilled = -1L;
                activityKey = ActivityStore.key(MinecraftClient.getInstance());
                ActivityStore.load(activityKey, activities);
            }
        });
        if (!attackHooked) {
            attackHooked = true;
            net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, w, hand, entity, hit) -> {
                if (enabled() && w.isClient()) {
                    String name = banditActivity(TextStrip.strip(entity.getName().getString()));
                    if (name != null) {
                        fight(name, System.currentTimeMillis());
                    }
                }
                return net.minecraft.util.ActionResult.PASS;
            });
        }
        every(1, "session-hud", this::tick);
    }

    @Override
    protected void onDisable() {
        ActivityStore.save(activityKey, activities);
    }

    /**
     * A fight action (bandit hit / killed, player killed): the Bandit stats - unless the ore macro runs, then it stays
     * Ore Mining and the kill is only added to the Bandit numbers.
     */
    private ActivityLog.Activity fight(String detail, long now) {
        if (macroRunning()) {
            return activities.entry(SessionMode.BANDIT);
        }
        ActivityLog.Activity a = act(SessionMode.BANDIT, detail, now);
        return a;
    }

    /** Switches to / keeps {@code mode} running, logging a switch. */
    private ActivityLog.Activity act(String mode, String detail, long now) {
        ActivityLog.Activity before = activities.current();
        ActivityLog.Activity a = activities.act(SessionMode.of(mode, macroRunning()), now);
        if (!detail.isEmpty()) {
            a.detail = detail;
        }
        if (before != a) {
            ThePrisonsClient.LOGGER.info("[session_hud] session stats: {} ({})", a.name, detail);
        }
        return a;
    }

    private boolean macroRunning() {
        return !macro.statusText().isEmpty();
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    private CosmicStats newStats() {
        return new CosmicStats(System.currentTimeMillis(), line -> {
            if (logUnparsed == null || logUnparsed.on()) {
                ThePrisonsClient.LOGGER.info("[session_hud] unparsed {}", line);
            }
        });
    }

    /** "Gold Bandit [Lv 4] ❤ 20" -> "Gold Bandits"; null when it is no bandit. */
    static @Nullable String banditActivity(String entityName) {
        if (!entityName.toLowerCase(java.util.Locale.ROOT).contains("bandit")) {
            return null;
        }
        String name = entityName.replaceAll("\\[.*?]|\\(.*?\\)", " ").replaceAll("[^A-Za-z ]", " ")
                .replaceAll("\\s+", " ").trim();
        int at = name.toLowerCase(java.util.Locale.ROOT).indexOf("bandit");
        name = name.substring(0, at + "bandit".length());
        return name.isEmpty() ? "Bandits" : name + "s";
    }

    /** Ore families: every block of the family counts as one activity (ore, deepslate ore, the block ...). */
    private static final String[][] FAMILIES = {
            {"quartz", "Meteor Mining"}, {"redstone", "Redstone"}, {"lapis", "Lapis"}, {"diamond", "Diamond"},
            {"emerald", "Emerald"}, {"gold", "Gold"}, {"iron", "Iron"}, {"copper", "Copper"}, {"coal", "Coal"},
            {"amethyst", "Amethyst"}, {"debris", "Ancient Debris"}};

    /**
     * What a broken block counts as: its ore family ("Redstone" for redstone ore, deepslate redstone ore and redstone
     * blocks; quartz is "Meteor Mining"), else "Meteor Mining" after a meteor crash, else the block's name.
     */
    static String blockActivity(String blockId, String blockName, boolean meteorWindow) {
        String path = blockId.substring(blockId.indexOf(':') + 1);
        for (String[] family : FAMILIES) {
            if (path.contains(family[0])) {
                return family[1];
            }
        }
        if (meteorWindow) {
            return "Meteor Mining";
        }
        return blockName.replaceFirst("(?i)^deepslate ", "").trim();
    }

    private void onBroken(CoreEvents.PlayerBrokeBlock event) {
        long now = System.currentTimeMillis();
        pending.remove(event.pos());
        pendingHit.remove(event.pos());
        if (event.state().isAir()) {
            return;
        }
        boolean target = world.classifier().target(event.state()) != 0;
        String name = blockActivity(net.minecraft.registry.Registries.BLOCK.getId(event.state().getBlock()).toString(),
                event.state().getBlock().getName().getString(), now < meteorUntilMs);
        ActivityLog.Activity activity = act(SessionMode.ORE, name, now);
        activity.stats.addOres(1, activity.clock(now));
        if (!target) {
            return;
        }
        stats.addOres(1, now);
        hitId++;
        // Ores around it that may go with it (procs): counted when they turn into something else soon.
        ClientWorld w = event.world();
        int bx = Pos.x(event.pos());
        int by = Pos.y(event.pos());
        int bz = Pos.z(event.pos());
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -PROC_RADIUS; dx <= PROC_RADIUS; dx++) {
            for (int dz = -PROC_RADIUS; dz <= PROC_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    p.set(bx + dx, by + dy, bz + dz);
                    if (world.classifier().target(w.getBlockState(p)) != 0) {
                        pending.put(p.asLong(), ticks + PROC_TICKS);
                        pendingHit.put(p.asLong(), hitId);
                    }
                }
            }
        }
    }

    private void tick() {
        ticks++;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld w = client.world;
        inWorld = player != null && w != null;
        if (!inWorld) {
            pending.clear();
            pendingHit.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (!pending.isEmpty()) {
            int[] gone = {0};
            int[] events = {0};
            BlockPos.Mutable p = new BlockPos.Mutable();
            pending.long2IntEntrySet().removeIf(e -> {
                p.set(e.getLongKey());
                if (world.classifier().target(w.getBlockState(p)) == 0) {
                    gone[0]++;
                    if (procHits.add(pendingHit.get(e.getLongKey()))) {
                        events[0]++;
                    }
                    pendingHit.remove(e.getLongKey());
                    return true;
                }
                if (e.getIntValue() < ticks) {
                    pendingHit.remove(e.getLongKey());
                    return true;
                }
                return false;
            });
            if (procHits.size() > 512) {
                procHits.clear();
            }
            if (gone[0] > 0) {
                stats.addProcOres(gone[0], events[0], now);
                ActivityLog.Activity a = activities.current();
                if (a != null && a.running()) {
                    a.stats.addProcOres(gone[0], events[0], a.clock(now));
                }
            }
        }
        if (ticks % READ_TICKS == 0) {
            List<String> side = ClientReadouts.sidebar(client, w);
            if (side != null) {
                stats.sidebar(side, now);
                long killed = SessionMode.banditsKilled(side);
                long step = SessionMode.killStep(banditsKilled, killed);
                if (step > 0L) {
                    ActivityLog.Activity a = fight(activities.entry(SessionMode.BANDIT).detail, now);
                    a.kills += step;
                }
                if (killed >= 0L) {
                    banditsKilled = killed;
                }
            }
            stats.bossBars(ClientReadouts.bossBarTitles(client), now);
            ItemStack held = player.getMainHandStack();
            if (held.isIn(ItemTags.PICKAXES)) {
                List<String> lore = ClientReadouts.lore(held);
                long energy = CosmicStats.loreEnergy(lore);
                if (energy >= 0L) {
                    stats.pickaxeEnergy(TextStrip.strip(held.getName().getString()), energy, now);
                }
                // Durability from the item data (unbreakable / no durability: no warning).
                double durability = held.isDamageable() && held.getMaxDamage() > 0
                        ? 1.0D - held.getDamage() / (double) held.getMaxDamage() : -1.0D;
                stats.pickaxe(CosmicStats.procLines(lore), durability, CosmicStats.procChance(lore));
            } else {
                stats.pickaxe("", -1.0D, -1.0D);
            }
            stats.vanillaXp(player.totalExperience, now);
            stats.vanillaXpLeft(Math.round((1.0D - player.experienceProgress) * player.getNextLevelExperience()));
        }
        if (ticks % 100 == 1) {
            recordedRoutes = routes.all(client).size();
            // The learned routes of this world, also while the ore macro is off.
            macro.syncRouteMemory(client);
        }
        if (ticks % 20 == 0 && macroRunning()) {
            // The macro walks and mines: Ore Mining, also on the way between ores.
            act(SessionMode.ORE, "", now);
        }
        forwardGains(now);
        activities.tick(now);
        if (ticks % 600 == 0) {
            ActivityStore.save(activityKey, activities);
        }
        if (ticks % SNAPSHOT_TICKS == 0) {
            CosmicStats.Snapshot global = stats.snapshot(now, recordedRoutes + macro.learnedRoutes(), macro.pathMode(),
                    macro.botState(), inventoryPercent(player));
            ActivityLog.Activity a = activities.current();
            CosmicStats.Snapshot shown = a == null ? zeroUptime(global) : combine(a.stats.snapshot(a.clock(now), 0, ""), global);
            snapshot = withBarRates(shown, stats, System.currentTimeMillis());
            title = a == null ? "" : a.name + (a.running() ? "" : "  ·  paused");
            activity = activityText(a, macro.statusText());
            bandit = a != null && a.name.equals(SessionMode.BANDIT);
            kills = a == null ? 0L : a.kills;
            playerKills = a == null ? 0L : a.playerKills;
        }
    }

    /**
     * What is being done right now: the ore macro's current step while it runs, else the running activity
     * ("Mining Iron Ore", "Fighting Gold Bandits"), else "Idle".
     */
    static String activityText(ActivityLog.@Nullable Activity a, String macroStatus) {
        if (!macroStatus.isEmpty() && !macroStatus.equals("Idle")) {
            return macroStatus.startsWith("Trip: ") ? macroStatus.substring("Trip: ".length()) : macroStatus;
        }
        if (a == null || !a.running()) {
            return "Idle";
        }
        String what = a.detail.isEmpty() ? (a.name.equals(SessionMode.BANDIT) ? "Bandits" : "Ore") : a.detail;
        if (what.endsWith("Mining")) {
            return what;
        }
        return (a.name.equals(SessionMode.BANDIT) ? "Fighting " : "Mining ") + what;
    }

    private volatile String activity = "";
    private volatile boolean bandit;
    private volatile long kills;
    private volatile long playerKills;
    /** The sidebar's "Bandits Killed" at the last reading (-1 = not shown). */
    private long banditsKilled = -1L;

    private volatile String title = "";
    private long xpForwarded;
    private long energyForwarded;

    /**
     * XP and energy are measured once (sidebar, lore, chat) by the global stats; what they gained since the last tick
     * goes to the running activity (nothing while none runs, so its rates only count its own active time).
     */
    private void forwardGains(long now) {
        long xpGain = stats.xpTotal() - xpForwarded;
        long energyGain = stats.energyTotal() - energyForwarded;
        xpForwarded = stats.xpTotal();
        energyForwarded = stats.energyTotal();
        ActivityLog.Activity a = activities.current();
        if (a != null && a.running()) {
            a.stats.addGains(xpGain, energyGain, a.clock(now));
        }
    }

    /** Before the first block: the global numbers but no uptime yet. */
    private static CosmicStats.Snapshot zeroUptime(CosmicStats.Snapshot g) {
        return new CosmicStats.Snapshot(0L, 0.0D, 0.0D, 0L, 0.0D, 0.0D, g.taxPercent(), g.taxNote(), g.serverBoosters(),
                g.personalBoosters(), g.learnedRoutes(), g.mode(), g.levelUpEtaMs(), -1.0D, g.loreProcs(), g.durability(),
                -1.0D, g.loreChance(), g.botState(), g.inventoryPercent());
    }

    /**
     * The action bar's per-minute XP / energy (x 60) win over everything measured: they are the server's own numbers
     * and stay shown (the last value) also while no activity runs.
     */
    static CosmicStats.Snapshot withBarRates(CosmicStats.Snapshot s, CosmicStats global, long now) {
        double xp = global.instantXpPerHour(now);
        double energy = global.instantEnergyPerHour(now);
        if (xp < 0.0D && energy < 0.0D) {
            return s;
        }
        // Big: what the last action bar gains make per hour right now; below it: the average.
        double energyAvg = s.energyAvgPerHour() >= 0.0D ? s.energyAvgPerHour() : s.energyPerHour();
        double xpAvg = s.xpAvgPerHour() >= 0.0D ? s.xpAvgPerHour() : s.xpPerHour();
        return new CosmicStats.Snapshot(s.uptimeMs(), s.opsRecent(), s.opsAverage(), s.ores(),
                energy >= 0.0D ? energy : s.energyPerHour(), xp >= 0.0D ? xp : s.xpPerHour(), s.taxPercent(), s.taxNote(),
                s.serverBoosters(), s.personalBoosters(), s.learnedRoutes(), s.mode(), s.levelUpEtaMs(), s.procShare(),
                s.loreProcs(), s.durability(), s.forecastOps(), s.loreChance(), s.botState(), s.inventoryPercent(), energyAvg, xpAvg);
    }

    /** Uptime, ores and rates of the activity; tax, boosters, pickaxe and level-up from the global stats. */
    static CosmicStats.Snapshot combine(CosmicStats.Snapshot a, CosmicStats.Snapshot g) {
        return new CosmicStats.Snapshot(a.uptimeMs(), a.opsRecent(), a.opsAverage(), a.ores(), a.energyPerHour(),
                a.xpPerHour(), g.taxPercent(), g.taxNote(), g.serverBoosters(), g.personalBoosters(), g.learnedRoutes(),
                g.mode(), g.levelUpEtaMs(), a.procShare(), g.loreProcs(), g.durability(), a.forecastOps(), g.loreChance(),
                g.botState(), g.inventoryPercent());
    }

    /** Used slots of the 36 main inventory slots, in percent. */
    private static int inventoryPercent(ClientPlayerEntity player) {
        int used = 0;
        java.util.List<ItemStack> main = player.getInventory().getMainStacks();
        for (ItemStack stack : main) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        return main.isEmpty() ? -1 : Math.round(used * 100.0F / main.size());
    }

    /** Every activity of this server (mining an ore, fighting bandits ...): {name, active ms, ores / kills}. */
    public java.util.List<Object[]> activitySummary() {
        long now = System.currentTimeMillis();
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        for (ActivityLog.Activity a : activities.all().values()) {
            long clock = a.clock(now);
            out.add(new Object[]{a.name, clock, a.name.equals(SessionMode.BANDIT) ? a.kills : a.stats.snapshot(clock, 0, "").ores()});
        }
        return out;
    }

    /** {xp/h, energy/h} from the action bar's per-minute rates, -1 each while unknown. */
    public double[] barRates() {
        return new double[]{stats.barXpPerHour(), stats.barEnergyPerHour()};
    }

    /** XP gained this session (sidebar total XP, lore, chat), for the classic stats HUD. */
    public long sessionXp() {
        return stats.xpTotal();
    }

    /** Pickaxe energy gained this session, for the classic stats HUD. */
    public long sessionEnergy() {
        return stats.energyTotal();
    }

    /** The last computed snapshot (also read by the dashboard), {@code null} while off or before the first one. */
    public CosmicStats.@Nullable Snapshot latest() {
        return snapshot;
    }

    /** HUD callback: draws the last snapshot (nothing while off, without a world or with the F1 HUD hidden). */
    public void render(DrawContext context, RenderTickCounter counter) {
        CosmicStats.Snapshot s = snapshot;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled() || !inWorld || s == null || client.options.hudHidden) {
            return;
        }
        lastSize = NebulaHudRenderer.draw(context, s, x.value(), y.value(), (float) scale.value(), sleekFont.on(), options());
    }

    private NebulaHudRenderer.Options options() {
        return new NebulaHudRenderer.Options(com.freelocs.theprisons.modules.FeatureProfile.DEV && showState.on(), showEta.on(),
                showYield.on(), title, showState.on() ? activity : "", bandit, kills, playerKills);
    }

    private int[] lastSize = {150, 120};

    // ── HUD editor ───────────────────────────────────────────────────────────

    @Override
    public int[] bounds(int screenWidth, int screenHeight) {
        double sc = scale.value();
        return new int[]{x.value(), y.value(), (int) Math.round(lastSize[0] * sc), (int) Math.round(lastSize[1] * sc)};
    }

    @Override
    public void moveTo(int nx, int ny, int screenWidth, int screenHeight) {
        x.set(Math.max(0, nx));
        y.set(Math.max(0, ny));
    }

    @Override
    public double scale() {
        return scale.value();
    }

    @Override
    public void setScale(double value) {
        scale.set(Math.max(0.5D, Math.min(2.5D, value)));
    }

    @Override
    public void drawPreview(DrawContext context, int screenWidth, int screenHeight) {
        CosmicStats.Snapshot s = snapshot;
        if (s == null) {
            long now = System.currentTimeMillis();
            s = new CosmicStats.Snapshot(5_400_000L, 4.2D, 3.9D, 18_240L, 1_250_000D, 860_000D, 6.5D, "",
                    java.util.List.of(new CosmicStats.Booster("2x Rested XP", 2.0D, now + 4_800_000L)),
                    java.util.List.of(new CosmicStats.Booster("Charge Orbs +12% Energy", 1.12D, 0L)), 0, "",
                    2_700_000L, -1.0D, "", 0.82D, -1.0D, -1.0D, "", -1);
        }
        lastSize = NebulaHudRenderer.draw(context, s, x.value(), y.value(), (float) scale.value(), sleekFont.on(), options());
    }

    @Override
    public void reset() {
        x.reset();
        y.reset();
        scale.reset();
    }
}
