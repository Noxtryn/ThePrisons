package com.freelocs.theprisons.core.client;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Strips every Minecraft formatting code from server text before any rule reads it: colour / style codes
 * ({@code §0-9 a-f k-o r}, either case), hex colour sequences ({@code §x§r§r§g§g§b§b}), stray section signs, zero-width
 * and control characters, and turns non-breaking spaces into spaces and runs of spaces into one. A cosmetic server update (other
 * colours, bold, rainbow names) then changes nothing for the parsers.
 */
public final class TextStrip {
    private static final Pattern HEX = Pattern.compile("(?i)§x(?:§[0-9a-f]){6}");
    private static final Pattern CODE = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern LONE = Pattern.compile("§");
    /** Zero-width characters and control characters (Cosmic ends every sidebar line with one: U+0086, U+0087 ...). */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u0000-\\u0008\\u000E-\\u001F\\u007F-\\u009F\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    private TextStrip() {
    }

    public static String strip(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = HEX.matcher(text).replaceAll("");
        s = CODE.matcher(s).replaceAll("");
        s = LONE.matcher(s).replaceAll("");
        s = INVISIBLE.matcher(s).replaceAll("");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }
}
