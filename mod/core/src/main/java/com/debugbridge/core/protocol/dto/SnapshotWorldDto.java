package com.debugbridge.core.protocol.dto;

/**
 * World-state portion of the {@code snapshot} response. Always emitted when
 * a level is loaded; null and omitted on the wire otherwise.
 */
public final class SnapshotWorldDto {
    public long dayTime;
    public boolean isRaining;
    public boolean isThundering;
}
