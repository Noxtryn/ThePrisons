package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot;

import java.util.Map;
import java.util.TreeMap;

/** A flat, comparable text view of a snapshot (what the parsers decided). Times are left out so replays are deterministic. */
public final class SnapshotSummary {
    private SnapshotSummary() {
    }

    public static Map<String, String> of(CosmicContextSnapshot s) {
        Map<String, String> m = new TreeMap<>();
        var c = s.cosmic();
        m.put("cosmic.zone", c.zone().describe());
        m.put("cosmic.event", c.event().describe());
        m.put("cosmic.mine", c.mine().describe());
        m.put("cosmic.sidebarPresent", String.valueOf(c.sidebarPresent()));
        m.put("cosmic.taxPercent", c.taxPercent().describe());
        m.put("cosmic.energyNow", c.energyNow().describe());
        m.put("cosmic.energyCapacity", c.energyCapacity().describe());
        m.put("cosmic.energyLevel", c.energyLevel().describe());
        m.put("cosmic.totalXp", c.totalXp().describe());
        m.put("pickaxe.energy", c.heldPickaxeEnergy().describe());
        m.put("pickaxe.capacity", c.heldPickaxeCapacity().describe());
        m.put("pickaxe.durability", c.heldDurability().describe());
        m.put("player.heldClass", s.player().held().itemClass() + "/" + s.player().held().itemTier());
        m.put("player.manualInput", String.valueOf(s.player().manualInput()));
        var combat = s.combat();
        m.put("combat.bandits", String.valueOf(combat.bandits()));
        m.put("combat.players", String.valueOf(combat.players()));
        m.put("combat.hostiles", String.valueOf(combat.hostiles()));
        m.put("combat.spear", combat.spear().recognised().describe());
        m.put("combat.spearCooldown", combat.spear().cooldown().describe());
        int i = 0;
        for (var e : combat.entities()) {
            m.put(String.format("entity.%02d", i++), e.entity().name() + " -> " + e.kind() + " [" + e.confidence() + "]");
        }
        i = 0;
        for (var slot : s.ui().slots()) {
            m.put(String.format("slot.%03d", slot.index()), slot.stack().itemClass() + "/" + slot.stack().itemTier());
            i++;
        }
        m.put("ui.title", String.valueOf(s.ui().title()));
        return m;
    }
}
