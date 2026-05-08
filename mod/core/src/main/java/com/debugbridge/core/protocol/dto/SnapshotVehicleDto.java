package com.debugbridge.core.protocol.dto;

/**
 * The entity the player is currently riding, surfaced inside
 * {@code snapshot.player.vehicle}.
 *
 * <p>{@code type} carries the runtime class name; the handler runs it through
 * {@code MappingResolver.unresolveClass} before serialization.
 */
public final class SnapshotVehicleDto {
    public int entityId;
    public String type;
}
