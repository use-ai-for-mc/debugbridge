package com.debugbridge.core.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * Wraps a Java object as Lua userdata with metamethods for field/method access.
 * All name lookups go through the MappingResolver for transparent obfuscation handling.
 */
public class JavaObjectWrapper extends LuaUserdata {
    private final Object javaObject;
    private final Class<?> declaredType;
    private final String mojangTypeName;
    private final JavaBridge bridge;

    /**
     * Provenance for this wrapper — how it was produced. When set on a wrapper
     * returned from a field access, the call-time path can either (a) recover
     * by dispatching a same-name method on the parent (the
     * {@code obj:level()} / {@code obj:registryAccess()} pattern), or (b) when
     * recovery is impossible, raise an actionable "obj.X is a field, not a
     * method" error instead of LuaJ's "attempt to call userdata".
     */
    private volatile AccessOrigin origin;

    public JavaObjectWrapper(Object javaObject, Class<?> declaredType, String mojangTypeName, JavaBridge bridge) {
        super(javaObject);
        this.javaObject = javaObject;
        this.declaredType = declaredType;
        this.mojangTypeName = mojangTypeName;
        this.bridge = bridge;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public Object getJavaObject() {
        return javaObject;
    }

    public Class<?> getDeclaredType() {
        return declaredType;
    }

    public String getMojangTypeName() {
        return mojangTypeName;
    }

    /**
     * Tag this wrapper with where it came from. Called by JavaObjectWrapper.get().
     */
    void setOrigin(AccessOrigin origin) {
        this.origin = origin;
    }

    @Override
    public LuaValue get(LuaValue key) {
        if (javaObject == null) {
            throw new LuaError("Attempted to access '" + key.tojstring() + "' on a null object");
        }

        String name = key.tojstring();

        // 1. Try field access first (cheap check)
        java.lang.reflect.Field field = null;
        try {
            field = findField(declaredType, name);
        } catch (Exception e) {
            // Not a field
        }

        // 2. Return field value if field exists. Field-first resolution keeps
        // nested field access working (`obj.foo.bar`). When the caller then
        // applies parens — `obj:foo()` or `obj.foo()` — invoke() consults the
        // origin and either dispatches a same-name method on the parent or
        // raises a targeted error if no such method exists.
        if (field != null) {
            try {
                field.setAccessible(true);
                final java.lang.reflect.Field f = field;
                Object value = bridge.getDispatcher().executeOnGameThread(() -> f.get(javaObject), 5000);
                LuaValue wrapped = bridge.wrapJavaValue(value);
                // Tag the returned wrapper so if the caller tries to invoke it
                // as a method (common mistake: "entity:level()") the error can
                // point at the exact field name and suggest alternatives.
                if (wrapped instanceof JavaObjectWrapper childWrapper) {
                    childWrapper.setOrigin(new AccessOrigin(name, mojangTypeName, declaredType, javaObject));
                }
                return wrapped;
            } catch (Exception e) {
                // Fall through to method wrapper
            }
        }

        // 3. No field — return a MethodCallWrapper (will error at call time
        // if no such method exists, with a helpful message)
        return new MethodCallWrapper(javaObject, declaredType, mojangTypeName, name, bridge);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        if (javaObject == null) {
            throw new LuaError("Attempted to set field on a null object");
        }

        String name = key.tojstring();
        try {
            java.lang.reflect.Field field = findField(declaredType, name);
            if (field == null) {
                throw new LuaError("No field '" + name + "' on " + mojangTypeName);
            }
            field.setAccessible(true);
            Object javaValue = bridge.unwrapLuaValue(value, field.getType());
            bridge.getDispatcher()
                    .executeOnGameThread(
                            () -> {
                                field.set(javaObject, javaValue);
                                return null;
                            },
                            5000);
        } catch (LuaError e) {
            throw e;
        } catch (Exception e) {
            throw new LuaError("Failed to set field '" + name + "': " + e.getMessage());
        }
    }

    // ==================== Call interception ====================

    @Override
    public LuaValue tostring() {
        if (javaObject == null) return LuaValue.valueOf("null");
        return LuaValue.valueOf(mojangTypeName + "@" + Integer.toHexString(System.identityHashCode(javaObject)));
    }

    /**
     * Intercept attempts to invoke this wrapper as if it were a function.
     * <p>
     * Field-first resolution means {@code obj.X} on a Mojang field/method
     * shadow returns the field's value. If the user wrote {@code obj:X(...)}
     * or {@code obj.X(...)}, the parens signal they wanted a method call —
     * fail closed by failing them is hostile when a same-name method exists
     * on the parent type and would do the right thing.
     * <p>
     * Recovery path: when origin metadata is present, the parent is still
     * alive, and a same-name method exists on the parent type, dispatch the
     * method via a fresh {@link MethodCallWrapper}. {@code MethodCallWrapper}
     * handles self-arg stripping (it sees {@code args[0] == parent} for the
     * colon-call shape) and overload resolution by arg count and types.
     * <p>
     * Only fall back to {@link #buildCallError} when recovery is impossible
     * — no origin, parent collected, or no matching method. Mistakes that
     * truly are "you called a field that has no getter" still get the
     * actionable error.
     */
    @Override
    public Varargs invoke(Varargs args) {
        AccessOrigin o = this.origin;
        if (o != null && o.parentInstance != null) {
            Object parent = o.parentInstance.get();
            if (parent != null && parentHasSameNameMethod(o)) {
                return new MethodCallWrapper(parent, o.parentType, o.parentTypeName, o.accessName, bridge).invoke(args);
            }
        }
        throw new LuaError(buildCallError(args));
    }

    @Override
    public Varargs invoke() {
        return invoke(LuaValue.NONE);
    }

    @Override
    public Varargs invoke(LuaValue[] a) {
        return invoke(LuaValue.varargsOf(a));
    }

    // The call(...) family propagates invoke's return value as the single
    // result. Previously these returned LuaValue.NIL because invoke always
    // threw; now invoke can succeed via method-dispatch recovery, so we
    // must forward the first return so call sites like `o:inner().value`
    // see the actual Inner, not nil.
    @Override
    public LuaValue call() {
        return invoke(LuaValue.NONE).arg1();
    }

    @Override
    public LuaValue call(LuaValue a) {
        return invoke(a).arg1();
    }

    @Override
    public LuaValue call(LuaValue a, LuaValue b) {
        return invoke(LuaValue.varargsOf(new LuaValue[] {a, b})).arg1();
    }

    @Override
    public LuaValue call(LuaValue a, LuaValue b, LuaValue c) {
        return invoke(LuaValue.varargsOf(new LuaValue[] {a, b, c})).arg1();
    }

    private String buildCallError(Varargs args) {
        StringBuilder sb = new StringBuilder();
        sb.append("Attempted to call a Java object (").append(mojangTypeName).append(") as if it were a function.");

        AccessOrigin o = this.origin;
        if (o != null) {
            // Detect whether this is the `obj:field()` colon-call shape — the
            // first arg in that case is the parent JavaObjectWrapper that
            // owns the field.
            boolean looksLikeColonCall = args != null
                    && args.narg() >= 1
                    && args.arg(1) instanceof JavaObjectWrapper parent
                    && parent.getDeclaredType() == o.parentType;

            sb.append("\n  ")
                    .append(o.parentTypeName)
                    .append(".")
                    .append(o.accessName)
                    .append(" is a FIELD of type ")
                    .append(mojangTypeName)
                    .append(", not a method.");

            if (looksLikeColonCall) {
                sb.append("\n  You wrote something like  obj:")
                        .append(o.accessName)
                        .append("(...)  which Lua parses as  (obj.")
                        .append(o.accessName)
                        .append(")(obj, ...)  — i.e. calling the field value itself.");
            }

            // Try to surface a getter-style method with the same name if one
            // exists on the parent type. MC's entity/player classes frequently
            // have both `level` (field) and `level()` (getter) — the getter
            // lives under a Mojang name and the field wins in the normal
            // resolution order, so the user's first instinct (colon-call) hits
            // this error. Offering the exact alternative syntax is the whole
            // point of this error message.
            String getterHint = findGetterHint(o);

            sb.append("\n\n  Fix options:");
            if (getterHint != null && getterHint.equals(o.accessName)) {
                // Same-name shadow: the parent type has both a field and a
                // method called accessName. Field-first resolution means
                // `obj:accessName()` always hits the field — so suggesting
                // `obj:accessName()` as the "getter method" form is the exact
                // syntax that just failed. The only working path is to
                // dot-access the field, then operate on its value.
                sb.append("\n    - The field shadows the same-name method. Access via the field:")
                        .append("\n      use  obj.")
                        .append(o.accessName)
                        .append(":<method>(...)")
                        .append("  or  obj.")
                        .append(o.accessName)
                        .append(".<sub-field>");
            } else {
                sb.append("\n    - If you want a nested field: use  obj.")
                        .append(o.accessName)
                        .append(".<sub-field-or-method>");
                if (getterHint != null) {
                    sb.append("\n    - If you want the getter method: use  obj:")
                            .append(getterHint)
                            .append("()");
                } else {
                    sb.append("\n    - If there is a getter method, use  obj:get")
                            .append(capitalize(o.accessName))
                            .append("()  or similar");
                }
            }
        } else {
            // No origin metadata — probably a constructed wrapper or something
            // deeper. Keep the error generic but still call out what happened.
            sb.append("\n  This value is a Java object wrapper, not a callable.")
                    .append("\n  If you got here via  obj:X()  where obj.X is a field,")
                    .append("\n  use  obj.X.<sub>  for field chaining, or look for a")
                    .append("\n  getter method like  obj:getX().");
        }
        return sb.toString();
    }

    /**
     * Does the parent type expose a method with the same name as the field?
     * Used by {@link #invoke(Varargs)} to decide whether to dispatch instead
     * of throwing. Walks the parent's hierarchy, checking both Mojang-mapped
     * names (via the resolver) and literal method names (covers JDK methods
     * and non-mapped runtimes). Arity is intentionally not checked here — if
     * the method exists but the user passed the wrong number of args,
     * {@link MethodCallWrapper#invoke} produces a clear "no method with N
     * args" error, which is more useful than the field-call error.
     */
    private boolean parentHasSameNameMethod(AccessOrigin o) {
        try {
            java.util.Set<Class<?>> visited = new java.util.LinkedHashSet<>();
            MethodCallWrapper.collectHierarchy(o.parentType, visited);
            for (Class<?> c : visited) {
                String mojClass = bridge.getResolver().unresolveClass(c.getName());
                String resolved = bridge.getResolver().resolveMethod(mojClass, o.accessName, null);
                if (!resolved.equals(o.accessName)) return true;
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(o.accessName)) return true;
                }
            }
        } catch (Throwable ignored) {
            // Resolver/reflection problems → no recovery, fall through to error.
        }
        return false;
    }

    /**
     * Check whether the parent type has a Mojang method with the same name as
     * the field access. If so, recommend it directly. If not, fall back to
     * {@code null} and let the caller suggest a generic {@code get<Name>()}.
     */
    private String findGetterHint(AccessOrigin o) {
        try {
            // Walk the parent type's hierarchy asking the resolver whether
            // this name resolves to a mapped runtime method. If so, the method
            // form exists and is almost certainly the user's intent.
            java.util.Set<Class<?>> visited = new java.util.LinkedHashSet<>();
            MethodCallWrapper.collectHierarchy(o.parentType, visited);
            for (Class<?> c : visited) {
                String mojClass = bridge.getResolver().unresolveClass(c.getName());
                String resolved = bridge.getResolver().resolveMethod(mojClass, o.accessName, null);
                if (!resolved.equals(o.accessName)) {
                    // Mapped successfully → there is a method with this Mojang
                    // name, and `obj:name()` (on the parent) would work once
                    // Lua's metamethod returns the field. Since we're stuck
                    // here (the field wins), tell them to use a different
                    // Mojang method name or the field-chain form.
                    return o.accessName;
                }
                // Also check for an existing runtime method whose name matches
                // literally — covers non-Mojang-mapped JDK methods like toString.
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(o.accessName) && m.getParameterCount() == 0) {
                        return o.accessName;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Resolver problems shouldn't mask the original error.
        }
        return null;
    }

    /**
     * Find a field by Mojang name, resolving through mappings and walking the hierarchy.
     */
    private java.lang.reflect.Field findField(Class<?> clazz, String mojangName) {
        // Walk up the class hierarchy
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            String mojangClass = bridge.getResolver().unresolveClass(c.getName());
            String runtimeName = bridge.getResolver().resolveField(mojangClass, mojangName);

            try {
                return c.getDeclaredField(runtimeName);
            } catch (NoSuchFieldException e) {
                // Try next in hierarchy
            }

            // Also try the original name directly (for non-mapped fields)
            if (!runtimeName.equals(mojangName)) {
                try {
                    return c.getDeclaredField(mojangName);
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }
        }
        return null;
    }

    /**
     * Provenance record — where this wrapper came from.
     * Package-private so {@link JavaObjectWrapper} can construct and consume it.
     */
    static final class AccessOrigin {
        final String accessName; // the Lua-side name used to access it
        final String parentTypeName; // parent's Mojang type name (for display)
        final Class<?> parentType; // parent's declared runtime type
        // Weak ref so a long-lived field-value wrapper doesn't artificially
        // extend the parent's lifetime. If the parent is collected we lose the
        // ability to dispatch the same-name method and fall back to the
        // actionable error — that's an acceptable degradation.
        final java.lang.ref.WeakReference<Object> parentInstance;

        AccessOrigin(String accessName, String parentTypeName, Class<?> parentType, Object parentInstance) {
            this.accessName = accessName;
            this.parentTypeName = parentTypeName;
            this.parentType = parentType;
            this.parentInstance = parentInstance == null ? null : new java.lang.ref.WeakReference<>(parentInstance);
        }
    }
}
