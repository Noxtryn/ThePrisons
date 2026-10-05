package com.freelocs.theprisons.modules;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.module.ModuleManager;
import com.freelocs.theprisons.modules.general.ClickGuiModule;
import com.freelocs.theprisons.modules.general.PerformanceModule;
import com.freelocs.theprisons.modules.general.SafetyModule;
import com.freelocs.theprisons.modules.legacy.LegacyModules;
import com.freelocs.theprisons.modules.mining.ore.BorderMarks;
import com.freelocs.theprisons.modules.mining.ore.OreMacroModule;
import com.freelocs.theprisons.modules.mining.ore.route.RouteRecorder;
import com.freelocs.theprisons.modules.mining.ore.route.RouteStore;
import com.freelocs.theprisons.modules.mining.ore.route.WaypointEditorModule;

/**
 * Creates every module with exactly the services it needs. Order = order within a GUI category.
 */
public final class ModuleRegistry {
    private ModuleRegistry() {
    }

    public static void registerAll(ThePrisonsCore core, Runnable openGui, Runnable openHudLayout) {
        ModuleManager modules = core.modules();
        // The shipped feature set (FeatureProfile): users cannot switch features.
        modules.setPolicy(FeatureProfile::forced);
        // Mining: the ore macro and its route/border tools are regular user-facing features.
        BorderMarks borders = new BorderMarks();
        RouteStore routes = new RouteStore();
        borders.register(core.bus(), core.commands());
        new RouteRecorder(routes).register(core.bus(), core.commands());
        OreMacroModule oreMacro = new OreMacroModule(core.control(), core.world(), core.targets(), core.stats(), modules.worker(),
                borders, routes, core.archive());
        modules.register(oreMacro);
        oreMacro.registerCommands(core.commands());
        modules.register(new WaypointEditorModule(routes, modules));
        // HUD
        com.freelocs.theprisons.modules.hud.SessionHudModule sessionHud =
                new com.freelocs.theprisons.modules.hud.SessionHudModule(core.world(), routes, oreMacro);
        modules.register(sessionHud);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(sessionHud::render);
        com.freelocs.theprisons.modules.hud.scoreboard.ScoreboardModule scoreboard =
                new com.freelocs.theprisons.modules.hud.scoreboard.ScoreboardModule();
        modules.register(scoreboard);
        scoreboard.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(scoreboard::render);
        modules.register(new com.freelocs.theprisons.modules.hud.tab.BetterTabModule());
        // QoL
        // Cosmic market: AH/History, /ee, /gz and /pb price tracking + inventory search overlay.
        com.freelocs.theprisons.modules.qol.market.MarketModule market =
                new com.freelocs.theprisons.modules.qol.market.MarketModule(oreMacro);
        modules.register(market);
        market.register(core.bus(), core.commands());
        com.freelocs.theprisons.modules.qol.SneakTradeModule sneakTrade = new com.freelocs.theprisons.modules.qol.SneakTradeModule();
        modules.register(sneakTrade);
        sneakTrade.register();
        com.freelocs.theprisons.modules.qol.players.FriendsModule friends = new com.freelocs.theprisons.modules.qol.players.FriendsModule();
        modules.register(friends);
        friends.register(core.commands());
        com.freelocs.theprisons.modules.qol.players.PlayerCardModule playerCards =
                new com.freelocs.theprisons.modules.qol.players.PlayerCardModule();
        modules.register(playerCards);
        playerCards.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(playerCards::render);
        com.freelocs.theprisons.modules.qol.storage.StorageOverlayModule storage =
                new com.freelocs.theprisons.modules.qol.storage.StorageOverlayModule();
        modules.register(storage);
        storage.register(core.bus());
        modules.register(new com.freelocs.theprisons.modules.qol.items.ItemLookModule());
        // General
        modules.register(new ClickGuiModule(openGui));
        modules.register(new com.freelocs.theprisons.modules.general.DesignModule());
        modules.register(new SafetyModule(core.safety()));
        modules.register(new PerformanceModule(core.profiler(), core.world()));
        // v1 features until they are migrated
        LegacyModules.register(modules, core.config()::markDirty, openHudLayout);
    }

    public static ClickGuiModule clickGui(ThePrisonsCore core) {
        return (ClickGuiModule) core.modules().get("click_gui");
    }
}
