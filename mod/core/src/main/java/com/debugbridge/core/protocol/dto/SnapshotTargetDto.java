package com.debugbridge.core.protocol.dto;

/**
 * The crosshair target of the local player, surfaced inside
 * {@code snapshot.target} when the player is aiming at something.
 *
 * <p>The target is one of two kinds, signalled by {@code type}:
 * <ul>
 *   <li>{@code "block"} — populates {@code x}, {@code y}, {@code z}, {@code face}.
 *   <li>{@code "entity"} — populates {@code entityId}, {@code entityType}.
 * </ul>
 *
 * <p>{@code entityType} carries the runtime class name; the handler runs it
 * through the mapping resolver. Mutually-exclusive fields drop on the wire
 * via the omit-nulls Gson when the other branch is taken.
 */
public final class SnapshotTargetDto {
    public String type; // "block" or "entity"
    public Integer x; // block branch
    public Integer y;
    public Integer z;
    public String face; // block branch — "north"/"south"/etc.
    public Integer entityId; // entity branch
    public String entityType; // entity branch — runtime class, mapped by handler
}
