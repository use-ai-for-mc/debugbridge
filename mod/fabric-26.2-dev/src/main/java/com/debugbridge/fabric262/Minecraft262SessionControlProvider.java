package com.debugbridge.fabric262;

import com.debugbridge.core.session.SessionControlProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Session control for the 26.2 snapshot. Every operation queues onto the game
 * thread and completes asynchronously — the bridge response only acknowledges
 * the request; callers poll {@code snapshot} / {@code screenInspect} for the
 * outcome.
 *
 * <p>Written against the 1.21.x connect/disconnect API; the snapshot may have
 * drifted (it already renamed {@code mc.screen} to {@code mc.gui.screen()}),
 * so expect to adjust call sites on the first 26.2 compile.
 */
public class Minecraft262SessionControlProvider implements SessionControlProvider {

    @Override
    public void disconnect() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            leaveWorld(mc);
            // 26.x: screen ownership moved from Minecraft to Gui.
            mc.gui.setScreen(new TitleScreen());
        });
    }

    @Override
    public void joinServer(String address, boolean acceptResourcePacks) {
        // Validate eagerly, on the request thread, so a malformed address fails
        // the request instead of dying silently on the game thread.
        if (!ServerAddress.isValidAddress(address)) {
            throw new IllegalArgumentException("invalid server address: " + address);
        }
        ServerAddress addr = ServerAddress.parseString(address);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            leaveWorld(mc);
            ServerData data = new ServerData("DebugBridge", address, ServerData.Type.OTHER);
            if (acceptResourcePacks) {
                // Pre-accept the server resource pack so the join flow doesn't
                // stall on the confirmation prompt.
                data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
            }
            ConnectScreen.startConnecting(new TitleScreen(), mc, addr, data, false, null);
        });
    }

    @Override
    public void quit() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(mc::stop);
    }

    /** Tear down the current world, mirroring the pause-screen disconnect flow. */
    private static void leaveWorld(Minecraft mc) {
        if (mc.level != null) {
            // 26.x (same as 1.21.11): one call tears down the level, shows the
            // saving/progress screen, and lands on the title (or multiplayer) screen.
            mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
    }
}
