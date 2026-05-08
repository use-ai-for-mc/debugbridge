package com.debugbridge.core.protocol.dto;

/**
 * Wire shape for the {@code snapshot} endpoint.
 *
 * <p>{@code fps} is a stable {@code int} on both 1.19 and 1.21.11 (the 1.19
 * provider parses the leading number from {@code Minecraft.fpsString}; older
 * code leaked the raw debug string here). {@code version} is always emitted.
 *
 * <p>{@code player} is null when there is no local player (title screen,
 * world loading) — omit-nulls drops it on the wire. Older code emitted
 * {@code "player": "not in world"} as a string in this case; the typed shape
 * above is more defensive for clients (which now do a presence check).
 *
 * <p>{@code target} is null unless the player is aiming at something within
 * reach; omit-nulls drops it.
 */
public final class SnapshotDto {
    public SnapshotPlayerDto player;
    public SnapshotTargetDto target;
    public SnapshotWorldDto world;
    public int fps;
    public String version;
}
