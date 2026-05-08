package com.debugbridge.core.protocol.dto;

/**
 * One slot in a container screen.
 *
 * <p>{@code container} is populated by the provider with the runtime class
 * name; {@link com.debugbridge.core.server.BridgeServer} applies
 * {@code MappingResolver.unresolveClass} before serialization so the wire
 * always sees the Mojang name (e.g. {@code net.minecraft.world.SimpleContainer}
 * rather than the {@code class_1277} intermediary).
 *
 * <p>{@code item} is null for empty slots; omitted on the wire.
 */
public final class SlotDto {
    public int idx;
    public String container;
    public ItemStackDto item;
}
