package com.debugbridge.core.protocol.dto;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * Wire shape for the {@code nearbyEntities} endpoint. {@code entities} and
 * {@code count} are always emitted; {@code icons} is added by the handler when
 * the request set {@code includeIcons=true}.
 *
 * <p>The {@code icons} pass-through is the same shape as
 * {@link ScreenInspectDto#icons} — owned by the texture provider.
 */
public final class NearbyEntitiesDto {
    public List<EntitySummaryDto> entities;
    public int count;
    public JsonElement icons;
    
    public NearbyEntitiesDto(List<EntitySummaryDto> entities) {
        this.entities = entities;
        this.count = entities.size();
    }
}
