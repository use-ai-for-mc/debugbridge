package com.debugbridge.fabric261;

import com.debugbridge.core.command.CommandProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public class Minecraft261CommandProvider implements CommandProvider {
    @Override
    public void execute(String command) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            send(mc, command);
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                send(mc, command);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        waitFor(future);
    }

    private static void waitFor(CompletableFuture<Void> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private static void send(Minecraft mc, String command) {
        LocalPlayer player = mc.player;
        if (player == null) {
            throw new IllegalStateException("not in world");
        }
        player.connection.sendCommand(command);
    }
}
