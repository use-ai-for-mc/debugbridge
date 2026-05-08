package com.debugbridge.core.lua;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bridge between Lua and Java. Provides the "java" global table
 * with functions for importing classes, creating instances, type introspection,
 * and reflection helpers for exploring the API surface.
 */
public class JavaBridge {
    private final MappingResolver resolver;
    private final ThreadDispatcher dispatcher;
    private final ObjectRefStore refs;
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, String>> reverseMethodCache = new ConcurrentHashMap<>();

    public JavaBridge(MappingResolver resolver, ThreadDispatcher dispatcher, ObjectRefStore refs) {
        this.resolver = resolver;
        this.dispatcher = dispatcher;
        this.refs = refs;
    }

    public MappingResolver getResolver() {
        return resolver;
    }

    public ThreadDispatcher getDispatcher() {
        return dispatcher;
    }

    public ObjectRefStore getRefs() {
        return refs;
    }

    /**
     * Create the "java" global table with all bridge functions.
     */
    public LuaTable createJavaTable() {
        LuaTable t = new LuaTable();
        t.set("import", new ImportFunction());
        t.set("new", new NewFunction());
        t.set("typeof", new TypeofFunction());
        t.set("cast", new CastFunction());
        t.set("iter", new IterFunction());
        t.set("array", new ArrayFunction());
        t.set("isNull", new IsNullFunction());
        t.set("ref", new RefFunction());

        // Reflection helpers for exploring classes and objects
        t.set("describe", new DescribeFunction());
        t.set("methods", new MethodsFunction());
        t.set("fields", new FieldsFunction());
        t.set("supers", new SupersFunction());
        t.set("find", new FindFunction());
        return t;
    }

    /**
     * Resolve a Mojang class name to a runtime Class<?>.
     */
    public Class<?> resolveClass(String mojangName) throws ClassNotFoundException {
        Class<?> cached = classCache.get(mojangName);
        if (cached != null) return cached;

        if (!SecurityPolicy.isAllowed(mojangName)) {
            throw new LuaError("Access to " + mojangName + " is blocked by security policy");
        }

        String runtimeName = resolver.resolveClass(mojangName);
        if (!SecurityPolicy.isAllowed(runtimeName)) {
            throw new LuaError("Access to " + runtimeName + " is blocked by security policy");
        }

        try {
            Class<?> cls = Class.forName(runtimeName);
            classCache.put(mojangName, cls);
            return cls;
        } catch (ClassNotFoundException e) {
            // If the mojang name is different from runtime, try mojang name directly
            if (!runtimeName.equals(mojangName)) {
                try {
                    Class<?> cls = Class.forName(mojangName);
                    classCache.put(mojangName, cls);
                    return cls;
                } catch (ClassNotFoundException e2) {
                    // fall through
                }
            }
            throw e;
        }
    }

    /**
     * Wrap a Java value as an appropriate Lua value.
     */
    public LuaValue wrapJavaValue(Object value) {
        if (value == null) return LuaValue.NIL;
        if (value instanceof Boolean b) return LuaValue.valueOf(b);
        if (value instanceof Byte b) return LuaValue.valueOf(b);
        if (value instanceof Short s) return LuaValue.valueOf(s);
        if (value instanceof Integer i) return LuaValue.valueOf(i);
        if (value instanceof Long l) return LuaValue.valueOf(l.doubleValue());
        if (value instanceof Float f) return LuaValue.valueOf(f);
        if (value instanceof Double d) return LuaValue.valueOf(d);
        if (value instanceof String s) return LuaValue.valueOf(s);
        if (value instanceof Character c) return LuaValue.valueOf(String.valueOf(c));

        // Wrap any other object
        String mojangType = resolver.unresolveClass(value.getClass().getName());
        return new JavaObjectWrapper(value, value.getClass(), mojangType, this);
    }

