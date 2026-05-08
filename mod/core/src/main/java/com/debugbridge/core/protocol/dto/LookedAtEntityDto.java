package com.debugbridge.core.protocol.dto;

/**
 * Wire shape for the {@code lookedAtEntity} endpoint.
 *
 * <p>{@code entityId} is explicitly nullable on the wire (clients distinguish
 * "no entity in range" from a malformed response by the presence of the key
 * with a null value). Serialized via the keep-nulls Gson in {@link
 * com.debugbridge.core.server.BridgeServer}.
 */
public final class LookedAtEntityDto {
    public Integer entityId;

    public LookedAtEntityDto(Integer entityId) {
        this.entityId = entityId;
    }
}
