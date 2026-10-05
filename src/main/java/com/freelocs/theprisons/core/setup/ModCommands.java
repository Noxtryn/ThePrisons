package com.freelocs.theprisons.core.setup;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.i18n.I18n;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.gui.click.ClickGuiScreen;
import com.freelocs.theprisons.modules.ModuleRegistry;
import com.freelocs.theprisons.modules.general.ClickGuiModule;
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
