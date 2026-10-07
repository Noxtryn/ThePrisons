package io.theprisons.modules.qol.players;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.command.CommandService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Friends and gang mates at a glance - information, no advantage over anyone else: friends ({@code /theprisons friend
 * add NAME}) get a blue frame in the tab list and a blue glow in the world, players of the own gang a pink one. Nothing
 * is shown for other players. Saved in {@code config/theprisons/friends.json}.
 */
public final class FriendsModule extends Module {
    public static final int FRIEND_COLOUR = 0x4DA3FF;
    public static final int GANG_COLOUR = 0xFF5FA2;
    private static @Nullable FriendsModule instance;

    private final FriendList list = new FriendList();
    /** Glow colour per player (and the list version it was made for): it is asked for every player every frame. */
    private final java.util.Map<java.util.UUID, int[]> glowCache = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> glowCacheAt = new java.util.HashMap<>();
    private int listVersion;
    private final Settings.BoolSetting tab;
    private final Settings.BoolSetting glow;
    private boolean loaded;

    public FriendsModule() {
        super("friends", "Friends", Category.QOL, "Players",
                "Friends (/theprisons friend add NAME) framed blue in the tab list and glowing blue in the world, your "
                        + "gang mates pink - so you see a mate coming.", Settings.KeybindSetting.NONE);
        tab = bool("tab", "Frame in the tab list", true).group("General");
        glow = bool("glow", "Glow in the world", true)
                .description("Friends and gang mates glow in their colour (also behind blocks, within view distance).")
                .group("General");
        instance = this;
    }

