package io.theprisons.core;

import io.theprisons.core.analytics.StatsService;
import io.theprisons.core.command.CommandService;
import io.theprisons.core.config.ConfigStore;
import io.theprisons.core.control.ControlService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.hud.HudService;
import io.theprisons.core.module.Module;
import io.theprisons.core.module.ModuleHost;
import io.theprisons.core.module.ModuleManager;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.core.safety.SafetyMonitor;
import io.theprisons.core.world.TargetRegistry;
import io.theprisons.core.world.WorldCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The core: owns every shared service and is the <b>only</b> place that registers Fabric callbacks. Everything else
 * (modules, legacy features) subscribes to the {@link EventBus}.
 */
public final class ThePrisonsCore {
    public static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/Core");
    public static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("theprisons", "main"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static @Nullable ThePrisonsCore instance;

    /**
     * The zone named in the last "You entered ... zone" message ("diamond", "gold", "spawn", "mine"; "" = none yet). The state
     * lives in the Cosmic model's memory ({@link io.theprisons.core.cosmic.state.CosmicMemory}), which uses the shared
     * {@link io.theprisons.core.cosmic.parse.ZoneParser}; this accessor keeps the old call sites working.
     */
    public static String lastZone() {
        ThePrisonsCore current = instance;
        return current == null ? "" : current.cosmic.zone();
    }

    private final Profiler profiler = new Profiler();
    private final io.theprisons.core.cosmic.state.CosmicStateService cosmic = new io.theprisons.core.cosmic.state.CosmicStateService(profiler);
    private final ModuleManager modules = new ModuleManager(profiler);
    private final TargetRegistry targets = new TargetRegistry();
    private final WorldCache world = new WorldCache(targets, profiler);
    private final io.theprisons.core.world.WorldArchive archive = new io.theprisons.core.world.WorldArchive(world, profiler);
    private final ControlService control = new ControlService();
    private final SafetyMonitor safety;
    private final StatsService stats = new StatsService(System::currentTimeMillis);
    private final HudService hud = new HudService(modules);
    private final CommandService commands = new CommandService();
    private final ConfigStore config;
    private final Path dataDir;
    private final Profiler.Section tickProfile = profiler.section("core:tick");
    private Runnable openGui = () -> {
    };
    private @Nullable ClientWorld lastWorld;

    private ThePrisonsCore(Path configDir) {
        this.dataDir = configDir.resolve("theprisons");
        this.config = new ConfigStore(dataDir.resolve("modules.json"), modules, Util.getIoWorkerExecutor());
        this.safety = new SafetyMonitor(control, (owner, reason) -> {
            if (owner instanceof Module module) {
                modules.disable(module, reason);
            }
        });
        control.telemetry().sink(line -> LOGGER.info(line));
        control.setSpinHandler((owner, verdict, metrics) -> {
            LOGGER.warn("[control] SPIN_LOOP_DETECTED ({}) owner={} yaw turned {}deg in {} ticks, moved at most {} blocks, no block changed",
                    verdict, owner instanceof Module m ? m.id() : String.valueOf(owner), Math.round(metrics.yawTurnedDegrees()),
                    metrics.ticks(), String.format(java.util.Locale.ROOT, "%.1f", metrics.maxDisplacement()));
            // 1. stop moving and turning for a moment
            control.stabilise();
            if (verdict == io.theprisons.core.control.SpinGuard.Verdict.SPIN_REPEATED && owner instanceof Module module) {
                // 5. it happened again shortly after: safe stop, no endless recovery spinning
                modules.disable(module, "spin loop detected twice - stopped for safety");
            } else if (owner instanceof io.theprisons.core.module.AutomationModule automation) {
                // 3./5. drop the current path / target, plan once more
                automation.onSpinLoop();
            }
        });
        modules.setDirtyHook(config::markDirty);
        safety.setRelocationHandler((owner, reason) -> owner instanceof io.theprisons.core.module.AutomationModule automation
                && automation.onRelocated(reason));
        safety.setToleratesDamage(owner -> owner instanceof io.theprisons.core.module.AutomationModule automation
                && automation.toleratesDamage());
        safety.setTravelling(owner -> owner instanceof io.theprisons.core.module.AutomationModule automation
                && automation.travelling());
        modules.addLifecycleListener(new ModuleManager.LifecycleListener() {
            @Override
            public void disabled(Module module, @Nullable String reason) {
                // Belt and braces: whatever happened inside the module, its lease and scan interest go away.
                control.release(module, MinecraftClient.getInstance());
                world.release(module);
                targets.clear(module);
                if (reason != null && MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.literal("[ThePrisons] " + module.name() + " stopped: " + reason).formatted(Formatting.RED), false);
                }
                scheduleRestart(module, reason);
            }
        });
        stats.setSink(summary -> {
            String json = GSON.toJson(stats.history());
            Path file = dataDir.resolve("sessions.json");
            Util.getIoWorkerExecutor().execute(() -> {
                try {
                    Files.createDirectories(dataDir);
                    Files.writeString(file, json);
                } catch (IOException error) {
                    LOGGER.warn("Could not write {}", file, error);
                }
            });
        });
    }

