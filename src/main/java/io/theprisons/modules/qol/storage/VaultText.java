package io.theprisons.modules.qol.storage;

import io.theprisons.core.client.TextStrip;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads vault numbers from Cosmic Prisons screen titles and chat lines (formatting codes are stripped first). */
final class VaultText {
    /** "PV #3", "PV 3", "Private Vault #3", "Player Vault 3", "Vault #3". */
    private static final Pattern TITLE = Pattern.compile(
            "(?i)\\b(?:pv|p\\.v\\.|(?:private|player)\\s*vault|vault)\\s*(?:#|no\\.?|nr\\.?)?\\s*(\\d{1,3})\\b");
    /** "(!) You do not have access to PV #7." / "... PV #7 is locked" (seen in the logs: the first form). */
    private static final Pattern DENIED_HINT = Pattern.compile("(?i)(?:no access|not have access|don't have access|locked|unlock)");

    private VaultText() {
    }

    /** The vault page named in a screen title, or {@code null}. */
    static @Nullable Integer pageOfTitle(@Nullable String title) {
        Matcher m = TITLE.matcher(TextStrip.strip(title));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** The page a chat line refuses access to, or 0. */
    static int deniedPage(@Nullable String line) {
        String s = TextStrip.strip(line);
        if (!DENIED_HINT.matcher(s).find()) {
            return 0;
        }
        Matcher m = TITLE.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
