package com.freelocs.theprisons.gui.welcome;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.i18n.I18n;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setup.ModChat;
import com.freelocs.theprisons.core.setup.SetupGate;
import com.freelocs.theprisons.gui.click.ClickGuiScreen;
import com.freelocs.theprisons.modules.ModuleRegistry;
import com.freelocs.theprisons.modules.general.ClickGuiModule;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Wires the welcome setup: the setup gate, the client commands behind the chat links ({@code /prisons open <module>
 * [setting]}, {@code /prisons setup}, {@code /prisons lang [en|de]}; client-side, never sent to the server) and the
 * welcome screen that opens by itself on the first join until the setup is finished.
 */
public final class SetupFlow {
    private static boolean shownThisSession;

    private SetupFlow() {
    }

    public static void register(ThePrisonsCore core) {
        SetupGate.setWelcomeDone(() -> {
            ClickGuiModule gui = ModuleRegistry.clickGui(core);
            return gui == null || gui.setupDone();
        });
        ClickGuiScreen.setWelcomeOpener(() -> openWelcome(core));
        core.commands().contribute(root -> root
                .then(ClientCommandManager.literal("setup").executes(ctx -> {
                    openWelcome(core);
                    return 1;
                }))
                .then(ClientCommandManager.literal("lang")
                        .executes(ctx -> {
                            setLanguage(core, I18n.lang().next());
                            return 1;
                        })
                        .then(ClientCommandManager.argument("language", StringArgumentType.word()).executes(ctx -> {
                            String code = StringArgumentType.getString(ctx, "language");
                            setLanguage(core, "de".equalsIgnoreCase(code) || "deutsch".equalsIgnoreCase(code) ? I18n.Lang.DE : I18n.Lang.EN);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("open")
                        .then(ClientCommandManager.argument("module", StringArgumentType.word())
                                .executes(ctx -> openAt(core, StringArgumentType.getString(ctx, "module"), null))
                                .then(ClientCommandManager.argument("setting", StringArgumentType.word())
                                        .executes(ctx -> openAt(core, StringArgumentType.getString(ctx, "module"),
                                                StringArgumentType.getString(ctx, "setting")))))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (shownThisSession || client.player == null || client.world == null || client.currentScreen != null
                    || SetupGate.welcomeDone()) {
                return;
            }
            // First join without a finished setup: straight to the welcome screen (once per game session).
            shownThisSession = true;
            ModChat.show(ModChat.header("Welcome"));
            ModChat.show(ModChat.line(I18n.t("The welcome setup explains the macro and sets it up."))
                    .append(ModChat.link("Start setup", SetupGate.SETUP_COMMAND, "Opens the welcome setup")));
            client.setScreen(new WelcomeScreen(null, core));
        });
    }

    private static void openWelcome(ThePrisonsCore core) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Next tick: a command typed in chat closes the chat screen right after it ran.
        client.send(() -> client.setScreen(new WelcomeScreen(null, core)));
    }

    private static int openAt(ThePrisonsCore core, String moduleId, String settingId) {
        Module module = core.modules().get(moduleId);
        if (module == null) {
            return 0;
        }
        ClickGuiScreen.focus(module, settingId);
        MinecraftClient client = MinecraftClient.getInstance();
        client.send(() -> client.setScreen(new ClickGuiScreen(null, core)));
        return 1;
    }

    private static void setLanguage(ThePrisonsCore core, I18n.Lang lang) {
        ClickGuiModule gui = ModuleRegistry.clickGui(core);
        if (gui != null) {
            gui.setLanguage(lang);
        } else {
            I18n.setLang(lang);
        }
        core.config().markDirty();
        ModChat.show(ModChat.header("Language: English"));
    }
}
