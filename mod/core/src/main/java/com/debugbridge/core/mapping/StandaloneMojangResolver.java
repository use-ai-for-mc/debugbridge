package com.debugbridge.core.mapping;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * A standalone Mojang mapping resolver that works without Fabric's runtime.
 * Uses only the parsed ProGuard mappings. This is suitable for:
 * - Testing outside of Minecraft
 * - Environments where Fabric's MappingResolver isn't available
 * <p>
 * For actual Fabric runtime use, a FabricMojangResolver would chain
 * ProGuard (mojang→obfuscated) with Fabric's resolver (obfuscated→intermediary).
 */
public class StandaloneMojangResolver implements MappingResolver {
    private final String version;
    private final ParsedMappings mappings;
    
    public StandaloneMojangResolver(String version, ParsedMappings mappings) {
        this.version = version;
        this.mappings = mappings;
    }
    
    @Override
    public String resolveClass(String mojangClassName) {
        return mappings.classes.getOrDefault(mojangClassName, mojangClassName);
    }
    
    @Override
    public String resolveField(String mojangClassName, String mojangFieldName) {
        Map<String, String> classFields = mappings.fields.get(mojangClassName);
        if (classFields == null) return mojangFieldName;
        return classFields.getOrDefault(mojangFieldName, mojangFieldName);
    }
    
    @Override
    public String resolveMethod(String mojangClassName, String mojangMethodName, String[] mojangParamTypes) {
        Map<String, String> classMethods = mappings.methods.get(mojangClassName);
        if (classMethods == null) return mojangMethodName;
        
        // Try exact match with param types if provided
        if (mojangParamTypes != null) {
            String key = mojangMethodName + "(" + String.join(",", mojangParamTypes) + ")";
            String result = classMethods.get(key);
            if (result != null) return result;
        }
        
        // Fallback: find any method with this name (first overload)
        String prefix = mojangMethodName + "(";
        for (Map.Entry<String, String> entry : classMethods.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return entry.getValue();
            }
        }
        
        return mojangMethodName;
    }
    
    @Override
    public String unresolveClass(String runtimeClassName) {
        return mappings.classesReverse.getOrDefault(runtimeClassName, runtimeClassName);
    }
    
    @Override
    public Collection<String> getAllClassNames() {
        return mappings.classes.keySet();
    }
    
    @Override
    public Collection<String> getFieldNames(String mojangClassName) {
        Map<String, String> classFields = mappings.fields.get(mojangClassName);
        return classFields != null ? classFields.keySet() : Collections.emptyList();
    }
    
    @Override
    public Collection<String> getMethodSignatures(String mojangClassName) {
        Map<String, String> classMethods = mappings.methods.get(mojangClassName);
        return classMethods != null ? classMethods.keySet() : Collections.emptyList();
    }
    
    @Override
    public String getVersion() {
        return version;
    }
    
    @Override
    public boolean isObfuscated() {
        return true;
    }
}
