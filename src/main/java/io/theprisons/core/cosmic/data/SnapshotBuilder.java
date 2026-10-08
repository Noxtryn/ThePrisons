package io.theprisons.core.cosmic.data;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot.ClassifiedEntity;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.CombatState;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.ContextKey;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.CosmicState;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.PlayerState;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.SpearState;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.UiState;
import io.theprisons.core.cosmic.data.CosmicContextSnapshot.WorldState;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.parse.BanditClassifier;
import io.theprisons.core.cosmic.parse.PickaxeLore;
import io.theprisons.core.cosmic.parse.SidebarParser;
import io.theprisons.core.cosmic.parse.SpearRule;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.core.cosmic.value.GameValue;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns one {@link Raw.Frame} into a {@link CosmicContextSnapshot}. Pure: the same frame, memory and model always give the same
 * snapshot, which is what lets a capture be replayed in a test. Anything the frame does not show becomes UNKNOWN, never 0.
 */
public final class SnapshotBuilder {
    /** At most this many entities go into a snapshot (the nearest first). */
    public static final int MAX_ENTITIES = 64;

    /** What the memory knew when the frame was taken. */
    public record Memory(String zone, @Nullable String event, List<Raw.Line> actionBar) {
        public static final Memory NONE = new Memory("", null, List.of());

        public Memory {
            actionBar = List.copyOf(actionBar);
        }
    }

    private SnapshotBuilder() {
    }

    public static CosmicContextSnapshot build(Raw.Frame frame, Memory memory, CosmicGameModel model) {
        long now = frame.nowMs();
        ContextKey key = frame.where().server().isEmpty() ? ContextKey.NONE
                : new ContextKey(frame.where().server(), frame.where().dimension(), "unknown");
        PlayerState player = player(frame.player());
        WorldState world = new WorldState(!frame.where().server().isEmpty() || frame.where().singleplayer(), frame.where().dimension(),
                frame.blocks(), frame.entities().size());
        CosmicState cosmic = cosmic(frame, memory, model, now);
        CombatState combat = combat(frame);
        Raw.Screen screen = frame.screen();
        UiState ui = new UiState(screen.title() != null || !screen.screenClass().isEmpty(), screen.title(), screen.screenClass(),
                screen.container(), screen.slots());
        return new CosmicContextSnapshot(CosmicContextSnapshot.SCHEMA, frame.tick(), now, key, player, world, cosmic, combat, ui);
    }

    private static PlayerState player(Raw.@Nullable Player p) {
        if (p == null) {
            return PlayerState.ABSENT;
        }
        return new PlayerState(true, p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.yaw(), p.pitch(), p.health(), p.maxHealth(), p.held(),
                p.offHand(), p.armor(), p.effects(), p.input(), p.onGround());
    }

    private static CosmicState cosmic(Raw.Frame frame, Memory memory, CosmicGameModel model, long now) {
        SidebarParser side = SidebarParser.parse(frame.sidebar());
        GameValue<String> zone = memory.zone().isEmpty()
                ? GameValue.unknown("chat zone message", "no zone message seen yet")
                : GameValue.observed(memory.zone(), "chat zone message", now, model.zones().forZone(memory.zone()).isPlaceholder()
                        ? "zone not in the registry" : null);
        GameValue<String> event = memory.event() == null ? GameValue.unknown("chat event message", "no running event known")
                : GameValue.observed(memory.event(), "chat event message", now, null);
        Raw.Stack held = frame.player() == null ? Raw.Stack.EMPTY : frame.player().held();
        boolean pickaxe = model.pickaxes().forItem(held.itemId()).isPresent();
        PickaxeLore.Energy lore = pickaxe ? PickaxeLore.read(held.lore()) : null;
        double durability = pickaxe ? held.durabilityShare() : -1.0D;
        return new CosmicState(zone, event,
                GameValue.unknown("mine detection", "no confirmed way to name the current mine yet"),
                GameValue.unknown("season", "season / map detection not available"),
                frame.sidebar() != null, frame.sidebar() == null ? List.of() : frame.sidebar(), frame.bossBars(), memory.actionBar(),
                live(side.taxPercent(), "sidebar tax line", now),
                live(side.energyNow(), "sidebar energy", now),
                side.energyMax() == null || side.energyMax() <= 0L ? GameValue.unknown("sidebar energy", "no pickaxe held or no sidebar") : live(side.energyMax(), "sidebar energy", now),
                live(side.energyLevel(), "sidebar energy level", now),
                live(side.totalXp(), "sidebar level row", now),
                live(lore == null ? null : lore.now(), "pickaxe lore", now),
                live(lore == null ? null : lore.capacity(), "pickaxe lore", now),
                live(durability < 0.0D ? null : durability, "item damage", now));
    }

    private static <T> GameValue<T> live(@Nullable T value, String source, long now) {
        return value == null ? GameValue.unknown(source) : GameValue.live(value, source, now);
    }

    private static CombatState combat(Raw.Frame frame) {
        List<Raw.Entity> sorted = new ArrayList<>(frame.entities());
        sorted.sort(Comparator.comparingDouble(Raw.Entity::distance));
        List<ClassifiedEntity> out = new ArrayList<>();
        int bandits = 0;
        int players = 0;
        int hostiles = 0;
        for (Raw.Entity e : sorted) {
            BanditClassifier.Classification c = BanditClassifier.classify(e.name(), (e.name() + " " + e.shown()).trim());
            ClassifiedEntity classified;
            if (c.isBandit()) {
                bandits++;
                classified = new ClassifiedEntity(e, c.kind().name(), c.confidence(), c.reason());
            } else if (e.player()) {
                players++;
                classified = new ClassifiedEntity(e, CombatState.PLAYER, Confidence.VERIFIED_LIVE, "player entity");
            } else if (e.hostile()) {
                hostiles++;
                classified = new ClassifiedEntity(e, CombatState.HOSTILE, Confidence.VERIFIED_LIVE, "hostile mob entity");
            } else {
                classified = new ClassifiedEntity(e, CombatState.OTHER, Confidence.VERIFIED_LIVE, "other living entity");
            }
            if (out.size() < MAX_ENTITIES) {
                out.add(classified);
            }
        }
        Raw.Player p = frame.player();
        SpearState spear;
        if (p == null) {
            spear = new SpearState(GameValue.unknown("held item", "no player"), GameValue.unknown("item cooldown", "no player"));
        } else {
            boolean isSpear = SpearRule.isSpear(p.held().itemId()) || SpearRule.isSpear(p.offHand().itemId());
            Raw.Stack weapon = SpearRule.isSpear(p.held().itemId()) ? p.held() : p.offHand();
            spear = new SpearState(GameValue.live(isSpear, "held item id", frame.nowMs()),
                    isSpear && p.spearCooldown() >= 0.0F ? GameValue.live((double) p.spearCooldown(), "item cooldown of " + weapon.itemId(), frame.nowMs())
                            : GameValue.unknown("item cooldown", isSpear ? "cooldown not readable" : "no spear held"));
        }
        return new CombatState(out, bandits, players, hostiles, spear);
    }
}
