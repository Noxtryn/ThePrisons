package com.freelocs.theprisons.gui.welcome;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.i18n.I18n;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Setting;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.core.setup.ModChat;
import com.freelocs.theprisons.modules.ModuleRegistry;
import com.freelocs.theprisons.modules.general.ClickGuiModule;
import com.freelocs.theprisons.modules.mining.ore.OreMacroModule;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Welcome setup: a step-by-step tutorial that explains the Ore Macro and sets it up at the same time - every control
 * here is the real setting, so after the last step the macro can start right away. Opens on the first join, again
 * with {@code /prisons setup} or the "Setup wizard" button. Language switch (EN / DE) at the top right, live.
 */
public final class WelcomeScreen extends Screen {
    private static final int CARD_W = 400;
    private static final int CARD_H = 270;
    private static final int CONTROL_W = 150;

    /** One page: heading, explanation and the settings (by id) set on it. */
    private record Step(String title, String body, List<String> settings) {
    }

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private static final List<Step> STEPS = List.of(
            new Step("Welcome to ThePrisons",
                    "This short setup explains the Ore Macro and sets it up at the same time. When you are through, you "
                            + "can start mining right away. First pick your language - it switches live and can be "
                            + "changed any time with the EN / DE button.",
                    List.of()),
            new Step("How the Ore Macro works",
                    "The macro walks through the middle of the mine's tunnels, decides block by block where the most "
                            + "ore is and mines the floor ores on the move - it never mines walls or the ceiling. It "
                            + "sells when your satchel is full, uses your pet and abilities, takes short breaks and "
                            + "sorts your inventory. Below is the key that starts and stops it.",
                    List.of("keybind")),
            new Step("Your ores",
                    "Pick the ores of the mine you are in. Each includes the ore, the deepslate ore and the ore block. "
                            + "Ores you do not pick mark the border of the mine: the macro turns back before them.",
                    List.of("ore_packs")),
            new Step("Route (optional)",
                    "Without a route the macro mines freely through the tunnels. With a route it follows your "
                            + "waypoints in a loop and fetches ore up to 5 blocks beside it. Record a route with the keys "
                            + "\"Start/Stop route recording\" (Options > Controls), then pick it here.",
                    List.of("route")),
            new Step("Item sorter",
                    "When 35 % of your inventory are prismarine shard stacks or half of it are items that are no blocks "
                            + "(ores do not count), the macro goes to /spawn, puts the shards "
                            + "into your shard vault and everything else into your other vault, and comes back. "
                            + "Satchels, pickaxe, sponges, light blue dye, pets and ability items stay with you. Enter "
                            + "the numbers of your private vaults (/pv).",
                    List.of("item_sorter", "vault_shards", "vault_other")),
            new Step("Pet and abilities",
                    "Pets and ability items in your hotbar are used as soon as they are ready. Enter a part of their "
                            + "names so the macro finds them.",
                    List.of("use_pet", "pet_name", "use_ability", "ability_names")),
            new Step("Breaks and walking",
                    "Now and then the macro walks to a warden and stands still for a short break. Auto sprint sprints "
                            + "on straight parts of the way.",
                    List.of("breaks", "sprint")),
            new Step("Ready to go",
                    "Stand in the mine and press the start key. Everything here can be changed later in the menu "
                            + "(Right Shift or /prisons). Missing settings are always shown in your chat - only you "
                            + "see them - with a link that leads straight there.",
                    List.of())
    );

    private final @Nullable Screen parent;
    private final ThePrisonsCore core;
    private final List<Hit> hits = new ArrayList<>();
    private int step;
    private int cardX;
    private int cardY;
    private Settings.@Nullable TextSetting editing;
    private String editBuffer = "";
    private Settings.@Nullable KeybindSetting listening;
    private double listScroll;

    public WelcomeScreen(@Nullable Screen parent, ThePrisonsCore core) {
        super(Text.literal("ThePrisons"));
        this.parent = parent;
        this.core = core;
    }

