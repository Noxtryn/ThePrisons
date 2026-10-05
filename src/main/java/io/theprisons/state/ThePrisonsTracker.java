package io.theprisons.state;

import io.theprisons.ThePrisonsClient;
import io.theprisons.cache.ThePrisonsCache.ThePrisonsEntry;
import io.theprisons.compat.ThePrisonsTrinketsCompat;
import io.theprisons.config.ThePrisonsConfig;
import io.theprisons.mixin.ThePrisonsItemCooldownInstanceAccessor;
import io.theprisons.mixin.ThePrisonsItemCooldownsAccessor;
import io.theprisons.ui.ThePrisonsHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ThePrisonsTracker {
    private static final String SOURCE_PET = "PET";
    private static final String SOURCE_TRINKET = "TRINKET";
    private static final long MISSING_ENTRY_PRUNE_GRACE_MS = 15_000L;

    private boolean announceReadyAllowed;
    /** The last seen stack of every tracked pet / trinket (by cache key), for its icon in the HUD. */
    private static final Map<String, ItemStack> STACKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static ItemStack stack(@org.jspecify.annotations.Nullable String key) {
        return key == null ? ItemStack.EMPTY : STACKS.getOrDefault(key, ItemStack.EMPTY);
    }

    public void tick(MinecraftClient client) {
        long nowMs = System.currentTimeMillis();
        boolean changed = updateReadyTransitions(nowMs);

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            announceReadyAllowed = false;
            if (changed) {
                ThePrisonsClient.CACHE.saveAsync();
            }
            return;
        }

        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.general.persistCooldownCache) {
            return;
        }

        changed |= syncInventory(player, nowMs);
        changed |= syncTrinkets(player, nowMs);

        if (changed) {
            ThePrisonsClient.CACHE.saveAsync();
        }
    }

    private boolean syncInventory(ClientPlayerEntity player, long nowMs) {
        return syncStacks(player, player.getInventory().getMainStacks(), nowMs, SOURCE_PET, ThePrisonsTracker::isPet);
    }

    /** Cosmic's trinkets are normal items (inventory / offhand); Trinkets-mod slots are read too when installed. */
    private boolean syncTrinkets(ClientPlayerEntity player, long nowMs) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>(player.getInventory().getMainStacks());
        stacks.add(player.getOffHandStack());
        for (ItemStack stack : ThePrisonsTrinketsCompat.getEquippedStacks(player)) {
            stacks.add(stack);
        }
        return syncStacks(player, stacks, nowMs, SOURCE_TRINKET, ThePrisonsTracker::isTrinket);
    }

    /** "(!) Anti XP Tax Pet is on cooldown for 13m 45s" */
    private static final java.util.regex.Pattern ON_COOLDOWN =
            java.util.regex.Pattern.compile("(?i)^(?:\\(!\\)\\s*)?(.+?) is on cooldown for (.+?)\\.?$");
    /** "(!) Anti XP Tax Pet [LVL 3]: no Guard XP Tax for 30m." / "(!) Lucky Pet: ... for 1 minute" */
    private static final java.util.regex.Pattern EFFECT =
            java.util.regex.Pattern.compile("(?i)^(?:\\(!\\)\\s*)?(.+?(?:pet|trinket)(?:\\s*\\[lvl\\s*\\d+])?):.*?\\bfor\\s+(.+?)\\.?$");
    /** "(!) Your Anti XP Tax [LVL 3] has run out." */
    private static final java.util.regex.Pattern RUN_OUT = java.util.regex.Pattern.compile("(?i)your (.+?) has run out");

    /** Server chat: cooldowns and running effects of pets and trinkets (more exact than item cooldowns). */
    public void onChat(String line) {
        long nowMs = System.currentTimeMillis();
        String text = line.trim();
        java.util.regex.Matcher m = ON_COOLDOWN.matcher(text);
        if (m.find()) {
            ThePrisonsEntry entry = entryFor(m.group(1));
            long left = parseDuration(m.group(2));
            if (entry != null && left > 0L) {
                entry.cooldownEndsAtMs = nowMs + left;
                entry.readyAnnounced = false;
                ThePrisonsClient.CACHE.saveAsync();
            }
            return;
        }
        m = EFFECT.matcher(text);
        if (m.find()) {
            ThePrisonsEntry entry = entryFor(m.group(1));
            long left = parseDuration(m.group(2));
            if (entry != null && left > 0L) {
                entry.activeUntilMs = nowMs + left;
                ThePrisonsClient.CACHE.saveAsync();
            }
            return;
        }
        m = RUN_OUT.matcher(text);
        if (m.find()) {
            String name = normalize(m.group(1));
            for (ThePrisonsEntry entry : ThePrisonsClient.CACHE.entries().values()) {
                if (entry != null && entry.displayName != null && normalize(entry.displayName).startsWith(name)) {
                    entry.activeUntilMs = 0L;
                }
            }
        }
    }

    private @org.jspecify.annotations.Nullable ThePrisonsEntry entryFor(String rawName) {
        String name = normalize(rawName);
        String source = isPet(name) ? SOURCE_PET : isTrinket(name) ? SOURCE_TRINKET : null;
        if (source == null) {
            return null;
        }
        return ThePrisonsClient.CACHE.entries().get(source + ":" + name);
    }

    /** "1h 13m 45s", "33m", "30s", "1 minute", "2 hrs 5 min" → ms (0 = none). */
    static long parseDuration(String text) {
        java.util.regex.Matcher u = java.util.regex.Pattern.compile("(?i)(\\d+)\\s*(d|h|m|s)[a-z]*").matcher(text);
        long ms = 0L;
        while (u.find()) {
            long v = Long.parseLong(u.group(1));
            ms += switch (Character.toLowerCase(u.group(2).charAt(0))) {
                case 'd' -> v * 86_400_000L;
                case 'h' -> v * 3_600_000L;
                case 'm' -> v * 60_000L;
                default -> v * 1_000L;
            };
        }
        return ms;
    }

    private boolean syncStacks(ClientPlayerEntity player, Iterable<ItemStack> stacks, long nowMs, String source, java.util.function.Predicate<String> matchesType) {
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        ItemCooldownManager cooldowns = player.getItemCooldownManager();
        ThePrisonsItemCooldownsAccessor cooldownsAccessor = (ThePrisonsItemCooldownsAccessor) cooldowns;
        Map<?, ?> activeCooldowns = cooldownsAccessor.theprisons$getCooldowns();
        int tickCount = cooldownsAccessor.theprisons$getTickCount();

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }

            String stackName = stack.getName().getString().replaceAll("(?i)\\s*\\[lvl\\s*\\d+]", "").trim();
            String normalizedName = normalize(stackName);
            if (!matchesType.test(normalizedName)) {
                continue;
            }

            String key = source + ":" + normalizedName;
            if (!seen.add(key)) {
                continue;
            }

            ItemStack known = STACKS.get(key);
            if (known == null || !ItemStack.areEqual(known, stack)) {
                STACKS.put(key, stack.copy());
            }
            ThePrisonsEntry entry = ThePrisonsClient.CACHE.getOrCreate(key);
            entry.key = key;
            entry.source = source;
            if (!stackName.equals(entry.displayName)) {
                entry.displayName = stackName;
                changed = true;
            }
            String itemId = getItemId(stack);
            if (!itemId.equals(entry.itemId)) {
                entry.itemId = itemId;
                changed = true;
            }
            if (entry.lastSeenAtMs == 0L || nowMs - entry.lastSeenAtMs >= 5000L) {
                // Only used for pruning in memory: refreshing it is no reason to rewrite the cache file.
                entry.lastSeenAtMs = nowMs;
            }

            Identifier cooldownGroup = cooldowns.getGroup(stack);
            Object instance = activeCooldowns.get(cooldownGroup);
            boolean onCooldown = cooldowns.isCoolingDown(stack) && instance != null;

            if (onCooldown) {
                announceReadyAllowed = true;
                int endTick = ((ThePrisonsItemCooldownInstanceAccessor) instance).theprisons$getEndTime();
                int remainingTicks = Math.max(0, endTick - tickCount);
                long cooldownEndsAtMs = nowMs + (remainingTicks * 50L);
                if (cooldownEndsAtMs > entry.cooldownEndsAtMs) {
                    entry.cooldownEndsAtMs = cooldownEndsAtMs;
                    entry.readyAnnounced = false;
                    changed = true;
                }
                continue;
            }

            if (entry.cooldownEndsAtMs <= nowMs) {
                if (!entry.readyAnnounced && announceReadyAllowed && ThePrisonsClient.CONFIG.get().general.showReadyAnnouncements) {
                    ThePrisonsHudRenderer.pushAnnouncement(entry.displayName);
                }
                if (!entry.readyAnnounced) {
                    entry.readyAnnounced = true;
                    changed = true;
                }
            }
        }

        changed |= pruneMissingEntries(source, seen, nowMs);
        return changed;
    }

    private boolean pruneMissingEntries(String source, Set<String> seen, long nowMs) {
        boolean changed = false;
        Set<String> toRemove = new HashSet<>();

        for (Map.Entry<String, ThePrisonsEntry> entry : ThePrisonsClient.CACHE.entries().entrySet()) {
            ThePrisonsEntry value = entry.getValue();
            if (value == null || value.key == null || value.source == null) {
                continue;
            }

            if (!source.equalsIgnoreCase(value.source)) {
                continue;
            }

            if (!seen.contains(value.key) && nowMs - value.lastSeenAtMs >= MISSING_ENTRY_PRUNE_GRACE_MS) {
                toRemove.add(entry.getKey());
            }
        }

        for (String key : toRemove) {
            ThePrisonsClient.CACHE.entries().remove(key);
            changed = true;
        }

        return changed;
    }

    private boolean updateReadyTransitions(long nowMs) {
        boolean changed = false;
        for (ThePrisonsEntry entry : ThePrisonsClient.CACHE.entries().values()) {
            if (entry == null || entry.displayName == null) {
                continue;
            }

            if (entry.cooldownEndsAtMs <= nowMs && !entry.readyAnnounced && announceReadyAllowed && ThePrisonsClient.CONFIG.get().general.showReadyAnnouncements) {
                ThePrisonsHudRenderer.pushAnnouncement(entry.displayName);
                entry.readyAnnounced = true;
                changed = true;
            }
        }
        return changed;
    }

    /** "anti xp tax pet", "lucky pet" - a name ending in "pet" (not "pet leash", not "carpet"). */
    static boolean isPet(String normalizedName) {
        return normalizedName.matches(".*\\S\\s+pet") ;
    }

    /** "blink trinket", "healing trinket" - not the "random trinket" lootbox. */
    static boolean isTrinket(String normalizedName) {
        return normalizedName.matches(".*\\btrinket\\b.*") && !normalizedName.startsWith("random ");
    }

    static String normalize(String value) {
        return stripLevel(value.toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripLevel(String value) {
        return value
                .replaceAll("\\s*\\[lvl\\s*\\d+\\]\\s*", " ")
                .replaceAll("\\s*lvl\\s*\\d+\\s*", " ")
                .replaceAll("\\s*level\\s*\\d+\\s*", " ")
                .trim();
    }

    private String getItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }
}
