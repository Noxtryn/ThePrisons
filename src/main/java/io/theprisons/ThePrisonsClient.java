package io.theprisons;

import io.theprisons.cache.ThePrisonsCache;
import io.theprisons.config.ThePrisonsConfigManager;
import io.theprisons.bandit.ThePrisonsBanditManager;
import io.theprisons.feature.ThePrisonsFeatureManager;
import io.theprisons.core.Phases;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.gui.click.ClickGuiScreen;
import io.theprisons.modules.ModuleRegistry;
import io.theprisons.ui.ThePrisonsColors;
import io.theprisons.state.ThePrisonsTracker;
import io.theprisons.ui.ThePrisonsHudRenderer;
import io.theprisons.update.ThePrisonsUpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ThePrisonsClient implements ClientModInitializer {
    public static final String MOD_ID = "theprisons";
    public static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons");
    public static final ThePrisonsConfigManager CONFIG = new ThePrisonsConfigManager();
    public static final ThePrisonsCache CACHE = new ThePrisonsCache();
    public static final ThePrisonsTracker TRACKER = new ThePrisonsTracker();
    private static final Object LEGACY_OWNER = new Object();

    @Override
    public void onInitializeClient() {
        CONFIG.load();
        CACHE.load();

        ThePrisonsCore core = ThePrisonsCore.install(FabricLoader.getInstance().getConfigDir());
        Runnable openHudLayout = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.setScreen(hudEditor(client.currentScreen, core));
        };
        Runnable openGui = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.setScreen(dashboard(client.currentScreen, core));
        };
        core.setGuiOpener(openGui);
        core.setNotifier(notice -> ThePrisonsHudRenderer.pushNotification(notice.title(), notice.body(), switch (notice.level()) {
            case SUCCESS -> ThePrisonsColors.ACCENT_LIME;
            case WARNING -> ThePrisonsColors.ACCENT_AMBER;
            case ERROR -> ThePrisonsColors.ACCENT_RED;
            case INFO -> ThePrisonsColors.ACCENT_CYAN;
        }));
        // The v1 settings shown in the new GUI are written together with the module config.
        core.config().addSaveListener(CONFIG::saveAsync);

        ThePrisonsFeatureManager.register();
        ThePrisonsFeatureManager.setGuiOpener(openGui);
        ModuleRegistry.registerAll(core, openGui, openHudLayout);
        if (io.theprisons.modules.FeatureProfile.DEV) {
            // The client commands behind the setup chat links (/prisons open, /prisons lang): developer build only.
            io.theprisons.core.setup.ModCommands.register(core);
        }
        registerLegacyHandlers(core);
        HudRenderCallback.EVENT.register(io.theprisons.items.client.InventoryItemList.hudGuard(profiled(core, "legacy:hud-render", ThePrisonsHudRenderer::render)));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            CACHE.save();
            CONFIG.save();
        });

        core.start();
        LOGGER.info("ThePrisons initialized");
    }

    /** The settings dashboard (also used by Mod Menu). */
    public static net.minecraft.client.gui.screen.Screen dashboard(net.minecraft.client.gui.screen.@org.jspecify.annotations.Nullable Screen parent,
                                                                  ThePrisonsCore core) {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable[] self = new Runnable[1];
        io.theprisons.gui.dashboard.DashboardScreen[] screen = new io.theprisons.gui.dashboard.DashboardScreen[1];
        Runnable hud = () -> client.setScreen(hudEditor(screen[0], core));
        // The dashboard is the curated fast path; the complete module editor stays available for market,
        // multi-choice and advanced settings rather than hiding functional configuration behind DEV mode.
        Runnable classic = () -> client.setScreen(new ClickGuiScreen(screen[0], core));
        screen[0] = new io.theprisons.gui.dashboard.DashboardScreen(parent, core, hud, classic);
        return screen[0];
    }

    /** The HUD editor with every HUD element that is on. */
    public static net.minecraft.client.gui.screen.Screen hudEditor(net.minecraft.client.gui.screen.@org.jspecify.annotations.Nullable Screen parent,
                                                                  ThePrisonsCore core) {
        return new io.theprisons.gui.hud.HudEditorScreen(parent, () -> {
            List<io.theprisons.gui.hud.HudElement> elements = new ArrayList<>();
            for (io.theprisons.core.module.Module module : core.modules().all()) {
                if (module.enabled() && module instanceof io.theprisons.gui.hud.HudElement element) {
                    elements.add(element);
                }
            }
            elements.addAll(io.theprisons.gui.hud.LegacyHudElements.all());
            return elements;
        }, context -> {
            io.theprisons.gui.hud.LegacyHudElements.drawWidgetPreviews(context);
            ThePrisonsHudRenderer.drawNotificationPreview(context, MinecraftClient.getInstance(), CONFIG.get());
        });
    }

    /**
     * The v1 features keep their code but receive their ticks and chat lines through the core event bus, each measured
     * by the profiler, instead of registering duplicate Fabric callbacks.
     */
    private static void registerLegacyHandlers(ThePrisonsCore core) {
        EventBus bus = core.bus();
        Object legacy = LEGACY_OWNER;
        Profiler.Section tracker = core.profiler().section("legacy:tracker");
        Profiler.Section features = core.profiler().section("legacy:features");
        Profiler.Section updates = core.profiler().section("legacy:update-check");
        Profiler.Section bandit = core.profiler().section("legacy:armor");
        Profiler.Section chat = core.profiler().section("legacy:chat");
        bus.subscribe(CoreEvents.TickEnd.class, legacy, Phases.HOUSEKEEPING + 100, event -> {
            MinecraftClient client = event.client();
            long start = tracker.begin();
            TRACKER.tick(client);
            tracker.end(start);
            start = features.begin();
            ThePrisonsFeatureManager.tick(client);
            features.end(start);
            start = updates.begin();
            ThePrisonsUpdateChecker.tick(client);
            updates.end(start);
            start = bandit.begin();
            ThePrisonsBanditManager.tick(client);
            bandit.end(start);
        });
        bus.subscribe(CoreEvents.ChatReceived.class, legacy, event -> {
            long start = chat.begin();
            ThePrisonsBanditManager.onGameMessage(event.message(), event.overlay());
            ThePrisonsFeatureManager.onGameMessage(event.message(), event.overlay());
            if (!event.overlay()) {
                TRACKER.onChat(io.theprisons.core.client.TextStrip.strip(event.message().getString()));
            }
            chat.end(start);
        });
    }

    private static HudRenderCallback profiled(ThePrisonsCore core, String name, HudRenderCallback callback) {
        Profiler.Section section = core.profiler().section(name);
        return (context, tickCounter) -> {
            long start = section.begin();
            try {
                callback.onHudRender(context, tickCounter);
            } finally {
                section.end(start);
            }
        };
    }

}
