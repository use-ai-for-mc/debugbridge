package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.script.ScriptRuntime;
import com.debugbridge.core.server.ResultSerializer;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the serializer's shallow field summary to <em>Mojang</em> field names
 * on simulated obfuscated builds (same fake-resolver pattern as
 * {@link GroovyObfuscatedDispatchTest}). Regression: returning a Minecraft
 * object from {@code execute} leaked intermediary names
 * ({@code "fields": {"field_1352": 1, ...}} instead of {@code x/y/z}).
 */
class ResultSerializerTest {

    /** Stands in for an obfuscated Vec3: runtime field names differ from Mojang names. */
    public static class ObfVec {
        public final double f_x;
        public final double f_y;
        public final double f_z;

        public ObfVec(double x, double y, double z) {
            this.f_x = x;
            this.f_y = y;
            this.f_z = z;
        }
    }

    /** A class the resolver knows nothing about — names must pass through untouched. */
    public static class Unmapped {
        public int count = 7;
    }

    private static final String VEC = "fake.Vec";

    private static PassthroughResolver obfResolver() {
        return new PassthroughResolver("test") {
            @Override
            public String resolveClass(String mojangName) {
                return VEC.equals(mojangName) ? ObfVec.class.getName() : mojangName;
            }

            @Override
            public String unresolveClass(String runtimeName) {
                return ObfVec.class.getName().equals(runtimeName) ? VEC : runtimeName;
            }

            @Override
            public String resolveField(String cls, String name) {
                if (VEC.equals(cls)) {
                    switch (name) {
                        case "x":
                            return "f_x";
                        case "y":
                            return "f_y";
                        case "z":
                            return "f_z";
                        default:
                    }
                }
                return name;
            }

            @Override
            public java.util.Collection<String> getFieldNames(String cls) {
                return VEC.equals(cls) ? List.of("x", "y", "z") : List.of();
            }
        };
    }

    private ScriptRuntime runtime;
    private ResultSerializer serializer;

    @BeforeEach
    void setup() {
        PassthroughResolver resolver = obfResolver();
        ObjectRefStore refs = new ObjectRefStore();
        runtime = new ScriptRuntime(resolver, new DirectDispatcher(), refs);
        serializer = new ResultSerializer(resolver, refs, runtime.getBridge());
    }

    @Test
    void fieldSummaryUsesMojangNamesOnObfuscatedClass() {
        JsonObject obj = serializer.serialize(new ObfVec(1, 2, 3)).getAsJsonObject();

        assertEquals("object", obj.get("type").getAsString());
        assertEquals(VEC, obj.get("className").getAsString());

        JsonObject fields = obj.getAsJsonObject("fields");
        assertEquals(1.0, fields.get("x").getAsDouble());
        assertEquals(2.0, fields.get("y").getAsDouble());
        assertEquals(3.0, fields.get("z").getAsDouble());
        assertFalse(fields.has("f_x"), "intermediary name leaked: " + fields);
    }

    @Test
    void executeReturningWrappedObjectSerializesWithMojangFieldNames() {
        var result = runtime.execute("return java.type('fake.Vec')(1, 2, 3)");
        assertTrue(result.isSuccess(), "Error: " + result.error);

        JsonObject obj = serializer.serialize(result.returnValue).getAsJsonObject();
        assertEquals(VEC, obj.get("className").getAsString());

        JsonObject fields = obj.getAsJsonObject("fields");
        assertEquals(1.0, fields.get("x").getAsDouble());
        assertFalse(fields.has("f_x"), "intermediary name leaked: " + fields);
    }

    @Test
    void unmappedClassKeepsRuntimeFieldNames() {
        JsonObject obj = serializer.serialize(new Unmapped()).getAsJsonObject();

        JsonObject fields = obj.getAsJsonObject("fields");
        assertEquals(7, fields.get("count").getAsInt());
    }
}