    private static final int RESTART_DELAY_TICKS = 100;
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW_MS = 600_000L;
    private final java.util.Map<Module, java.util.ArrayDeque<Long>> restarts = new java.util.IdentityHashMap<>();

    /**
     * Continuous operation: a macro that stopped because of an internal error (not a safety stop, not the player)
     * is started again after {@value #RESTART_DELAY_TICKS} ticks, at most {@value #MAX_RESTARTS} times per 10 minutes.
     */
    private void scheduleRestart(Module module, @Nullable String reason) {
        if (reason == null || !safety.rules().restartAfterError()
                || !(module instanceof io.theprisons.core.module.AutomationModule)
                || !(reason.startsWith("crashed") || reason.startsWith("failed to start"))) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> recent = restarts.computeIfAbsent(module, m -> new java.util.ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > RESTART_WINDOW_MS) {
            recent.pollFirst();
        }
        if (recent.size() >= MAX_RESTARTS) {
            LOGGER.warn("{} crashed {} times within 10 minutes, not restarting", module.id(), MAX_RESTARTS);
            return;
        }
        recent.addLast(now);
        int[] waited = {0};
        io.theprisons.core.tick.TickScheduler.Task[] task = new io.theprisons.core.tick.TickScheduler.Task[1];
        task[0] = modules.scheduler().every(this, "restart:" + module.id(), 1, () -> {
            if (++waited[0] < RESTART_DELAY_TICKS) {
                return;
            }
            task[0].cancel();
            if (!module.enabled() && MinecraftClient.getInstance().player != null) {
                LOGGER.info("Restarting {} after: {}", module.id(), reason);
                modules.enable(module);
            }
        });
    }

    /** {@code config/theprisons}: settings, routes, captures. */
    public Path dataDir() {
        return dataDir;
    }

    /** The Cosmic game state: the model, the latest snapshot, the memory (zone, event) and the capture frame. */
    public io.theprisons.core.cosmic.state.CosmicStateService cosmic() {
        return cosmic;
    }

    public static ThePrisonsCore get() {
        ThePrisonsCore current = instance;
        if (current == null) {
            throw new IllegalStateException("ThePrisons core is not installed yet");
        }
        return current;
    }

    public static @Nullable ThePrisonsCore getOrNull() {
        return instance;
    }

    /** Creates the core and registers the Fabric callbacks. Modules are registered by the caller afterwards. */
    public static ThePrisonsCore install(Path configDir) {
        if (instance != null) {
            throw new IllegalStateException("already installed");
        }
        ThePrisonsCore core = new ThePrisonsCore(configDir);
        instance = core;
        core.registerFabric();
        core.registerCoreListeners();
        core.registerCommands();
        return core;
    }

    /** Loads the module config and enables what should be enabled. Call after all modules are registered. */
    public void start() {
        config.load(true);
        LOGGER.info("Core started: {} modules, {} enabled", modules.all().size(),
                modules.all().stream().filter(Module::enabled).count());
    }

    // ── Fabric bridge ────────────────────────────────────────────────────────

