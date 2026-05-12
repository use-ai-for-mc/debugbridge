package com.debugbridge.core.entity;

/**
 * Raycast from the player's eye in the look direction and report the first
 * entity hit. Lets the web UI follow the player's gaze to auto-select
 * whatever they're aiming at. Each version-specific mod provides its own
 * implementation since the raycast helpers differ between Mojang mapping
 * revisions.
 */
public interface LookedAtEntityProvider {
    
    /**
     * @param range maximum raycast distance in blocks
     * @return the runtime ID of the entity being looked at, or {@code null}
     * if the player is not in a world or nothing is aimed at within
     * {@code range}
     */
    Integer getLookedAtEntity(double range) throws Exception;
}
