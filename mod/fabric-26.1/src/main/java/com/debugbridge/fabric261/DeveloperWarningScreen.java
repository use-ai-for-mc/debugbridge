package com.debugbridge.fabric261;

import com.debugbridge.core.BridgeConfig;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Warning screen shown on first launch to confirm this is a developer tool.
 * The user must click "I Understand" to enable the mod.
 */
public class DeveloperWarningScreen extends Screen {

    private static final Component TITLE = Component.literal("DebugBridge - Developer Tool Warning");

    private static final String[] WARNING_LINES = {
        "",
        "DebugBridge is a DEVELOPER TOOL that exposes a WebSocket server",
        "allowing external programs to execute code inside Minecraft.",
        "",
        "This mod is intended for:",
        "  - Mod developers debugging their mods",
        "  - AI agent integration (Claude Code, etc.)",
        "  - Automated testing and scripting",
        "",
        "This mod is NOT intended for:",
        "  - Regular gameplay",
        "  - Use on public servers (client-side only anyway)",
        "  - Users who don't understand the security implications",
        "",
        "The WebSocket server binds to localhost only (127.0.0.1),",
        "so only programs on your computer can connect.",
        "",
        "By clicking 'I Understand', you acknowledge that:",
        "  1. You are a developer or advanced user",
        "  2. You understand this mod can execute arbitrary code",
        "  3. You will not ask for support for non-developer use cases",
        "",
    };

    private final BridgeConfig config;
    private final Consumer<Boolean> onComplete;

    /**
     * @param config     The config to save acceptance to
     * @param onComplete Callback with true if accepted, false if declined
     */
    public DeveloperWarningScreen(BridgeConfig config, Consumer<Boolean> onComplete) {
        super(TITLE);
        this.config = config;
        this.onComplete = onComplete;
    }

    @Override
    protected void init() {
        int leftPadding = 40;
        int contentWidth = Math.max(120, this.width - leftPadding * 2);

        this.addRenderableOnly(new StringWidget(0, 15, this.width, 12, this.title, this.font));
        this.addRenderableOnly(
                new MultiLineTextWidget(leftPadding, 35, Component.literal(String.join("\n", WARNING_LINES)), this.font)
                        .setMaxWidth(contentWidth));

        int buttonWidth = 150;
        int buttonHeight = 20;
        int spacing = 10;
        int totalWidth = buttonWidth * 2 + spacing;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.height - 40;

        this.addRenderableWidget(Button.builder(Component.literal("I Understand - Enable Mod"), button -> {
                    config.developerModeAccepted = true;
                    config.save();
                    onComplete.accept(true);
                })
                .bounds(startX, buttonY, buttonWidth, buttonHeight)
                .build());

        this.addRenderableWidget(
                Button.builder(Component.literal("Cancel - Disable Mod"), button -> onComplete.accept(false))
                        .bounds(startX + buttonWidth + spacing, buttonY, buttonWidth, buttonHeight)
                        .build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
