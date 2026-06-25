package com.debugbridge.core.command;

/** Sends a Minecraft slash command as the local player. */
public interface CommandProvider {
    /**
     * Execute {@code command} without a leading slash. Implementations should
     * validate that the client is in-world and report failures by throwing.
     */
    void execute(String command) throws Exception;
}