    /**
     * Unwrap a Lua value to a Java object.
     */
    public Object unwrapLuaValue(LuaValue value, Class<?> targetType) {
        if (value.isnil()) return null;
        if (value.isboolean()) return value.toboolean();
        if (value instanceof JavaObjectWrapper wrapper) return wrapper.getJavaObject();
        if (value instanceof JavaClassWrapper wrapper) return wrapper.getJavaClass();

        if (value.isnumber()) {
            if (targetType != null) {
                if (targetType == int.class || targetType == Integer.class) return value.toint();
                if (targetType == long.class || targetType == Long.class) return (long) value.todouble();
                if (targetType == float.class || targetType == Float.class) return (float) value.todouble();
                if (targetType == double.class || targetType == Double.class) return value.todouble();
                if (targetType == byte.class || targetType == Byte.class) return (byte) value.toint();
                if (targetType == short.class || targetType == Short.class) return (short) value.toint();
            }
            // Default to int if it looks like an integer, double otherwise
            double d = value.todouble();
            if (d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return (int) d;
            }
            return d;
        }

        if (value.isstring()) {
            if (targetType == char.class || targetType == Character.class) {
                String s = value.tojstring();
                return s.isEmpty() ? '\0' : s.charAt(0);
            }
            return value.tojstring();
        }

        if (value.istable()) {
            // Convert Lua table to array if target is array type
            if (targetType != null && targetType.isArray()) {
                return luaTableToArray(value.checktable(), targetType.getComponentType());
            }
            return value.tojstring(); // fallback
        }

        return value.tojstring();
    }

    // ==================== Bridge Functions ====================

    private Object luaTableToArray(LuaTable table, Class<?> componentType) {
        int len = table.length();
        Object array = Array.newInstance(componentType, len);
        for (int i = 0; i < len; i++) {
            Array.set(array, i, unwrapLuaValue(table.get(i + 1), componentType));
        }
        return array;
    }

    /**
     * Reverse-lookup: given a runtime {@link Method}, find a Mojang method name
     * for it (or fall back to the runtime name).
     * <p>
     * Walks the declaring class's full ancestor graph (superclasses + super-interfaces)
     * because Fabric's runtime mappings only attach methods to the class that
     * <em>originally</em> declares them: e.g. {@code Entity.method_5628} is
     * mapped via {@code EntityAccess.getId}, not via {@code Entity.getId}.
     * Without the walk, every method that's only known via an ancestor
     * interface comes back as its raw {@code method_NNNN} name.
     */
    public String getMethodMojangName(Class<?> declaringClass, Method m) {
        Map<String, String> reverse = getReverseMethodTable(declaringClass);
        return reverse.getOrDefault(m.getName(), m.getName());
    }

