package io.theprisons.modules.qol.players;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.client.TextStrip;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cosmic's player stats as the viewer's "API": {@code /playerstats <name>} answers for any player with
 * <pre>
 * [/playerstats Name]
 * Blocks Mined: 1,821 · Mining: 17 (5,562 xp) · Balance: $1.10K · Bandit Kills: 1 · Playtime: 1d 18h 11m 39s · Gang: Deutsch
 * [/playerstats Name]
 * </pre>
 * The command is sent quietly and its answer is kept out of the chat. Also collects your own skill points and Ground
 * Zero points from chat.
 */
public final class PlayerStats {
    private static final long CACHE_MS = 60_000L;
    private static final long ANSWER_MS = 5_000L;
    private static final long SPACING_MS = 2_500L;
    private static final Pattern MARKER = Pattern.compile("^\\[/playerstats .*]$");
    private static final Pattern ROW = Pattern.compile("^([A-Za-z ]{2,24}):\\s*(.+)$");
    private static final Pattern SKILL_AVAILABLE = Pattern.compile("(?i)available skill points:\\s*(\\d+)");
    private static final Pattern SKILL_EARNED = Pattern.compile("(?i)skill tree! you earned \\+(\\d+) skill point");
    private static final Pattern GZ_REDEEMED = Pattern.compile("(?i)redeemed (\\d[\\d,]*) ground zero points");
    private static final Pattern GZ_RESET = Pattern.compile("(?i)ground zero will reset in:\\s*(\\d+) minutes?");

    public record Stats(Map<String, String> rows, long receivedMs) {
    }

    private static final Map<String, Stats> CACHE = new ConcurrentHashMap<>();
    /** When each name was last asked for (a player whose stats do not come is not asked again for a minute). */
    private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
    private static @Nullable String waitingFor;
    private static long askedMs;
    private static boolean capturing;
    private static final Map<String, String> CAPTURE = new LinkedHashMap<>();

    /** Your own numbers from chat. */
    private static int skillPointsAvailable = -1;
    private static int skillPointsEarned;
    private static long groundZeroPoints;
    private static long groundZeroResetAtMs;

    private PlayerStats() {
    }

    /** Hides the answers of our own requests and reads them (registered once at start). */
    public static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !onLine(TextStrip.strip(message.getString()).trim()));
    }

    /** The cached stats of a player, asking the server when they are missing or old. */
    public static @Nullable Stats get(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Stats s = CACHE.get(key);
        long now = System.currentTimeMillis();
        if ((s == null || now - s.receivedMs() > CACHE_MS) && now - ASKED.getOrDefault(key, 0L) > CACHE_MS) {
            ask(name);
        }
        return s;
    }

    /** The cached stats without asking the server. */
    public static @Nullable Stats cached(String name) {
        return CACHE.get(name.toLowerCase(Locale.ROOT));
    }

    public static boolean waiting(String name) {
        return name.equalsIgnoreCase(waitingFor) && System.currentTimeMillis() - askedMs < ANSWER_MS;
    }

    /** Sends {@code /playerstats name} (one request at a time, a little spacing between them). */
    public static void ask(String name) {
        long now = System.currentTimeMillis();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || now - askedMs < SPACING_MS) {
            return;
        }
        waitingFor = name;
        askedMs = now;
        ASKED.put(name.toLowerCase(Locale.ROOT), now);
        capturing = false;
        CAPTURE.clear();
        client.player.networkHandler.sendChatCommand("playerstats " + name);
    }

    /** {@code true} = the line belongs to our request and stays out of the chat. */
    static boolean onLine(String line) {
        own(line);
        String name = waitingFor;
        if (name == null || System.currentTimeMillis() - askedMs > ANSWER_MS) {
            return false;
        }
        if (MARKER.matcher(line).matches()) {
            if (capturing) {
                CACHE.put(name.toLowerCase(Locale.ROOT), new Stats(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(CAPTURE)), System.currentTimeMillis()));
                ThePrisonsClient.LOGGER.info("[player_stats] {}: {}", name, CAPTURE);
                waitingFor = null;
                capturing = false;
            } else {
                capturing = true;
                CAPTURE.clear();
            }
            return true;
        }
        if (capturing) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                CAPTURE.put(m.group(1).trim(), m.group(2).trim());
            }
            return true;
        }
        // the blank lines around the answer
        return line.isEmpty();
    }

    private static void own(String line) {
        Matcher m = SKILL_AVAILABLE.matcher(line);
        if (m.find()) {
            skillPointsAvailable = Integer.parseInt(m.group(1));
        }
        m = SKILL_EARNED.matcher(line);
        if (m.find()) {
            skillPointsEarned += Integer.parseInt(m.group(1));
            skillPointsAvailable = Math.max(0, skillPointsAvailable) + Integer.parseInt(m.group(1));
        }
        m = GZ_REDEEMED.matcher(line);
        if (m.find()) {
            groundZeroPoints += Long.parseLong(m.group(1).replace(",", ""));
        }
        m = GZ_RESET.matcher(line);
        if (m.find()) {
            groundZeroResetAtMs = System.currentTimeMillis() + Long.parseLong(m.group(1)) * 60_000L;
        }
    }

    public static int skillPointsAvailable() {
        return skillPointsAvailable;
    }

    public static int skillPointsEarned() {
        return skillPointsEarned;
    }

    public static long groundZeroPoints() {
        return groundZeroPoints;
    }

    /** Minutes until Ground Zero resets, -1 when unknown. */
    public static long groundZeroResetMinutes() {
        long left = groundZeroResetAtMs - System.currentTimeMillis();
        return groundZeroResetAtMs == 0L || left < 0L ? -1L : (left + 59_999L) / 60_000L;
    }
}
