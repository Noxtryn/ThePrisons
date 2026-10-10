package io.theprisons.core.setup;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.i18n.I18n;
import io.theprisons.core.module.Module;
import io.theprisons.gui.config.ConfigScreen;
import io.theprisons.modules.ModuleRegistry;
import io.theprisons.modules.general.ClickGuiModule;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;

/**
 * The client commands behind the chat links of the setup gate: {@code /prisons open <module> [setting]} and
 * {@code /prisons lang [en|de]} (client-side, never sent to the server). There is no welcome screen any more.
 */
public final class ModCommands {
    private ModCommands() {
    }

    public static void register(ThePrisonsCore core) {
        if (io.theprisons.core.cosmic.capture.CaptureService.enabled(core.dataDir())) {
            // Capture mode (developer / hidden): /prisons capture [note] writes the current moment, anonymised, to a local file.
            core.commands().contribute(root -> root.then(ClientCommandManager.literal("capture")
                    .executes(ctx -> capture(core, ""))
                    .then(ClientCommandManager.argument("note", StringArgumentType.greedyString())
                            .executes(ctx -> capture(core, StringArgumentType.getString(ctx, "note"))))));
        }
        core.commands().contribute(root -> root
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
    }

    private static int capture(ThePrisonsCore core, String note) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return 0;
        }
        io.theprisons.core.cosmic.capture.CaptureService.capture(client, core.cosmic(), core.dataDir(), note);
        return 1;
    }

    private static int openAt(ThePrisonsCore core, String moduleId, String settingId) {
        Module module = core.modules().get(moduleId);
        if (module == null) {
            ModChat.show(ModChat.header("Unknown module: " + moduleId));
            return 0;
        }
        if (settingId != null && module.setting(settingId) == null) {
            ModChat.show(ModChat.header("Unknown setting: " + moduleId + "." + settingId));
            settingId = null;
        }
        ConfigScreen.focus(module, settingId);
        MinecraftClient client = MinecraftClient.getInstance();
        client.send(() -> client.setScreen(new ConfigScreen(null, core)));
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
