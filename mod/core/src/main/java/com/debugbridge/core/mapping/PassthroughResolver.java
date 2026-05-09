package com.debugbridge.core.mapping;

import java.util.Collection;
import java.util.Collections;

/**
 * Trivial resolver for unobfuscated versions (e.g., 26.1).
 * All names pass through unchanged.
 */
public class PassthroughResolver implements MappingResolver {
    private final String version;

    public PassthroughResolver(String version) {
        this.version = version;
    }

    @Override
    public String resolveClass(String name) {
        return name;
    }

    @Override
    public String resolveField(String cls, String name) {
        return name;
    }

    @Override
    public String resolveMethod(String cls, String name, String[] params) {
        return name;
    }

    @Override
    public String unresolveClass(String name) {
        return name;
    }

    @Override
    public Collection<String> getAllClassNames() {
        // For passthrough, we don't have a pre-built list.
        // Scanning classloaders would be needed at runtime.
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getFieldNames(String mojangClassName) {
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getMethodSignatures(String mojangClassName) {
        return Collections.emptyList();
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean isObfuscated() {
        return false;
    }
}
