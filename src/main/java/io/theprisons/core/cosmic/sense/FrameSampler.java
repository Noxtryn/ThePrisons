package io.theprisons.core.cosmic.sense;

import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.cosmic.data.Raw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.player.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads one {@link Raw.Frame} from the normal Minecraft client: only what the client already has (its own player, the entities
 * and blocks it loaded, the sidebar, boss bars, the open screen). It never touches launcher files, account data or the network.
 */
public final class FrameSampler {
    /** What to read this time. Cheap parts are always read; the others are the caller's choice. */
    public record Plan(double entityRadius, int blockRadius, boolean scanBlocks, boolean inventory, boolean slots) {
    }

    private final PlayerSensor player = new PlayerSensor();
    private final EntitySensor entities = new EntitySensor();
    private final BlockSensor blocks = new BlockSensor();
    private final ScreenSensor screen = new ScreenSensor();
    private final StackReader.Cache inventoryCache = new StackReader.Cache();

    public Raw.Frame sample(MinecraftClient client, long tick, long nowMs, Plan plan, Raw.@Nullable Blocks lastBlocks,
                            List<Raw.Line> actionBar) {
        Raw.Player me = player.sample(client);
        Raw.Where where = where(client);
        List<Raw.Entity> near = me == null ? List.of() : entities.sample(client, plan.entityRadius());
        Raw.Blocks cube = me == null ? null : plan.scanBlocks() ? blocks.sample(client, plan.blockRadius()) : lastBlocks;
        List<String> sidebar = client.world == null ? null : ClientReadouts.sidebar(client, client.world);
        List<String> bars = client.world == null ? List.of() : ClientReadouts.bossBarTitles(client);
        return new Raw.Frame(tick, nowMs, where, me, near, cube, sidebar, bars, actionBar, screen.sample(client, plan.slots()),
                plan.inventory() && client.player != null ? inventory(client) : List.of());
    }

    private List<Raw.Stack> inventory(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        List<Raw.Stack> out = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) {
            out.add(inventoryCache.read(300 + i, inv.getStack(i)));
        }
        return out;
    }

    public static Raw.Where where(MinecraftClient client) {
        if (client.world == null) {
            return Raw.Where.NONE;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        if (client.isInSingleplayer()) {
            return new Raw.Where("singleplayer", dimension, true);
        }
        ServerInfo info = client.getCurrentServerEntry();
        return new Raw.Where(info == null ? "unknown" : serverId(info.address), dimension, false);
    }

    /** The Cosmic Prisons host (public) stays readable; any other address becomes "other-xxxxxx" (a hash, never the address). */
    public static String serverId(String address) {
        String host = address.toLowerCase(Locale.ROOT).trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        if (host.endsWith("cosmicprisons.com")) {
            return host;
        }
        return "other-" + String.format("%06x", host.hashCode() & 0xFFFFFF);
    }

    public void clear() {
        player.clear();
        screen.clear();
        inventoryCache.clear();
    }
}
