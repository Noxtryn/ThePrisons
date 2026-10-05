package com.freelocs.theprisons.core.setting;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One configurable value of a module. Values are always normalised (clamped, validated) on write, so readers never
 * see out-of-range data.
 *
 * <p>Storage is either <em>owned</em> (the value lives here and the {@code ConfigStore} persists it when it differs
 * from the default) or <em>bound</em> to an external getter / setter, used by the legacy adapters whose values still
 * live in the v1 config file.
 *
 * <p>Fluent modifiers return the concrete type through inference: {@code IntSetting speed = integer(...).group("Rotation");}
 *
 * @param <T> value type; must be immutable or treated as such
 */
public abstract class Setting<T> {
    private static final BooleanSupplier ALWAYS = () -> true;

    private final String id;
    private final String name;
    private final T defaultValue;
    private String description = "";
    private String group = "";
    private BooleanSupplier visible = ALWAYS;
    private final List<Consumer<T>> listeners = new ArrayList<>(2);
    private T value;
    private @Nullable Supplier<T> boundGetter;
    private @Nullable Consumer<T> boundSetter;
    private @Nullable Runnable changeHook;
    /** Must be set before a macro may start: the check and what to tell the player when it fails. */
    private java.util.function.@Nullable Predicate<T> requirement;
    private String requirementText = "";

    protected Setting(String id, String name, T defaultValue) {
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Setting ids are lower_snake_case: " + id);
        }
        this.id = id;
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    // ── Definition ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S description(String text) {
        this.description = text;
        return (S) this;
    }

    /** Sub-section inside the module's settings panel. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S group(String group) {
        this.group = group;
        return (S) this;
    }

    /** Only shown (and only meaningful) while the condition holds. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
        this.visible = condition;
        return (S) this;
    }

    /**
     * Must be filled in before the module's macro starts (only while the setting is visible). {@code problem} is the
     * English text shown in chat and in the GUI when the value does not pass.
     */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S required(java.util.function.Predicate<T> valid, String problem) {
        this.requirement = valid;
        this.requirementText = problem;
        return (S) this;
    }

    /** What is missing (English, see {@link #required}); {@code null} when the setting is fine. */
    public @Nullable String problem() {
        java.util.function.Predicate<T> check = requirement;
        if (check == null || !visible() || check.test(get())) {
            return null;
        }
        return requirementText;
    }

    /** Called on the client thread after every effective change. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Consumer<T> listener) {
        listeners.add(listener);
        return (S) this;
    }

    /** Stores the value outside of this object (legacy config). Bound settings are not persisted by the core. */
    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S bind(Supplier<T> getter, Consumer<T> setter) {
        this.boundGetter = getter;
        this.boundSetter = setter;
        return (S) this;
    }

    /** Set by the module manager: marks the config dirty after changes. */
    public void setChangeHook(@Nullable Runnable hook) {
        this.changeHook = hook;
    }

    // ── Value ────────────────────────────────────────────────────────────────

    public T get() {
        Supplier<T> getter = boundGetter;
        return getter != null ? getter.get() : value;
    }

    /** @return true when the stored value changed */
    public boolean set(T newValue) {
        T normalized = normalize(newValue == null ? defaultValue : newValue);
        if (Objects.equals(normalized, get())) {
            return false;
        }
        Consumer<T> setter = boundSetter;
        if (setter != null) {
            setter.accept(normalized);
        } else {
            value = normalized;
        }
        for (Consumer<T> listener : listeners) {
            listener.accept(normalized);
        }
        Runnable hook = changeHook;
        if (hook != null) {
            hook.run();
        }
        return true;
    }

    public void reset() {
        set(defaultValue);
    }

    public boolean isDefault() {
        return Objects.equals(get(), defaultValue);
    }

    public T defaultValue() {
        return defaultValue;
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String group() {
        return group;
    }

    public boolean visible() {
        return visible.getAsBoolean();
    }

    public boolean persistent() {
        return boundGetter == null;
    }

    /** Short value text for summaries and search. */
    public String display() {
        return String.valueOf(get());
    }

    // ── Type specific ────────────────────────────────────────────────────────

    /** Clamps / validates a value; never returns null. */
    protected abstract T normalize(T raw);

    public abstract JsonElement toJson();

    /** Applies a persisted value; invalid data keeps the default and throws so the store can log it. */
    public abstract void fromJson(JsonElement json);
}
