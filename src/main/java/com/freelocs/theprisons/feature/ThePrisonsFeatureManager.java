package com.freelocs.theprisons.feature;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import com.freelocs.theprisons.ui.ThePrisonsHudRenderer;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.DrawItemStackOverlayCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ThePrisonsFeatureManager {
    private static final Pattern DURATION_PAIR = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\b");
    private static final Pattern CLOCK_TIME = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\b");
    private static final Pattern STAT_GAIN_SUFFIX = Pattern.compile("(?i)(?:\\+\\s*)?([\\d,.]+\\s*[kmb]?)\\s*(cosmic\\s+energy|energy|xp|experience)\\b");
    private static final Pattern STAT_GAIN_PREFIX = Pattern.compile("(?i)(cosmic\\s+energy|energy|xp|experience)\\s*(?:\\+\\s*)?([\\d,.]+\\s*[kmb]?)\\b");
    private static final String[] NON_PLAYER_STATS_CONTEXT = {
            "pet xp", "pet exp", "trinket xp", "trinket exp", "boost", "bonus", "chance", "multiplier", "multi"
    };
    private static final Pattern FRACTION = Pattern.compile("([\\d,.]+\\s*[kmb]?)\\s*/\\s*([\\d,.]+\\s*[kmb]?)");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");
    private static final Pattern CLUE_STEP = Pattern.compile("(?i)(?:step|clue)\\s*#?\\s*(\\d{1,3})(?:\\s*/\\s*(\\d{1,3}))?");
    private static final Pattern SUCCESS_RATE = Pattern.compile("(?i)(success|destroy)\\D{0,12}(\\d{1,3})\\s*%");
    private static final Pattern EXPIRY = Pattern.compile("(?i)(expires?|expiry|valid for)\\D{0,20}((?:\\d+\\s*(?:d|day|days|h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\s*)+)");

    private static final Map<String, Integer> DEFAULT_COMMAND_SECONDS = new LinkedHashMap<>();
    private static final Map<String, RuntimeCooldown> COMMAND_COOLDOWNS = new LinkedHashMap<>();
    private static final List<SatchelSummary> SATCHELS = new ArrayList<>();
    private static final SessionStats STATS = new SessionStats();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static KeyBinding configKey;
    private static KeyBinding resetStatsKey;
    private static KeyBinding pauseStatsKey;
    private static int tickCounter;
    private static long lastSatchelWarningAtMs;
    private static long lastLowHealthWarningAtMs;
    private static long lastLowHungerWarningAtMs;
    private static long lastChatXpGainAtMs;
    private static int lastApiTotalXp = -1;

    static {
        DEFAULT_COMMAND_SECONDS.put("jet", 120);
        DEFAULT_COMMAND_SECONDS.put("feed", 180);
        DEFAULT_COMMAND_SECONDS.put("fix", 300);
        DEFAULT_COMMAND_SECONDS.put("home", 10);
        DEFAULT_COMMAND_SECONDS.put("tpa", 60);
        DEFAULT_COMMAND_SECONDS.put("tpahere", 60);
        DEFAULT_COMMAND_SECONDS.put("dangle", 300);
        DEFAULT_COMMAND_SECONDS.put("adangle", 300);
        DEFAULT_COMMAND_SECONDS.put("near", 30);
        DEFAULT_COMMAND_SECONDS.put("pulse", 90);
        DEFAULT_COMMAND_SECONDS.put("combat", 30);
        DEFAULT_COMMAND_SECONDS.put("adrenaline", 120);
        DEFAULT_COMMAND_SECONDS.put("powerball", 120);
        DEFAULT_COMMAND_SECONDS.put("superbreaker", 180);
        DEFAULT_COMMAND_SECONDS.put("super", 180);
    }

    /** Compiled once instead of once per command and chat line. */
    private static final Map<String, Pattern> COMMAND_PATTERNS = new LinkedHashMap<>();

    static {
        for (String command : DEFAULT_COMMAND_SECONDS.keySet()) {
            COMMAND_PATTERNS.put(command, Pattern.compile("\\b/?(" + Pattern.quote(command) + ")\\b"));
        }
    }

    private ThePrisonsFeatureManager() {
    }

    public static void register() {
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.theprisons.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                ThePrisonsCore.KEY_CATEGORY
        ));
        resetStatsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.theprisons.reset_stats",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                ThePrisonsCore.KEY_CATEGORY
        ));
        pauseStatsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.theprisons.pause_stats",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                ThePrisonsCore.KEY_CATEGORY
        ));

        ClientSendMessageEvents.COMMAND.register(ThePrisonsFeatureManager::onSendCommand);
        DrawItemStackOverlayCallback.EVENT.register(ThePrisonsFeatureManager::drawItemOverlay);
        ItemTooltipCallback.EVENT.register(ThePrisonsFeatureManager::appendTooltip);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
            if (config.qol.peacefulMiningSafety && entity instanceof PlayerEntity && isMiningTool(player.getStackInHand(hand))) {
                ThePrisonsHudRenderer.pushNotification("Peaceful Mining", "Player hit blocked while mining.", ThePrisonsColors.ACCENT_AMBER);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    public static void tick(MinecraftClient client) {
        tickCounter++;
        handleKeys(client);
        announceReadyCooldowns();

        if (client.player == null || client.world == null) {
            SATCHELS.clear();
            lastApiTotalXp = -1;
            return;
        }

        sampleApiStats(client.player);
        maybeWarnVitals(client.player);

        if (tickCounter % 20 == 0) {
            scanInventory(client);
        }
    }

    private static void sampleApiStats(PlayerEntity player) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.hud.showStatsHud || config.hud.pauseStatsHud) {
            lastApiTotalXp = player.totalExperience;
            return;
        }

        int currentXp = Math.max(0, player.totalExperience);
        if (lastApiTotalXp < 0) {
            lastApiTotalXp = currentXp;
            return;
        }

        int delta = currentXp - lastApiTotalXp;
        if (delta > 0 && System.currentTimeMillis() - lastChatXpGainAtMs > 1800L) {
            STATS.sessionXp += delta;
            STATS.ensureStarted();
        }
        lastApiTotalXp = currentXp;
    }

    public static void onGameMessage(Text message, boolean overlay) {
        parseIncomingMessage(message);
        if (!overlay) {
            satchelChat(com.freelocs.theprisons.core.client.TextStrip.strip(message.getString()));
        }
    }

    /** Satchels the server reported full ("(!) Your Redstone Ore Satchel is full!"), by normalized name. */
    private static final java.util.Set<String> FULL_SATCHELS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Pattern SATCHEL_FULL = Pattern.compile("(?i)your (.+? satchel) is full");

    static void satchelChat(String line) {
        Matcher full = SATCHEL_FULL.matcher(line);
        if (full.find()) {
            FULL_SATCHELS.add(normalizeKey(full.group(1)));
        } else if (line.toLowerCase(Locale.ROOT).contains("(satchel)") && line.toLowerCase(Locale.ROOT).contains("sold")) {
            FULL_SATCHELS.clear(); // sold: the satchels are empty again
        }
    }

    private static boolean reportedFull(String displayName) {
        String key = normalizeKey(displayName.replaceAll("\\s+x\\d+$", ""));
        for (String full : FULL_SATCHELS) {
            if (key.contains(full) || full.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static void onChatMessage(Text message, @Nullable GameProfile sender, Instant receptionTimestamp) {
        parseIncomingMessage(message);
    }

    public static List<FeatureHudEntry> commandCooldownEntries() {
        if (!ThePrisonsClient.CONFIG.get().hud.showCommandCooldowns) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<FeatureHudEntry> entries = new ArrayList<>();
        for (RuntimeCooldown cooldown : COMMAND_COOLDOWNS.values()) {
            long remaining = cooldown.endsAtMs - now;
            if (remaining <= -15_000L) {
                continue;
            }
            String value = remaining <= 0 ? "Ready" : formatRemaining(remaining);
            int color = remaining <= 0 ? ThePrisonsColors.ACCENT_LIME : ThePrisonsColors.ACCENT_AMBER;
            entries.add(new FeatureHudEntry(cooldown.displayName, value, color));
        }
        entries.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return entries;
    }

    public static List<FeatureHudEntry> satchelEntries() {
        if (!ThePrisonsClient.CONFIG.get().hud.showSatchelHud) {
            return List.of();
        }
        List<FeatureHudEntry> entries = new ArrayList<>();
        for (SatchelSummary satchel : SATCHELS) {
            String value = satchel.capacity <= 0
                    ? compact(satchel.amount)
                    : compact(satchel.amount) + "/" + compact(satchel.capacity);
            int color = satchel.fillPercent >= ThePrisonsClient.CONFIG.get().hud.satchelWarningPercent
                    ? ThePrisonsColors.ACCENT_AMBER
                    : ThePrisonsColors.ACCENT_LIME;
            entries.add(new FeatureHudEntry(satchel.displayName, value, color));
        }
        return entries;
    }

    public static List<FeatureHudEntry> statsEntries() {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.hud.showStatsHud) {
            return List.of();
        }
        List<FeatureHudEntry> entries = new ArrayList<>();
        // Cosmic shows no "+N XP" lines and keeps the vanilla XP at 0: the session HUD reads the sidebar instead.
        long xp = STATS.sessionXp;
        long energy = STATS.sessionEnergy;
        double[] bar = {-1.0D, -1.0D};
        com.freelocs.theprisons.core.ThePrisonsCore core = com.freelocs.theprisons.core.ThePrisonsCore.getOrNull();
        if (core != null && core.modules().get("session_hud") instanceof com.freelocs.theprisons.modules.hud.SessionHudModule hud) {
            xp = Math.max(xp, hud.sessionXp());
            energy = Math.max(energy, hud.sessionEnergy());
            bar = hud.barRates();
        }
        entries.add(new FeatureHudEntry("XP", compact(xp), ThePrisonsColors.ACCENT_LIME));
        entries.add(new FeatureHudEntry("XP/h", compact(bar[0] >= 0.0D ? Math.round(bar[0]) : STATS.perHour(xp)), ThePrisonsColors.ACCENT_CYAN));
        entries.add(new FeatureHudEntry("Energy", compact(energy), ThePrisonsColors.ACCENT_LIME));
        entries.add(new FeatureHudEntry("Energy/h", compact(bar[1] >= 0.0D ? Math.round(bar[1]) : STATS.perHour(energy)), ThePrisonsColors.ACCENT_CYAN));
        if (config.hud.pauseStatsHud) {
            entries.add(new FeatureHudEntry("Stats", "Paused", ThePrisonsColors.ACCENT_AMBER));
        }
        return entries;
    }


    private static void handleKeys(MinecraftClient client) {
        if (client == null) {
            return;
        }
        while (configKey != null && configKey.wasPressed()) {
            openConfig(client);
        }
        while (resetStatsKey != null && resetStatsKey.wasPressed()) {
            resetStats();
        }
        while (pauseStatsKey != null && pauseStatsKey.wasPressed()) {
            ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
            setStatsPaused(!config.hud.pauseStatsHud);
        }
    }

    public static void resetStats() {
        STATS.reset();
        ThePrisonsHudRenderer.pushNotification("Stats HUD", "Session totals reset.", ThePrisonsColors.ACCENT_CYAN);
    }

    public static void setStatsPaused(boolean paused) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        config.hud.pauseStatsHud = paused;
        if (paused) {
            STATS.pause();
        } else {
            STATS.resume();
        }
        ThePrisonsClient.CONFIG.saveAsync();
        ThePrisonsHudRenderer.pushNotification("Stats HUD", paused ? "Tracking paused." : "Tracking resumed.", ThePrisonsColors.ACCENT_CYAN);
    }

    private static void onSendCommand(String command) {
        String name = commandName(command);
        if (name.isEmpty() || !ThePrisonsClient.CONFIG.get().hud.showCommandCooldowns) {
            return;
        }
        Integer defaultSeconds = DEFAULT_COMMAND_SECONDS.get(name);
        if (defaultSeconds == null) {
            return;
        }
        addCooldown(name, displayCommand(name), defaultSeconds * 1000L, false);
    }

    private static Runnable guiOpener = () -> {
    };

    /** The config key opens the module GUI of the core. */
    public static void setGuiOpener(Runnable opener) {
        guiOpener = opener;
    }

    private static int openConfig(MinecraftClient client) {
        if (client != null) {
            guiOpener.run();
        }
        return 1;
    }

    private static void parseIncomingMessage(@Nullable Text message) {
        if (message == null) {
            return;
        }
        String raw = clean(message.getString());
        if (raw.isBlank()) {
            return;
        }
        parseCooldown(raw);
        parseStats(raw);
        maybeNotifyMessage(raw);
    }

    private static void parseCooldown(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String matched = "";
        for (Map.Entry<String, Pattern> command : COMMAND_PATTERNS.entrySet()) {
            if (command.getValue().matcher(lower).find()) {
                matched = command.getKey();
                break;
            }
        }
        if (matched.isEmpty()) {
            return;
        }

        if (lower.contains("ready") || lower.contains("available") || lower.contains("can use")) {
            RuntimeCooldown cooldown = COMMAND_COOLDOWNS.get(matched);
            if (cooldown != null && cooldown.endsAtMs > System.currentTimeMillis()) {
                cooldown.endsAtMs = System.currentTimeMillis();
                cooldown.readyAnnounced = false;
            }
        }

        if (!lower.contains("cooldown") && !lower.contains("wait") && !lower.contains("again")
                && !lower.contains("remaining") && !lower.contains("recharge") && !lower.contains("ready in")) {
            return;
        }
        long duration = parseDurationMs(raw);
        if (duration > 0L) {
            addCooldown(matched, displayCommand(matched), duration, false);
        }
    }

    private static void parseStats(String raw) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.hud.showStatsHud || config.hud.pauseStatsHud) {
            return;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (isNonPlayerStatsContext(lower)) {
            return;
        }

        boolean changed = false;
        boolean xpChanged = false;

        Matcher matcher = STAT_GAIN_SUFFIX.matcher(raw);
        while (matcher.find()) {
            long amount = parseNumber(matcher.group(1));
            String type = matcher.group(2).toLowerCase(Locale.ROOT);
            if (amount <= 0L || hasNearbyPercent(raw, matcher.start(1), matcher.end(1))) {
                continue;
            }
            if (type.contains("energy")) {
                STATS.sessionEnergy += amount;
            } else {
                STATS.sessionXp += amount;
                xpChanged = true;
            }
            changed = true;
        }

        Matcher prefixMatcher = STAT_GAIN_PREFIX.matcher(raw);
        while (prefixMatcher.find()) {
            long amount = parseNumber(prefixMatcher.group(2));
            String type = prefixMatcher.group(1).toLowerCase(Locale.ROOT);
            if (amount <= 0L || hasNearbyPercent(raw, prefixMatcher.start(2), prefixMatcher.end(2))) {
                continue;
            }
            if (type.contains("energy")) {
                STATS.sessionEnergy += amount;
            } else {
                STATS.sessionXp += amount;
                xpChanged = true;
            }
            changed = true;
        }

        if (changed) {
            if (xpChanged) {
                lastChatXpGainAtMs = System.currentTimeMillis();
            }
            STATS.ensureStarted();
        }
    }

    private static boolean hasNearbyPercent(String text, int start, int end) {
        int from = Math.max(0, start - 2);
        int to = Math.min(text.length(), end + 2);
        return text.substring(from, to).contains("%");
    }

    private static boolean isNonPlayerStatsContext(String lower) {
        for (String fragment : NON_PLAYER_STATS_CONTEXT) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void maybeNotifyMessage(String raw) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.qol.messageNotifications) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String playerName = client.player.getName().getString();
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean mentioned = !playerName.isBlank() && lower.contains(playerName.toLowerCase(Locale.ROOT));
        boolean privateMessage = lower.startsWith("from ")
                || lower.startsWith("to ")
                || lower.contains(" whispers ")
                || lower.contains(" msg ")
                || lower.contains(" -> me")
                || lower.contains("me ->");
        if (!mentioned && !privateMessage) {
            return;
        }
        playNotificationSound(client, config);
    }

    private static void scanInventory(MinecraftClient client) {
        Map<String, SatchelSummary.Mutable> satchels = new LinkedHashMap<>();
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> lines = tooltipLines(stack, client);
            SatchelInfo info = detectSatchel(stack, lines);
            if (info == null) {
                continue;
            }
            SatchelSummary.Mutable summary = satchels.computeIfAbsent(info.key, ignored -> new SatchelSummary.Mutable(info.displayName));
            if (summary.icon.isEmpty()) {
                summary.icon = stack.copyWithCount(1);
            }
            summary.amount += info.amount;
            summary.capacity += info.capacity;
            summary.count++;
        }

        SATCHELS.clear();
        for (SatchelSummary.Mutable mutable : satchels.values()) {
            SATCHELS.add(mutable.freeze());
        }
        SATCHELS.sort(Comparator.comparing(summary -> summary.displayName.toLowerCase(Locale.ROOT)));
        maybeWarnSatchels();
    }

    private static void maybeWarnSatchels() {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.hud.showSatchelWarnings || SATCHELS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSatchelWarningAtMs < 30_000L) {
            return;
        }
        for (SatchelSummary satchel : SATCHELS) {
            if (satchel.capacity > 0L && satchel.fillPercent >= config.hud.satchelWarningPercent) {
                lastSatchelWarningAtMs = now;
                ThePrisonsHudRenderer.pushNotification("Satchel Nearly Full", satchel.displayName + " is " + Math.round(satchel.fillPercent) + "% full.", ThePrisonsColors.ACCENT_AMBER);
                return;
            }
        }
    }

    private static void maybeWarnVitals(PlayerEntity player) {
        if (player == null) {
            return;
        }

        if (!ThePrisonsClient.CONFIG.get().hud.lowVitalsWarnings) {
            return;
        }
        long now = System.currentTimeMillis();
        if (player.getHealth() <= 8.0F && now - lastLowHealthWarningAtMs >= 15_000L) {
            lastLowHealthWarningAtMs = now;
            ThePrisonsHudRenderer.pushNotification("Low Health", "Health dropped below 4 hearts.", ThePrisonsColors.ACCENT_AMBER);
        }

        if (player.getHungerManager().getFoodLevel() <= 6 && now - lastLowHungerWarningAtMs >= 20_000L) {
            lastLowHungerWarningAtMs = now;
            ThePrisonsHudRenderer.pushNotification("Low Hunger", "Hunger dropped below 3 bars.", ThePrisonsColors.ACCENT_AMBER);
        }
    }

    private static void drawItemOverlay(DrawContext context, TextRenderer textRenderer, ItemStack stack, int x, int y) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> lines = null;

        if (config.hud.showClueScrollSteps) {
            lines = tooltipLines(stack, client);
            String clue = clueStepLabel(stack, lines);
            if (clue != null) {
                drawOverlayText(context, textRenderer, clue, x + 1, y + 1, ThePrisonsColors.ACCENT_AMBER);
            }
        }

    }

    private static void appendTooltip(ItemStack stack, Item.TooltipContext context, TooltipType type, List<Text> lines) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.hud.showItemInsightTooltips || stack == null || stack.isEmpty()) {
            return;
        }

        List<String> plain = new ArrayList<>();
        for (Text line : lines) {
            plain.add(clean(line.getString()));
        }

        SatchelInfo satchel = detectSatchel(stack, plain);
        if (satchel != null && satchel.capacity > 0L) {
            lines.add(Text.literal("[TP] Fill: " + compact(satchel.amount) + "/" + compact(satchel.capacity) + " (" + Math.round(satchel.fillPercent()) + "%)").formatted(Formatting.AQUA));
        }

        String clue = clueStepLabel(stack, plain);
        if (clue != null) {
            lines.add(Text.literal("[TP] Clue step: " + clue).formatted(Formatting.GOLD));
        }

        BookInsight book = detectBookInsight(stack, plain);
        if (book != null) {
            lines.add(Text.literal("[TP] " + book.summary()).formatted(Formatting.LIGHT_PURPLE));
        }

        String expiry = detectExpiry(plain);
        if (expiry != null) {
            lines.add(Text.literal("[TP] Expires around " + expiry).formatted(Formatting.YELLOW));
        }
    }


    private static final java.util.Set<String> LOGGED_SATCHELS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Nullable
    private static SatchelInfo detectSatchel(ItemStack stack, List<String> lines) {
        String name = clean(stack.getName().getString());
        String all = allText(stack, lines);
        if (!all.toLowerCase(Locale.ROOT).contains("satchel")) {
            return null;
        }
        long amount = 0L;
        long capacity = 0L;
        for (String line : lines) {
            Matcher fraction = FRACTION.matcher(line);
            if (fraction.find()) {
                amount = Math.max(amount, parseNumber(fraction.group(1)));
                capacity = Math.max(capacity, parseNumber(fraction.group(2)));
            }
        }
        if (capacity <= 0L) {
            // "Stored: 1,234" / "Capacity: 6,912" on separate lines
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                Matcher number = java.util.regex.Pattern.compile("([\\d,.]+\\s*[kmb]?)").matcher(line);
                if (!number.find()) {
                    continue;
                }
                if (lower.contains("capacity") || lower.contains("max")) {
                    capacity = Math.max(capacity, parseNumber(number.group(1)));
                } else if (lower.contains("stored") || lower.contains("amount") || lower.contains("ores") || lower.contains("contains")) {
                    amount = Math.max(amount, parseNumber(number.group(1)));
                }
            }
        }
        if (LOGGED_SATCHELS.add(name)) {
            com.freelocs.theprisons.ThePrisonsClient.LOGGER.info("[satchel] '{}' -> {} / {} ({})", name, amount, capacity,
                    String.join(" | ", lines));
        }
        if (capacity <= 0L) {
            Matcher percent = PERCENT.matcher(all);
            if (percent.find()) {
                amount = parseInt(percent.group(1)) == null ? 0L : parseInt(percent.group(1));
                capacity = 100L;
            }
        }
        String display = satchelDisplayName(name, lines);
        return new SatchelInfo(normalizeKey(display), display, amount, capacity);
    }

    private static String satchelDisplayName(String name, List<String> lines) {
        if (name.toLowerCase(Locale.ROOT).contains("satchel")) {
            return trimSatchelName(name);
        }
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).contains("satchel")) {
                return trimSatchelName(line);
            }
        }
        return "Satchel";
    }

    private static String trimSatchelName(String value) {
        String cleaned = clean(value)
                .replaceAll("(?i)\\b(level|lvl)\\s*\\d+\\b", "")
                .replaceAll("(?i)\\[[^]]*]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 28) {
            cleaned = cleaned.substring(0, 28).trim();
        }
        return cleaned.isBlank() ? "Satchel" : cleaned;
    }

    @Nullable
    private static String clueStepLabel(ItemStack stack, List<String> lines) {
        String text = allText(stack, lines);
        if (!text.toLowerCase(Locale.ROOT).contains("clue")) {
            return null;
        }
        Matcher matcher = CLUE_STEP.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(2) == null ? "#" + matcher.group(1) : matcher.group(1) + "/" + matcher.group(2);
    }

    @Nullable
    private static BookInsight detectBookInsight(ItemStack stack, List<String> lines) {
        String text = allText(stack, lines).toLowerCase(Locale.ROOT);
        if (!text.contains("book") && !text.contains("enchant")) {
            return null;
        }
        Integer success = null;
        Integer destroy = null;
        for (String line : lines) {
            Matcher matcher = SUCCESS_RATE.matcher(line);
            while (matcher.find()) {
                if (matcher.group(1).equalsIgnoreCase("success")) {
                    success = parseInt(matcher.group(2));
                } else if (matcher.group(1).equalsIgnoreCase("destroy")) {
                    destroy = parseInt(matcher.group(2));
                }
            }
        }
        if (success == null && destroy == null) {
            return null;
        }
        return new BookInsight(success, destroy);
    }

    @Nullable
    private static String detectExpiry(List<String> lines) {
        for (String line : lines) {
            if (!line.toLowerCase(Locale.ROOT).contains("gang point") && !line.toLowerCase(Locale.ROOT).contains("expire")) {
                continue;
            }
            Matcher matcher = EXPIRY.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            long duration = parseDurationMs(matcher.group(2));
            if (duration <= 0L) {
                continue;
            }
            LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis() + duration), ZoneId.systemDefault());
            return dateTime.format(TIME_FORMAT);
        }
        return null;
    }

    private static void addCooldown(String key, String displayName, long durationMs, boolean readyAnnounced) {
        RuntimeCooldown cooldown = COMMAND_COOLDOWNS.computeIfAbsent(key, ignored -> new RuntimeCooldown(displayName));
        cooldown.endsAtMs = System.currentTimeMillis() + Math.max(1_000L, durationMs);
        cooldown.readyAnnounced = readyAnnounced;
    }

    private static void announceReadyCooldowns() {
        long now = System.currentTimeMillis();
        COMMAND_COOLDOWNS.entrySet().removeIf(entry -> now - entry.getValue().endsAtMs > 60_000L);
        for (RuntimeCooldown cooldown : COMMAND_COOLDOWNS.values()) {
            if (cooldown.readyAnnounced || cooldown.endsAtMs > now) {
                continue;
            }
            cooldown.readyAnnounced = true;
            if (cooldown.displayName.equalsIgnoreCase("Powerball") && ThePrisonsClient.CONFIG.get().hud.powerballReadyAlert) {
                ThePrisonsHudRenderer.pushNotification("Powerball Ready", "Powerball cooldown finished.", ThePrisonsColors.ACCENT_LIME);
            }
        }
    }

    private static void playNotificationSound(MinecraftClient client, ThePrisonsConfig config) {
        if (!config.qol.messageNotificationSound || config.qol.messageNotificationVolume <= 0.0F) {
            return;
        }
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.35F, config.qol.messageNotificationVolume));
    }

    private static List<String> tooltipLines(ItemStack stack, MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        try {
            List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, client == null ? null : client.player, TooltipType.BASIC);
            for (Text text : tooltip) {
                lines.add(clean(text.getString()));
            }
        } catch (RuntimeException ignored) {
            lines.add(clean(stack.getName().getString()));
        }
        return lines;
    }

    private static void drawOverlayText(DrawContext context, TextRenderer renderer, String text, int x, int y, int color) {
        int width = renderer.getWidth(text);
        context.fill(x - 1, y - 1, x + width + 1, y + 9, 0xB8000000);
        context.drawTextWithShadow(renderer, Text.literal(text), x, y, color);
    }

    private static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String text = (stack.getName().getString() + " " + stack.getItem().getTranslationKey()).toLowerCase(Locale.ROOT);
        return text.contains("pickaxe") || text.contains("mace");
    }

    private static String commandName(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String first = command.trim().split("\\s+", 2)[0].replace("/", "").toLowerCase(Locale.ROOT);
        return first.replace("_", "");
    }

    private static String displayCommand(String key) {
        return switch (key) {
            case "tpa" -> "TPA";
            case "tpahere" -> "TPAHere";
            case "adangle" -> "ADangle";
            case "super", "superbreaker" -> "Super Breaker";
            default -> key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
        };
    }

    private static long parseDurationMs(String text) {
        long total = 0L;
        Matcher clock = CLOCK_TIME.matcher(text);
        if (clock.find()) {
            long first = Long.parseLong(clock.group(1));
            long second = Long.parseLong(clock.group(2));
            String third = clock.group(3);
            if (third == null) {
                total += first * 60_000L + second * 1_000L;
            } else {
                total += first * 3_600_000L + second * 60_000L + Long.parseLong(third) * 1_000L;
            }
        }

        Matcher matcher = DURATION_PAIR.matcher(text);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("h")) {
                total += Math.round(value * 3_600_000L);
            } else if (unit.startsWith("m")) {
                total += Math.round(value * 60_000L);
            } else {
                total += Math.round(value * 1_000L);
            }
        }
        return total;
    }

    private static long parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace(" ", "").trim();
        double multiplier = 1.0D;
        if (normalized.endsWith("k")) {
            multiplier = 1_000.0D;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("m")) {
            multiplier = 1_000_000.0D;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("b")) {
            multiplier = 1_000_000_000.0D;
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(normalized) * multiplier);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Nullable
    private static Integer parseInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String compact(long value) {
        long abs = Math.abs(value);
        if (abs >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fb", value / 1_000_000_000.0D).replace(".0", "");
        }
        if (abs >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000.0D).replace(".0", "");
        }
        if (abs >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0D).replace(".0", "");
        }
        return Long.toString(value);
    }

    private static String formatRemaining(long remainingMs) {
        long seconds = Math.max(0L, remainingMs / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    private static String allText(ItemStack stack, List<String> lines) {
        StringBuilder builder = new StringBuilder(clean(stack.getName().getString()));
        for (String line : lines) {
            builder.append(' ').append(clean(line));
        }
        return builder.toString();
    }

    private static String normalizeKey(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "").replaceAll("\\s+", " ").trim();
    }

    public record FeatureHudEntry(String name, String value, int color) {
    }

    /** A satchel for the HUD: name, amount / capacity, fill 0-100 and its item (icon). */
    public record SatchelView(String name, long amount, long capacity, float fillPercent, ItemStack icon, boolean warn) {
    }

    public static List<SatchelView> satchelViews() {
        if (!ThePrisonsClient.CONFIG.get().hud.showSatchelHud) {
            return List.of();
        }
        List<SatchelView> views = new ArrayList<>();
        for (SatchelSummary s : SATCHELS) {
            if (reportedFull(s.displayName)) {
                long cap = s.capacity > 0 ? s.capacity : Math.max(1L, s.amount);
                views.add(new SatchelView(s.displayName, cap, cap, 100.0F, s.icon, true));
                continue;
            }
            views.add(new SatchelView(s.displayName, s.amount, s.capacity, s.fillPercent, s.icon,
                    s.capacity > 0 && s.fillPercent >= ThePrisonsClient.CONFIG.get().hud.satchelWarningPercent));
        }
        return views;
    }

    public static String compactAmount(long value) {
        return compact(value);
    }

    private static final class RuntimeCooldown {
        private final String displayName;
        private long endsAtMs;
        private boolean readyAnnounced;

        private RuntimeCooldown(String displayName) {
            this.displayName = displayName;
        }
    }

    private static final class SessionStats {
        private long startedAtMs = System.currentTimeMillis();
        private long pausedAtMs;
        private long pausedDurationMs;
        private long sessionXp;
        private long sessionEnergy;

        private void ensureStarted() {
            if (startedAtMs <= 0L) {
                startedAtMs = System.currentTimeMillis();
            }
        }

        private long perHour(long value) {
            long activeMs = Math.max(1L, System.currentTimeMillis() - startedAtMs - pausedDurationMs);
            if (ThePrisonsClient.CONFIG.get().hud.pauseStatsHud && pausedAtMs > 0L) {
                activeMs = Math.max(1L, pausedAtMs - startedAtMs - pausedDurationMs);
            }
            return Math.round(value * 3_600_000.0D / activeMs);
        }

        private void pause() {
            if (pausedAtMs == 0L) {
                pausedAtMs = System.currentTimeMillis();
            }
        }

        private void resume() {
            if (pausedAtMs > 0L) {
                pausedDurationMs += System.currentTimeMillis() - pausedAtMs;
                pausedAtMs = 0L;
            }
        }

        private void reset() {
            startedAtMs = System.currentTimeMillis();
            pausedAtMs = 0L;
            pausedDurationMs = 0L;
            sessionXp = 0L;
            sessionEnergy = 0L;
        }
    }

    private record SatchelInfo(String key, String displayName, long amount, long capacity) {
        private float fillPercent() {
            return capacity <= 0L ? 0.0F : Math.min(100.0F, amount * 100.0F / capacity);
        }
    }

    private static final class SatchelSummary {
        private final String displayName;
        private final long amount;
        private final long capacity;
        private final int count;
        private final float fillPercent;
        private ItemStack icon = ItemStack.EMPTY;

        private SatchelSummary(String displayName, long amount, long capacity, int count) {
            this.displayName = count > 1 ? displayName + " x" + count : displayName;
            this.amount = amount;
            this.capacity = capacity;
            this.count = count;
            this.fillPercent = capacity <= 0L ? 0.0F : Math.min(100.0F, amount * 100.0F / capacity);
        }

        private static final class Mutable {
            private final String displayName;
            private long amount;
            private long capacity;
            private int count;
            private ItemStack icon = ItemStack.EMPTY;

            private Mutable(String displayName) {
                this.displayName = displayName;
            }

            private SatchelSummary freeze() {
                SatchelSummary summary = new SatchelSummary(displayName, amount, capacity, count);
                summary.icon = icon;
                return summary;
            }
        }
    }

    private record BookInsight(@Nullable Integer success, @Nullable Integer destroy) {
        private String summary() {
            List<String> parts = new ArrayList<>();
            if (success != null) {
                parts.add("Success " + success + "%");
            }
            if (destroy != null) {
                parts.add("Destroy " + destroy + "%");
            }
            return String.join(" / ", parts);
        }
    }
}