    /**
     * Build (and cache) a runtime-method-name → Mojang-method-name table for the
     * full ancestor graph of {@code clazz}. Walks classes + super-interfaces in
     * BFS order; the first Mojang sig that resolves to a given runtime name wins.
     */
    private Map<String, String> getReverseMethodTable(Class<?> clazz) {
        Map<String, String> cached = reverseMethodCache.get(clazz);
        if (cached != null) return cached;

        Map<String, String> table = new HashMap<>();
        // Walk class hierarchy.
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            populateReverseFromClass(c, table);
        }
        // Walk all interfaces (recursive over super-interfaces).
        Set<Class<?>> ifaces = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Collections.addAll(queue, c.getInterfaces());
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (!ifaces.add(iface)) continue;
            populateReverseFromClass(iface, table);
            Collections.addAll(queue, iface.getInterfaces());
        }

        reverseMethodCache.put(clazz, table);
        return table;
    }

    private void populateReverseFromClass(Class<?> c, Map<String, String> table) {
        String mojangClassName = resolver.unresolveClass(c.getName());
        for (String sig : resolver.getMethodSignatures(mojangClassName)) {
            String simpleName = ParsedMappings.simpleMethodName(sig);
            String runtimeName = resolver.resolveMethod(mojangClassName, simpleName, null);
            if (!runtimeName.equals(simpleName)) {
                // First-write-wins so a class higher in the chain (more derived)
                // doesn't get clobbered by a less-derived ancestor.
                table.putIfAbsent(runtimeName, simpleName);
            }
        }
    }

    private String getFieldMojangName(Class<?> declaringClass, Field f) {
        String runtimeClassName = declaringClass.getName();
        String mojangClassName = resolver.unresolveClass(runtimeClassName);
        Collection<String> fieldNames = resolver.getFieldNames(mojangClassName);
        for (String fieldName : fieldNames) {
            String resolved = resolver.resolveField(mojangClassName, fieldName);
            if (resolved.equals(f.getName())) {
                return fieldName;
            }
        }
        return f.getName();
    }

    private String buildMethodSignature(Method m, String mojangName) {
        StringBuilder sb = new StringBuilder();
        sb.append(resolver.unresolveClass(m.getReturnType().getName()));
        sb.append(" ").append(mojangName).append("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(resolver.unresolveClass(params[i].getName()));
        }
        sb.append(")");
        if (Modifier.isStatic(m.getModifiers())) sb.append(" [static]");
        return sb.toString();
    }

    private List<Class<?>> getAllInterfaces(Class<?> clazz) {
        // Use BFS to collect ALL interfaces in the hierarchy, no matter how deep.
        // The old code only recursed one level, missing deeply nested interfaces
        // like EntityGetter which is 3+ levels deep in Minecraft's hierarchy:
        //   ClientLevel -> Level -> LevelAccessor -> CommonLevelAccessor -> EntityGetter
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();

        // Seed the queue with direct interfaces from all superclasses
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                if (seen.add(iface)) {
                    queue.add(iface);
                }
            }
        }

        // BFS over super-interfaces
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            for (Class<?> superIface : iface.getInterfaces()) {
                if (seen.add(superIface)) {
                    queue.add(superIface);
                }
            }
        }

        return new ArrayList<>(seen);
    }

    // Import for ParsedMappings.simpleMethodName
    private static class ParsedMappings {
        static String simpleMethodName(String key) {
            int paren = key.indexOf('(');
            return paren >= 0 ? key.substring(0, paren) : key;
        }
    }

    // ==================== Reflection Helpers ====================

    /**
     * java.import(className) -> JavaClassWrapper
     */
    private class ImportFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            String mojangName = arg.checkjstring();
            try {
                Class<?> cls = resolveClass(mojangName);
                return new JavaClassWrapper(cls, mojangName, JavaBridge.this);
            } catch (ClassNotFoundException e) {
                throw new LuaError("Class not found: " + mojangName
                        + " (resolved to: " + resolver.resolveClass(mojangName) + ")");
            }
        }
    }

    /**
     * java.new(classWrapper, args...) -> JavaObjectWrapper
     */
    private class NewFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            if (args.narg() < 1) throw new LuaError("java.new requires at least a class argument");

            LuaValue classArg = args.arg1();
            Class<?> cls;
            String mojangName;

            if (classArg instanceof JavaClassWrapper wrapper) {
                cls = wrapper.getJavaClass();
                mojangName = wrapper.getMojangClassName();
            } else {
                throw new LuaError("java.new: first argument must be a class from java.import()");
            }

            // Collect constructor args
            int nargs = args.narg() - 1;
            Object[] javaArgs = new Object[nargs];
            Class<?>[] argTypes = new Class<?>[nargs];
            for (int i = 0; i < nargs; i++) {
                javaArgs[i] = unwrapLuaValue(args.arg(i + 2), null);
                argTypes[i] = javaArgs[i] == null ? null : javaArgs[i].getClass();
            }

            try {
                Constructor<?> ctor = findConstructor(cls, argTypes, nargs);
                if (ctor == null) {
                    throw new LuaError("No constructor for " + mojangName + " with " + nargs + " args");
                }
                ctor.setAccessible(true);
                Object[] converted = convertConstructorArgs(javaArgs, ctor.getParameterTypes());
                Object instance = ctor.newInstance(converted);
                return new JavaObjectWrapper(instance, cls, mojangName, JavaBridge.this);
            } catch (LuaError e) {
                throw e;
            } catch (Exception e) {
                throw new LuaError("Failed to construct " + mojangName + ": " + e.getMessage());
            }
        }

        private Constructor<?> findConstructor(Class<?> cls, Class<?>[] argTypes, int nargs) {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (c.getParameterCount() == nargs) {
                    return c; // Take first match by arg count
                }
            }
            return null;
        }

        private Object[] convertConstructorArgs(Object[] args, Class<?>[] paramTypes) {
            Object[] result = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                result[i] = convertArg(args[i], paramTypes[i]);
            }
            return result;
        }

        private Object convertArg(Object arg, Class<?> target) {
            if (arg == null) return null;
            if (target.isInstance(arg)) return arg;
            if (arg instanceof Number num) {
                if (target == int.class || target == Integer.class) return num.intValue();
                if (target == long.class || target == Long.class) return num.longValue();
                if (target == float.class || target == Float.class) return num.floatValue();
                if (target == double.class || target == Double.class) return num.doubleValue();
                if (target == byte.class || target == Byte.class) return num.byteValue();
                if (target == short.class || target == Short.class) return num.shortValue();
            }
            return arg;
        }
    }

    /**
     * java.typeof(wrapper) -> string
     */
    private class TypeofFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            if (arg instanceof JavaObjectWrapper wrapper) {
                return LuaValue.valueOf(wrapper.getMojangTypeName());
            }
            if (arg instanceof JavaClassWrapper wrapper) {
                return LuaValue.valueOf(wrapper.getMojangClassName());
            }
            return LuaValue.valueOf(arg.typename());
        }
    }

    /**
     * java.cast(wrapper, className) -> re-wrapped
     */
    private class CastFunction extends TwoArgFunction {
        @Override
        public LuaValue call(LuaValue objArg, LuaValue classNameArg) {
            if (!(objArg instanceof JavaObjectWrapper wrapper)) {
                throw new LuaError("java.cast: first argument must be a Java object");
            }
            String mojangName = classNameArg.checkjstring();
            try {
                Class<?> cls = resolveClass(mojangName);
                return new JavaObjectWrapper(wrapper.getJavaObject(), cls, mojangName, JavaBridge.this);
            } catch (ClassNotFoundException e) {
                throw new LuaError("Class not found for cast: " + mojangName);
            }
        }
    }

    /**
     * java.iter(javaIterable) -> Lua iterator function
     */
    private class IterFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            if (!(arg instanceof JavaObjectWrapper wrapper)) {
                throw new LuaError("java.iter: argument must be a Java object (Iterable)");
            }
            Object obj = wrapper.getJavaObject();
            if (!(obj instanceof Iterable<?> iterable)) {
                throw new LuaError("java.iter: object is not Iterable: " + obj.getClass().getName());
            }

            Iterator<?> it = iterable.iterator();
            return new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    try {
                        if (it.hasNext()) {
                            return wrapJavaValue(it.next());
                        }
                        return LuaValue.NIL;
                    } catch (Exception e) {
                        throw new LuaError("Iterator error: " + e.getMessage());
                    }
                }
            };
        }
    }

    // ==================== Helper methods ====================

    /**
     * java.array(javaCollection) -> Lua table
     */
    private class ArrayFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            if (!(arg instanceof JavaObjectWrapper wrapper)) {
                throw new LuaError("java.array: argument must be a Java object");
            }
            Object obj = wrapper.getJavaObject();
            LuaTable table = new LuaTable();

            if (obj instanceof Collection<?> coll) {
                int i = 1;
                for (Object item : coll) {
                    table.set(i++, wrapJavaValue(item));
                }
            } else if (obj.getClass().isArray()) {
                int len = Array.getLength(obj);
                for (int i = 0; i < len; i++) {
                    table.set(i + 1, wrapJavaValue(Array.get(obj, i)));
                }
            } else {
                throw new LuaError("java.array: object is not a Collection or array");
            }
            return table;
        }
    }

    /**
     * java.isNull(wrapper) -> boolean
     */
    private class IsNullFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            if (arg.isnil()) return LuaValue.TRUE;
            if (arg instanceof JavaObjectWrapper wrapper) {
                return LuaValue.valueOf(wrapper.getJavaObject() == null);
            }
            return LuaValue.FALSE;
        }
    }

    /**
     * java.ref(refId) -> stored object
     */
    private class RefFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            String refId = arg.checkjstring();
            Object obj = refs.get(refId);
            if (obj == null) {
                throw new LuaError("Reference " + refId + " not found or has been garbage collected");
            }
            return wrapJavaValue(obj);
        }
    }

    /**
     * java.describe(obj) -> table with class info, fields, methods, supers
     * Comprehensive reflection dump for exploring unknown objects.
     */
    private class DescribeFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            Class<?> cls;
            String mojangName;

            if (arg instanceof JavaObjectWrapper wrapper) {
                cls = wrapper.getDeclaredType();
                mojangName = wrapper.getMojangTypeName();
            } else if (arg instanceof JavaClassWrapper wrapper) {
                cls = wrapper.getJavaClass();
                mojangName = wrapper.getMojangClassName();
            } else {
                throw new LuaError("java.describe: argument must be a Java object or class");
            }

            LuaTable result = new LuaTable();
            result.set("class", mojangName);
            result.set("runtimeClass", cls.getName());

            // Superclass
            if (cls.getSuperclass() != null) {
                result.set("superclass", resolver.unresolveClass(cls.getSuperclass().getName()));
            }

            // Interfaces
            LuaTable interfaces = new LuaTable();
            int idx = 1;
            for (Class<?> iface : cls.getInterfaces()) {
                interfaces.set(idx++, resolver.unresolveClass(iface.getName()));
            }
            result.set("interfaces", interfaces);

            // Fields (include inherited, non-private)
            LuaTable fieldsTable = new LuaTable();
            idx = 1;
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    LuaTable entry = new LuaTable();
                    String fieldMojangName = getFieldMojangName(c, f);
                    entry.set("name", fieldMojangName);
                    entry.set("type", resolver.unresolveClass(f.getType().getName()));
                    entry.set("static", LuaValue.valueOf(Modifier.isStatic(f.getModifiers())));
                    entry.set("final", LuaValue.valueOf(Modifier.isFinal(f.getModifiers())));
                    String declaring = resolver.unresolveClass(c.getName());
                    if (!declaring.equals(mojangName)) {
                        entry.set("declaredIn", declaring);
                    }
                    fieldsTable.set(idx++, entry);
                }
            }
            result.set("fields", fieldsTable);

            // Methods (include inherited, non-private, including interface defaults)
            LuaTable methodsTable = new LuaTable();
            idx = 1;
            Set<String> seen = new HashSet<>();
            // Walk superclasses
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    String sig = m.getName() + "(" + m.getParameterCount() + ")";
                    if (seen.contains(sig)) continue;
                    seen.add(sig);

                    LuaTable entry = new LuaTable();
                    String methodMojangName = getMethodMojangName(c, m);
                    entry.set("name", methodMojangName);
                    entry.set("returnType", resolver.unresolveClass(m.getReturnType().getName()));
                    entry.set("static", LuaValue.valueOf(Modifier.isStatic(m.getModifiers())));

                    // Parameter types
                    LuaTable params = new LuaTable();
                    int pi = 1;
                    for (Class<?> p : m.getParameterTypes()) {
                        params.set(pi++, resolver.unresolveClass(p.getName()));
                    }
                    entry.set("params", params);

                    String declaring = resolver.unresolveClass(c.getName());
                    if (!declaring.equals(mojangName)) {
                        entry.set("declaredIn", declaring);
                    }
                    methodsTable.set(idx++, entry);
                }
            }
            // Also walk interfaces (for default methods like getEntitiesOfClass)
            for (Class<?> iface : getAllInterfaces(cls)) {
                for (Method m : iface.getDeclaredMethods()) {
                    String sig = m.getName() + "(" + m.getParameterCount() + ")";
                    if (seen.contains(sig)) continue;
                    seen.add(sig);

                    LuaTable entry = new LuaTable();
                    String methodMojangName = getMethodMojangName(iface, m);
                    entry.set("name", methodMojangName);
                    entry.set("returnType", resolver.unresolveClass(m.getReturnType().getName()));
                    entry.set("static", LuaValue.valueOf(Modifier.isStatic(m.getModifiers())));

                    LuaTable params = new LuaTable();
                    int pi = 1;
                    for (Class<?> p : m.getParameterTypes()) {
                        params.set(pi++, resolver.unresolveClass(p.getName()));
                    }
                    entry.set("params", params);

                    entry.set("declaredIn", resolver.unresolveClass(iface.getName()));
                    methodsTable.set(idx++, entry);
                }
            }
            result.set("methods", methodsTable);

            return result;
        }
    }

    /**
     * java.methods(obj, [filter]) -> table of method descriptions
     * Optional string filter to match method names.
     */
    private class MethodsFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaValue arg = args.arg1();
            String filter = args.narg() >= 2 ? args.arg(2).optjstring(null) : null;

            Class<?> cls;
            if (arg instanceof JavaObjectWrapper wrapper) {
                cls = wrapper.getDeclaredType();
            } else if (arg instanceof JavaClassWrapper wrapper) {
                cls = wrapper.getJavaClass();
            } else {
                throw new LuaError("java.methods: argument must be a Java object or class");
            }

            LuaTable result = new LuaTable();
            int idx = 1;
            Set<String> seen = new HashSet<>();

            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    String mojangName = getMethodMojangName(c, m);
                    if (filter != null && !mojangName.toLowerCase().contains(filter.toLowerCase())) {
                        continue;
                    }
                    String sig = buildMethodSignature(m, mojangName);
                    if (seen.contains(sig)) continue;
                    seen.add(sig);
                    result.set(idx++, LuaValue.valueOf(sig));
                }
            }
            // Also check interfaces
            for (Class<?> iface : getAllInterfaces(cls)) {
                for (Method m : iface.getDeclaredMethods()) {
                    String mojangName = getMethodMojangName(iface, m);
                    if (filter != null && !mojangName.toLowerCase().contains(filter.toLowerCase())) {
                        continue;
                    }
                    String sig = buildMethodSignature(m, mojangName);
                    if (seen.contains(sig)) continue;
                    seen.add(sig);
                    result.set(idx++, LuaValue.valueOf(sig));
                }
            }

            return result;
        }
    }

    /**
     * java.fields(obj, [filter]) -> table of field descriptions
     */
    private class FieldsFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            LuaValue arg = args.arg1();
            String filter = args.narg() >= 2 ? args.arg(2).optjstring(null) : null;

            Class<?> cls;
            if (arg instanceof JavaObjectWrapper wrapper) {
                cls = wrapper.getDeclaredType();
            } else if (arg instanceof JavaClassWrapper wrapper) {
                cls = wrapper.getJavaClass();
            } else {
                throw new LuaError("java.fields: argument must be a Java object or class");
            }

            LuaTable result = new LuaTable();
            int idx = 1;

            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    String mojangName = getFieldMojangName(c, f);
                    if (filter != null && !mojangName.toLowerCase().contains(filter.toLowerCase())) {
                        continue;
                    }
                    String modifiers = Modifier.isStatic(f.getModifiers()) ? "static " : "";
                    String typeName = resolver.unresolveClass(f.getType().getName());
                    String declaring = resolver.unresolveClass(c.getName());
                    result.set(idx++, LuaValue.valueOf(
                            modifiers + typeName + " " + mojangName + "  [from " + declaring + "]"));
                }
            }
            return result;
        }
    }

    /**
     * java.supers(obj) -> table of superclass chain + interfaces
     */
    private class SupersFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            Class<?> cls;
            if (arg instanceof JavaObjectWrapper wrapper) {
                cls = wrapper.getDeclaredType();
            } else if (arg instanceof JavaClassWrapper wrapper) {
                cls = wrapper.getJavaClass();
            } else {
                throw new LuaError("java.supers: argument must be a Java object or class");
            }

            LuaTable result = new LuaTable();
            LuaTable chain = new LuaTable();
            int idx = 1;
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                chain.set(idx++, resolver.unresolveClass(c.getName()));
            }
            result.set("hierarchy", chain);

            LuaTable ifaces = new LuaTable();
            idx = 1;
            for (Class<?> iface : getAllInterfaces(cls)) {
                ifaces.set(idx++, resolver.unresolveClass(iface.getName()));
            }
            result.set("interfaces", ifaces);

            return result;
        }
    }

    /**
     * java.find(pattern, [scope]) -> table of matching class/method/field names from mappings
     * Searches the mapping database, not loaded classes.
     */
    private class FindFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String pattern = args.arg1().checkjstring().toLowerCase();
            String scope = args.narg() >= 2 ? args.arg(2).optjstring("all") : "all";

            LuaTable result = new LuaTable();
            int idx = 1;
            int limit = 50;

            if (scope.equals("class") || scope.equals("all")) {
                for (String name : resolver.getAllClassNames()) {
                    if (name.toLowerCase().contains(pattern)) {
                        result.set(idx++, LuaValue.valueOf("[class] " + name));
                        if (idx > limit) break;
                    }
                }
            }

            if (idx <= limit && (scope.equals("method") || scope.equals("all"))) {
                for (String className : resolver.getAllClassNames()) {
                    for (String methodSig : resolver.getMethodSignatures(className)) {
                        if (methodSig.toLowerCase().contains(pattern)) {
                            String simpleName = className.substring(className.lastIndexOf('.') + 1);
                            result.set(idx++, LuaValue.valueOf("[method] " + simpleName + "." + methodSig));
                            if (idx > limit) break;
                        }
                    }
                    if (idx > limit) break;
                }
            }

            if (idx <= limit && (scope.equals("field") || scope.equals("all"))) {
                for (String className : resolver.getAllClassNames()) {
                    for (String fieldName : resolver.getFieldNames(className)) {
                        if (fieldName.toLowerCase().contains(pattern)) {
                            String simpleName = className.substring(className.lastIndexOf('.') + 1);
                            result.set(idx++, LuaValue.valueOf("[field] " + simpleName + "." + fieldName));
                            if (idx > limit) break;
                        }
                    }
                    if (idx > limit) break;
                }
            }

            return result;
        }
    }
}
