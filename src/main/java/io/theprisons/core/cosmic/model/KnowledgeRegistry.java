package io.theprisons.core.cosmic.model;

import com.google.gson.JsonObject;

import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A data-driven list of things the game model knows about (ores, pickaxes, enchants, bandits, zones, items). The data lives in
 * {@code assets/theprisons/cosmic/<name>.json}; an id nobody listed gives {@link Entry#unknown(String)}, never an exception.
 */
public class KnowledgeRegistry {
    private final String name;
    private final Map<String, Entry> byId = new LinkedHashMap<>();

    public KnowledgeRegistry(String name, List<Entry> entries) {
        this.name = name;
        for (Entry entry : entries) {
            if (byId.put(entry.id(), entry) != null) {
                throw new IllegalStateException(name + ": duplicate id " + entry.id());
            }
        }
    }

    public static List<Entry> load(String file) {
        String path = "/assets/theprisons/cosmic/" + file;
        JsonObject root = RegistryData.read(KnowledgeRegistry.class.getResourceAsStream(path), path);
        return RegistryData.entries(root, file);
    }

    public static List<Entry> load(InputStream in, String what) {
        return RegistryData.entries(RegistryData.read(in, what), what);
    }

    public String name() {
        return name;
    }

    public int size() {
        return byId.size();
    }

    public Collection<Entry> all() {
        return byId.values();
    }

    public Optional<Entry> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The entry, or a placeholder without attributes for an id nobody listed. */
    public Entry get(String id) {
        Entry entry = byId.get(id);
        return entry != null ? entry : Entry.unknown(id);
    }
}
