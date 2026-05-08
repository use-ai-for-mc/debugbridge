package com.debugbridge.core.protocol.dto;

import java.util.List;

/**
 * Wire shape for the {@code nearbyBlocks} endpoint. Always emits both fields.
 * {@code count} is auto-derived from the list size at construction.
 */
public final class NearbyBlocksDto {
    public List<BlockSummaryDto> blocks;
    public int count;

    public NearbyBlocksDto(List<BlockSummaryDto> blocks) {
        this.blocks = blocks;
        this.count = blocks.size();
    }
}
