package com.debugbridge.core.script;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bridge between Groovy and Java/Minecraft. Holds the shared services
 * (mapping resolver, game-thread dispatcher, ref store), wraps/unwraps values
 * across the boundary, and owns the obfuscation-name machinery so the two
 * wrappers ({@link GroovyJavaObject}, {@link GroovyJavaClass}) stay thin.
 *
 * <p><b>Game-thread fast path.</b> By default every reflective call hops to the
 * game thread (a 5s-bounded {@code Minecraft.execute}). When script code runs
 * inside {@code sync { ... }} the whole closure is already on the game thread,
 * so a {@link ThreadLocal} flag lets nested calls invoke directly with no
 * per-call hop — that's what makes bulk loops over many entities fast.
 */
public class GroovyBridge {
    private static final long GAME_THREAD_TIMEOUT_MS = 5000;

    private final MappingResolver resolver;
    private final ThreadDispatcher dispatcher;
    private final ObjectRefStore refs;
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, String>> reverseMethodCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> methodCandidateCache = new ConcurrentHashMap<>();

    // True on threads that are already executing on the game thread (inside sync{}).
    private final ThreadLocal<Boolean> onGameThread = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public GroovyBridge(MappingResolver resolver, ThreadDispatcher dispatcher, ObjectRefStore refs) {
        this.resolver = resolver;
        this.dispatcher = dispatcher;
        this.refs = refs;
    }

    public MappingResolver getResolver() {
        return resolver;
    }

    public ObjectRefStore getRefs() {
        return refs;
    }

    public boolean isOnGameThread() {
        return onGameThread.get();
    }

    public void setOnGameThread(boolean value) {
        onGameThread.set(value);
    }

    /**
     * Run a reflective task on the game thread — unless we're already there
     * (inside {@code sync{}}), in which case run it inline to avoid the hop.
     */
    public <T> T dispatch(Callable<T> task) throws Exception {
        return dispatch(task, GAME_THREAD_TIMEOUT_MS);
    }

    /** As {@link #dispatch(Callable)} but with an explicit game-thread budget (used by {@code sync{}}). */
    public <T> T dispatch(Callable<T> task, long timeoutMs) throws Exception {
        if (onGameThread.get()) {
            return task.call();
        }
        return dispatcher.executeOnGameThread(task, timeoutMs);
    }

    // ==================== Class resolution ====================

    /** Resolve a Mojang class name to a runtime {@link Class}, honoring the security policy. */
    public Class<?> resolveClass(String mojangName) throws ClassNotFoundException {
        Class<?> cached = classCache.get(mojangName);
        if (cached != null) return cached;

        if (!SecurityPolicy.isAllowed(mojangName)) {
            throw new SecurityException("Access to " + mojangName + " is blocked by security policy");
        }
        String runtimeName = resolver.resolveClass(mojangName);
        if (!SecurityPolicy.isAllowed(runtimeName)) {
            throw new SecurityException("Access to " + runtimeName + " is blocked by security policy");
        }

        try {
            Class<?> cls = Class.forName(runtimeName);
            classCache.put(mojangName, cls);
            return cls;
        } catch (ClassNotFoundException e) {
            if (!runtimeName.equals(mojangName)) {
                try {
                    Class<?> cls = Class.forName(mojangName);
                    classCache.put(mojangName, cls);
                    return cls;
                } catch (ClassNotFoundException ignored) {
                    // fall through to original
                }
            }
            throw e;
        }
    }

    // ==================== Minecraft convenience globals ====================

    private volatile GroovyJavaClass minecraftClass;

    private GroovyJavaClass minecraftClass() throws ClassNotFoundException {
        GroovyJavaClass c = minecraftClass;
        if (c == null) {
            Class<?> cls = resolveClass("net.minecraft.client.Minecraft");
            c = new GroovyJavaClass(cls, "net.minecraft.client.Minecraft", this);
            minecraftClass = c;
        }
        return c;
    }

    /** {@code Minecraft.getInstance()}, wrapped. */
    public Object mcInstance() throws ClassNotFoundException {
        return minecraftClass().invokeMethod("getInstance", new Object[0]);
    }

    /** {@code Minecraft.getInstance().player}, wrapped (may be null when not in a world). */
    public Object playerInstance() throws ClassNotFoundException {
        Object mc = mcInstance();
        return ((GroovyJavaObject) mc).getProperty("player");
    }

    /** {@code Minecraft.getInstance().level}, wrapped (may be null when not in a world). */
    public Object levelInstance() throws ClassNotFoundException {
        Object mc = mcInstance();
        return ((GroovyJavaObject) mc).getProperty("level");
    }

    // ==================== Value marshalling ====================

