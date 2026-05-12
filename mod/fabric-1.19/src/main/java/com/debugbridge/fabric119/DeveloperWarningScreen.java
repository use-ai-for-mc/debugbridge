package com.debugbridge.fabric119;

import com.debugbridge.core.BridgeConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

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
        int buttonWidth = 150;
        int buttonHeight = 20;
        int spacing = 10;
        int totalWidth = buttonWidth * 2 + spacing;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.height - 40;
        
        // "I Understand" button (1.19 uses constructor, not builder)
        this.addRenderableWidget(new Button(
                startX, buttonY, buttonWidth, buttonHeight,
                Component.literal("I Understand - Enable Mod"),
                button -> {
                    config.developerModeAccepted = true;
                    config.save();
                    onComplete.accept(true);
                }
        ));
        
        // "Cancel" button
        this.addRenderableWidget(new Button(
                startX + buttonWidth + spacing, buttonY, buttonWidth, buttonHeight,
                Component.literal("Cancel - Disable Mod"),
                button -> onComplete.accept(false)
        ));
    }
    
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        // Darken background
        fill(poseStack, 0, 0, this.width, this.height, 0xC0000000);
        
        // Draw title
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, 15, 0xFFFF5555);
        
        // Draw warning lines (left-aligned with padding)
        int y = 35;
        int leftPadding = 40;
        for (String line : WARNING_LINES) {
            if (line.isEmpty()) {
                y += 5;
            } else {
                drawString(poseStack, this.font, line, leftPadding, y, 0xFFFFFFFF);
                y += 11;
            }
        }
        
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
    
    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Force user to make a choice
    }
}
