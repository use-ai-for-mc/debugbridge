package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.script.ScriptRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the Groovy-Java bridge using plain Java classes (no Minecraft dependency).
 * Uses {@link PassthroughResolver} since we work with real (already-deobfuscated)
 * Java class names, which exercises both the {@code java.*} bridge path and
 * Groovy's native interop.
 */
class GroovyBridgeTest {

    private ScriptRuntime runtime;

    @BeforeEach
    void setup() {
        runtime = new ScriptRuntime(new PassthroughResolver("test"), new DirectDispatcher(), new ObjectRefStore());
    }

    @Test
    void testBasicArithmetic() {
        var result = runtime.execute("return 1 + 2");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(3, ((Number) result.returnValue).intValue());
    }

    @Test
    void testPrintCapture() {
        var result = runtime.execute("println 'hello'\nprintln 'world'");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("hello\nworld\n", result.output);
    }

    @Test
    void testPersistentState() {
        runtime.execute("x = 42");
        var result = runtime.execute("return x");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(42, ((Number) result.returnValue).intValue());
    }

    @Test
    void testTypeConstructAndMethodCall() {
        var result = runtime.execute("""
                def list = java.type("java.util.ArrayList").create()
                list.add("hello")
                list.add("world")
                return list.size()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(2, ((Number) result.returnValue).intValue());
    }

    @Test
    void testConstructViaCallOperator() {
        var result = runtime.execute("""
                def Sb = java.type("java.lang.StringBuilder")
                def sb = Sb("hello")
                sb.append(" world")
                return sb.toString()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("hello world", result.returnValue.toString());
    }

    @Test
    void testStaticFieldAccess() {
        var result = runtime.execute("return java.type('java.lang.Integer').MAX_VALUE");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(Integer.MAX_VALUE, ((Number) result.returnValue).intValue());
    }

    @Test
    void testStaticMethodCall() {
        var result = runtime.execute("return java.type('java.lang.Integer').valueOf(7)");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(7, ((Number) result.returnValue).intValue());
    }

    @Test
    void testNativeGroovyInterop() {
        // On a deobfuscated classpath Groovy can construct + call natively.
        var result = runtime.execute("""
                def list = new java.util.ArrayList()
                list.add("x")
                list.add("y")
                return list.size()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(2, ((Number) result.returnValue).intValue());
    }

    @Test
    void testJavaList() {
        var result = runtime.execute("""
                def list = java.type("java.util.ArrayList").create()
                list.add("a")
                list.add("b")
                list.add("c")
                return java.list(list).size()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(3, ((Number) result.returnValue).intValue());
    }

    @Test
    void testDescribe() {
        var result = runtime.execute("""
                def list = java.type("java.util.ArrayList").create()
                return java.describe(list)['class']
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("java.util.ArrayList", result.returnValue.toString());
    }

    @Test
    void testMethodsReflection() {
        var result = runtime.execute("""
                def list = java.type("java.util.ArrayList").create()
                return java.methods(list, "add").size() > 0
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(Boolean.TRUE, result.returnValue);
    }

    @Test
    void testReturnMap() {
        var result = runtime.execute("return [name: 'test', value: 42, active: true]");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue instanceof Map);
        Map<?, ?> map = (Map<?, ?>) result.returnValue;
        assertEquals("test", map.get("name"));
        assertEquals(42, ((Number) map.get("value")).intValue());
    }

    @Test
    void testReturnList() {
        var result = runtime.execute("return [1, 2, 3]");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue instanceof List);
        assertEquals(3, ((List<?>) result.returnValue).size());
    }

    @Test
    void testSyncBlock() {
        // sync runs its closure on the (here, direct) game thread in one hop.
        var result = runtime.execute("return sync { 40 + 2 }");
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(42, ((Number) result.returnValue).intValue());
    }

    @Test
    void testComplexScenario() {
        var result = runtime.execute("""
                def list = java.type("java.util.ArrayList").create()
                list.add("alpha")
                list.add("beta")
                list.add("gamma")
                def upper = java.list(list).collect { it.toString().toUpperCase() }
                return [count: list.size(), items: upper.join(";")]
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        Map<?, ?> map = (Map<?, ?>) result.returnValue;
        assertEquals(3, ((Number) map.get("count")).intValue());
        assertEquals("ALPHA;BETA;GAMMA", map.get("items"));
    }

    @Test
    void testSecurityBlockingViaType() {
        var result = runtime.execute("java.type('java.lang.Runtime')");
        assertFalse(result.isSuccess());
        assertTrue(
                result.error.contains("blocked") || result.error.contains("security"),
                "Expected security error, got: " + result.error);
    }

    @Test
    void testClassNotFound() {
        var result = runtime.execute("java.type('nonexistent.Foo')");
        assertFalse(result.isSuccess());
        assertTrue(
                result.error.contains("not found") || result.error.contains("Foo"),
                "Expected class-not-found error, got: " + result.error);
    }
}
