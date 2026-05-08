package com.debugbridge.core.protocol.dto;

/**
 * One slot's worth of equipment in {@code entityDetails.equipment}, which is
 * shaped as {@code {SLOT_NAME: {itemId, damage?, maxDamage?, name?}}}.
 *
 * <p>No {@code count} field by design: a worn equipment slot always holds
 * exactly one stack, and the historical wire shape omitted it. {@code itemId}
 * uses {@code Item#getDescriptionId()}.
 */
public final class EntityEquipmentItemDto {
    public String itemId;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
