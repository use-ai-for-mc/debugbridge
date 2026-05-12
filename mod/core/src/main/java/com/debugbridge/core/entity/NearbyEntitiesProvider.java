package com.debugbridge.core.entity;

import com.debugbridge.core.protocol.dto.EntityDetailsDto;
import com.debugbridge.core.protocol.dto.EntitySummaryDto;

import java.util.List;

/**
 * Provides a fast, native query of nearby entities.
 * Each version-specific mod implements this using version-appropriate APIs.
 */
public interface NearbyEntitiesProvider {
    
    /**
     * Get entities within the given range of the local player, sorted
     * nearest-first.
     *
     * <p>Implementations populate {@code dto.type} with the runtime class name
     * (e.g. {@code net.minecraft.class_1531}); {@link
     * com.debugbridge.core.server.BridgeServer} runs it through the mapping
     * resolver before serialization.
     *
     * @param range maximum distance in blocks
     * @param limit maximum number of entities to return
     * @return list of summaries
     * @throws Exception if the query fails (e.g. player not in world)
     */
    List<EntitySummaryDto> getNearbyEntities(double range, int limit) throws Exception;
    
    /**
     * Get detailed information about a specific entity by its runtime ID.
     *
     * <p>{@code dto.type}, {@code dto.vehicle}, and each entry of
     * {@code dto.passengers} carry runtime class names from the provider; the
     * handler maps them.
     *
     * @param entityId the entity's runtime ID (from {@code Entity.getId()})
     * @return populated DTO, or {@code null} if the entity is not found. The
     * handler converts {@code null} to {@link EntityDetailsDto#gone()}.
     * @throws Exception if the query fails
     */
    EntityDetailsDto getEntityDetails(int entityId) throws Exception;
}
