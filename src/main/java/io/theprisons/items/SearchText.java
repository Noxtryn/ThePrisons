package io.theprisons.items;

import java.text.Normalizer;
import java.util.Locale;

/** The one place that normalises text for searching: lower case, no accents, everything that is no letter or digit becomes a single space. */
public final class SearchText {
    private SearchText() {
    }

    public static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(n.length());
        boolean space = true;
        for (int i = 0; i < n.length(); i++) {
            char c = Character.toLowerCase(n.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        int end = sb.length();
        if (end > 0 && sb.charAt(end - 1) == ' ') {
            sb.setLength(end - 1);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
