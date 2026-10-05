package com.freelocs.theprisons.modules.mining.ore.route;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Opens when a route recording is stopped: name the route and save it (Enter / "Save"), or throw it away
 * ("Discard" / Escape).
 */
public final class RouteNameScreen extends Screen {
    private final String defaultName;
    private final String summary;
    private final Consumer<String> onSave;
    private final Runnable onDiscard;
    private TextFieldWidget nameField;
    private boolean done;

    public RouteNameScreen(String defaultName, String summary, Consumer<String> onSave, Runnable onDiscard) {
        super(Text.literal("Save route"));
        this.defaultName = defaultName;
        this.summary = summary;
        this.onSave = onSave;
        this.onDiscard = onDiscard;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        String previous = nameField == null ? defaultName : nameField.getText();
        nameField = new TextFieldWidget(textRenderer, cx - 100, cy - 10, 200, 20, Text.literal("Route name"));
        nameField.setMaxLength(48);
        nameField.setText(previous);
        addDrawableChild(nameField);
        setInitialFocus(nameField);
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> save()).dimensions(cx - 100, cy + 18, 97, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Discard"), button -> close()).dimensions(cx + 3, cy + 18, 97, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 46, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, summary, width / 2, height / 2 - 30, 0xFFB0B8C8);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void save() {
        if (done) {
            return;
        }
        done = true;
        onSave.accept(nameField.getText());
        super.close();
    }

    @Override
    public void close() {
        if (!done) {
            done = true;
            onDiscard.run();
        }
        super.close();
    }
}
