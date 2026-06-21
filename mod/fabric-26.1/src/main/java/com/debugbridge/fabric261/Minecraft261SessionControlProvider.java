package com.debugbridge.fabric261;

import com.debugbridge.core.block.ClientBlockGlowManager;
import com.debugbridge.core.entity.ClientEntityGlowManager;
import com.debugbridge.core.session.ClientSettleGate;
import com.debugbridge.core.session.SessionControlProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Session control for exact 26.1. {@code disconnect} and {@code quit}
 * queue onto the game thread and complete asynchronously — their bridge
 * response only acknowledges the request; callers poll {@code snapshot} /
 * {@code screenInspect} for the outcome. {@code joinServer} additionally waits
 * (bounded) for the client to settle before connecting, so its ack means the
 * connect attempt actually started — see {@link ClientSettleGate}.
 */
public class Minecraft261SessionControlProvider implements SessionControlProvider {

    // Lambdas (not method refs) so Minecraft.getInstance() resolves per call,
    // not at provider construction during mod init.
    private final ClientSettleGate settleGate = new ClientSettleGate(
            task -> Minecraft.getInstance().execute(task),
            () -> Minecraft.getInstance().getOverlay() == null);

    @Override
    public void disconnect() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            clearDebugHighlights();
            leaveWorld(mc);
        });
    }

    @Override
    public void joinServer(String address, boolean acceptResourcePacks) throws Exception {
        // Validate eagerly, on the request thread, so a malformed address fails
        // the request instead of dying silently on the game thread.
        if (!ServerAddress.isValidAddress(address)) {
            throw new IllegalArgumentException("invalid server address: " + address);
        }
        ServerAddress addr = ServerAddress.parseString(address);
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            throw new IllegalStateException("26.1 joinServer must be requested off the Minecraft client thread; "
                    + "it blocks while ClientSettleGate polls the client thread");
        }
        // Connecting while the startup/reload overlay is still up silently
        // drops the server resource pack (its application is itself a reload
        // and gets replaced by the in-flight one) — reachable whenever a join
        // is issued seconds after launch, since the bridge port opens before
        // the initial reload finishes. Defer until the client settles.
        settleGate.runWhenSettled(
                () -> {
                    clearDebugHighlights();
                    leaveWorld(mc);
                    ServerData data = new ServerData("DebugBridge", address, ServerData.Type.OTHER);
                    if (acceptResourcePacks) {
                        // Pre-accept the server resource pack so the join flow doesn't
                        // stall on the confirmation prompt.
                        data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                    }
                    ConnectScreen.startConnecting(new TitleScreen(), mc, addr, data, false, null);
                },
                ClientSettleGate.DEFAULT_SETTLE_TIMEOUT_MS);
    }

    @Override
    public void quit() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            clearDebugHighlights();
            mc.stop();
        });
    }

    /** Tear down the current world, mirroring the pause-screen disconnect flow. */
    private static void leaveWorld(Minecraft mc) {
        if (mc.level != null) {
            // 26.x (same as 1.21.11): one call tears down the level, shows the
            // saving/progress screen, and lands on the title (or multiplayer) screen.
            mc.disconnectFromWorld(Component.translatable("menu.savingLevel"));
        }
    }

    private static void clearDebugHighlights() {
        ClientEntityGlowManager.clear();
        ClientBlockGlowManager.clear();
    }
}