    public static @Nullable FriendsModule get() {
        return instance;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public void register(CommandService commands) {
        commands.contribute(root -> root.then(ClientCommandManager.literal("friend")
                .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client.getNetworkHandler() != null) {
                                        for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
                                            String n = e.getProfile().name();
                                            if (n != null && FriendList.NAME.matcher(n).matches() && !list.isFriend(n)) {
                                                b.suggest(n);
                                            }
                                        }
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(ClientCommandManager.literal("remove")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    ensureLoaded();
                                    list.friends().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(ClientCommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
                .executes(ctx -> list(ctx.getSource()))));
    }

    @Override
    protected void onEnable() {
        ensureLoaded();
        on(CoreEvents.ChatReceived.class, e -> {
            if (!e.fromPlayer() && !e.overlay() && list.chat(e.message().getString())) {
                ThePrisonsClient.LOGGER.info("[friends] own gang: {}", list.gang().isEmpty() ? "none" : list.gang());
                save();
            }
        });
        every(100, "friends-gang", this::readOwnGang);
    }

    /** The own gang from the own tab name ("***Deutsch (42) <Arcanist> M4cL4ren"). */
    private void readOwnGang() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        PlayerListEntry self = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (self == null) {
            return;
        }
        String gang = FriendList.gangOf(client.inGameHud.getPlayerListHud().getPlayerName(self).getString());
        if (gang != null && !gang.equals(list.gang())) {
            list.gang(gang);
            ThePrisonsClient.LOGGER.info("[friends] own gang from the tab list: {}", gang);
            save();
        }
    }

    /** Friend / gang mate / nobody - for the tab list (account name and the tab name as shown). */
    public FriendList.Relation relation(String name, @Nullable String shown) {
        if (!enabled()) {
            return FriendList.Relation.NONE;
        }
        ensureLoaded();
        return list.relation(name, shown);
    }

    /** The frame colour in the tab list ({@code -1} = none). */
    public int tabColour(String name, @Nullable String shown) {
        if (!tab.on()) {
            return -1;
        }
        return colour(relation(name, shown));
    }

    /** The glow colour of a player entity in the world ({@code -1} = no glow). */
    public int glowColour(Entity entity) {
        if (!glow.on() || !enabled() || !(entity instanceof PlayerEntity player) || entity == MinecraftClient.getInstance().player) {
            return -1;
        }
        ensureLoaded();
        if (list.friends().isEmpty() && list.gang().isEmpty()) {
            // Nobody to mark: the common case costs nothing (this runs for every player, every frame).
            return -1;
        }
        long now = System.currentTimeMillis();
        int[] cached = glowCache.get(player.getUuid());
        if (cached != null && glowCacheAt.getOrDefault(player.getUuid(), 0L) + 1_000L > now && cached[1] == listVersion) {
            return cached[0];
        }
        int colour = computeGlow(player);
        glowCache.put(player.getUuid(), new int[]{colour, listVersion});
        glowCacheAt.put(player.getUuid(), now);
        if (glowCache.size() > 2_000) {
            glowCache.clear();
            glowCacheAt.clear();
        }
        return colour;
    }

    private int computeGlow(PlayerEntity player) {
        String name = player.getGameProfile().name();
        if (name == null || name.startsWith("bandit_") || name.startsWith("guard_")) {
            return -1;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        String shown = null;
        if (client.getNetworkHandler() != null) {
            PlayerListEntry e = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (e == null) {
                // NPCs in player shape (guards, shop keepers) are not in the tab list.
                return -1;
            }
            shown = client.inGameHud.getPlayerListHud().getPlayerName(e).getString();
        }
        return colour(relation(name, shown));
    }

    private static int colour(FriendList.Relation relation) {
        return switch (relation) {
            case FRIEND -> FRIEND_COLOUR;
            case GANG -> GANG_COLOUR;
            case NONE -> -1;
        };
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    private int add(FabricClientCommandSource source, String name) {
        ensureLoaded();
        if (!FriendList.NAME.matcher(name).matches()) {
            source.sendError(Text.literal("\"" + name + "\" is no Minecraft name."));
            return 0;
        }
        if (!list.add(name)) {
            source.sendFeedback(Text.literal(name + " is your friend already.").formatted(Formatting.GRAY));
            return 1;
        }
        save();
        source.sendFeedback(Text.literal("Friend added: ").formatted(Formatting.GRAY)
                .append(Text.literal(name).styled(s -> s.withColor(FRIEND_COLOUR))));
        return 1;
    }

    private int remove(FabricClientCommandSource source, String name) {
        ensureLoaded();
        if (!list.remove(name)) {
            source.sendError(Text.literal(name + " is not on your friend list."));
            return 0;
        }
        save();
        source.sendFeedback(Text.literal("Friend removed: " + name).formatted(Formatting.GRAY));
        return 1;
    }

    private int list(FabricClientCommandSource source) {
        ensureLoaded();
        source.sendFeedback(Text.literal("Friends (" + list.friends().size() + "): ").formatted(Formatting.GRAY)
                .append(Text.literal(list.friends().isEmpty() ? "none - /theprisons friend add NAME" : String.join(", ", list.friends()))
                        .styled(s -> s.withColor(FRIEND_COLOUR))));
        source.sendFeedback(Text.literal("Gang: ").formatted(Formatting.GRAY)
                .append(Text.literal(list.gang().isEmpty() ? "not known yet" : list.gang()).styled(s -> s.withColor(GANG_COLOUR))));
        return 1;
    }

    // ── File ─────────────────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("friends.json");
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file())) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            if (root.has("friends")) {
                root.getAsJsonArray("friends").forEach(e -> list.add(e.getAsString()));
            }
            if (root.has("gang")) {
                list.gang(root.get("gang").getAsString());
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[friends] could not read {}", file(), e);
        }
    }

    private void save() {
        listVersion++;
        JsonObject root = new JsonObject();
        JsonArray names = new JsonArray();
        list.friends().forEach(names::add);
        root.add("friends", names);
        root.addProperty("gang", list.gang());
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("friends.json.tmp");
            Files.writeString(tmp, root.toString());
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[friends] could not write {}", file(), e);
        }
    }
}
