package com.debugbridge.core.script;

import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Wraps a {@link Class} for Groovy: static field/method access and
 * construction. Returned by {@code java.type(name)}.
 *
 * <p>On obfuscated builds Groovy can't name {@code net.minecraft.*} classes
 * directly (the runtime class is {@code class_NNNN}), so construction goes
 * through here: {@code def Vec3 = java.type('net.minecraft.world.phys.Vec3');
 * Vec3(1,2,3)} or {@code Vec3.create(1,2,3)}. (Single-quote the name: a
 * double-quoted GString interpolates the {@code $} in inner-class names.)
 */
public class GroovyJavaClass extends GroovyObjectSupport {
    private final Class<?> javaClass;
    private final String mojangClassName;
    private final GroovyBridge bridge;

    public GroovyJavaClass(Class<?> javaClass, String mojangClassName, GroovyBridge bridge) {
        this.javaClass = javaClass;
        this.mojangClassName = mojangClassName;
        this.bridge = bridge;
    }

    public Class<?> getTheClass() {
        return javaClass;
    }

    public String getMojangName() {
        return mojangClassName;
    }

    @Override
    public Object getProperty(String name) {
        if ("metaClass".equals(name)) return getMetaClass();
        String runtimeField = bridge.getResolver().resolveField(mojangClassName, name);
        Field field = findStaticField(javaClass, runtimeField);
        if (field == null && !runtimeField.equals(name)) {
            field = findStaticField(javaClass, name);
        }
        if (field == null) {
            throw new MissingPropertyException("No static field '" + name + "' on " + mojangClassName);
        }
        try {
            field.setAccessible(true);
            final Field f = field;
            return bridge.wrap(bridge.dispatch(() -> f.get(null)));
        } catch (Exception e) {
            throw new MissingPropertyException("Failed to read static field '" + name + "' on " + mojangClassName);
        }
    }

    @Override
    public Object invokeMethod(String name, Object argsObj) {
        Object[] rawArgs = (argsObj instanceof Object[]) ? (Object[]) argsObj : new Object[] {argsObj};

        // Allow `cls.create(...)` / `cls.new(...)` as a construction spelling.
        if ("create".equals(name) || "new".equals(name)) {
            return construct(rawArgs);
        }

        int nargs = rawArgs.length;
        Object[] javaArgs = new Object[nargs];
        Class<?>[] argTypes = new Class<?>[nargs];
        for (int i = 0; i < nargs; i++) {
            javaArgs[i] = bridge.unwrap(rawArgs[i]);
            argTypes[i] = bridge.argType(javaArgs[i]);
        }

        Set<String> candidates = bridge.resolveMethodCandidates(javaClass, name, nargs);
        Method method = ReflectUtil.findBestMatch(javaClass, candidates, argTypes, nargs);
        if (method == null || !Modifier.isStatic(method.getModifiers())) {
            throw missingStatic(name, javaArgs);
        }

        method = ReflectUtil.preferAccessibleMethod(method);
        method.setAccessible(true);
        Object[] converted = ReflectUtil.convertArgs(javaArgs, method.getParameterTypes());
        final Method finalMethod = method;
        try {
            return bridge.wrap(bridge.dispatch(() -> finalMethod.invoke(null, converted)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to call static '" + name + "' on " + mojangClassName + ": " + e.getMessage(), e);
        }
    }

    /** Groovy invokes this for {@code Cls(args)}. */
    public Object call(Object... args) {
        return construct(args);
    }

    private Object construct(Object[] rawArgs) {
        int nargs = rawArgs.length;
        Object[] javaArgs = new Object[nargs];
        Class<?>[] argTypes = new Class<?>[nargs];
        for (int i = 0; i < nargs; i++) {
            javaArgs[i] = bridge.unwrap(rawArgs[i]);
            argTypes[i] = bridge.argType(javaArgs[i]);
        }
        Constructor<?> ctor = ReflectUtil.findConstructor(javaClass, argTypes, nargs);
        if (ctor == null) {
            throw missingStatic("<init>", javaArgs);
        }
        try {
            ctor.setAccessible(true);
            Object[] converted = ReflectUtil.convertArgs(javaArgs, ctor.getParameterTypes());
            return bridge.wrap(ctor.newInstance(converted));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct " + mojangClassName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "Class<" + mojangClassName + ">";
    }

    /** Missing static method / constructor, reported with Mojang names. */
    private MissingMethodException missingStatic(String name, Object[] javaArgs) {
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < javaArgs.length; i++) {
            if (i > 0) types.append(", ");
            types.append(
                    javaArgs[i] == null
                            ? "null"
                            : bridge.getResolver()
                                    .unresolveClass(javaArgs[i].getClass().getName()));
        }
        String what = "<init>".equals(name) ? "constructor " : "static method '" + name + "' ";
        String message = "No " + what + "(" + types + ") on " + mojangClassName;
        return new MissingMethodException(name, javaClass, javaArgs) {
            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    private Field findStaticField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) return f;
            } catch (NoSuchFieldException ignored) {
                // continue
            }
        }
        return null;
    }
}
