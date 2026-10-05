package io.theprisons.core.setup;

import io.theprisons.core.i18n.I18n;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * Chat lines of the mod itself. They are only added to the player's own chat window ({@code sendMessage} on the
 * client), never sent to the server - nobody else sees them. Style: mod name light blue and bold first, the heading
 * pink and bold, the text white; links are light blue, underlined and run a client command (handled locally by the
 * mod, also never sent).
 */
public final class ModChat {
    public static final int MOD_BLUE = 0x7FD8FF;
    public static final int HEADING_PINK = 0xFF6EC7;
    public static final int TEXT_WHITE = 0xFFFFFF;
    public static final int MUTED = 0x9A9AA8;

    private ModChat() {
    }

    /** "ThePrisons » Heading" */
    public static MutableText header(String heading) {
        return Text.literal("ThePrisons").setStyle(Style.EMPTY.withColor(MOD_BLUE).withBold(true))
                .append(Text.literal(" » ").setStyle(Style.EMPTY.withColor(MUTED).withBold(false)))
                .append(Text.literal(I18n.t(heading)).setStyle(Style.EMPTY.withColor(HEADING_PINK).withBold(true)));
    }

    /** "  • text" in white. */
    public static MutableText line(String text) {
        return Text.literal("  • ").setStyle(Style.EMPTY.withColor(HEADING_PINK))
                .append(Text.literal(text).setStyle(Style.EMPTY.withColor(TEXT_WHITE)));
    }

    /** A clickable "[label]" running {@code command} (a /prisons client command). */
    public static MutableText link(String label, String command, String hover) {
        return Text.literal(" [" + I18n.t(label) + "]").setStyle(Style.EMPTY.withColor(MOD_BLUE).withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(I18n.t(hover)).setStyle(Style.EMPTY.withColor(TEXT_WHITE)))));
    }

    /** Only in the player's own chat window. */
    public static void show(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }
}