    private void registerFabric() {
        EventBus bus = modules.bus();
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            modules.worker().drain();
            bus.post(new CoreEvents.TickStart(client));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long start = tickProfile.begin();
            try {
                if (client.world != lastWorld) {
                    ClientWorld previous = lastWorld;
                    lastWorld = client.world;
                    bus.post(new CoreEvents.WorldChanged(previous, client.world));
                }
                bus.post(new CoreEvents.TickEnd(client));
            } finally {
                tickProfile.end(start);
            }
        });
        ClientChunkEvents.CHUNK_LOAD.register((clientWorld, chunk) -> {
            world.onChunkLoaded(clientWorld, chunk.getPos().x, chunk.getPos().z);
            archive.onChunkLoaded(clientWorld, chunk.getPos().x, chunk.getPos().z);
            if (bus.hasListeners(CoreEvents.ChunkLoaded.class)) {
                bus.post(new CoreEvents.ChunkLoaded(clientWorld, chunk.getPos().x, chunk.getPos().z));
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((clientWorld, chunk) -> {
            world.onChunkUnloaded(clientWorld, chunk.getPos().x, chunk.getPos().z);
            if (bus.hasListeners(CoreEvents.ChunkUnloaded.class)) {
                bus.post(new CoreEvents.ChunkUnloaded(clientWorld, chunk.getPos().x, chunk.getPos().z));
            }
        });
        ClientPlayerBlockBreakEvents.AFTER.register((clientWorld, player, pos, state) -> {
            if (clientWorld instanceof ClientWorld cw) {
                world.onPlayerBrokeBlock(cw, pos.asLong());
                archive.onBlockChanged(cw, pos.asLong());
                bus.post(new CoreEvents.PlayerBrokeBlock(cw, pos.asLong(), state));
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                // Zone, event and the short system-line memory (player chat is not stored; see CosmicMemory).
                cosmic.onMessage(message, false, false);
            }
            bus.post(new CoreEvents.ChatReceived(message, overlay, false));
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
                bus.post(new CoreEvents.ChatReceived(message, false, true)));
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (bus.hasListeners(CoreEvents.WorldRender.class)) {
                bus.post(new CoreEvents.WorldRender(context));
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> commands.register(dispatcher));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    /** Called from the {@code ClientWorld} mixin for every server block update ({@code previous}: the block before). */
    public void onBlockUpdate(ClientWorld clientWorld, BlockPos pos, BlockState state, BlockState previous) {
        if (previous != state) {
            control.noteWorldProgress();
        }
        world.onBlockChanged(clientWorld, pos.asLong());
        archive.onBlockChanged(clientWorld, pos.asLong());
        EventBus bus = modules.bus();
        if (bus.hasListeners(CoreEvents.BlockChanged.class)) {
            bus.post(new CoreEvents.BlockChanged(clientWorld, pos.asLong(), state, previous));
        }
    }

    /** Called from the {@code InGameHud} mixin whenever the action bar text is set. */
    public void onActionBar(net.minecraft.text.Text message) {
        cosmic.onMessage(message, true, false);
        modules.bus().post(new CoreEvents.ChatReceived(message, true, false));
    }

    /** Called from the {@code LivingEntity} mixin when the local player takes damage. */
    public void onPlayerHurt(net.minecraft.entity.damage.DamageSource source) {
        EventBus bus = modules.bus();
        if (bus.hasListeners(CoreEvents.PlayerHurt.class)) {
            bus.post(new CoreEvents.PlayerHurt(source.getAttacker() != null ? source.getAttacker() : source.getSource()));
        }
    }

    private void registerCoreListeners() {
        EventBus bus = modules.bus();
        cosmic.attach(bus);
        bus.subscribe(CoreEvents.WorldChanged.class, this, event -> world.onWorldChanged(event.current()));
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.WORLD, event -> {
            world.tick(event.client());
            archive.tick(event.client());
        });
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.KEYBINDS, event -> {
            MinecraftClient client = event.client();
            modules.pollKeybinds(key -> InputUtil.isKeyPressed(client.getWindow(), key), client.currentScreen == null);
        });
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.SAFETY, event -> safety.tick(event.client()));
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.SCHEDULER, event -> modules.scheduler().tick());
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.CONTROL, event -> control.apply(event.client()));
        bus.subscribe(CoreEvents.TickEnd.class, this, Phases.HOUSEKEEPING, event -> {
            hud.tick();
            config.tick();
        });
    }

    private void registerCommands() {
        commands.contribute(root -> root
                .executes(ctx -> {
                    // Opening a screen from a chat command must wait until the chat screen closed.
                    ctx.getSource().getClient().send(openGui);
                    return 1;
                })
                .then(ClientCommandManager.literal("gui").executes(ctx -> {
                    ctx.getSource().getClient().send(openGui);
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    Object owner = control.owner();
                    if (owner instanceof Module module) {
                        modules.disable(module);
                        ctx.getSource().sendFeedback(Text.literal("Stopped " + module.name() + ".").formatted(Formatting.AQUA));
                    } else {
                        ctx.getSource().sendFeedback(Text.literal("No macro is running.").formatted(Formatting.GRAY));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    modules.all().forEach(module -> builder.suggest(module.id()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    Module module = modules.get(StringArgumentType.getString(ctx, "module"));
                                    if (module == null) {
                                        ctx.getSource().sendError(Text.literal("Unknown module."));
                                        return 0;
                                    }
                                    boolean on = modules.toggle(module);
                                    ctx.getSource().sendFeedback(Text.literal(module.name() + (on ? " enabled." : " disabled."))
                                            .formatted(on ? Formatting.GREEN : Formatting.GRAY));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("perf")
                        .executes(ctx -> {
                            printPerf(line -> ctx.getSource().sendFeedback(Text.literal(line).formatted(Formatting.GRAY)));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            profiler.reset();
                            ctx.getSource().sendFeedback(Text.literal("Profiler reset.").formatted(Formatting.AQUA));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("stats").executes(ctx -> {
                    List<StatsService.Summary> history = stats.history();
                    if (history.isEmpty()) {
                        ctx.getSource().sendFeedback(Text.literal("No finished runs yet.").formatted(Formatting.GRAY));
                    }
                    for (StatsService.Summary summary : history.subList(0, Math.min(5, history.size()))) {
                        ctx.getSource().sendFeedback(Text.literal(formatSummary(summary)).formatted(Formatting.GRAY));
                    }
                    return 1;
                })));
    }

    private void printPerf(Consumer<String> out) {
        out.accept(String.format(Locale.ROOT, "ThePrisons profiler — modules %d on, bus listeners %d, tasks %d, jobs %d, cached sections %d",
                modules.all().stream().filter(Module::enabled).count(), modules.bus().listenerCount(), modules.scheduler().size(),
                modules.worker().pending(), world.store().size()));
        List<Profiler.Section> sections = profiler.sections();
        for (Profiler.Section section : sections.subList(0, Math.min(14, sections.size()))) {
            if (section.calls() > 0) {
                out.accept(Profiler.format(section));
            }
        }
    }

    private static String formatSummary(StatsService.Summary summary) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%s: %d s", summary.namespace(), summary.durationMs() / 1000L));
        summary.counters().forEach((name, value) -> text.append(String.format(Locale.ROOT, ", %s %d (%.0f/h)", name, value, summary.perHour(name))));
        return text.toString();
    }

    private void shutdown() {
        modules.disableAll();
        archive.close();
        config.saveNow(true);
        modules.worker().shutdown();
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public void setGuiOpener(Runnable opener) {
        this.openGui = opener;
    }

    public void setNotifier(Consumer<ModuleHost.Notice> notifier) {
        modules.setNotifier(notifier);
    }

    public Profiler profiler() {
        return profiler;
    }

    public ModuleManager modules() {
        return modules;
    }

    public EventBus bus() {
        return modules.bus();
    }

    public TargetRegistry targets() {
        return targets;
    }

    public WorldCache world() {
        return world;
    }

    public io.theprisons.core.world.WorldArchive archive() {
        return archive;
    }

    public ControlService control() {
        return control;
    }

    public SafetyMonitor safety() {
        return safety;
    }

    public StatsService stats() {
        return stats;
    }

    public HudService hud() {
        return hud;
    }

    public CommandService commands() {
        return commands;
    }

    public ConfigStore config() {
        return config;
    }
}
