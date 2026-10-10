package io.theprisons.gametest;

import io.theprisons.modules.general.tunnel.TunnelVisionModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code ./gradlew runClientGameTest -Ptunnel}: Tunnel Vision - the iris closing over the game, the tunnel scene with the
 * player on the rainbow road while walking, the way out. Screenshots in build/run/clientGameTest/screenshots.
 */
public final class TunnelClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/TunnelTest");

    /** The test harness deletes older shots: keep a copy. */
    private static java.nio.file.Path keep(java.nio.file.Path shot) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "tunnel-shots");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.copy(shot, dir.resolve(shot.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException ignored) {
            // the original is still there
        }
        return shot;
    }

    /** Sets an enum setting of the tunnel by the constant's name. */
    private static void set(String id, String name) {
        TunnelVisionModule m = TunnelVisionModule.get();
        if (m.setting(id) instanceof io.theprisons.core.setting.Settings.EnumSetting<?> e) {
            setEnum(e, name);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(io.theprisons.core.setting.Settings.EnumSetting e, String name) {
        for (Object o : e.options()) {
            if (((Enum<?>) o).name().equals(name)) {
                e.set((Enum) o);
            }
        }
    }

    @java.lang.Override
    public void runTest(ClientGameTestContext context) {
        if (!ShowcaseClientGameTest.TUNNEL) {
            return;
        }
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(client -> {
            client.options.getGuiScale().setValue(3);
            client.onResolutionChanged();
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("gamemode survival @a");
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] world {}", keep(context.takeScreenshot("tunnel_0_world")));
            context.runOnClient(client -> {
                TunnelVisionModule m = TunnelVisionModule.get();
                if (m == null || !m.enabled()) {
                    throw new AssertionError("tunnel_vision is not enabled in the user build");
                }
                m.set(true);
            });
            context.waitTicks(8);
            LOGGER.info("[tunnel-test] iris closing {}", keep(context.takeScreenshot("tunnel_1_closing")));
            context.waitTicks(22);
            LOGGER.info("[tunnel-test] iris opening {}", keep(context.takeScreenshot("tunnel_2_opening")));
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] standing {}", keep(context.takeScreenshot("tunnel_3_standing")));
            context.runOnClient(client -> set("surface", "ROAD"));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(11);
                keep(context.takeScreenshot("seq_road_" + String.format("%02d", i)));
            }
            // performance: the same scene at every quality (frames per second of this machine)
            for (String q : new String[]{"HIGH", "BALANCED", "FAST"}) {
                context.runOnClient(client -> set("quality", q));
                context.waitTicks(80);
                int[] fps = new int[1];
                context.runOnClient(client -> fps[0] = client.getCurrentFps());
                LOGGER.info("[tunnel-test] fps quality={} -> {}", q, fps[0]);
            }
            context.runOnClient(client -> set("quality", "BALANCED"));
            context.runOnClient(client -> client.options.forwardKey.setPressed(true));
            context.waitTicks(60);
            LOGGER.info("[tunnel-test] walking {}", keep(context.takeScreenshot("tunnel_4_walking")));
            context.runOnClient(client -> {
                client.player.setYaw(client.player.getYaw() + 25.0F);
                client.options.sprintKey.setPressed(true);
            });
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] sprinting {}", keep(context.takeScreenshot("tunnel_5_sprinting")));
            // the second animation: the road dissolves into particles, the carpet appears
            context.runOnClient(client -> set("surface", "CARPET"));
            context.waitTicks(12);
            LOGGER.info("[tunnel-test] carpet: dissolving {}", keep(context.takeScreenshot("tunnel_5b_dissolve")));
            context.waitTicks(14);
            LOGGER.info("[tunnel-test] carpet: appearing {}", keep(context.takeScreenshot("tunnel_5c_appear")));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(11);
                keep(context.takeScreenshot("seq_carpet_" + String.format("%02d", i)));
            }
            context.runOnClient(client -> {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
                TunnelVisionModule.get().set(false);
            });
            context.waitTicks(12);
            LOGGER.info("[tunnel-test] leaving {}", keep(context.takeScreenshot("tunnel_6_leaving")));
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] back in the game {}", keep(context.takeScreenshot("tunnel_7_back")));
            // The config GUI.
            context.setScreen(() -> io.theprisons.ThePrisonsClient.configScreen(null, io.theprisons.core.ThePrisonsCore.getOrNull()));
            context.waitTicks(25);
            LOGGER.info("[tunnel-test] config {}", keep(context.takeScreenshot("tunnel_8_config_overview")));
        }
    }
}
