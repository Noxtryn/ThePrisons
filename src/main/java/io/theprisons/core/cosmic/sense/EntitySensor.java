package io.theprisons.core.cosmic.sense;

import io.theprisons.core.client.TextStrip;
import io.theprisons.core.cosmic.data.Raw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Living entities in a bounded box around the player (never the whole loaded world). At most {@link #MAX} are read per sample;
 * the box is a setting of the service. This replaces nothing yet: the spear helper, bandit macro and ore macro still run their
 * own loops until they move to the store.
 */
public final class EntitySensor {
    public static final int MAX = 96;

    public List<Raw.Entity> sample(MinecraftClient client, double radius) {
        ClientPlayerEntity me = client.player;
        if (me == null || client.world == null) {
            return List.of();
        }
        List<LivingEntity> found = client.world.getEntitiesByClass(LivingEntity.class, me.getBoundingBox().expand(radius),
                entity -> entity != me && entity.isAlive());
        List<Raw.Entity> out = new ArrayList<>(Math.min(found.size(), MAX));
        for (LivingEntity e : found) {
            if (out.size() >= MAX) {
                break;
            }
            String name = TextStrip.strip(e.getName().getString());
            StringBuilder shown = new StringBuilder();
            if (e instanceof PlayerEntity player) {
                var handler = client.getNetworkHandler();
                PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(player.getUuid());
                if (entry != null && entry.getDisplayName() != null) {
                    shown.append(TextStrip.strip(entry.getDisplayName().getString()).toLowerCase(Locale.ROOT));
                }
                shown.append(' ').append(TextStrip.strip(e.getDisplayName().getString()).toLowerCase(Locale.ROOT));
            }
            out.add(new Raw.Entity(e.getId(), Registries.ENTITY_TYPE.getId(e.getType()).toString(), name, shown.toString().trim(),
                    e instanceof PlayerEntity, e instanceof HostileEntity, e.getX(), e.getY(), e.getZ(), e.distanceTo(me),
                    e.getHealth(), Raw.Stack.EMPTY));
        }
        return out;
    }
}
