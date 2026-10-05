package com.freelocs.theprisons.gametest;

import com.freelocs.theprisons.modules.general.tunnel.TunnelVisionModule;
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
            context.runOnClient(client -> client.options.forwardKey.setPressed(true));
            context.waitTicks(60);
            LOGGER.info("[tunnel-test] walking {}", keep(context.takeScreenshot("tunnel_4_walking")));
            context.runOnClient(client -> {
                client.player.setYaw(client.player.getYaw() + 25.0F);
                client.options.sprintKey.setPressed(true);
            });
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] sprinting {}", keep(context.takeScreenshot("tunnel_5_sprinting")));
            context.runOnClient(client -> {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
                TunnelVisionModule.get().set(false);
            });
            context.waitTicks(12);
            LOGGER.info("[tunnel-test] leaving {}", keep(context.takeScreenshot("tunnel_6_leaving")));
            context.waitTicks(40);
            LOGGER.info("[tunnel-test] back in the game {}", keep(context.takeScreenshot("tunnel_7_back")));
            // The dashboard pages: Overview -> Mining -> Bandits -> Tunnel (Tab cycles)
            context.setScreen(() -> new com.freelocs.theprisons.gui.dashboard.DashboardScreen(null,
                    com.freelocs.theprisons.core.ThePrisonsCore.getOrNull(), () -> { }, null));
            context.waitTicks(25);
            LOGGER.info("[tunnel-test] dashboard {}", keep(context.takeScreenshot("tunnel_8_dash_overview")));
            String[] pages = {"mining", "bandits", "tunnel"};
            for (String page : pages) {
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
                context.waitTicks(25);
                LOGGER.info("[tunnel-test] dashboard {} {}", page, keep(context.takeScreenshot("tunnel_9_dash_" + page)));
            }
        }
    }
}
