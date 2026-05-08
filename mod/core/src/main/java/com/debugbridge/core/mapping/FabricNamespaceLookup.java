package com.debugbridge.core.mapping;

/**
 * SPI by which {@link FabricMojangResolver} (kernel) reaches Fabric Loader's
 * {@code MappingResolver} (per-version adapter). Captures the four-method
 * Fabric API surface needed for Mojang↔runtime translation, with no compile-time
 * dependency on {@code net.fabricmc.*} — the kernel can therefore live in
 * {@code mod/core/} and be unit-tested with stubs.
 *
 * <p>Implementations live in each {@code mod/fabric-X.Y/} module and typically
 * delegate straight to {@code FabricLoader.getInstance().getMappingResolver()}.
 *
 * <h3>Namespace conventions</h3>
 * The "obfuscated" form below corresponds to Fabric's {@code "official"}
 * namespace (the names Mojang ships in the proprietary jars). The "runtime"
 * form is whatever Fabric's loader actually loads classes under at execution
 * time — typically the {@code "intermediary"} namespace produced by
 * fabric-loom remapping.
 */
public interface FabricNamespaceLookup {

    /**
     * Translate an obfuscated class name to its runtime intermediary name.
     * Returns the input unchanged when no mapping is known (matches Fabric's
     * own {@code mapClassName} echo-on-miss behavior).
     */
    String runtimeForObfuscatedClass(String obfClassName);

    /**
     * Translate an obfuscated method (named on an obfuscated owner class with
     * an obfuscated JVM descriptor) to its runtime intermediary name.
     * Returns {@code null} when Fabric has no mapping for that owner+name+desc
     * combination — caller can then walk parent classes/interfaces.
     */
    String runtimeForObfuscatedMethod(String obfOwner, String obfMethodName, String obfJvmDesc);

    /**
     * Translate an obfuscated field (named on an obfuscated owner class with
     * an obfuscated JVM type descriptor) to its runtime intermediary name.
     * Returns {@code null} when Fabric has no mapping for the field.
     */
    String runtimeForObfuscatedField(String obfOwner, String obfFieldName, String obfJvmDesc);

    /**
     * Reverse-translate a runtime intermediary class name back to its
     * obfuscated form. Returns the input unchanged when no mapping is known.
     */
    String obfuscatedForRuntimeClass(String runtimeClassName);
}
