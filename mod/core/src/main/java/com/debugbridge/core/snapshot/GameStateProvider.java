package com.debugbridge.core.snapshot;

import com.debugbridge.core.protocol.dto.SnapshotDto;

/**
 * Interface for capturing a snapshot of current game state.
 * Each version-specific mod provides its own implementation
 * since it has direct access to Minecraft classes.
 *
 * <p>Implementations populate {@code dto.player.vehicle.type} and
 * {@code dto.target.entityType} with raw runtime class names; {@link
 * com.debugbridge.core.server.BridgeServer} runs them through the mapping
 * resolver before serialization.
 */
public interface GameStateProvider {
    /**
     * Capture a snapshot of the current game state.
     * Called on the game thread.
     */
    SnapshotDto captureSnapshot();
}
