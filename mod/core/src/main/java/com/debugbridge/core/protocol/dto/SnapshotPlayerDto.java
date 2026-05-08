package com.debugbridge.core.protocol.dto;

/**
 * Player-state portion of the {@code snapshot} response. Always populated when
 * a local player exists; the wrapper {@link SnapshotDto#player} is null
 * (and dropped on the wire) otherwise.
 *
 * <p>{@code biome} is currently a stub field always emitting {@code ""}; tracked
 * separately for resolution (review-queue Theme 6 / dead-code).
 */
public final class SnapshotPlayerDto {
    public String name;
    public double x;
    public double y;
    public double z;
    public double yaw;
    public double pitch;
    public int hotbarSlot;
    public double health;
    public double maxHealth;
    public int food;
    public double saturation;
    public String dimension;
    public String biome;
    public Vec3Dto velocity;
    public Vec3Dto look;
    public SnapshotVehicleDto vehicle;
}
