package com.debugbridge.core.block;

import com.debugbridge.core.protocol.dto.BlockDetailsDto;
import com.debugbridge.core.protocol.dto.BlockSummaryDto;

import java.util.List;

/**
 * Provides a fast, native query of nearby block entities (signs, chests,
 * banners, beacons, etc.) — the blocks worth browsing for debugging.
 *
 * <p>Plain-terrain blocks (dirt, stone, etc.) are intentionally excluded; this
 * provider only surfaces blocks that carry per-instance state via a
 * BlockEntity.
 *
 * <p>Implementations populate {@code dto.type} with the raw runtime class
 * name; {@link com.debugbridge.core.server.BridgeServer} runs it through the
 * mapping resolver before serialization.
 */
public interface NearbyBlocksProvider {

    /**
     * Get block entities within the given range of the local player, sorted
     * nearest-first.
     *
     * @param range maximum distance in blocks
     * @param limit maximum number of entries to return
     * @return list of block-entity summaries
     * @throws Exception on query failure
     */
    List<BlockSummaryDto> getNearbyBlocks(double range, int limit) throws Exception;

    /**
     * Get detailed information about a specific block entity at (x, y, z).
     *
     * @return populated DTO, or {@code null} when there is no block entity at
     *         that position. The handler converts {@code null} to the on-wire
     *         {@code {"gone": true}} shape.
     * @throws Exception on query failure
     */
    BlockDetailsDto getBlockDetails(int x, int y, int z) throws Exception;
}
