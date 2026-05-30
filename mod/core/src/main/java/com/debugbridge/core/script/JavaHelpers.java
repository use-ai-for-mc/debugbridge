package com.debugbridge.core.script;

import com.debugbridge.core.mapping.MappingResolver;
import groovy.lang.Closure;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code java} global exposed to scripts: object-browser refs, mapping-aware
 * reflection/discovery helpers, mapping-aware class loading + construction, and
 * the {@code sync { }} game-thread batching helper.
 *
 * <p>Construction/instantiation that Groovy can do natively (on deobfuscated
 * builds) is left to Groovy; {@link #type(String)} exists for the obfuscated
 * builds where the runtime class name differs from the Mojang name.
 */
public class JavaHelpers {
    /** Game-thread budget for a whole {@code sync{}} block (longer than a single call). */
    static final long SYNC_TIMEOUT_MS = 30_000;

    private final GroovyBridge bridge;

    public JavaHelpers(GroovyBridge bridge) {
        this.bridge = bridge;
    }

    private MappingResolver resolver() {
        return bridge.getResolver();
    }

    // ==================== object browser / refs ====================

    /** {@code java.ref(id)} -> the stored object (wrapped), or error if collected. */
    public Object ref(String refId) {
        Object obj = bridge.getRefs().get(refId);
        if (obj == null) {
            throw new IllegalArgumentException("Reference " + refId + " not found or has been garbage collected");
        }
        return bridge.wrap(obj);
    }

    /** {@code java.isNull(x)} -> true for null or a wrapper around null. */
    public boolean isNull(Object x) {
        if (x == null) return true;
        if (x instanceof GroovyJavaObject w) return w.getTarget() == null;
        return false;
    }

    // ==================== mapping-aware class access ====================

    /** {@code java.type(name)} / {@code java.importClass(name)} -> class handle for statics + construction. */
    public GroovyJavaClass type(String mojangName) {
        try {
            return new GroovyJavaClass(bridge.resolveClass(mojangName), mojangName, bridge);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Class not found: " + mojangName + " (resolved to: "
                    + resolver().resolveClass(mojangName) + ")");
        }
    }

    public GroovyJavaClass importClass(String mojangName) {
        return type(mojangName);
    }

    /** {@code java.list(iterableOrArray)} -> a Groovy List of wrapped elements for iteration. */
    public List<Object> list(Object x) {
        Object obj = bridge.unwrap(x);
        List<Object> out = new ArrayList<>();
        if (obj instanceof Iterable<?> it) {
            for (Object item : it) out.add(bridge.wrap(item));
        } else if (obj != null && obj.getClass().isArray()) {
            int len = Array.getLength(obj);
            for (int i = 0; i < len; i++) out.add(bridge.wrap(Array.get(obj, i)));
        } else {
            throw new IllegalArgumentException("java.list: argument is not iterable or an array");
        }
        return out;
    }

    /** {@code java.typeName(x)} -> the Mojang type name of a wrapped object/class (or its raw class name). */
    public String typeName(Object x) {
        if (x instanceof GroovyJavaObject w) return w.getMojangType();
        if (x instanceof GroovyJavaClass w) return w.getMojangName();
        if (x == null) return "null";
        return resolver().unresolveClass(x.getClass().getName());
    }

    // ==================== game-thread batching ====================

    /**
     * {@code java.sync { ... }} runs the closure entirely on the game thread in a
     * single hop; nested wrapper calls then invoke inline (no per-call dispatch),
     * which is what makes bulk loops over many entities fast. The whole block is
     * bounded by {@link #SYNC_TIMEOUT_MS}; a long block briefly stalls rendering.
     */
    public Object sync(Closure<?> closure) {
        try {
            return bridge.dispatch(
                    () -> {
                        bridge.setOnGameThread(true);
                        try {
                            return closure.call();
                        } finally {
                            bridge.setOnGameThread(false);
                        }
                    },
                    SYNC_TIMEOUT_MS);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("sync block failed: " + e.getMessage(), e);
        }
    }

    // ==================== reflection / discovery ====================

    private Class<?> classOf(Object arg) {
        if (arg instanceof GroovyJavaObject w) return w.getDeclaredType();
        if (arg instanceof GroovyJavaClass w) return w.getTheClass();
        if (arg != null) return arg.getClass();
        throw new IllegalArgumentException("expected a Java object or class");
    }

    private String mojangOf(Object arg) {
        if (arg instanceof GroovyJavaObject w) return w.getMojangType();
        if (arg instanceof GroovyJavaClass w) return w.getMojangName();
        return resolver().unresolveClass(classOf(arg).getName());
    }

    /** {@code java.describe(x)} -> map with class, supers, fields, methods (Mojang names). */
    public Map<String, Object> describe(Object arg) {
        Class<?> cls = classOf(arg);
        String mojangName = mojangOf(arg);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", mojangName);
        result.put("runtimeClass", cls.getName());
        if (cls.getSuperclass() != null) {
            result.put(
                    "superclass", resolver().unresolveClass(cls.getSuperclass().getName()));
        }

        List<String> interfaces = new ArrayList<>();
        for (Class<?> iface : cls.getInterfaces()) interfaces.add(resolver().unresolveClass(iface.getName()));
        result.put("interfaces", interfaces);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", bridge.getFieldMojangName(c, f));
                entry.put("type", resolver().unresolveClass(f.getType().getName()));
                entry.put("static", Modifier.isStatic(f.getModifiers()));
                entry.put("final", Modifier.isFinal(f.getModifiers()));
                String declaring = resolver().unresolveClass(c.getName());
                if (!declaring.equals(mojangName)) entry.put("declaredIn", declaring);
                fields.add(entry);
            }
        }
        result.put("fields", fields);

        List<Map<String, Object>> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendMethods(cls, false, mojangName, seen, methods);
        for (Class<?> iface : ReflectUtil.getAllInterfaces(cls)) {
            appendMethods(iface, true, mojangName, seen, methods);
        }
        result.put("methods", methods);
        return result;
    }

    private void appendMethods(
            Class<?> cls, boolean isInterface, String selfMojang, Set<String> seen, List<Map<String, Object>> out) {
        for (Class<?> c = cls; c != null && c != Object.class; c = (isInterface ? null : c.getSuperclass())) {
            for (Method m : c.getDeclaredMethods()) {
                String sig = m.getName() + "(" + m.getParameterCount() + ")";
                if (!seen.add(sig)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", bridge.getMethodMojangName(c, m));
                entry.put(
                        "returnType",
                        resolver().unresolveClass(m.getReturnType().getName()));
                entry.put("static", Modifier.isStatic(m.getModifiers()));
                List<String> params = new ArrayList<>();
                for (Class<?> p : m.getParameterTypes()) params.add(resolver().unresolveClass(p.getName()));
                entry.put("params", params);
                String declaring = resolver().unresolveClass(c.getName());
                if (!declaring.equals(selfMojang)) entry.put("declaredIn", declaring);
                out.add(entry);
            }
            if (isInterface) break;
        }
    }

    /** {@code java.methods(x[, filter])} -> list of method signature strings. */
    public List<String> methods(Object arg, String filter) {
        Class<?> cls = classOf(arg);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Class<?>> hierarchy = new java.util.LinkedHashSet<>();
        ReflectUtil.collectHierarchy(cls, hierarchy);
        for (Class<?> c : hierarchy) {
            for (Method m : c.getDeclaredMethods()) {
                String mojangName = bridge.getMethodMojangName(c, m);
                if (filter != null && !mojangName.toLowerCase().contains(filter.toLowerCase())) continue;
                String sig = bridge.buildMethodSignature(m, mojangName);
                if (seen.add(sig)) result.add(sig);
            }
        }
        return result;
    }

    public List<String> methods(Object arg) {
        return methods(arg, null);
    }

    /** {@code java.fields(x[, filter])} -> list of field description strings. */
    public List<String> fields(Object arg, String filter) {
        Class<?> cls = classOf(arg);
        List<String> result = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String mojangName = bridge.getFieldMojangName(c, f);
                if (filter != null && !mojangName.toLowerCase().contains(filter.toLowerCase())) continue;
                String modifiers = Modifier.isStatic(f.getModifiers()) ? "static " : "";
                String typeName = resolver().unresolveClass(f.getType().getName());
                String declaring = resolver().unresolveClass(c.getName());
                result.add(modifiers + typeName + " " + mojangName + "  [from " + declaring + "]");
            }
        }
        return result;
    }

    public List<String> fields(Object arg) {
        return fields(arg, null);
    }

    /** {@code java.supers(x)} -> map with the class hierarchy and all interfaces. */
    public Map<String, Object> supers(Object arg) {
        Class<?> cls = classOf(arg);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> chain = new ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            chain.add(resolver().unresolveClass(c.getName()));
        result.put("hierarchy", chain);
        List<String> ifaces = new ArrayList<>();
        for (Class<?> iface : ReflectUtil.getAllInterfaces(cls))
            ifaces.add(resolver().unresolveClass(iface.getName()));
        result.put("interfaces", ifaces);
        return result;
    }

    /** {@code java.find(pattern[, scope])} -> matching class/method/field names from the mapping DB. */
    public List<String> find(String pattern, String scope) {
        String needle = pattern.toLowerCase();
        String s = scope == null ? "all" : scope;
        List<String> result = new ArrayList<>();
        int limit = 50;

        if (s.equals("class") || s.equals("all")) {
            for (String name : resolver().getAllClassNames()) {
                if (name.toLowerCase().contains(needle)) {
                    result.add("[class] " + name);
                    if (result.size() >= limit) return result;
                }
            }
        }
        if (s.equals("method") || s.equals("all")) {
            for (String className : resolver().getAllClassNames()) {
                for (String methodSig : resolver().getMethodSignatures(className)) {
                    if (methodSig.toLowerCase().contains(needle)) {
                        String simple = className.substring(className.lastIndexOf('.') + 1);
                        result.add("[method] " + simple + "." + methodSig);
                        if (result.size() >= limit) return result;
                    }
                }
            }
        }
        if (s.equals("field") || s.equals("all")) {
            for (String className : resolver().getAllClassNames()) {
                for (String fieldName : resolver().getFieldNames(className)) {
                    if (fieldName.toLowerCase().contains(needle)) {
                        String simple = className.substring(className.lastIndexOf('.') + 1);
                        result.add("[field] " + simple + "." + fieldName);
                        if (result.size() >= limit) return result;
                    }
                }
            }
        }
        return result;
    }

    public List<String> find(String pattern) {
        return find(pattern, "all");
    }
}
