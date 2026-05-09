package com.debugbridge.core.protocol.dto;

/**
 * The first-found item carried by a nearby entity, used by
 * {@code nearbyEntities} for thumbnail rendering when {@code includeIcons=true}.
 *
 * <p>{@code slot} is one of the {@code EquipmentSlot} names ({@code HEAD},
 * {@code MAINHAND}, etc.) for living entities, or the synthetic literals
 * {@code "FRAME"} (item frames) and {@code "DISPLAY"} (1.21.11 item displays).
 * {@code itemId} is the canonical registry-key form (e.g.
 * {@code "minecraft:iron_helmet"}), shared by every other {@code itemId} on
 * the wire.
 */
public final class EntityPrimaryEquipmentDto {
    public String slot;
    public String itemId;
}
