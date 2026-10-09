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
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.items.client.InventoryItemList.hudGuard(sessionHud::render));
        io.theprisons.modules.hud.scoreboard.ScoreboardModule scoreboard =
                new io.theprisons.modules.hud.scoreboard.ScoreboardModule();
        modules.register(scoreboard);
        scoreboard.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.items.client.InventoryItemList.hudGuard(scoreboard::render));
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
        modules.register(new io.theprisons.modules.qol.bandit.BanditMacroModule(core.control(), core.world(), routes));
        spearHelper.register(core.bus());
        if (FeatureProfile.DEV) {
            // Temporary developer test of the movement alone; never part of a shipped profile.
            io.theprisons.modules.qol.bandit.debug.BanditDodgeTestModule dodgeTest =
                    new io.theprisons.modules.qol.bandit.debug.BanditDodgeTestModule(core.control(), core.world());
            modules.register(dodgeTest);
            dodgeTest.register(core);
        }
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.items.client.InventoryItemList.hudGuard(spearHelper::render));
        io.theprisons.modules.general.tunnel.TunnelVisionModule tunnel =
                new io.theprisons.modules.general.tunnel.TunnelVisionModule(oreMacro, sessionHud);
        modules.register(tunnel);
        tunnel.register(core.bus());
        io.theprisons.modules.general.tunnel.TunnelActionBarModule tunnelBar = new io.theprisons.modules.general.tunnel.TunnelActionBarModule();
        modules.register(tunnelBar);
        tunnelBar.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(tunnelBar::render);
        io.theprisons.modules.qol.players.FriendsModule friends = new io.theprisons.modules.qol.players.FriendsModule();
        modules.register(friends);
        friends.register(core.commands());
        io.theprisons.modules.qol.players.PlayerCardModule playerCards =
                new io.theprisons.modules.qol.players.PlayerCardModule();
        modules.register(playerCards);
        playerCards.register(core.bus());
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(io.theprisons.items.client.InventoryItemList.hudGuard(playerCards::render));
        io.theprisons.modules.qol.storage.StorageOverlayModule storage =
                new io.theprisons.modules.qol.storage.StorageOverlayModule();
        modules.register(storage);
        storage.register(core.bus());
        // The shared item platform (registry, search, item list); the AH overlay and the energy overlay read the same data.
        io.theprisons.items.ItemsService itemsService = io.theprisons.items.ItemsService.init(core.dataDir());
        itemsService.loadCatalogOnce(core.dataDir().resolve("market").resolve("catalog.json"), r -> net.minecraft.client.MinecraftClient.getInstance().execute(r));
        modules.register(new io.theprisons.items.client.ItemListModule());
        modules.register(new io.theprisons.items.client.AhOverlayModule(core.dataDir()));
        modules.register(new io.theprisons.items.client.EnergyOverlayModule());
        modules.register(new io.theprisons.items.client.EeOverlayModule());
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
