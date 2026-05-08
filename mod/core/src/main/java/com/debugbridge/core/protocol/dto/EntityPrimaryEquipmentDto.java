package com.debugbridge.core.protocol.dto;

/**
 * The first-found item carried by a nearby entity, used by
 * {@code nearbyEntities} for thumbnail rendering when {@code includeIcons=true}.
 *
 * <p>{@code slot} is one of the {@code EquipmentSlot} names ({@code HEAD},
 * {@code MAINHAND}, etc.) for living entities, or the synthetic literals
 * {@code "FRAME"} (item frames) and {@code "DISPLAY"} (1.21.11 item displays).
 * {@code itemId} is a registry key like {@code minecraft:iron_helmet} —
 * differs intentionally from the {@code Item#getDescriptionId()} convention
 * used in {@code blockDetails} and {@code equipment}; tracked for follow-up
 * convergence.
 */
public final class EntityPrimaryEquipmentDto {
    public String slot;
    public String itemId;
}
