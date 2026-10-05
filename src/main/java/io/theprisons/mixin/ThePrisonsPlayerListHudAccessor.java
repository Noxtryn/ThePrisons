package io.theprisons.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Header, footer and the ordered player entries of the tab list, for the Better Tab. */
@Mixin(PlayerListHud.class)
public interface ThePrisonsPlayerListHudAccessor {
    @Accessor("header")
    Text theprisons$header();

    @Accessor("footer")
    Text theprisons$footer();

    @Invoker("collectPlayerEntries")
    List<PlayerListEntry> theprisons$collectPlayerEntries();
}
