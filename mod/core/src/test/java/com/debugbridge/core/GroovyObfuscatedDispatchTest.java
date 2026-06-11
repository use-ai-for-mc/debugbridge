package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.script.ScriptRuntime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dispatch tests against a <em>simulated obfuscated</em> class: every Mojang
 * method name maps to a different runtime name per overload, exactly like
 * intermediary mappings on a live client ({@code Vec3.add(Vec3)} and
 * {@code Vec3.add(double,double,double)} are distinct {@code method_NNNN}s).
 *
 * <p>{@link GroovyBridgeTest} runs against plain JDK classes where runtime and
 * Mojang names coincide, which is blind to overload-collapse bugs in the
 * Mojang→runtime resolution path. This suite locks the candidate-set behavior:
 * one Mojang name, several runtime names, matched by arity + argument types.
 */
class GroovyObfuscatedDispatchTest {

    /** Stands in for an obfuscated Vec3: runtime names differ from Mojang names. */
    public static class ObfVec {
        public final double f_x;
        public final double f_y;
        public final double f_z;

        public ObfVec(double x, double y, double z) {
            this.f_x = x;
            this.f_y = y;
            this.f_z = z;
        }

        public ObfVec m_addVec(ObfVec o) {
            return new ObfVec(f_x + o.f_x, f_y + o.f_y, f_z + o.f_z);
        }

        public ObfVec m_addDDD(double a, double b, double c) {
            return new ObfVec(f_x + a, f_y + b, f_z + c);
        }

        public double m_len() {
            return Math.sqrt(f_x * f_x + f_y * f_y + f_z * f_z);
        }

        @Override
        public String toString() {
            return "vec(" + f_x + "," + f_y + "," + f_z + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ObfVec v && v.f_x == f_x && v.f_y == f_y && v.f_z == f_z;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(f_x + f_y + f_z);
        }
    }

    /** Same-arity constructor overloads — selection must look at types, not just count. */
    public static class Picky {
        public final String which;

        public Picky(String s) {
            this.which = "string";
        }

        public Picky(StringBuilder b) {
            this.which = "builder";
        }
    }

    private static final String VEC = "fake.Vec";

    /**
     * Mapping fake that mimics {@code FabricMojangResolver}'s contract: signature
     * keys are ProGuard-style {@code name(type,...)}; resolving with explicit
     * params is exact; resolving with null params collapses to the first overload
     * (the historical behavior the candidate set must compensate for).
     */
    private static PassthroughResolver obfResolver() {
        return new PassthroughResolver("test") {
            @Override
            public String resolveClass(String mojangName) {
                if (VEC.equals(mojangName)) return ObfVec.class.getName();
                if ("fake.Picky".equals(mojangName)) return Picky.class.getName();
                return mojangName;
            }

            @Override
            public String unresolveClass(String runtimeName) {
                if (ObfVec.class.getName().equals(runtimeName)) return VEC;
                if (Picky.class.getName().equals(runtimeName)) return "fake.Picky";
                return runtimeName;
            }

            @Override
            public String resolveField(String cls, String name) {
                if (VEC.equals(cls)) {
                    switch (name) {
                        case "x":
                            return "f_x";
                        case "y":
                            return "f_y";
                        case "z":
                            return "f_z";
                        default:
                    }
                }
                return name;
            }

            @Override
            public java.util.Collection<String> getMethodSignatures(String cls) {
                if (VEC.equals(cls)) {
                    return List.of("add(fake.Vec)", "add(double,double,double)", "length()");
                }
                return List.of();
            }

            @Override
            public String resolveMethod(String cls, String name, String[] params) {
                if (!VEC.equals(cls)) return name;
                if ("add".equals(name)) {
                    if (params == null) return "m_addVec"; // first-match collapse
                    if (params.length == 1) return "m_addVec";
                    if (params.length == 3) return "m_addDDD";
                }
                if ("length".equals(name)) return "m_len";
                return name;
            }
        };
    }

    private ScriptRuntime runtime;

    @BeforeEach
    void setup() {
        runtime = new ScriptRuntime(obfResolver(), new DirectDispatcher(), new ObjectRefStore());
    }

    private Object eval(String code) {
        var result = runtime.execute(code);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        return result.returnValue;
    }

    @Test
    void wrappedObjectAsArgumentSelectsObjectOverload() {
        Object out = eval("""
                def V = java.type('fake.Vec')
                def a = V(1, 2, 3)
                def b = V(4, 5, 6)
                return a.add(b).x
                """);
        assertEquals(5.0, ((Number) out).doubleValue());
    }

    @Test
    void decimalLiteralsSelectPrimitiveOverload() {
        // Groovy decimal literals are BigDecimal; they must coerce to double
        // and pick the (double,double,double) overload's runtime name.
        Object out = eval("""
                def V = java.type('fake.Vec')
                return java.type('fake.Vec')(1, 2, 3).add(0.5, 0.5, 0.5).x
                """);
        assertEquals(1.5, ((Number) out).doubleValue());
    }

    @Test
    void zeroArgMethodResolvesThroughItsOwnRuntimeName() {
        Object out = eval("return java.type('fake.Vec')(3, 0, 4).length()");
        assertEquals(5.0, ((Number) out).doubleValue());
    }

    @Test
    void methodsListingShowsEveryOverloadUnderItsMojangName() {
        Object out = eval("""
                def a = java.type('fake.Vec')(1, 2, 3)
                return java.methods(a, 'add')
                """);
        @SuppressWarnings("unchecked")
        List<String> sigs = (List<String>) out;
        assertEquals(2, sigs.stream().filter(s -> s.contains(" add(")).count(), "got: " + sigs);
    }

    @Test
    void toStringDelegatesToWrappedObject() {
        Object out = eval("return '' + java.type('fake.Vec')(1, 2, 3)");
        assertEquals("vec(1.0,2.0,3.0)", out.toString());
    }

    @Test
    void equalityDelegatesToWrappedObject() {
        Object out = eval("""
                def V = java.type('fake.Vec')
                return V(1, 2, 3) == V(1, 2, 3)
                """);
        assertEquals(Boolean.TRUE, out);
    }

    @Test
    void missingMethodReportsMojangNames() {
        var result = runtime.execute("return java.type('fake.Vec')(1, 2, 3).frob()");
        assertFalse(result.isSuccess());
        assertTrue(result.error.contains(VEC), "error should name " + VEC + ", got: " + result.error);
        assertFalse(result.error.contains("ObfVec"), "error leaks runtime name: " + result.error);
    }

    @Test
    void sameArityConstructorOverloadsSelectByType() {
        assertEquals("string", eval("return java.type('fake.Picky')('hello').which"));
        assertEquals("builder", eval("return java.type('fake.Picky')(java.type('java.lang.StringBuilder')()).which"));
    }

    @Test
    void jdkStaticCallCoercesDecimalArgs() {
        Object out = eval("return java.type('java.lang.Math').max(2.5, 3.5)");
        assertEquals(3.5, ((Number) out).doubleValue());
    }
}
