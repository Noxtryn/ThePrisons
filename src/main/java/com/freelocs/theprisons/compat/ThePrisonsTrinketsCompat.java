package com.freelocs.theprisons.compat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class ThePrisonsTrinketsCompat {
    private static final boolean AVAILABLE;
    private static final Method GET_TRINKET_COMPONENT;
    private static final Method FOR_EACH;

    static {
        Method getTrinketComponent = null;
        Method forEach = null;
        boolean available = false;

        try {
            Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            Class<?> trinketComponent = Class.forName("dev.emi.trinkets.api.TrinketComponent");
            getTrinketComponent = trinketsApi.getMethod("getTrinketComponent", net.minecraft.entity.LivingEntity.class);
            forEach = trinketComponent.getMethod("forEach", BiConsumer.class);
            available = true;
        } catch (ReflectiveOperationException exception) {
            available = false;
        }

        AVAILABLE = available;
        GET_TRINKET_COMPONENT = getTrinketComponent;
        FOR_EACH = forEach;
    }

    private ThePrisonsTrinketsCompat() {
    }


    public static List<ItemStack> getEquippedStacks(ClientPlayerEntity player) {
        if (player == null) {
            return Collections.emptyList();
        }

        List<ItemStack> stacks = new java.util.ArrayList<>();
        for (ItemStack stack : player.getInventory().getMainStacks()) {
            if (stack != null && !stack.isEmpty()) {
                stacks.add(stack);
            }
        }

        if (!AVAILABLE) {
            return stacks;
        }

        try {
            Object optionalValue = GET_TRINKET_COMPONENT.invoke(null, player);
            if (!(optionalValue instanceof Optional<?> optional) || optional.isEmpty()) {
                return stacks;
            }

            Object component = optional.get();
            FOR_EACH.invoke(component, (BiConsumer<Object, Object>) (slotReference, stack) -> {
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    stacks.add(itemStack);
                }
            });
            return stacks;
        } catch (ReflectiveOperationException exception) {
            return stacks;
        }
    }
}
