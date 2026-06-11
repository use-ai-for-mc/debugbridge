package com.debugbridge.core.script;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflection helpers shared by {@link GroovyJavaObject} and {@link GroovyJavaClass}.
 * <p>
 * This is the obfuscation-agnostic machinery: hierarchy walking, overload
 * resolution by arity + assignability, numeric/array argument coercion, and the
 * JPMS-accessibility dance. All of it was previously embedded in the Lua
 * {@code MethodCallWrapper}; pulling it out keeps the two Groovy wrappers thin.
 */
final class ReflectUtil {
    private ReflectUtil() {}

    /**
     * Collect the full ancestor set for {@code clazz}: superclass chain (most-
     * derived first) then all interfaces in BFS order. {@link LinkedHashSet}
     * preserves order and de-duplicates.
     */
    static void collectHierarchy(Class<?> clazz, Set<Class<?>> out) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            out.add(c);
        }
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
     * Every interface in {@code clazz}'s hierarchy, however deeply nested.
     */
    static List<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> all = new LinkedHashSet<>();
        collectHierarchy(clazz, all);
        List<Class<?>> ifaces = new ArrayList<>();
        for (Class<?> c : all) {
            if (c.isInterface()) ifaces.add(c);
        }
        return ifaces;
    }

    /**
     * Find the best matching method by name and argument count/types. Walks the
     * full class + interface hierarchy: exact type match first, then a relaxed
     * arity-only pass so a near-miss still dispatches with a clear downstream
     * error rather than a silent null.
     */
    static Method findBestMatch(Class<?> clazz, String name, Class<?>[] argTypes, int nargs) {
        return findBestMatch(clazz, List.of(name), argTypes, nargs);
    }

    /**
     * As {@link #findBestMatch(Class, String, Class[], int)} but considering a set
     * of candidate runtime names at once. On obfuscated builds one Mojang name maps
     * to a <em>different</em> runtime name per overload, so the caller passes every
     * candidate and we do the strict pass across all of them before relaxing —
     * otherwise an arity-only match on one overload would shadow an exact type
     * match on another.
     */
    static Method findBestMatch(Class<?> clazz, Collection<String> names, Class<?>[] argTypes, int nargs) {
        // Three passes of decreasing strictness, each across ALL candidate names:
        // boxed-assignable, then numeric-coercible (floating-aware, so BigDecimal
        // picks max(double,double) over max(int,int)), then arity-only.
        for (String name : names) {
            Method m = findTypeMatch(clazz, name, argTypes, nargs, false);
            if (m != null) return m;
        }
        for (String name : names) {
            Method m = findTypeMatch(clazz, name, argTypes, nargs, true);
            if (m != null) return m;
        }
        for (String name : names) {
            Method m = findArityMatch(clazz, name, nargs);
            if (m != null) return m;
        }
        return null;
    }

    private static Method findTypeMatch(Class<?> clazz, String name, Class<?>[] argTypes, int nargs, boolean numeric) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterCount() == nargs
                        && isCompatible(m.getParameterTypes(), argTypes, numeric)) {
                    return m;
                }
            }
        }
        for (Class<?> iface : getAllInterfaces(clazz)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterCount() == nargs
                        && isCompatible(m.getParameterTypes(), argTypes, numeric)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findArityMatch(Class<?> clazz, String name, int nargs) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) return m;
            }
        }
        for (Class<?> iface : getAllInterfaces(clazz)) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == nargs) return m;
            }
        }
        return null;
    }

    /**
     * Best-matching constructor: exact/coercible types first, then arity-only.
     * Arity alone is not enough — e.g. {@code ItemStack(ItemLike, int)} vs
     * {@code ItemStack(Holder, int)} share an arg count.
     */
    static Constructor<?> findConstructor(Class<?> cls, Class<?>[] argTypes, int nargs) {
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == nargs && isCompatible(c.getParameterTypes(), argTypes, false)) {
                return c;
            }
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == nargs && isCompatible(c.getParameterTypes(), argTypes, true)) {
                return c;
            }
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == nargs) return c;
        }
        return null;
    }

    /**
     * If {@code method}'s declaring class isn't reflectively reachable from our
     * module (unexported package or package-private type, e.g.
     * {@code HashMap$Node}), find the same signature on a reachable
     * interface/superclass. Falls back to the original.
     */
    static Method preferAccessibleMethod(Method method) {
        Class<?> dc = method.getDeclaringClass();
        if (isReachable(dc)) return method;
        Set<Class<?>> hierarchy = new LinkedHashSet<>();
        collectHierarchy(dc, hierarchy);
        String name = method.getName();
        Class<?>[] params = method.getParameterTypes();
        for (Class<?> c : hierarchy) {
            if (c == dc || !isReachable(c)) continue;
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignore) {
            }
        }
        return method;
    }

    private static boolean isReachable(Class<?> cls) {
        Module ours = ReflectUtil.class.getModule();
        return cls.getModule().isExported(cls.getPackageName(), ours) && Modifier.isPublic(cls.getModifiers());
    }

    static boolean isCompatible(Class<?>[] paramTypes, Class<?>[] argTypes, boolean numeric) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (argTypes[i] == null) continue; // null matches any reference type
            Class<?> param = boxType(paramTypes[i]);
            Class<?> arg = boxType(argTypes[i]);
            if (param.isAssignableFrom(arg)) continue;
            if (numeric && isNumericCompatible(param, arg)) continue;
            return false;
        }
        return true;
    }

    /**
     * Numeric coercion compatibility. A floating-typed argument (Double, Float,
     * BigDecimal) only coerces to a floating parameter — silently truncating
     * {@code 2.5} into an {@code int} overload would pick the wrong method when
     * both exist. Integral arguments may widen to anything numeric.
     */
    private static boolean isNumericCompatible(Class<?> param, Class<?> arg) {
        if (!Number.class.isAssignableFrom(param) || !Number.class.isAssignableFrom(arg)) {
            return false;
        }
        return !isFloating(arg) || isFloating(param);
    }

    private static boolean isFloating(Class<?> boxed) {
        return boxed == Double.class || boxed == Float.class || java.math.BigDecimal.class.isAssignableFrom(boxed);
    }

    static Class<?> boxType(Class<?> t) {
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

    static Object[] convertArgs(Object[] javaArgs, Class<?>[] paramTypes) {
        Object[] result = new Object[javaArgs.length];
        for (int i = 0; i < javaArgs.length; i++) {
            result[i] = convertArg(javaArgs[i], paramTypes[i]);
        }
        return result;
    }

    static Object convertArg(Object arg, Class<?> targetType) {
        if (arg == null) return null;
        if (targetType.isInstance(arg)) return arg;

        if (arg instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) return num.intValue();
            if (targetType == long.class || targetType == Long.class) return num.longValue();
            if (targetType == float.class || targetType == Float.class) return num.floatValue();
            if (targetType == double.class || targetType == Double.class) return num.doubleValue();
            if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
            if (targetType == short.class || targetType == Short.class) return num.shortValue();
        }

        if (arg instanceof CharSequence cs && (targetType == char.class || targetType == Character.class)) {
            String str = cs.toString();
            if (!str.isEmpty()) return str.charAt(0);
        }

        // Groovy lists → Java arrays when the parameter wants an array.
        if (targetType.isArray() && arg instanceof List<?> list) {
            Class<?> component = targetType.getComponentType();
            Object array = Array.newInstance(component, list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, convertArg(list.get(i), component));
            }
            return array;
        }

        return arg;
    }
}
