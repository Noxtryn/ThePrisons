package io.theprisons.core.cosmic.parse;

import io.theprisons.core.cosmic.value.Confidence;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a living entity is, as far as bandits go. This is the rule {@code BanditScan} (spear helper and bandit macro) used on its
 * own; it now delegates here so the model and the macros classify the same way.
 *
 * <p>Cosmic's ore bandits are fake players named "bandit_&lt;2 hex&gt;_&lt;4-8 hex&gt;" (their skull shows in chat as
 * [bandit_ae_821e4c head]). Everything else that says "bandit" in a name is a special / boss / elite variant. "Elite" is only
 * ever a keyword guess: it comes back with {@link Confidence#UNKNOWN}.
 */
public final class BanditClassifier {
    public static final Pattern NAME = Pattern.compile("bandit_[0-9a-f]{2}_[0-9a-f]{4,8}");
    static final String[] ORES = {"coal", "iron", "gold", "diamond", "emerald"};

    public enum Kind {
        /** A fake player "bandit_xx_xxxxxx". */
        ORE_BANDIT,
        /** A boss (the name says "boss"), named like a bandit or not. */
        BOSS,
        /** Some other thing with "bandit" in its name. */
        SPECIAL,
        /** The name says "elite": a keyword guess, never confirmed. */
        ELITE,
        NOT_BANDIT
    }

    /**
     * @param named   the name has the fake-player form
     * @param boss    the shown name says "boss"
     * @param ore     the ore word in the shown names ("diamond"), or null
     * @param reason  why, for logs and captures
     */
    public record Classification(Kind kind, Confidence confidence, boolean named, boolean boss, @Nullable String ore, String reason) {
        public boolean isBandit() {
            return kind != Kind.NOT_BANDIT;
        }
    }

    private BanditClassifier() {
    }

    /**
     * @param name  the entity's own name, formatting stripped
     * @param shown the own name plus every other shown name (tab list, display name), lower case, space separated
     */
    public static Classification classify(String name, String shown) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerShown = shown.toLowerCase(Locale.ROOT);
        boolean boss = lowerShown.contains("boss");
        String ore = null;
        for (String candidate : ORES) {
            if (lowerShown.contains(candidate)) {
                ore = candidate;
                break;
            }
        }
        if (NAME.matcher(lowerName).matches()) {
            return new Classification(boss ? Kind.BOSS : Kind.ORE_BANDIT, Confidence.OBSERVED, true, boss, ore,
                    boss ? "fake-player name with 'boss'" : "fake-player name bandit_xx_xxxxxx");
        }
        if (!lowerShown.contains("bandit")) {
            return new Classification(Kind.NOT_BANDIT, Confidence.OBSERVED, false, boss, ore, "no 'bandit' in any shown name");
        }
        if (lowerShown.contains("elite")) {
            return new Classification(Kind.ELITE, Confidence.UNKNOWN, false, boss, ore, "'elite' keyword only, not confirmed");
        }
        if (boss) {
            return new Classification(Kind.BOSS, Confidence.OBSERVED, false, true, ore, "'bandit' and 'boss' in the name");
        }
        return new Classification(Kind.SPECIAL, Confidence.OBSERVED, false, false, ore, "'bandit' in the name");
    }

    /** The exact yes / no the spear helper and the bandit macro have always used. */
    public static boolean isBandit(Classification c, boolean any) {
        if (c.named()) {
            return any || !c.boss();
        }
        if (!c.isBandit()) {
            return false;
        }
        return any || c.ore() != null;
    }
}
