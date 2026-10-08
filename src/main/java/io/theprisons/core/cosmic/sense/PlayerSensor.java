package io.theprisons.core.cosmic.sense;

import io.theprisons.core.client.TextStrip;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.parse.SpearRule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The local player: position, motion, view, health, held item, armour, effects, keys. */
public final class PlayerSensor {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final StackReader.Cache stacks = new StackReader.Cache();

    public Raw.@Nullable Player sample(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            return null;
        }
        Vec3d v = p.getVelocity();
        List<Raw.Stack> armor = new ArrayList<>(4);
        for (int i = 0; i < ARMOR.length; i++) {
            armor.add(stacks.read(100 + i, p.getEquippedStack(ARMOR[i])));
        }
        List<Raw.Effect> effects = new ArrayList<>();
        for (StatusEffectInstance effect : p.getStatusEffects()) {
            effects.add(new Raw.Effect(effect.getEffectType().getIdAsString(), effect.getAmplifier(), effect.getDuration()));
        }
        ItemStack held = p.getMainHandStack();
        ItemStack off = p.getOffHandStack();
        float cooldown = -1.0F;
        ItemStack weapon = SpearRule.isSpear(Registries.ITEM.getId(held.getItem()).toString()) ? held
                : SpearRule.isSpear(Registries.ITEM.getId(off.getItem()).toString()) ? off : ItemStack.EMPTY;
        if (!weapon.isEmpty()) {
            cooldown = p.getItemCooldownManager().getCooldownProgress(weapon, 0.0F);
        }
        return new Raw.Player(TextStrip.strip(p.getName().getString()), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYaw(),
                p.getPitch(), p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), stacks.read(0, held),
                stacks.read(1, off), armor, effects, InputSensor.sample(client), p.isOnGround(), cooldown);
    }

    public void clear() {
        stacks.clear();
    }
}
