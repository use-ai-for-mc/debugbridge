package com.debugbridge.core;

import com.debugbridge.core.mapping.FabricMojangResolver;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.mapping.ParsedMappings;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link FabricMojangResolver}. These tests were impossible
 * before Phase 3 of {@code MULTIVERSION_PLAN.md}: the resolver used to live
 * in each version module and pulled in {@code net.fabricmc.*} statically, so
 * exercising it in core's pure-JVM test suite required either reflection
 * tricks or a running Fabric loader. With the {@link FabricNamespaceLookup}
 * SPI extracted, the kernel logic is now testable with a tiny in-memory stub.
 *
 * <p>Each test feeds a deterministic ProGuard-style {@link ParsedMappings} +
 * a stubbed Fabric lookup, then asserts the resolver's externally-visible
 * behavior.
 */
class FabricMojangResolverTest {
    
    @Test
    void resolveClassUsesProGuardThenFabric() {
        MappingsBuilder mb = new MappingsBuilder();
        mb.classes.put("net.minecraft.world.entity.Entity", "ahu");
        
        StubLookup lookup = new StubLookup();
        lookup.classes.put("ahu", "net.minecraft.class_1297");
        
        FabricMojangResolver r = new FabricMojangResolver("test", mb.build(), lookup);
        
        assertEquals("net.minecraft.class_1297", r.resolveClass("net.minecraft.world.entity.Entity"));
        // Unknown class → input passes through unchanged.
        assertEquals("com.example.Foo", r.resolveClass("com.example.Foo"));
    }
    
    @Test
    void unresolveClassReversesViaSpiAndProGuardReverse() {
        MappingsBuilder mb = new MappingsBuilder();
        mb.classes.put("net.minecraft.world.entity.Entity", "ahu");
        mb.classesReverse.put("ahu", "net.minecraft.world.entity.Entity");
        
        StubLookup lookup = new StubLookup();
        lookup.classes.put("ahu", "net.minecraft.class_1297");
        lookup.classesReverse.put("net.minecraft.class_1297", "ahu");
        
        FabricMojangResolver r = new FabricMojangResolver("test", mb.build(), lookup);
        
        // class_1297 → "ahu" (via SPI) → Entity (via ProGuard reverse map).
        assertEquals("net.minecraft.world.entity.Entity",
                r.unresolveClass("net.minecraft.class_1297"));
        // Unknown runtime → echoed.
        assertEquals("net.minecraft.class_9999", r.unresolveClass("net.minecraft.class_9999"));
    }
    
    @Test
    void resolveMethodWithExactArgsHitsFabricLookup() {
        MappingsBuilder mb = new MappingsBuilder();
        mb.classes.put("net.minecraft.world.entity.Entity", "ahu");
        Map<String, String> entityMethods = new LinkedHashMap<>();
        entityMethods.put("getId()", "ag");
        mb.methods.put("net.minecraft.world.entity.Entity", entityMethods);
        Map<String, String> entityDescs = new LinkedHashMap<>();
        // ProGuard descriptors use source-style names ("int", "void"), not JVM
        // signatures ("I", "V"). The kernel converts them via toJvmDescriptor.
        entityDescs.put("getId()", "()int");
        mb.methodDescriptors.put("net.minecraft.world.entity.Entity", entityDescs);
        
        StubLookup lookup = new StubLookup();
        // The kernel asks the SPI with the obfuscated owner + obfuscated method name +
        // obfuscated JVM descriptor. For getId: owner=ahu, name=ag, desc=()I (no class refs).
        lookup.methods.put("ahu.ag()I", "method_5628");
        
        FabricMojangResolver r = new FabricMojangResolver("test", mb.build(), lookup);
        
        assertEquals("method_5628",
                r.resolveMethod("net.minecraft.world.entity.Entity", "getId", new String[0]));
    }
    
    @Test
    void resolveMethodFallsBackToInputWhenNothingMatches() {
        // Class not in mappings → resolver returns the Mojang name unchanged.
        FabricMojangResolver r = new FabricMojangResolver("test",
                new MappingsBuilder().empty(), new StubLookup());
        assertEquals("foo",
                r.resolveMethod("com.example.UnknownClass", "foo", new String[0]));
    }
    
