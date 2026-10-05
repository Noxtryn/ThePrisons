package com.freelocs.theprisons.modules.qol.players;

import com.freelocs.theprisons.core.client.TextStrip;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friends and the own gang (pure logic). Friends are added by name ({@code /theprisons friend add NAME}); gang members
 * are found by the gang in front of their name - Cosmic shows it in chat and tab as
 * {@code "***Deutsch (42) <Arcanist> ToyotaSupra_MK4 [Escapee]"} (stars = gang tier, then the gang, the level, the rank,
 * the name, a title). The own gang comes from the own name, or from "(!) You are now a gang member of Deutsch." /
 * "Gang: Deutsch".
 */
public final class FriendList {
    public enum Relation { NONE, FRIEND, GANG }

    /** A Minecraft account name. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** The gang before the level: "***Deutsch (42) <...>", "Free (90) <...>", "[AD] **Reformed (90) <...>". */
    private static final Pattern GANG = Pattern.compile("^(?:\\[AD]\\s*)?\\**([A-Za-z0-9_]{2,16})\\s+\\(\\d+\\)\\s*<");
    private static final Pattern JOINED = Pattern.compile("you are now a gang member of ([A-Za-z0-9_]{2,16})", Pattern.CASE_INSENSITIVE);
    private static final Pattern GANG_INFO = Pattern.compile("^gang:\\s*([A-Za-z0-9_]{2,16})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEFT = Pattern.compile("you (?:have )?(?:left|been kicked from|been removed from) (?:the|your) gang",
            Pattern.CASE_INSENSITIVE);

    /** Lower-case name → the name as typed. */
    private final Map<String, String> friends = new TreeMap<>();
    private String gang = "";

    public boolean add(String name) {
        if (!NAME.matcher(name).matches()) {
            return false;
        }
        return friends.put(name.toLowerCase(Locale.ROOT), name) == null;
    }

    public boolean remove(String name) {
        return friends.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public boolean isFriend(String name) {
        return friends.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Collection<String> friends() {
        return friends.values();
    }

    public String gang() {
        return gang;
    }

    public void gang(String gang) {
        this.gang = gang == null ? "" : gang;
    }

    /** The gang in front of a chat / tab name ({@code null} = none shown). */
    static @Nullable String gangOf(String shownName) {
        Matcher m = GANG.matcher(TextStrip.strip(shownName).strip());
        return m.find() ? m.group(1) : null;
    }

    /** What the player with account name {@code name} and tab name {@code shown} is to us (a friend wins over the gang). */
    public Relation relation(String name, @Nullable String shown) {
        if (isFriend(name)) {
            return Relation.FRIEND;
        }
        if (!gang.isEmpty() && shown != null && gang.equalsIgnoreCase(gangOf(shown))) {
            return Relation.GANG;
        }
        return Relation.NONE;
    }

    /**
     * A server chat line about the own gang: joined / "Gang: X" → that gang, left / kicked → none.
     * @return whether the gang changed
     */
    public boolean chat(String line) {
        String text = TextStrip.strip(line).replaceFirst("^\\(!\\)\\s*", "").strip();
        Matcher m = JOINED.matcher(text);
        if (!m.find()) {
            // "Gang: X" is also in another player's /stats: only taken while the own gang is not known yet.
            m = GANG_INFO.matcher(text);
            if (!gang.isEmpty() || !m.find()) {
                if (LEFT.matcher(text).find() && !gang.isEmpty()) {
                    gang = "";
                    return true;
                }
                return false;
            }
        }
        String found = m.group(1);
        if (found.equals(gang)) {
            return false;
        }
        gang = found;
        return true;
    }
}
