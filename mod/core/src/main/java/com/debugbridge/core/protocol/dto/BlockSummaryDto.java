package com.debugbridge.core.protocol.dto;

/**
 * One entry in the {@code nearbyBlocks} list.
 *
 * <p>{@code type} is populated by the provider with the raw runtime BlockEntity
 * class name; {@link com.debugbridge.core.server.BridgeServer} runs it through
 * {@code MappingResolver.unresolveClass} before serialization. {@code preview}
 * is optional — providers add it for signs (concatenated front lines) and
 * containers (filled/total fill ratio).
 */
public final class BlockSummaryDto {
    public int x;
    public int y;
    public int z;
    public double distance;
    public String type;
    public String blockId;
    public String preview;
}
