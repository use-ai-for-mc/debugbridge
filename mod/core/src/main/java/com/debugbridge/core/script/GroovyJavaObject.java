package com.debugbridge.core.script;

import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a live Java/Minecraft object for Groovy. All name lookups go through the
 * {@link MappingResolver} so scripts can use Mojang names on obfuscated builds.
 *
 * <p>Unlike the old Lua wrapper, Groovy disambiguates field vs. method access by
 * syntax: {@code obj.foo} reads a field (with a JavaBean getter fallback) and
 * {@code obj.foo(args)} invokes a method. That removes the whole
 * field/colon-call recovery dance the Lua bridge needed.
 */
public class GroovyJavaObject extends GroovyObjectSupport {
    private final Object target;
    private final Class<?> declaredType;
    private final String mojangTypeName;
    private final GroovyBridge bridge;

    public GroovyJavaObject(Object target, Class<?> declaredType, String mojangTypeName, GroovyBridge bridge) {
        this.target = target;
        this.declaredType = declaredType;
        this.mojangTypeName = mojangTypeName;
        this.bridge = bridge;
    }

    public Object getTarget() {
        return target;
    }

    public Class<?> getDeclaredType() {
        return declaredType;
    }

    public String getMojangType() {
        return mojangTypeName;
    }

    @Override
    public Object getProperty(String name) {
        if ("metaClass".equals(name)) return getMetaClass();
        if (target == null) {
            throw new MissingPropertyException("Cannot read '" + name + "' on a null " + mojangTypeName);
        }

        // 1. Field first, by Mojang name, walking the hierarchy.
        Field field = findField(declaredType, name);
        if (field != null) {
            try {
                field.setAccessible(true);
                final Field f = field;
                return bridge.wrap(bridge.dispatch(() -> f.get(target)));
            } catch (Exception e) {
                throw new MissingPropertyException(
                        "Failed to read field '" + name + "' on " + mojangTypeName + ": " + e.getMessage());
            }
        }

        // 2. JavaBean-style fallback: treat obj.foo as a zero-arg getter
        //    (foo() / getFoo() / isFoo()) so common accessors read naturally.
        Method getter = findZeroArgGetter(name);
        if (getter != null) {
            try {
                getter.setAccessible(true);
                final Method g = getter;
                return bridge.wrap(bridge.dispatch(() -> g.invoke(target)));
            } catch (Exception e) {
                throw new MissingPropertyException(
                        "Failed to read property '" + name + "' on " + mojangTypeName + ": " + rootMessage(e));
            }
        }

        throw new MissingPropertyException("No field or zero-arg getter '" + name + "' on " + mojangTypeName
                + ". Did you mean a method call: obj." + name + "(...) ?");
    }

    @Override
    public void setProperty(String name, Object value) {
        if (target == null) {
            throw new MissingPropertyException("Cannot set '" + name + "' on a null " + mojangTypeName);
        }
        Field field = findField(declaredType, name);
        if (field == null) {
            throw new MissingPropertyException("No field '" + name + "' on " + mojangTypeName);
        }
        try {
            field.setAccessible(true);
            Object javaValue = ReflectUtil.convertArg(bridge.unwrap(value), field.getType());
            bridge.dispatch(() -> {
                field.set(target, javaValue);
                return null;
            });
        } catch (Exception e) {
            throw new MissingPropertyException(
                    "Failed to set field '" + name + "' on " + mojangTypeName + ": " + rootMessage(e));
        }
    }

