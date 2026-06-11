package com.debugbridge.core.server;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.GroovyBridge;
import com.debugbridge.core.script.GroovyJavaClass;
import com.debugbridge.core.script.GroovyJavaObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

/**
 * Serializes script return values to JSON for transmission over WebSocket.
 * <p>
 * The wire format (a {@code {type, value, ...}} envelope) is unchanged from the
 * Lua era so the web UI and MCP clients keep working; the difference is that
 * Groovy hands us plain Java/Groovy objects (numbers, strings, {@code List},
 * {@code Map}, wrappers, or raw Minecraft objects) instead of {@code LuaValue}s.
 */
public class ResultSerializer {
    private final MappingResolver resolver;
    private final ObjectRefStore refs;
    private final GroovyBridge bridge;

    public ResultSerializer(MappingResolver resolver, ObjectRefStore refs, GroovyBridge bridge) {
        this.resolver = resolver;
        this.refs = refs;
        this.bridge = bridge;
    }

    /** Serialize a script return value to a JSON element. */
    public JsonElement serialize(Object value) {
        if (value == null) {
            return typed("nil");
        }

        if (value instanceof Boolean b) {
            JsonObject obj = typed("boolean");
            obj.addProperty("value", b);
            return obj;
        }

        if (value instanceof Number n) {
            JsonObject obj = typed("number");
            obj.addProperty("value", n);
            return obj;
        }

        if (value instanceof Character || value instanceof CharSequence) {
            JsonObject obj = typed("string");
            obj.addProperty("value", value.toString());
            return obj;
        }

        if (value instanceof GroovyJavaObject wrapper) {
            return serializeJavaObject(wrapper.getTarget());
        }

        if (value instanceof GroovyJavaClass wrapper) {
            JsonObject obj = typed("class");
            obj.addProperty("className", wrapper.getMojangName());
            return obj;
        }

        if (value instanceof Map<?, ?> map) {
            JsonObject obj = typed("table");
            JsonObject inner = new JsonObject();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                inner.add(String.valueOf(e.getKey()), serialize(e.getValue()));
            }
            obj.add("value", inner);
            return obj;
        }

        if (value instanceof Collection<?> coll) {
            JsonObject obj = typed("table");
            JsonArray arr = new JsonArray();
            for (Object item : coll) arr.add(serialize(item));
            obj.add("value", arr);
            return obj;
        }

        if (value.getClass().isArray()) {
            JsonObject obj = typed("table");
            JsonArray arr = new JsonArray();
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) arr.add(serialize(Array.get(value, i)));
            obj.add("value", arr);
            return obj;
        }

        // Any other raw Java object (e.g. a Minecraft object returned unwrapped).
        return serializeJavaObject(value);
    }

    private JsonObject typed(String type) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        return obj;
    }

    private JsonElement serializeJavaObject(Object javaObj) {
        if (javaObj == null) {
            return typed("null");
        }

        String mojangType = resolver.unresolveClass(javaObj.getClass().getName());
        String ref = refs.store(javaObj);

        JsonObject obj = typed("object");
        obj.addProperty("className", mojangType);
        obj.addProperty("ref", ref);

        try {
            obj.addProperty("toString", javaObj.toString());
        } catch (Exception e) {
            obj.addProperty("toString", mojangType + "@" + Integer.toHexString(System.identityHashCode(javaObj)));
        }

        try {
            JsonObject fields = summarizeFields(javaObj);
            if (!fields.isEmpty()) {
                obj.add("fields", fields);
            }
        } catch (Exception e) {
            // Skip field summary on error
        }
        return obj;
    }

    private JsonObject summarizeFields(Object obj) {
        JsonObject fields = new JsonObject();
        int count = 0;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (count >= 15) break;
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                String name = bridge.getFieldMojangName(f.getDeclaringClass(), f);
                if (val == null) {
                    fields.add(name, JsonNull.INSTANCE);
                } else if (val instanceof Boolean b) {
                    fields.addProperty(name, b);
                } else if (val instanceof Number n) {
                    fields.addProperty(name, n);
                } else if (val instanceof String s) {
                    fields.addProperty(name, s);
                } else {
                    fields.addProperty(
                            name,
                            val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val)));
                }
                count++;
            } catch (Exception e) {
                // Skip inaccessible fields
            }
        }
        return fields;
    }
}
