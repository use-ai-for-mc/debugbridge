package com.debugbridge.core.script;

import com.debugbridge.core.ErrorFormatter;
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

        Set<String> candidates = bridge.resolveMethodCandidates(declaredType, name, nargs);
        Method method = ReflectUtil.findBestMatch(declaredType, candidates, argTypes, nargs);
        if (method == null) {
            throw missingMethod(name, javaArgs);
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
                    "Method '" + name + "' on " + mojangTypeName + " threw " + ErrorFormatter.format(cause), cause);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call '" + name + "' on " + mojangTypeName + ": " + rootMessage(e), e);
        }
    }

    /** Delegates to the wrapped object so string interpolation and the REPL show real values. */
    @Override
    public String toString() {
        if (target == null) return "null";
        try {
            return String.valueOf(target);
        } catch (Throwable t) {
            return mojangTypeName + "@" + Integer.toHexString(System.identityHashCode(target));
        }
    }

    /** Delegates to the wrapped object (unwrapping the other side) so {@code ==} compares values. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        Object otherTarget = other instanceof GroovyJavaObject w ? w.getTarget() : other;
        return target != null ? target.equals(otherTarget) : otherTarget == null;
    }

    @Override
    public int hashCode() {
        return target != null ? target.hashCode() : 0;
    }

    // ==================== resolution helpers ====================

    private Method findZeroArgGetter(String name) {
        String cap = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : new String[] {name, "get" + cap, "is" + cap}) {
            Set<String> candidates = bridge.resolveMethodCandidates(declaredType, candidate, 0);
            Method m = ReflectUtil.findBestMatch(declaredType, candidates, new Class<?>[0], 0);
            if (m != null && m.getReturnType() != void.class) return m;
        }
        return null;
    }

    /**
     * A {@link MissingMethodException} whose message speaks Mojang: the class
     * name is unmapped and the hint lists the real methods of this class (via
     * {@link #suggestMethods}) instead of Groovy's DGM guesses.
     */
    private MissingMethodException missingMethod(String name, Object[] javaArgs) {
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < javaArgs.length; i++) {
            if (i > 0) types.append(", ");
            types.append(
                    javaArgs[i] == null
                            ? "null"
                            : bridge.getResolver()
                                    .unresolveClass(javaArgs[i].getClass().getName()));
        }
        String message =
                "No method '" + name + "(" + types + ")' on " + mojangTypeName + suggestMethods(name.toLowerCase());
        return new MissingMethodException(name, declaredType, javaArgs) {
            @Override
            public String getMessage() {
                return message;
            }
        };
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
        return ErrorFormatter.format(t);
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
