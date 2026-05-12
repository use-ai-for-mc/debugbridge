package com.debugbridge.core.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/*
 * Note: this class intentionally overrides the invoke/call family so that
 * attempting to call an imported class directly (e.g. `local c =
 * java.import("..."); c(arg)`) produces a descriptive error instructing the
 * user to use java.new(c, arg) instead of LuaJ's generic
 * "attempt to call userdata".
 */

/**
 * Wraps a Java Class<?> as Lua userdata for static field/method access.
 * Returned by java.import().
 */
public class JavaClassWrapper extends LuaUserdata {
    private final Class<?> javaClass;
    private final String mojangClassName;
    private final JavaBridge bridge;
    
    public JavaClassWrapper(Class<?> javaClass, String mojangClassName, JavaBridge bridge) {
        super(javaClass);
        this.javaClass = javaClass;
        this.mojangClassName = mojangClassName;
        this.bridge = bridge;
    }
    
    public Class<?> getJavaClass() {
        return javaClass;
    }
    
    public String getMojangClassName() {
        return mojangClassName;
    }
    
    @Override
    public LuaValue get(LuaValue key) {
        String name = key.tojstring();
        
        // 1. Try static field first (cheap check)
        String runtimeFieldName = bridge.getResolver().resolveField(mojangClassName, name);
        Field field = findStaticField(javaClass, runtimeFieldName);
        if (field == null && !runtimeFieldName.equals(name)) {
            field = findStaticField(javaClass, name);
        }
        
        // 2. If field exists, check if a static method with the same name also exists.
        // If both exist, prefer the method to handle the common Java pattern:
        //   private static boolean isConnected;
        //   public static boolean isConnected() { return isConnected; }
        // Without this, `cls.isConnected()` returns the field and fails with
        // "attempt to call boolean".
        // We only do this check when a field exists (rare) to avoid the overhead
        // of hasStaticMethod() on every property access.
        if (field != null) {
            String runtimeMethodName = bridge.getResolver().resolveMethod(mojangClassName, name, null);
            if (hasStaticMethod(javaClass, runtimeMethodName) || hasStaticMethod(javaClass, name)) {
                return new MethodCallWrapper(null, javaClass, mojangClassName, name, bridge);
            }
            // No method collision — return field value
            try {
                field.setAccessible(true);
                final Field f = field;
                Object value = bridge.getDispatcher().executeOnGameThread(
                        () -> f.get(null), 5000);
                return bridge.wrapJavaValue(value);
            } catch (Exception e) {
                // Fall through to method wrapper
            }
        }
        
        // 3. No field — return a MethodCallWrapper (most common case: method call)
        return new MethodCallWrapper(null, javaClass, mojangClassName, name, bridge);
    }
    
    private boolean hasStaticMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private Field findStaticField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) return f;
            } catch (NoSuchFieldException e) {
                // continue
            }
        }
        return null;
    }
    
    @Override
    public LuaValue tostring() {
        return LuaValue.valueOf("Class<" + mojangClassName + ">");
    }
    
    // ==================== Call interception ====================
    //
    // A LuaUserdata with no __call metatable raises LuaJ's unhelpful
    // "attempt to call userdata" message. Override the whole invoke/call
    // family to raise a descriptive error that tells the user what to write
    // instead. This is the "I imported a class and then tried to call it as a
    // constructor" mistake — very natural coming from Python or JS, where
    // that's the expected syntax.
    
    @Override
    public Varargs invoke(Varargs args) {
        throw new LuaError(buildCallError());
    }
    
    @Override
    public Varargs invoke() {
        return invoke(LuaValue.NONE);
    }
    
    @Override
    public Varargs invoke(LuaValue[] a) {
        return invoke(LuaValue.varargsOf(a));
    }
    
    @Override
    public LuaValue call() {
        invoke(LuaValue.NONE);
        return LuaValue.NIL;
    }
    
    @Override
    public LuaValue call(LuaValue a) {
        invoke(a);
        return LuaValue.NIL;
    }
    
    @Override
    public LuaValue call(LuaValue a, LuaValue b) {
        invoke(LuaValue.varargsOf(new LuaValue[]{a, b}));
        return LuaValue.NIL;
    }
    
    @Override
    public LuaValue call(LuaValue a, LuaValue b, LuaValue c) {
        invoke(LuaValue.varargsOf(new LuaValue[]{a, b, c}));
        return LuaValue.NIL;
    }
    
    private String buildCallError() {
        return "Attempted to call the class " + mojangClassName + " directly."
                + "\n  Classes returned by java.import() are not callable."
                + "\n  Fix options:"
                + "\n    - Construct an instance:     java.new(cls, args...)"
                + "\n    - Call a static method:      cls:methodName(args)  or  cls.methodName(args)"
                + "\n    - Read a static field:       cls.fieldName";
    }
}
