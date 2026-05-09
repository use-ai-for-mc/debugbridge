package com.debugbridge.core.mapping;

import java.util.Collection;

/**
 * Resolves Mojang-mapped class/field/method names to runtime names.
 * Different implementations handle obfuscated (1.19) vs unobfuscated (26.1) versions.
 */
public interface MappingResolver {

    /**
     * Resolve a Mojang class name to the runtime class name.
     * Example (1.19): "net.minecraft.client.Minecraft" -> "net.minecraft.class_310"
     * Example (26.1): "net.minecraft.client.Minecraft" -> "net.minecraft.client.Minecraft"
     */
    String resolveClass(String mojangClassName);

    /**
     * Resolve a Mojang field name to the runtime field name.
     * Requires the owning class (Mojang name) for context.
     */
    String resolveField(String mojangClassName, String mojangFieldName);

    /**
     * Resolve a Mojang method name to the runtime method name.
     * Parameter types are Mojang names for overload disambiguation (may be null for best-effort).
     */
    String resolveMethod(String mojangClassName, String mojangMethodName, String[] mojangParamTypes);

    /**
     * Reverse lookup: given a runtime class name, return the Mojang name.
     */
    String unresolveClass(String runtimeClassName);

    /**
     * Get all known Mojang class names (for search).
     */
    Collection<String> getAllClassNames();

    /**
     * Get all known field names for a Mojang class.
     */
    Collection<String> getFieldNames(String mojangClassName);

    /**
     * Get all known method signatures for a Mojang class.
     */
    Collection<String> getMethodSignatures(String mojangClassName);

    /**
     * The Minecraft version this resolver is for.
     */
    String getVersion();

    /**
     * Whether this resolver actually translates names (false for passthrough).
     */
    boolean isObfuscated();
}
