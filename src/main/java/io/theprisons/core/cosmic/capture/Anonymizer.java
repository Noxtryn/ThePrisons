package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.parse.PrivacyFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a frame safe to share: the own name becomes "Self", every other real player becomes "Player_1", "Player_2" ... (the same
 * name always gets the same number within one capture), and {@link PrivacyFilter} removes tokens, addresses and the like from every
 * text. The names of bandits ("bandit_ae_821e4c") stay: they are the mechanic, not people.
 */
public final class Anonymizer {
    private final Map<String, String> names = new LinkedHashMap<>();
    private Pattern pattern;

    public Anonymizer(Raw.Frame frame) {
        if (frame.player() != null && frame.player().name().length() >= 2) {
            names.put(frame.player().name(), "Self");
        }
        int n = 1;
        for (Raw.Entity e : frame.entities()) {
            // Bandits are fake players too; their names ("bandit_ae_821e4c", "Gold Bandit") are the mechanic, not people.
            if (e.player() && e.name().length() >= 2 && !e.name().toLowerCase(java.util.Locale.ROOT).contains("bandit")
                    && !names.containsKey(e.name())) {
                names.put(e.name(), "Player_" + n++);
            }
        }
        if (!names.isEmpty()) {
            List<String> quoted = new ArrayList<>();
            // longest first, so "Steve2" is not cut at "Steve"
            names.keySet().stream().sorted((a, b) -> b.length() - a.length()).forEach(k -> quoted.add(Pattern.quote(k)));
            pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:" + String.join("|", quoted) + ")(?![A-Za-z0-9_])");
        }
    }

    /** Names, then secrets. Null stays null. */
    public String text(String text) {
        if (text == null) {
            return null;
        }
        String s = PrivacyFilter.redact(text);
        if (pattern == null) {
            return s;
        }
        Matcher m = pattern.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = "Player";
            for (Map.Entry<String, String> e : names.entrySet()) {
                if (e.getKey().equalsIgnoreCase(m.group())) {
                    replacement = e.getValue();
                    break;
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    public List<String> texts(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(text(line));
        }
        return out;
    }

    public Raw.Stack stack(Raw.Stack s) {
        return new Raw.Stack(s.itemId(), text(s.name()), s.count(), texts(s.lore()), s.damage(), s.maxDamage(), s.itemClass(), s.itemTier());
    }

    public Raw.Frame frame(Raw.Frame f) {
        Raw.Player p = f.player();
        Raw.Player player = p == null ? null : new Raw.Player(text(p.name()), p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.yaw(), p.pitch(),
                p.health(), p.maxHealth(), p.food(), stack(p.held()), stack(p.offHand()), p.armor().stream().map(this::stack).toList(),
                p.effects(), p.input(), p.onGround(), p.spearCooldown());
        List<Raw.Entity> entities = new ArrayList<>();
        for (Raw.Entity e : f.entities()) {
            entities.add(new Raw.Entity(e.id(), e.type(), text(e.name()), text(e.shown()), e.player(), e.hostile(), e.x(), e.y(), e.z(),
                    e.distance(), e.health(), stack(e.held())));
        }
        List<Raw.Line> actionBar = new ArrayList<>();
        for (Raw.Line line : f.actionBar()) {
            actionBar.add(new Raw.Line(line.atMs(), text(line.text())));
        }
        List<Raw.Slot> slots = new ArrayList<>();
        for (Raw.Slot slot : f.screen().slots()) {
            slots.add(new Raw.Slot(slot.index(), stack(slot.stack())));
        }
        Raw.Screen screen = new Raw.Screen(text(f.screen().title()), f.screen().screenClass(), f.screen().container(), slots);
        return new Raw.Frame(f.tick(), f.nowMs(), f.where(), player, entities, f.blocks(), f.sidebar() == null ? null : texts(f.sidebar()),
                texts(f.bossBars()), actionBar, screen, f.inventory().stream().map(this::stack).toList());
    }

    /** The mapping real name -> alias, for tests only (never written to a capture). */
    Map<String, String> aliases() {
        return Map.copyOf(names);
    }
}
