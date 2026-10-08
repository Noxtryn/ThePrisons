package io.theprisons.items;

/**
 * The typing of the search bar in the inventory: everything that writes a character belongs to the bar (so "E" does not close the inventory), except
 * digits over a slot (the hotbar swap keeps working) and Ctrl / Alt / Super shortcuts. One Esc clears the search and is NOT consumed (the inventory
 * closes with it). Pure; key codes are GLFW's.
 */
public final class SearchInput {
    public static final int MAX_LENGTH = 40;
    private static final int KEY_SPACE = 32;
    private static final int KEY_GRAVE = 96;
    private static final int KEY_0 = 48;
    private static final int KEY_9 = 57;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_BACKSPACE = 259;
    private static final int MOD_SHORTCUT = 0x2 | 0x4 | 0x8;     // control, alt, super

    private String text = "";
    private boolean showAll;
    private boolean changed;

    public String text() {
        return text;
    }

    public boolean showAll() {
        return showAll;
    }

    public void toggleShowAll() {
        showAll = !showAll;
        changed = true;
    }

    /** The list is shown when something is searched or "all items" is on. */
    public boolean open() {
        return showAll || !text.isEmpty();
    }

    /** True once after the text or the show-all mode changed (the caller then refreshes the list). */
    public boolean takeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    public void reset() {
        if (!text.isEmpty() || showAll) {
            changed = true;
        }
        text = "";
        showAll = false;
    }

    public static boolean allowed(char c) {
        return Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' || c == '.' || c == '@' || c == '\'' || c == '%'
                || c == '(' || c == ')' || c == ':';
    }

    /** A typed character; always consumed (an unusable one is simply dropped). */
    public boolean charTyped(char c) {
        if (allowed(c) && text.length() < MAX_LENGTH) {
            text += c;
            changed = true;
        }
        return true;
    }

    /** @return true = consumed */
    public boolean keyPressed(int key, int modifiers, boolean overSlot) {
        boolean shortcut = (modifiers & MOD_SHORTCUT) != 0;
        if (key == KEY_BACKSPACE && !text.isEmpty()) {
            text = shortcut ? "" : text.substring(0, text.length() - 1);
            changed = true;
            return true;
        }
        if (key == KEY_ESCAPE) {
            reset();
            return false;
        }
        if (shortcut) {
            return false;
        }
        boolean digit = key >= KEY_0 && key <= KEY_9;
        if (digit && overSlot) {
            return false;
        }
        return key >= KEY_SPACE && key <= KEY_GRAVE;
    }
}