    /**
     * Wrap a Java value for the Groovy side. Primitives, strings and booleans
     * pass through as-is so Groovy can do arithmetic/comparisons natively;
     * everything else (including collections and arrays) is wrapped so further
     * property/method access goes through the mapping resolver. Use
     * {@code java.list(x)} to iterate a wrapped collection with wrapped elements.
     */
    public Object wrap(Object value) {
        if (value == null) return null;
        if (value instanceof GroovyJavaObject || value instanceof GroovyJavaClass) return value;
        if (value instanceof Boolean
                || value instanceof Character
                || value instanceof CharSequence
                || value instanceof Number) {
            return value;
        }
        Class<?> c = value.getClass();
        return new GroovyJavaObject(value, c, resolver.unresolveClass(c.getName()), this);
    }

    /** Unwrap a Groovy-side value back to the underlying Java object. */
    public Object unwrap(Object value) {
        if (value instanceof GroovyJavaObject w) return w.getTarget();
        if (value instanceof GroovyJavaClass w) return w.getTheClass();
        return value;
    }

    /** Class of an (unwrapped) argument, for overload matching; {@code null} stays {@code null}. */
    public Class<?> argType(Object unwrapped) {
        return unwrapped == null ? null : unwrapped.getClass();
    }

    /**
     * Every runtime-name candidate for a Mojang method name at a given arity,
     * walking the hierarchy. One Mojang name maps to a <em>different</em> runtime
     * name per overload (e.g. {@code Vec3.add(Vec3)} and
     * {@code Vec3.add(double,double,double)} are distinct {@code method_NNNN}s),
     * so resolution must enumerate the mapping table's signatures instead of
     * taking the first name match — that collapse made every non-first overload
     * uncallable and mis-dispatched calls into the wrong one.
     *
     * <p>The Mojang name itself is always the last candidate so unobfuscated
     * (dev / passthrough) environments and JDK classes keep working.
     */
    Set<String> resolveMethodCandidates(Class<?> declaredType, String mojangName, int nargs) {
        String cacheKey = declaredType.getName() + "#" + mojangName + "/" + nargs;
        Set<String> cached = methodCandidateCache.get(cacheKey);
        if (cached != null) return cached;

        Set<String> out = new LinkedHashSet<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        ReflectUtil.collectHierarchy(declaredType, visited);
        for (Class<?> c : visited) {
            String mojClass = resolver.unresolveClass(c.getName());
            for (String sig : resolver.getMethodSignatures(mojClass)) {
                if (!simpleMethodName(sig).equals(mojangName)) continue;
                String[] params = sigParams(sig);
                if (params != null && params.length != nargs) continue;
                String resolved = resolver.resolveMethod(mojClass, mojangName, params);
                if (!resolved.equals(mojangName)) out.add(resolved);
            }
        }
        out.add(mojangName);
        Set<String> frozen = Collections.unmodifiableSet(out);
        methodCandidateCache.put(cacheKey, frozen);
        return frozen;
    }

    // ==================== Mapping-name reflection helpers ====================

    /**
     * Reverse-lookup: given a runtime {@link Method}, find a Mojang name for it
     * (or fall back to the runtime name). Walks the declaring class's full
     * ancestor graph because Fabric attaches a mapping to the class that
     * originally declares a method, not to every subclass.
     */
    public String getMethodMojangName(Class<?> declaringClass, Method m) {
        return getReverseMethodTable(declaringClass).getOrDefault(m.getName(), m.getName());
    }

    private Map<String, String> getReverseMethodTable(Class<?> clazz) {
        Map<String, String> cached = reverseMethodCache.get(clazz);
        if (cached != null) return cached;

        Map<String, String> table = new HashMap<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            populateReverseFromClass(c, table);
        }
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
            String simpleName = simpleMethodName(sig);
            // Resolve with the signature's own params: each overload has its own
            // runtime name, and resolving by bare name would map them all to one.
            String runtimeName = resolver.resolveMethod(mojangClassName, simpleName, sigParams(sig));
            if (!runtimeName.equals(simpleName)) {
                table.putIfAbsent(runtimeName, simpleName);
            }
        }
    }

    static String simpleMethodName(String key) {
        int paren = key.indexOf('(');
        return paren >= 0 ? key.substring(0, paren) : key;
    }

    /**
     * Parameter type names from a ProGuard-style signature key
     * {@code name(type1,type2)}. Returns an empty array for {@code name()} and
     * {@code null} when the key has no parameter list at all (some resolver
     * implementations expose bare names — null tells the caller "arity unknown,
     * resolve by name only").
     */
    static String[] sigParams(String key) {
        int open = key.indexOf('(');
        if (open < 0) return null;
        int close = key.lastIndexOf(')');
        String inner = close > open ? key.substring(open + 1, close) : "";
        if (inner.isBlank()) return new String[0];
        return inner.split(",");
    }

    public String getFieldMojangName(Class<?> declaringClass, Field f) {
        String mojangClassName = resolver.unresolveClass(declaringClass.getName());
        Collection<String> fieldNames = resolver.getFieldNames(mojangClassName);
        for (String fieldName : fieldNames) {
            if (resolver.resolveField(mojangClassName, fieldName).equals(f.getName())) {
                return fieldName;
            }
        }
        return f.getName();
    }

    public String buildMethodSignature(Method m, String mojangName) {
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
}
