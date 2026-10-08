package io.theprisons.core.cosmic.parse;

import java.util.regex.Pattern;

/**
 * Keeps secrets and private talk out of anything that is stored or exported (captures, logs). It cannot know what is
 * private; it removes what clearly is: tokens, session ids, addresses, e-mails and private messages. The sensors never read
 * launcher or account files in the first place - this is the second line.
 */
public final class PrivacyFilter {
    public static final String REDACTED = "<redacted>";

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{4,})?");
    private static final Pattern DISCORD_TOKEN = Pattern.compile("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6}\\.[A-Za-z0-9_-]{27,40}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:bearer|bot)\\s+[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern KEYED = Pattern.compile(
            "(?i)\\b(access[_ -]?token|refresh[_ -]?token|session[_ -]?(?:id|token)|client[_ -]?secret|password|passwd|api[_ -]?key|authorization)\\b\\s*[:=]\\s*\\S+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    private static final Pattern IPV6 = Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_SECRET = Pattern.compile("\\b[A-Fa-f0-9]{32,}\\b|\\b[A-Za-z0-9+/]{40,}={0,2}\\b");
    /** "[Name -> me] hi", "From Name: hi", "To Name: hi", "Name whispers to you: hi", "[PM] ...". */
    private static final Pattern PRIVATE_MESSAGE = Pattern.compile(
            "(?i)^\\s*(?:\\[[^\\]]{1,32}\\s*(?:->|=>|»)\\s*[^\\]]{1,32}\\]|(?:from|to)\\s+\\S+\\s*:|\\[(?:pm|msg|dm)\\]|\\S+\\s+whispers(?:\\s+to you)?\\s*:)");

    private PrivacyFilter() {
    }

    /** True for lines that look like a private message (they are dropped, not redacted). */
    public static boolean isPrivateMessage(String line) {
        return PRIVATE_MESSAGE.matcher(line).find();
    }

    /** The text with every recognised secret replaced by {@value #REDACTED}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = JWT.matcher(text).replaceAll(REDACTED);
        s = BEARER.matcher(s).replaceAll(REDACTED);
        s = KEYED.matcher(s).replaceAll(m -> m.group(1) + ": " + REDACTED);
        s = DISCORD_TOKEN.matcher(s).replaceAll(REDACTED);
        s = IPV4.matcher(s).replaceAll(REDACTED);
        s = IPV6.matcher(s).replaceAll(REDACTED);
        s = EMAIL.matcher(s).replaceAll(REDACTED);
        return LONG_SECRET.matcher(s).replaceAll(REDACTED);
    }

    /** A system / chat line safe to store: {@code null} for private messages, else redacted. */
    public static @org.jspecify.annotations.Nullable String safeLine(String line) {
        return isPrivateMessage(line) ? null : redact(line);
    }
}
