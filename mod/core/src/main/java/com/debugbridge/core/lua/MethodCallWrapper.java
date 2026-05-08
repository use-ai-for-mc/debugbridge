package com.debugbridge.core.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * A callable Lua function that invokes a Java method via reflection.
 * Returned by JavaObjectWrapper.__index when no field matches.
 */
public class MethodCallWrapper extends org.luaj.vm2.lib.VarArgFunction {
    private final Object target;
    private final Class<?> targetClass;
    private final String mojangClass;
    private final String mojangMethodName;
    private final JavaBridge bridge;

    public MethodCallWrapper(Object target, Class<?> targetClass,
                             String mojangClass, String mojangMethodName, JavaBridge bridge) {
        this.target = target;
        this.targetClass = targetClass;
        this.mojangClass = mojangClass;
        this.mojangMethodName = mojangMethodName;
        this.bridge = bridge;
    }

    /**
     * Collect the full ancestor set for {@code clazz}: superclass chain plus all
     * interfaces (recursive over super-interfaces). Order: classes first
     * (most-derived to least-derived), then interfaces in BFS order. The
     * {@link LinkedHashSet} both preserves order and de-duplicates.
     */
    static void collectHierarchy(Class<?> clazz, Set<Class<?>> out) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            out.add(c);
        }
        // BFS over interfaces from every class in the chain.
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Collections.addAll(queue, c.getInterfaces());
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (!out.add(iface)) continue;
            Collections.addAll(queue, iface.getInterfaces());
        }
    }

    /**
     * If {@code method}'s declaring class is not reachable for reflective
     * setAccessible from our module — either because the package isn't
     * exported (e.g. an internal jdk.* type) or because the class itself is
     * package-private (e.g. {@code java.util.HashMap$KeySet} or
     * {@code java.util.HashMap$Node}, both inside the exported {@code java.util}
     * package) — find the same signature on a reachable interface or superclass
     * and return that {@link Method} instead. Falls back to the original if no
     * reachable equivalent exists.
     */
    private static Method preferAccessibleMethod(Method method) {
        Class<?> dc = method.getDeclaringClass();
        if (isReachable(dc)) {
            return method;
        }
        Set<Class<?>> hierarchy = new LinkedHashSet<>();
        collectHierarchy(dc, hierarchy);
        String name = method.getName();
        Class<?>[] params = method.getParameterTypes();
        for (Class<?> c : hierarchy) {
            if (c == dc) continue;
            if (!isReachable(c)) continue;
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignore) {
            }
        }
        return method;
    }

    /**
     * A class is reachable for setAccessible from our module iff its package
     * is exported to us AND the class itself is public. The package-export
     * check on its own isn't enough: HashMap$Node lives in the exported
     * java.util package but is package-private, so reflective access still
     * fails the module-open check.
     */
    private static boolean isReachable(Class<?> cls) {
        Module ours = MethodCallWrapper.class.getModule();
        return cls.getModule().isExported(cls.getPackageName(), ours)
                && Modifier.isPublic(cls.getModifiers());
    }

    @Override
    public Varargs invoke(Varargs args) {
        try {
            // Detect and skip the implicit 'self' argument from Lua's ':' call syntax.
            // When called as obj:method(a, b), Lua passes (obj, a, b).
            // We need to strip the leading 'self' if it matches our target object —
            // OR, for static calls (target == null), if the first arg is the
            // class wrapper for our target class (Class:staticMethod() syntax).
            int startIdx = 0;
            int totalArgs = args.narg();
            if (totalArgs > 0) {
                LuaValue firstArg = args.arg(1);
                if (target != null
                        && firstArg instanceof JavaObjectWrapper wrapper
                        && wrapper.getJavaObject() == target) {
                    startIdx = 1; // skip self for instance call
                } else if (target == null
                        && firstArg instanceof JavaClassWrapper classWrapper
                        && classWrapper.getJavaClass() == targetClass) {
                    startIdx = 1; // skip self for static call via Class:method()
                }
            }

            int nargs = totalArgs - startIdx;
            Object[] javaArgs = new Object[nargs];
            Class<?>[] argTypes = new Class<?>[nargs];
            for (int i = 0; i < nargs; i++) {
                LuaValue arg = args.arg(i + 1 + startIdx);
                javaArgs[i] = bridge.unwrapLuaValue(arg, null);
                argTypes[i] = javaArgs[i] == null ? null : javaArgs[i].getClass();
            }

            // Resolve method name through mappings
            String runtimeName = resolveMethodName();

            // Find the best matching method
            Method method = findBestMatch(targetClass, runtimeName, argTypes, nargs);
            if (method == null) {
                // Try with original name (for non-Minecraft methods like toString, hashCode)
                method = findBestMatch(targetClass, mojangMethodName, argTypes, nargs);
            }
            if (method == null) {
                throw new LuaError("No method '" + mojangMethodName + "' with " + nargs
                        + " args on " + mojangClass + suggestMethods());
            }

            // If the declaring class is in a JPMS-sealed module (e.g.
            // HashMap$KeySet inside java.util), try the same signature on a
            // JPMS-accessible interface / superclass first. setAccessible(true)
            // would otherwise throw InaccessibleObjectException.
            method = preferAccessibleMethod(method);
            method.setAccessible(true);

            // Convert args to match parameter types
            Object[] convertedArgs = convertArgs(javaArgs, method.getParameterTypes());

            final Method finalMethod = method;
            Object result = bridge.getDispatcher().executeOnGameThread(
                    () -> finalMethod.invoke(target, convertedArgs), 5000);

            return bridge.wrapJavaValue(result);
        } catch (LuaError e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new LuaError("Method '" + mojangMethodName + "' threw: "
                    + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
        } catch (Exception e) {
            throw new LuaError("Failed to call '" + mojangMethodName + "': " + e.getMessage());
        }
    }

    private String resolveMethodName() {
        // Walk the runtime class+interface graph (recursive over super-interfaces).
        // For each class encountered, ask the resolver to map mojangMethodName on
        // THAT specific class. The resolver is strict — it won't return matches
        // from unrelated classes — so we have to do the hierarchy walk ourselves.
        Set<Class<?>> visited = new LinkedHashSet<>();
        collectHierarchy(targetClass, visited);
        for (Class<?> c : visited) {
            String mojClass = bridge.getResolver().unresolveClass(c.getName());
            String resolved = bridge.getResolver().resolveMethod(mojClass, mojangMethodName, null);
            if (!resolved.equals(mojangMethodName)) {
                return resolved;
            }
        }
        return mojangMethodName;
    }

    /**
     * Backwards-compat helper kept for {@link #findBestMatch}.
     */
    private List<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> all = new LinkedHashSet<>();
        collectHierarchy(clazz, all);
        List<Class<?>> ifaces = new ArrayList<>();
        for (Class<?> c : all) {
            if (c.isInterface()) ifaces.add(c);
        }
        return ifaces;
    }

    /**
     * Find the best matching method by name and argument count/types.
     * Walks the entire class hierarchy including interfaces.
     */
    private Method findBestMatch(Class<?> clazz, String name, Class<?>[] argTypes, int nargs) {
        // First pass: exact hierarchy walk
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    if (isCompatible(m.getParameterTypes(), argTypes)) {
                        return m;
                    }
                }
            }
        }
        // Check interfaces
        for (Class<?> iface : getAllInterfaces(clazz)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    if (isCompatible(m.getParameterTypes(), argTypes)) {
                        return m;
                    }
                }
            }
        }
        // Relax: just match by name and arg count (ignore types)
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    return m;
                }
            }
        }
        // Relaxed pass for interfaces too (was missing before!)
        for (Class<?> iface : getAllInterfaces(clazz)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) {
                    return m;
                }
            }
        }
        return null;
    }

    private boolean isCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (argTypes[i] == null) continue; // null matches any reference type
            Class<?> param = boxType(paramTypes[i]);
            Class<?> arg = boxType(argTypes[i]);
            if (!param.isAssignableFrom(arg) && !isNumericCompatible(param, arg)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNumericCompatible(Class<?> param, Class<?> arg) {
        return Number.class.isAssignableFrom(param) && Number.class.isAssignableFrom(arg);
    }

    private Class<?> boxType(Class<?> t) {
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private Object[] convertArgs(Object[] javaArgs, Class<?>[] paramTypes) {
        Object[] result = new Object[javaArgs.length];
        for (int i = 0; i < javaArgs.length; i++) {
            result[i] = convertArg(javaArgs[i], paramTypes[i]);
        }
        return result;
    }

    private Object convertArg(Object arg, Class<?> targetType) {
        if (arg == null) return null;
        if (targetType.isInstance(arg)) return arg;

        // Numeric conversions
        if (arg instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) return num.intValue();
            if (targetType == long.class || targetType == Long.class) return num.longValue();
            if (targetType == float.class || targetType == Float.class) return num.floatValue();
            if (targetType == double.class || targetType == Double.class) return num.doubleValue();
            if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
            if (targetType == short.class || targetType == Short.class) return num.shortValue();
        }

        // String to char
        if (arg instanceof String str && (targetType == char.class || targetType == Character.class)) {
            if (!str.isEmpty()) return str.charAt(0);
        }

        return arg;
    }

    private String suggestMethods() {
        // Build a list of methods that are visible on the target class, displayed
        // in Mojang names where the resolver can find them. Walk the full
        // ancestor graph (classes + interfaces) so inherited and interface-only
        // methods show up — that's where most useful methods live in MC's deep
        // entity/item hierarchies.
        Set<Class<?>> hierarchy = new LinkedHashSet<>();
        collectHierarchy(targetClass, hierarchy);

        // Group by Mojang method name → set of arities, so we collapse overloads.
        java.util.Map<String, java.util.Set<Integer>> byName = new java.util.LinkedHashMap<>();
        // Also track methods whose names start with the requested prefix so we
        // can surface "did you mean ..." matches first.
        java.util.List<String> matches = new ArrayList<>();
        java.util.List<String> others = new ArrayList<>();
        String wanted = mojangMethodName.toLowerCase();

        for (Class<?> c : hierarchy) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPrivate(m.getModifiers())) continue;
                if (m.isSynthetic()) continue;
                String displayName = bridge.getMethodMojangName(c, m);
                // Skip noisy mixin/transformer-injected methods (they have $ in
                // their names — controlify$..., handler$..., yumi_$..., etc.).
                if (displayName.indexOf('$') >= 0) continue;
                int arity = m.getParameterCount();
                java.util.Set<Integer> arities = byName.computeIfAbsent(
                        displayName, k -> new java.util.LinkedHashSet<>());
                if (!arities.add(arity)) continue;

                String entry = displayName + "(" + arity + " args)";
                if (displayName.toLowerCase().contains(wanted)) {
                    matches.add(entry);
                } else {
                    others.add(entry);
                }
            }
        }

        if (matches.isEmpty() && others.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!matches.isEmpty()) {
            int n = Math.min(matches.size(), 10);
            sb.append("\n  Did you mean: ").append(String.join(", ", matches.subList(0, n)));
            if (matches.size() > n) sb.append(", ...");
        }
        if (!others.isEmpty()) {
            int n = Math.min(others.size(), 10);
            sb.append("\n  Other methods (first ").append(n).append("): ")
                    .append(String.join(", ", others.subList(0, n)));
            if (others.size() > n) sb.append(", ...");
        }
        return sb.toString();
    }
}
