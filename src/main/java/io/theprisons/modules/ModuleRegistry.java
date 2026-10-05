package io.theprisons.modules;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.module.ModuleManager;
import io.theprisons.modules.general.ClickGuiModule;
import io.theprisons.modules.general.PerformanceModule;
import io.theprisons.modules.general.SafetyModule;
import io.theprisons.modules.legacy.LegacyModules;
import io.theprisons.modules.mining.ore.BorderMarks;
import io.theprisons.modules.mining.ore.OreMacroModule;
import io.theprisons.modules.mining.ore.route.RouteRecorder;
import io.theprisons.modules.mining.ore.route.RouteStore;
import io.theprisons.modules.mining.ore.route.WaypointEditorModule;

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
        io.theprisons.modules.hud.SessionHudModule sessionHud =
                new io.theprisons.modules.hud.SessionHudModule(core.world(), routes, oreMacro);
        modules.register(sessionHud);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.modules.qol.market.MarketSearch.hudGuard(sessionHud::render));
        io.theprisons.modules.hud.scoreboard.ScoreboardModule scoreboard =
                new io.theprisons.modules.hud.scoreboard.ScoreboardModule();
        modules.register(scoreboard);
        scoreboard.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.modules.qol.market.MarketSearch.hudGuard(scoreboard::render));
        modules.register(new io.theprisons.modules.hud.tab.BetterTabModule());
        // QoL
        // Cosmic market: AH/History, /ee, /gz and /pb price tracking + inventory search overlay.
        io.theprisons.modules.qol.market.MarketModule market =
                new io.theprisons.modules.qol.market.MarketModule(oreMacro);
        modules.register(market);
        market.register(core.bus(), core.commands());
        io.theprisons.modules.qol.SneakTradeModule sneakTrade = new io.theprisons.modules.qol.SneakTradeModule();
        modules.register(sneakTrade);
        sneakTrade.register();
        io.theprisons.modules.qol.bandit.SpearHelperModule spearHelper =
                new io.theprisons.modules.qol.bandit.SpearHelperModule(core.control());
        modules.register(spearHelper);
        spearHelper.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.modules.qol.market.MarketSearch.hudGuard(spearHelper::render));
        io.theprisons.modules.general.tunnel.TunnelVisionModule tunnel =
                new io.theprisons.modules.general.tunnel.TunnelVisionModule(oreMacro, sessionHud);
        modules.register(tunnel);
        tunnel.register(core.bus());
        io.theprisons.modules.qol.players.FriendsModule friends = new io.theprisons.modules.qol.players.FriendsModule();
        modules.register(friends);
        friends.register(core.commands());
        io.theprisons.modules.qol.players.PlayerCardModule playerCards =
                new io.theprisons.modules.qol.players.PlayerCardModule();
        modules.register(playerCards);
        playerCards.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.modules.qol.market.MarketSearch.hudGuard(playerCards::render));
        io.theprisons.modules.qol.storage.StorageOverlayModule storage =
                new io.theprisons.modules.qol.storage.StorageOverlayModule();
        modules.register(storage);
        storage.register(core.bus());
        modules.register(new io.theprisons.modules.qol.items.ItemLookModule());
        // General
        modules.register(new ClickGuiModule(openGui));
        modules.register(new io.theprisons.modules.general.DesignModule());
        modules.register(new SafetyModule(core.safety()));
        modules.register(new PerformanceModule(core.profiler(), core.world()));
        // v1 features until they are migrated
        LegacyModules.register(modules, core.config()::markDirty, openHudLayout);
    }

    public static ClickGuiModule clickGui(ThePrisonsCore core) {
        return (ClickGuiModule) core.modules().get("click_gui");
    }
}
