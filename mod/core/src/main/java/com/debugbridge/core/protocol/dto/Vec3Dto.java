package com.debugbridge.core.protocol.dto;

/**
 * 3-component vector wire shape: {@code {x, y, z}}.
 *
 * <p>Used for nested vectors that benefit from a structured shape — e.g.
 * {@code snapshot.player.velocity} and {@code snapshot.player.look}. Top-level
 * x/y/z fields (e.g. on entity summaries) stay flat for parity with existing
 * clients.
 */
public final class Vec3Dto {
    public double x;
    public double y;
    public double z;

    public Vec3Dto() {}

    public Vec3Dto(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
