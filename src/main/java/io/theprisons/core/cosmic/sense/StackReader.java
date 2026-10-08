package io.theprisons.core.cosmic.sense;

import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.modules.qol.items.PrisonsItems;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

/**
 * ItemStack -> {@link Raw.Stack}. The item class comes from {@link PrisonsItems} (the one place that knows Cosmic items), the lore
 * from {@link ClientReadouts}. A {@link Cache} keeps the result of a slot while its stack is unchanged, so sampling every few
 * ticks does not re-read lore that did not change.
 */
public final class StackReader {
    private StackReader() {
    }

    public static Raw.Stack read(ItemStack stack) {
        if (stack.isEmpty()) {
            return Raw.Stack.EMPTY;
        }
        String itemClass = "";
        String tier = "";
        PrisonsItems.Info info = PrisonsItems.info(stack);
        if (info.model() != null) {
            String path = info.model().getPath();
            if (path.startsWith("prisons/")) {
                path = path.substring("prisons/".length());
            }
            int slash = path.indexOf('/');
            itemClass = slash >= 0 ? path.substring(0, slash) : path;
        }
        if (info.tier() != null) {
            tier = info.tier().id;
        }
        boolean damageable = stack.isDamageable();
        return new Raw.Stack(Registries.ITEM.getId(stack.getItem()).toString(), TextStrip.strip(stack.getName().getString()),
                stack.getCount(), ClientReadouts.lore(stack), damageable ? stack.getDamage() : 0,
                damageable ? stack.getMaxDamage() : 0, itemClass, tier);
    }

    /** Re-reads a stack only when it changed since the last call for the same key (a slot index). Client thread only. */
    public static final class Cache {
        private final Map<Integer, ItemStack> last = new HashMap<>();
        private final Map<Integer, Raw.Stack> read = new HashMap<>();

        public Raw.Stack read(int key, ItemStack stack) {
            ItemStack before = last.get(key);
            if (before != null && ItemStack.areEqual(before, stack)) {
                return read.get(key);
            }
            Raw.Stack fresh = StackReader.read(stack);
            last.put(key, stack.copy());
            read.put(key, fresh);
            return fresh;
        }

        public void clear() {
            last.clear();
            read.clear();
        }
    }
}