    @Override
    public Object invokeMethod(String name, Object argsObj) {
        Object[] rawArgs = (argsObj instanceof Object[]) ? (Object[]) argsObj : new Object[] {argsObj};
        int nargs = rawArgs.length;
        Object[] javaArgs = new Object[nargs];
        Class<?>[] argTypes = new Class<?>[nargs];
        for (int i = 0; i < nargs; i++) {
            javaArgs[i] = bridge.unwrap(rawArgs[i]);
            argTypes[i] = bridge.argType(javaArgs[i]);
        }

        String runtimeName = resolveRuntimeMethodName(name);
        Method method = ReflectUtil.findBestMatch(declaredType, runtimeName, argTypes, nargs);
        if (method == null && !runtimeName.equals(name)) {
            method = ReflectUtil.findBestMatch(declaredType, name, argTypes, nargs);
        }
        if (method == null) {
            throw new MissingMethodException(name, declaredType, rawArgs);
        }

        method = ReflectUtil.preferAccessibleMethod(method);
        method.setAccessible(true);
        Object[] converted = ReflectUtil.convertArgs(javaArgs, method.getParameterTypes());
        final Method finalMethod = method;
        try {
            return bridge.wrap(bridge.dispatch(() -> finalMethod.invoke(target, converted)));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(
                    "Method '" + name + "' on " + mojangTypeName + " threw "
                            + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    cause);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call '" + name + "' on " + mojangTypeName + ": " + rootMessage(e), e);
        }
    }

    @Override
    public String toString() {
        if (target == null) return "null";
        return mojangTypeName + "@" + Integer.toHexString(System.identityHashCode(target));
    }

    // ==================== resolution helpers ====================

    private String resolveRuntimeMethodName(String mojangName) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        ReflectUtil.collectHierarchy(declaredType, visited);
        for (Class<?> c : visited) {
            String mojClass = bridge.getResolver().unresolveClass(c.getName());
            String resolved = bridge.getResolver().resolveMethod(mojClass, mojangName, null);
            if (!resolved.equals(mojangName)) return resolved;
        }
        return mojangName;
    }

    private Method findZeroArgGetter(String name) {
        String cap = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : new String[] {name, "get" + cap, "is" + cap}) {
            String runtime = resolveRuntimeMethodName(candidate);
            Method m = ReflectUtil.findBestMatch(declaredType, runtime, new Class<?>[0], 0);
            if (m == null && !runtime.equals(candidate)) {
                m = ReflectUtil.findBestMatch(declaredType, candidate, new Class<?>[0], 0);
            }
            if (m != null && m.getReturnType() != void.class) return m;
        }
        return null;
    }

    private Field findField(Class<?> clazz, String mojangName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            String mojangClass = bridge.getResolver().unresolveClass(c.getName());
            String runtimeName = bridge.getResolver().resolveField(mojangClass, mojangName);
            try {
                return c.getDeclaredField(runtimeName);
            } catch (NoSuchFieldException ignored) {
                // try next
            }
            if (!runtimeName.equals(mojangName)) {
                try {
                    return c.getDeclaredField(mojangName);
                } catch (NoSuchFieldException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /**
     * Human-readable list of available methods (Mojang names) for error context.
     * Mirrors the old Lua "did you mean" helper.
     */
    String suggestMethods(String wantedLower) {
        Set<Class<?>> hierarchy = new LinkedHashSet<>();
        ReflectUtil.collectHierarchy(declaredType, hierarchy);
        Map<String, Set<Integer>> byName = new LinkedHashMap<>();
        List<String> matches = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (Class<?> c : hierarchy) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPrivate(m.getModifiers()) || m.isSynthetic()) continue;
                String displayName = bridge.getMethodMojangName(c, m);
                if (displayName.indexOf('$') >= 0) continue;
                int arity = m.getParameterCount();
                if (!byName.computeIfAbsent(displayName, k -> new LinkedHashSet<>())
                        .add(arity)) continue;
                String entry = displayName + "(" + arity + ")";
                if (displayName.toLowerCase().contains(wantedLower)) matches.add(entry);
                else others.add(entry);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!matches.isEmpty()) {
            int n = Math.min(matches.size(), 10);
            sb.append("\n  Did you mean: ").append(String.join(", ", matches.subList(0, n)));
        }
        return sb.toString();
    }
}
