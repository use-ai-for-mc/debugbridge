package com.debugbridge.core.server;

import com.debugbridge.core.lua.JavaBridge;
import com.debugbridge.core.lua.JavaClassWrapper;
import com.debugbridge.core.lua.JavaObjectWrapper;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.luaj.vm2.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Serializes Lua return values to JSON for transmission over WebSocket.
 */
public class ResultSerializer {
    private final MappingResolver resolver;
    private final ObjectRefStore refs;

    public ResultSerializer(MappingResolver resolver, ObjectRefStore refs) {
        this.resolver = resolver;
        this.refs = refs;
    }

    /**
     * Serialize a Lua value to a JSON element.
     */
    public JsonElement serialize(LuaValue value) {
        if (value == null || value.isnil()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "nil");
            return obj;
        }

        if (value.isboolean()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "boolean");
            obj.addProperty("value", value.toboolean());
            return obj;
        }

        if (value.isint()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "number");
            obj.addProperty("value", value.toint());
            return obj;
        }

        if (value.isnumber()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "number");
            obj.addProperty("value", value.todouble());
            return obj;
        }

        if (value.isstring() && !(value instanceof JavaObjectWrapper)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "string");
            obj.addProperty("value", value.tojstring());
            return obj;
        }

        if (value instanceof JavaObjectWrapper wrapper) {
            return serializeJavaObject(wrapper);
        }

        if (value instanceof JavaClassWrapper wrapper) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "class");
            obj.addProperty("className", wrapper.getMojangClassName());
            return obj;
        }

        if (value.istable()) {
            return serializeLuaTable(value.checktable());
        }

        // Fallback
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "unknown");
        obj.addProperty("value", value.tojstring());
        return obj;
    }

    private JsonElement serializeJavaObject(JavaObjectWrapper wrapper) {
        Object javaObj = wrapper.getJavaObject();
        if (javaObj == null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "null");
            return obj;
        }

        String mojangType = resolver.unresolveClass(javaObj.getClass().getName());
        String ref = refs.store(javaObj);

        JsonObject obj = new JsonObject();
        obj.addProperty("type", "object");
        obj.addProperty("className", mojangType);
        obj.addProperty("ref", ref);

        try {
            obj.addProperty("toString", javaObj.toString());
        } catch (Exception e) {
            obj.addProperty("toString", mojangType + "@" + Integer.toHexString(System.identityHashCode(javaObj)));
        }

        // Shallow field summary
        try {
            JsonObject fields = summarizeFields(javaObj, mojangType);
            if (fields.size() > 0) {
                obj.add("fields", fields);
            }
        } catch (Exception e) {
            // Skip field summary on error
        }

        return obj;
    }

    private JsonObject summarizeFields(Object obj, String mojangType) {
        JsonObject fields = new JsonObject();
        int count = 0;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (count >= 15) break; // Limit field count

            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                // Use the runtime name since we don't have reverse field lookup here
                String name = f.getName();

                if (val == null) {
                    fields.add(name, JsonNull.INSTANCE);
                } else if (val instanceof Boolean b) {
                    fields.addProperty(name, b);
                } else if (val instanceof Number n) {
                    fields.addProperty(name, n);
                } else if (val instanceof String s) {
                    fields.addProperty(name, s);
                } else {
                    fields.addProperty(name, val.getClass().getSimpleName() + "@" +
                        Integer.toHexString(System.identityHashCode(val)));
                }
                count++;
            } catch (Exception e) {
                // Skip inaccessible fields
            }
        }
        return fields;
    }

    private JsonElement serializeLuaTable(LuaTable table) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "table");

        // Check if it's an array (sequential integer keys from 1)
        int len = table.length();
        if (len > 0) {
            JsonArray arr = new JsonArray();
            for (int i = 1; i <= len; i++) {
                arr.add(serialize(table.get(i)));
            }
            obj.add("value", arr);
        } else {
            // Map-style table
            JsonObject map = new JsonObject();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs pair = table.next(k);
                k = pair.arg1();
                if (k.isnil()) break;
                LuaValue v = pair.arg(2);
                map.add(k.tojstring(), serialize(v));
            }
            obj.add("value", map);
        }
        return obj;
    }
}