    @Test
    void resolveFieldUsesObfuscatedDescriptorWhenAvailable() {
        MappingsBuilder mb = new MappingsBuilder();
        mb.classes.put("net.minecraft.world.entity.Entity", "ahu");
        Map<String, String> entityFields = new LinkedHashMap<>();
        entityFields.put("level", "p");
        mb.fields.put("net.minecraft.world.entity.Entity", entityFields);
        Map<String, String> entityFieldTypes = new LinkedHashMap<>();
        entityFieldTypes.put("level", "net.minecraft.world.level.Level");
        mb.fieldTypes.put("net.minecraft.world.entity.Entity", entityFieldTypes);
        
        // Level class is also mapped; the kernel obfuscates the type name.
        mb.classes.put("net.minecraft.world.level.Level", "dhd");
        
        StubLookup lookup = new StubLookup();
        // Expected SPI call: owner=ahu, name=p, desc=Ldhd; (descriptor uses the obfuscated form).
        lookup.fields.put("ahu.pLdhd;", "field_6002");
        
        FabricMojangResolver r = new FabricMojangResolver("test", mb.build(), lookup);
        
        assertEquals("field_6002",
                r.resolveField("net.minecraft.world.entity.Entity", "level"));
    }
    
    @Test
    void resultsAreCachedAcrossCalls() {
        MappingsBuilder mb = new MappingsBuilder();
        mb.classes.put("net.minecraft.world.entity.Entity", "ahu");
        Map<String, String> entityMethods = new LinkedHashMap<>();
        entityMethods.put("tick()", "j");
        mb.methods.put("net.minecraft.world.entity.Entity", entityMethods);
        Map<String, String> entityDescs = new LinkedHashMap<>();
        entityDescs.put("tick()", "()void");
        mb.methodDescriptors.put("net.minecraft.world.entity.Entity", entityDescs);
        
        // Counting stub: returns the canned answer once, then null. If the
        // resolver re-asked the SPI on the second call, the second answer
        // would be null → resolveMethod would fall through to "tick".
        int[] hits = {0};
        StubLookup lookup = new StubLookup() {
            @Override
            public String runtimeForObfuscatedMethod(String owner, String name, String desc) {
                hits[0]++;
                // For tick: owner=ahu, name=j, desc=()V.
                return hits[0] == 1 && "ahu".equals(owner) && "j".equals(name) && "()V".equals(desc)
                        ? "method_xyz" : null;
            }
        };
        
        FabricMojangResolver r = new FabricMojangResolver("test", mb.build(), lookup);
        
        assertEquals("method_xyz",
                r.resolveMethod("net.minecraft.world.entity.Entity", "tick", new String[0]));
        assertEquals("method_xyz",
                r.resolveMethod("net.minecraft.world.entity.Entity", "tick", new String[0]),
                "Second call must hit the cache, not the SPI");
        assertEquals(1, hits[0], "SPI should be queried only once");
    }
    
    /**
     * In-memory stub of {@link FabricNamespaceLookup} backed by simple maps.
     * Non-final so individual tests can subclass to spy on call counts.
     */
    private static class StubLookup implements FabricNamespaceLookup {
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> classesReverse = new HashMap<>();
        // method/field maps keyed by "owner.name(desc)" → mapped runtime name.
        final Map<String, String> methods = new HashMap<>();
        final Map<String, String> fields = new HashMap<>();
        
        @Override
        public String runtimeForObfuscatedClass(String obf) {
            return classes.getOrDefault(obf, obf);
        }
        
        @Override
        public String runtimeForObfuscatedMethod(String owner, String name, String desc) {
            return methods.get(owner + "." + name + desc);
        }
        
        @Override
        public String runtimeForObfuscatedField(String owner, String name, String desc) {
            return fields.get(owner + "." + name + desc);
        }
        
        @Override
        public String obfuscatedForRuntimeClass(String runtime) {
            return classesReverse.getOrDefault(runtime, runtime);
        }
    }
    
    /**
     * Mutable scratchpad for building {@link ParsedMappings} in a test.
     */
    private static final class MappingsBuilder {
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> classesReverse = new HashMap<>();
        final Map<String, Map<String, String>> fields = new HashMap<>();
        final Map<String, Map<String, String>> methods = new HashMap<>();
        final Map<String, Map<String, String>> fieldTypes = new HashMap<>();
        final Map<String, Map<String, String>> methodDescriptors = new HashMap<>();
        
        ParsedMappings build() {
            return new ParsedMappings(classes, classesReverse, fields, methods, fieldTypes, methodDescriptors);
        }
        
        ParsedMappings empty() {
            return new ParsedMappings(Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap());
        }
    }
}
