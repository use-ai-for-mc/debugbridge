package com.debugbridge.fabric119;

import com.debugbridge.core.mapping.FabricNamespaceLookup;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 1.19 adapter implementing {@link FabricNamespaceLookup} on top of
 * Fabric's runtime mapping resolver. Pure delegation — the entire kernel
 * resolver body lives in {@link com.debugbridge.core.mapping.FabricMojangResolver}.
 *
 * <p>Identical in shape to the 1.21.11 sibling; the duplication is intentional
 * because each version module must own its compile-time dependency on
 * {@code net.fabricmc.*}.
 */
public final class FabricLoaderNamespaceLookup implements FabricNamespaceLookup {
    private final net.fabricmc.loader.api.MappingResolver fabric =
        FabricLoader.getInstance().getMappingResolver();

    @Override
    public String runtimeForObfuscatedClass(String obfClassName) {
        return fabric.mapClassName("official", obfClassName);
    }

    @Override
    public String runtimeForObfuscatedMethod(String obfOwner, String obfMethodName, String obfJvmDesc) {
        String mapped = fabric.mapMethodName("official", obfOwner, obfMethodName, obfJvmDesc);
        // Fabric echoes the input on miss; the SPI contract is null-on-miss.
        return mapped.equals(obfMethodName) ? null : mapped;
    }

    @Override
    public String runtimeForObfuscatedField(String obfOwner, String obfFieldName, String obfJvmDesc) {
        String mapped = fabric.mapFieldName("official", obfOwner, obfFieldName, obfJvmDesc);
        return mapped.equals(obfFieldName) ? null : mapped;
    }

    @Override
    public String obfuscatedForRuntimeClass(String runtimeClassName) {
        return fabric.unmapClassName("official", runtimeClassName);
    }
}