    @Override
    protected void init() {
        cardX = (width - CARD_W) / 2;
        cardY = Math.max(8, (height - CARD_H) / 2);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private @Nullable Module macro() {
        return core.modules().get(OreMacroModule.ID);
    }

    private @Nullable Setting<?> setting(String id) {
        Module module = macro();
        if (module == null) {
            return null;
        }
        if ("keybind".equals(id)) {
            return module.keybind();
        }
        for (Setting<?> setting : module.settings()) {
            if (setting.id().equals(id)) {
                return setting;
            }
        }
        return null;
    }

    /** Settings of the current page that still have a problem (Next is blocked until they are fixed). */
    private List<Setting<?>> pageProblems() {
        List<Setting<?>> list = new ArrayList<>();
        for (String id : STEPS.get(step).settings()) {
            Setting<?> setting = setting(id);
            if (setting != null && setting.problem() != null) {
                list.add(setting);
            }
        }
        return list;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        hits.clear();
        renderBackground(context, mouseX, mouseY, delta);
        TextRenderer font = textRenderer;
        int x = cardX;
        int y = cardY;
        context.fill(x, y, x + CARD_W, y + CARD_H, 0xD8060608);
        context.fillGradient(x, y, x + CARD_W, y + 2, ThePrisonsColors.HEADING_PINK, ThePrisonsColors.MOD_BLUE);
        context.drawStrokedRectangle(x, y, CARD_W, CARD_H, 0x30FFFFFF);

        // Mod name first, light blue and bold; language button at the top right.
        context.drawText(font, Text.literal("ThePrisons").styled(s -> s.withBold(true)), x + 12, y + 10, ThePrisonsColors.MOD_BLUE, true);
        String lang = I18n.lang().code();
        int lw = font.getWidth(lang) + 12;
        int lx = x + CARD_W - 12 - lw;
        boolean langHover = inside(mouseX, mouseY, lx, y + 7, lx + lw, y + 21);
        context.fill(lx, y + 7, lx + lw, y + 21, langHover ? 0x50FF6EC7 : 0x80000000);
        context.drawStrokedRectangle(lx, y + 7, lw, 14, ThePrisonsColors.HEADING_PINK);
        context.drawText(font, lang, lx + 6, y + 10, 0xFFFFFFFF, false);
        hits.add(new Hit(lx, y + 7, lx + lw, y + 21, () -> setLanguage(I18n.lang().next())));

        // Progress: one dot per step.
        int dots = STEPS.size();
        int dx = x + CARD_W / 2 - dots * 7;
        for (int i = 0; i < dots; i++) {
            int color = i < step ? ThePrisonsColors.MOD_BLUE : i == step ? ThePrisonsColors.HEADING_PINK : 0x40FFFFFF;
            context.fill(dx + i * 14, y + 13, dx + i * 14 + 8, y + 16, color);
        }
        String counter = I18n.f("Step %d of %d", step + 1, dots);
        context.drawText(font, counter, x + CARD_W / 2 - font.getWidth(counter) / 2, y + 20, 0x80FFFFFF, false);

        // Heading pink and bold, text white.
        Step page = STEPS.get(step);
        int ty = y + 36;
        context.drawText(font, Text.literal(I18n.t(page.title())).styled(s -> s.withBold(true)), x + 16, ty, ThePrisonsColors.HEADING_PINK, true);
        ty += 15;
        for (OrderedText line : font.wrapLines(StringVisitable.plain(I18n.t(page.body())), CARD_W - 32)) {
            context.drawText(font, line, x + 16, ty, 0xFFFFFFFF, true);
            ty += 10;
        }
        ty += 8;

        if (step == 0) {
            ty = languageButtons(context, mouseX, mouseY, x + 16, ty);
        } else if (step == STEPS.size() - 1) {
            ty = checklist(context, x + 16, ty);
        }
        for (String id : page.settings()) {
            Setting<?> setting = setting(id);
            if (setting != null && setting.visible()) {
                ty = row(context, mouseX, mouseY, setting, x + 16, ty, CARD_W - 32);
            }
        }

        // Navigation
        int by = y + CARD_H - 24;
        if (step > 0) {
            button(context, mouseX, mouseY, x + 12, by, I18n.t("Back"), true, () -> go(step - 1));
        }
        String skip = I18n.t("Close");
        int sw = font.getWidth(skip) + 14;
        button(context, mouseX, mouseY, x + CARD_W / 2 - sw / 2, by, skip, true, this::close);
        boolean last = step == STEPS.size() - 1;
        List<Setting<?>> problems = pageProblems();
        String nextLabel = last ? I18n.t("Finish") : I18n.t("Next");
        int nw = font.getWidth(nextLabel) + 14;
        button(context, mouseX, mouseY, x + CARD_W - 12 - nw, by, nextLabel, problems.isEmpty(), () -> {
            if (last) {
                finish();
            } else {
                go(step + 1);
            }
        });
        if (!problems.isEmpty()) {
            String hint = I18n.t("Fill in the red fields to go on.");
            context.drawText(font, hint, x + CARD_W - 12 - font.getWidth(hint), by - 11, ThePrisonsColors.ACCENT_RED, false);
        }
    }

    private int languageButtons(DrawContext context, int mouseX, int mouseY, int x, int y) {
        int bw = 110;
        for (I18n.Lang lang : I18n.Lang.values()) {
            boolean active = I18n.lang() == lang;
            boolean hover = inside(mouseX, mouseY, x, y, x + bw, y + 20);
            context.fill(x, y, x + bw, y + 20, active ? 0x50FF6EC7 : hover ? 0x30FFFFFF : 0x80000000);
            context.drawStrokedRectangle(x, y, bw, 20, active ? ThePrisonsColors.HEADING_PINK : 0x40FFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, lang.label(), x + bw / 2, y + 6, 0xFFFFFFFF);
            hits.add(new Hit(x, y, x + bw, y + 20, () -> setLanguage(lang)));
            x += bw + 8;
        }
        return y + 28;
    }

    /** Last page: what is set, what is still missing. */
    private int checklist(DrawContext context, int x, int y) {
        Module module = macro();
        if (module == null) {
            return y;
        }
        List<Setting<?>> missing = module.missing();
        if (missing.isEmpty()) {
            context.drawText(textRenderer, "✔ " + I18n.t("Everything is set up."), x, y, ThePrisonsColors.ACCENT_LIME, true);
            y += 12;
            String key = keyName(module.keybind().key());
            context.drawText(textRenderer, I18n.f("Start / stop key: %s", key), x, y, 0xFFFFFFFF, true);
            return y + 14;
        }
        for (Setting<?> setting : missing) {
            String problem = setting.problem();
            context.drawText(textRenderer, "✖ " + I18n.t(setting.name()) + ": " + I18n.t(problem == null ? "" : problem),
                    x, y, ThePrisonsColors.ACCENT_RED, true);
            y += 11;
        }
        return y + 4;
    }

    /** One setting: name white, control, and in red what is missing. */
    private int row(DrawContext context, int mouseX, int mouseY, Setting<?> setting, int x, int y, int w) {
        TextRenderer font = textRenderer;
        String problem = setting.problem();
        context.drawText(font, I18n.t(setting.name()), x, y + 3, problem != null ? ThePrisonsColors.ACCENT_RED : 0xFFFFFFFF, true);
        int cx = x + w - CONTROL_W;
        int bottom = y + 14;
        if (setting instanceof Settings.BoolSetting bool) {
            int tx = x + w - 22;
            if (bool.on()) {
                context.fillGradient(tx, y + 2, tx + 20, y + 12, ThePrisonsColors.HEADING_PINK, ThePrisonsColors.MOD_BLUE);
            } else {
                context.fill(tx, y + 2, tx + 20, y + 12, 0x40FFFFFF);
            }
            int knob = bool.on() ? tx + 11 : tx + 1;
            context.fill(knob, y + 3, knob + 8, y + 11, 0xFFFFFFFF);
            hits.add(new Hit(tx - 4, y, tx + 24, y + 14, bool::toggle));
        } else if (setting instanceof Settings.TextSetting text) {
            boolean active = editing == text;
            box(context, cx, y, CONTROL_W, 14, active, problem != null);
            String value = active ? editBuffer + (blink() ? "_" : "") : text.get();
            context.drawText(font, trimLeft(value, CONTROL_W - 8), cx + 4, y + 3, 0xFFFFFFFF, false);
            hits.add(new Hit(cx, y, cx + CONTROL_W, y + 14, () -> {
                commitEdit();
                editing = text;
                editBuffer = text.get();
            }));
        } else if (setting instanceof Settings.KeybindSetting bind) {
            boolean active = listening == bind;
            box(context, cx, y, CONTROL_W, 14, active, false);
            context.drawText(font, active ? I18n.t("Press a key…") : keyName(bind.key()), cx + 4, y + 3,
                    active ? ThePrisonsColors.ACCENT_AMBER : 0xFFFFFFFF, false);
            hits.add(new Hit(cx, y, cx + CONTROL_W, y + 14, () -> {
                commitEdit();
                listening = bind;
            }));
        } else if (setting instanceof Settings.MultiChoiceSetting multi) {
            int chipX = x;
            int chipY = y + 16;
            for (Settings.Option option : multi.options()) {
                boolean on = multi.contains(option.id());
                int chipW = font.getWidth(option.label()) + 16;
                if (chipX + chipW > x + w) {
                    chipX = x;
                    chipY += 16;
                }
                boolean hover = inside(mouseX, mouseY, chipX, chipY, chipX + chipW, chipY + 14);
                context.fill(chipX, chipY, chipX + chipW, chipY + 14, on ? 0x50FF6EC7 : hover ? 0x30FFFFFF : 0x80000000);
                context.drawStrokedRectangle(chipX, chipY, chipW, 14, on ? option.color() : 0x40FFFFFF);
                context.fill(chipX + 4, chipY + 5, chipX + 8, chipY + 9, on ? option.color() : 0x40FFFFFF);
                context.drawText(font, option.label(), chipX + 11, chipY + 3, on ? 0xFFFFFFFF : 0xA0FFFFFF, false);
                String id = option.id();
                hits.add(new Hit(chipX, chipY, chipX + chipW, chipY + 14, () -> multi.toggle(id)));
                chipX += chipW + 4;
            }
            bottom = chipY + 14;
        } else if (setting instanceof Settings.ChoiceSetting choice) {
            // Inline list: "off" first, then the options (scroll with the mouse wheel).
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"", I18n.t(choice.offLabel())});
            for (Settings.Option option : choice.options()) {
                rows.add(new String[]{option.id(), option.section() + " · " + option.label()});
            }
            int visible = 6;
            int first = (int) MathHelper.clamp(listScroll, 0, Math.max(0, rows.size() - visible));
            int ly = y + 16;
            for (int i = first; i < Math.min(rows.size(), first + visible); i++) {
                String[] row = rows.get(i);
                boolean current = row[0].equals(choice.get());
                boolean hover = inside(mouseX, mouseY, x, ly, x + w, ly + 13);
                context.fill(x, ly, x + w, ly + 13, current ? 0x50FF6EC7 : hover ? 0x30FFFFFF : 0x60000000);
                context.drawText(font, trim((current ? "● " : "○ ") + row[1], w - 8), x + 4, ly + 3, 0xFFFFFFFF, false);
                String id = row[0];
                hits.add(new Hit(x, ly, x + w, ly + 13, () -> choice.set(id)));
                ly += 14;
            }
            if (rows.size() > visible) {
                context.drawText(font, I18n.t("(scroll for more)"), x, ly + 1, 0x80FFFFFF, false);
                ly += 10;
            }
            bottom = ly;
        }
        if (problem != null) {
            for (OrderedText line : font.wrapLines(StringVisitable.plain("! " + I18n.t(problem)), w)) {
                context.drawText(font, line, x, bottom + 2, ThePrisonsColors.ACCENT_RED, false);
                bottom += 9;
            }
            bottom += 2;
        }
        return bottom + 8;
    }

    private void box(DrawContext context, int x, int y, int w, int h, boolean focused, boolean error) {
        context.fill(x, y, x + w, y + h, 0xA0000000);
        context.drawStrokedRectangle(x, y, w, h, error ? ThePrisonsColors.ACCENT_RED : focused ? ThePrisonsColors.MOD_BLUE : 0x50FFFFFF);
    }

    private void button(DrawContext context, int mouseX, int mouseY, int x, int y, String label, boolean enabled, Runnable action) {
        int w = textRenderer.getWidth(label) + 14;
        boolean hover = enabled && inside(mouseX, mouseY, x, y, x + w, y + 16);
        context.fill(x, y, x + w, y + 16, !enabled ? 0x40000000 : hover ? 0x60FF6EC7 : 0x90000000);
        context.drawStrokedRectangle(x, y, w, 16, enabled ? ThePrisonsColors.HEADING_PINK : 0x30FFFFFF);
        context.drawText(textRenderer, label, x + 7, y + 4, enabled ? 0xFFFFFFFF : 0x60FFFFFF, false);
        if (enabled) {
            hits.add(new Hit(x, y, x + w, y + 16, action));
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void go(int target) {
        commitEdit();
        listening = null;
        listScroll = 0;
        step = MathHelper.clamp(target, 0, STEPS.size() - 1);
    }

    private void setLanguage(I18n.Lang lang) {
        ClickGuiModule gui = ModuleRegistry.clickGui(core);
        if (gui != null) {
            gui.setLanguage(lang);
        } else {
            I18n.setLang(lang);
        }
    }

    private void finish() {
        commitEdit();
        ClickGuiModule gui = ModuleRegistry.clickGui(core);
        if (gui != null) {
            gui.setSetupDone(true);
        }
        core.config().saveNow(false);
        Module module = macro();
        ModChat.show(ModChat.header("Setup complete"));
        if (module != null) {
            ModChat.show(ModChat.line(I18n.f("Stand in the mine and press %s to start the Ore Macro.", keyName(module.keybind().key()))));
        }
        close();
    }

    @Override
    public void close() {
        commitEdit();
        if (core.config().dirty()) {
            core.config().saveNow(false);
        }
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void commitEdit() {
        Settings.TextSetting current = editing;
        if (current != null) {
            current.set(editBuffer);
        }
        editing = null;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        listening = null;
        // Any click commits a text edit; clicking a field opens it (again) with the committed value.
        commitEdit();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(click.x(), click.y())) {
                hit.action().run();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        listScroll = Math.max(0, listScroll - verticalAmount);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        Settings.KeybindSetting bind = listening;
        if (bind != null) {
            bind.set(key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE ? Settings.KeybindSetting.NONE : key);
            listening = null;
            return true;
        }
        Settings.TextSetting text = editing;
        if (text != null) {
            switch (key) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> commitEdit();
                case GLFW.GLFW_KEY_ESCAPE -> editing = null;
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!editBuffer.isEmpty()) {
                        editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                    }
                }
                default -> {
                    if (input.isPaste() && client != null) {
                        editBuffer += client.keyboard.getClipboard().replace("\n", " ");
                    }
                }
            }
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && pageProblems().isEmpty()) {
            if (step == STEPS.size() - 1) {
                finish();
            } else {
                go(step + 1);
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        Settings.TextSetting text = editing;
        if (text != null && input.isValidChar()) {
            if (editBuffer.length() < text.maxLength()) {
                editBuffer += input.asString();
            }
            return true;
        }
        return false;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    private String trim(String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return textRenderer.trimToWidth(text, Math.max(0, maxWidth - textRenderer.getWidth("…"))) + "…";
    }

    private String trimLeft(String text, int maxWidth) {
        String result = text;
        while (result.length() > 1 && textRenderer.getWidth(result) > maxWidth) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean blink() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    private static String keyName(int key) {
        if (key == Settings.KeybindSetting.NONE) {
            return I18n.t("None");
        }
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }
}
