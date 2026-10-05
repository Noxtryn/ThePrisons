package com.freelocs.theprisons.modules.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActivityLogTest {
    private final ActivityLog log = new ActivityLog(name -> new CosmicStats(0L, line -> {
    }));

    @Test
    void clockRunsOnlyForTheCurrentActivityAndResumes() {
        ActivityLog.Activity iron = log.act("Iron Ore", 1_000L);
        log.act("Iron Ore", 11_000L);
        assertEquals(10_000L, iron.clock(11_000L));
        ActivityLog.Activity gold = log.act("Gold Ore", 12_000L);
        // iron stopped at its last action + up to 5 s (the switch came 1 s later)
        assertEquals(11_000L, iron.clock(50_000L));
        log.act("Gold Ore", 20_000L);
        assertEquals(8_000L, gold.clock(20_000L));
        ActivityLog.Activity back = log.act("Iron Ore", 30_000L);
        assertEquals(iron, back);
        assertEquals(11_000L + 4_000L, iron.clock(34_000L));
        assertEquals(8_000L + 5_000L, gold.clock(34_000L), "gold paused at its last action + 5 s");
    }

    @Test
    void idlePausesTheClock() {
        ActivityLog.Activity iron = log.act("Iron Ore", 0L);
        log.tick(ActivityLog.IDLE_MS + 1_000L);
        assertFalse(iron.running());
        assertEquals(5_000L, iron.clock(ActivityLog.IDLE_MS + 60_000L));
    }

    @Test
    void namesOfActivities() {
        assertEquals("Redstone", SessionHudModule.blockActivity("minecraft:redstone_ore", "Redstone Ore", false));
        assertEquals("Redstone", SessionHudModule.blockActivity("minecraft:deepslate_redstone_ore", "x", false));
        assertEquals("Redstone", SessionHudModule.blockActivity("minecraft:redstone_block", "Block of Redstone", false));
        assertEquals("Iron", SessionHudModule.blockActivity("minecraft:deepslate_iron_ore", "Deepslate Iron Ore", true));
        assertEquals("Meteor Mining", SessionHudModule.blockActivity("minecraft:nether_quartz_ore", "Nether Quartz Ore", false));
        assertEquals("Meteor Mining", SessionHudModule.blockActivity("minecraft:magma_block", "Magma Block", true));
        assertEquals("Stone", SessionHudModule.blockActivity("minecraft:stone", "Stone", false));
        assertEquals("Gold Bandits", SessionHudModule.banditActivity("Gold Bandit [Lv 4] ❤ 20"));
        assertEquals("Bandits", SessionHudModule.banditActivity("Bandit"));
        assertNull(SessionHudModule.banditActivity("Zombie"));
    }

    @Test
    void activityTextSaysWhatIsDoneNow() {
        assertEquals("Idle", SessionHudModule.activityText(null, ""));
        ActivityLog.Activity ore = log.act(SessionMode.ORE, 1_000L);
        ore.detail = "Gold";
        assertEquals("Mining Gold", SessionHudModule.activityText(ore, ""));
        assertEquals("Mining Gold", SessionHudModule.activityText(ore, "Idle"));
        assertEquals("/home tmp", SessionHudModule.activityText(ore, "Trip: /home tmp"));
        ActivityLog.Activity bandit = log.act(SessionMode.BANDIT, 2_000L);
        bandit.detail = "Gold Bandits";
        assertEquals("Fighting Gold Bandits", SessionHudModule.activityText(bandit, ""));
        log.tick(2_000L + ActivityLog.IDLE_MS + 1L);
        assertEquals("Idle", SessionHudModule.activityText(log.current(), ""));
    }

    @Test
    void oldSavesPerOreAddUpIntoTheirMode() {
        log.restore(SessionMode.migrate("Gold"), 60_000L, 100L, 0L, 0L);
        log.restore(SessionMode.migrate("Iron"), 30_000L, 50L, 0L, 0L);
        log.restore(SessionMode.migrate("bandits"), 10_000L, 0L, 7L, 1L);
        log.restoreCurrent(SessionMode.ORE);
        assertEquals(2, log.all().size());
        ActivityLog.Activity ore = log.current();
        assertEquals(SessionMode.ORE, ore.name);
        assertEquals(90_000L, ore.clock(0L));
        assertEquals(150L, ore.stats.snapshot(ore.clock(0L), 0, "").ores());
        assertEquals(7L, log.all().get(SessionMode.BANDIT).kills);
        assertFalse(ore.running(), "shown paused until the next action");
    }
}
