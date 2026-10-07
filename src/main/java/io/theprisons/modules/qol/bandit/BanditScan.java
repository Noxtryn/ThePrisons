package io.theprisons.modules.qol.bandit;

import io.theprisons.core.client.TextStrip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Locale;
import java.util.regex.Pattern;

/** What a Cosmic bandit is (shared by the spear helper and the bandit macro). */
final class BanditScan {
    private static final String[] ORES = {"coal", "iron", "gold", "diamond", "emerald"};
    /** Cosmic's bandits are fake players named "bandit_<2 hex>_<6 hex>" (their skull shows in chat as [bandit_ae_821e4c head]). */
    static final Pattern NAME = Pattern.compile("bandit_[0-9a-f]{2}_[0-9a-f]{4,8}");

    private BanditScan() {
    }

    static boolean isBanditName(String name) {
        return NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /** @param any bosses and special bandits count too */
    static boolean isBandit(LivingEntity e, boolean any) {
        String name = TextStrip.strip(e.getName().getString()).toLowerCase(Locale.ROOT);
        String shown = name;
        if (e instanceof PlayerEntity p) {
            var handler = MinecraftClient.getInstance().getNetworkHandler();
            var entry = handler == null ? null : handler.getPlayerListEntry(p.getUuid());
            if (entry != null && entry.getDisplayName() != null) {
                shown += " " + TextStrip.strip(entry.getDisplayName().getString()).toLowerCase(Locale.ROOT);
            }
            shown += " " + TextStrip.strip(e.getDisplayName().getString()).toLowerCase(Locale.ROOT);
        }
        if (NAME.matcher(name).matches()) {
            // Ore bandit by name; bosses and the like only when asked for. The name alone does not tell the ore.
            return any || !shown.contains("boss");
        }
        if (!shown.contains("bandit")) {
            return false;
        }
        if (any) {
            return true;
        }
        for (String ore : ORES) {
            if (shown.contains(ore)) {
                return true;
            }
        }
        return false;
    }
}
