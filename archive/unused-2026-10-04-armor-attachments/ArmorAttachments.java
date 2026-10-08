package com.freelocs.theprisons.modules.qol.items;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D armour attachments (tools/textures/armor_attach.py) that break the boxy silhouette: pauldrons on the arms, a
 * chest emblem, tassets over the hips, boot wings and toe caps, a helmet crest. Filled in when an entity's render state
 * is built, drawn by {@link ArmorAttachmentFeature} at the body parts.
 */
public final class ArmorAttachments {
    public enum Part { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    public static final class Entry {
        Part part = Part.BODY;
        final ItemRenderState state = new ItemRenderState();

        public Part part() {
            return part;
        }

        public ItemRenderState state() {
            return state;
        }
    }

    /** Per render state (see the render state duck mixin). */
    public static final class Holder {
        final List<Entry> entries = new ArrayList<>();
        int used;

        public void reset() {
            used = 0;
        }

        public List<Entry> active() {
            return entries.subList(0, used);
        }

        Entry next(Part part) {
            if (used == entries.size()) {
                entries.add(new Entry());
            }
            Entry e = entries.get(used++);
            e.part = part;
            return e;
        }
    }

    public interface Duck {
        Holder theprisons$attachments();
    }

    private static final String[] MATERIALS = {"leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite"};
    private static final Map<Identifier, ItemStack> STACKS = new HashMap<>();

    private ArmorAttachments() {
    }

    /** The material of a vanilla armour piece ("diamond_chestplate" -> "diamond"), or null. */
    static String material(ItemStack stack) {
        if (stack.isEmpty() || stack.isOf(Items.PLAYER_HEAD)) {
            return null;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        for (String m : MATERIALS) {
            if (path.startsWith(m + "_")) {
                return m;
            }
        }
        return null;
    }

    static void update(LivingEntity entity, LivingEntityRenderState state, boolean headFree) {
        Holder holder = ((Duck) state).theprisons$attachments();
        holder.used = 0;
        if (state.invisible) {
            return;
        }
        String head = material(entity.getEquippedStack(EquipmentSlot.HEAD));
        String chest = material(entity.getEquippedStack(EquipmentSlot.CHEST));
        String legs = material(entity.getEquippedStack(EquipmentSlot.LEGS));
        String feet = material(entity.getEquippedStack(EquipmentSlot.FEET));
        if (head != null && headFree) {
            add(holder, Part.HEAD, head + "_crest", entity);
        }
        if (chest != null) {
            add(holder, Part.RIGHT_ARM, chest + "_pauldron_right", entity);
            add(holder, Part.LEFT_ARM, chest + "_pauldron_left", entity);
            add(holder, Part.BODY, chest + "_emblem", entity);
        }
        if (legs != null) {
            add(holder, Part.BODY, legs + "_tassets", entity);
        }
        if (feet != null) {
            add(holder, Part.RIGHT_LEG, feet + "_boot_right", entity);
            add(holder, Part.LEFT_LEG, feet + "_boot_left", entity);
        }
    }

    private static void add(Holder holder, Part part, String model, LivingEntity entity) {
        Identifier id = Identifier.of("theprisons", "prisons/armor_attach/" + model);
        ItemStack stack = STACKS.computeIfAbsent(id, i -> {
            ItemStack s = new ItemStack(Items.PAPER);
            s.set(DataComponentTypes.ITEM_MODEL, i);
            return s;
        });
        Entry entry = holder.next(part);
        MinecraftClient.getInstance().getItemModelManager().updateForLivingEntity(entry.state, stack, ItemDisplayContext.NONE, entity);
    }
}
