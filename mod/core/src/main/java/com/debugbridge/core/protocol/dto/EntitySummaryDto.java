package com.debugbridge.core.protocol.dto;

/**
 * One entry in the {@code nearbyEntities} list.
 *
 * <p>{@code type} is populated with the runtime entity class name; the handler
 * applies {@code MappingResolver.unresolveClass} before serialization. All
 * optional fields drop on the wire when null.
 */
public final class EntitySummaryDto {
    public int id;
    public String type;
    public double distance;
    public double x;
    public double y;
    public double z;
    public String customName;
    public String typeId;
    public EntityPrimaryEquipmentDto primaryEquipment;
}
