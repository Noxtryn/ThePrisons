package com.freelocs.theprisons.modules.qol.market;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The real menus recorded by {@link MenuRecorder} in the game on 2026-10-05 (test resource), read back as pages. */
final class MenuDumps {
    record Page(String title, List<MarketParser.Item> items) {
    }

    private static final Pattern HEADER = Pattern.compile("^=== \\S+\\s+title='(.*)'\\s+type=");
    private static final Pattern SLOT = Pattern.compile("^\\[(\\d+)] (\\S+) x(\\d+)\\s+name='(.*)'$");
    private static final Pattern CUSTOM = Pattern.compile("\"cosmicprisons:custom_item_id\":\"([^\"]+)\"");

    private MenuDumps() {
    }

    static List<Page> pages() {
        String text;
        try (InputStream in = MenuDumps.class.getResourceAsStream("/menus/2026-10-05.txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Page> pages = new ArrayList<>();
        String title = null;
        List<MarketParser.Item> items = null;
        int slot = -1;
        String id = null;
        int count = 0;
        String name = null;
        List<String> lore = null;
        String custom = null;
        for (String line : (text + "\n=== end  title='' type=x").split("\n")) {
            Matcher h = HEADER.matcher(line);
            Matcher s = SLOT.matcher(line);
            if ((h.find() || s.find()) && name != null) {
                items.add(new MarketParser.Item(slot, id, count, name, lore, custom));
                name = null;
            }
            h = HEADER.matcher(line);
            s = SLOT.matcher(line);
            if (h.find()) {
                if (title != null) {
                    pages.add(new Page(title, items));
                }
                title = h.group(1);
                items = new ArrayList<>();
            } else if (s.find()) {
                slot = Integer.parseInt(s.group(1));
                id = s.group(2);
                count = Integer.parseInt(s.group(3));
                name = s.group(4);
                lore = new ArrayList<>();
                custom = null;
            } else if (line.startsWith("      | ") && lore != null) {
                lore.add(line.substring(8));
            } else if (line.startsWith("      |") && lore != null) {
                lore.add("");
            } else if (line.startsWith("      data=")) {
                Matcher c = CUSTOM.matcher(line);
                custom = c.find() ? c.group(1) : null;
            }
        }
        return pages;
    }

    static Page first(String title) {
        return pages().stream().filter(p -> p.title().equals(title)).findFirst().orElseThrow();
    }
}
