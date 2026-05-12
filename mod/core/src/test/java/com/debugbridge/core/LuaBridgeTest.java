package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Lua-Java bridge using plain Java classes (no Minecraft dependency).
 * Uses PassthroughResolver since we're working with real Java class names.
 */
class LuaBridgeTest {
    
    private LuaRuntime runtime;
    
    @BeforeEach
    void setup() {
        PassthroughResolver resolver = new PassthroughResolver("test");
        DirectDispatcher dispatcher = new DirectDispatcher();
        ObjectRefStore refs = new ObjectRefStore();
        runtime = new LuaRuntime(resolver, dispatcher, refs);
    }
    
    @Test
    void testBasicLua() {
        var result = runtime.execute("return 1 + 2");
        assertTrue(result.isSuccess());
        assertEquals(3, result.returnValue.toint());
    }
    
    @Test
    void testPrint() {
        var result = runtime.execute("print('hello', 'world')");
        assertTrue(result.isSuccess());
        assertEquals("hello\tworld\n", result.output);
    }
    
    @Test
    void testPersistentState() {
        runtime.execute("x = 42");
        var result = runtime.execute("return x");
        assertTrue(result.isSuccess());
        assertEquals(42, result.returnValue.toint());
    }
    
    @Test
    void testImportJavaClass() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                return java.typeof(ArrayList)
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("java.util.ArrayList", result.returnValue.tojstring());
    }
    
    @Test
    void testCreateInstance() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                return java.typeof(list)
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("java.util.ArrayList", result.returnValue.tojstring());
    }
    
    @Test
    void testMethodCall() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("hello")
                list:add("world")
                return list:size()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(2, result.returnValue.toint());
    }
    
    @Test
    void testMethodCallReturnValue() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("hello")
                return list:get(0)
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("hello", result.returnValue.tojstring());
    }
    
    @Test
    void testFieldAccess() {
        var result = runtime.execute("""
                local Integer = java.import("java.lang.Integer")
                return Integer.MAX_VALUE
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(Integer.MAX_VALUE, result.returnValue.toint());
    }
    
    @Test
    void testIsNull() {
        var result = runtime.execute("""
                return java.isNull(nil)
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue.toboolean());
    }
    
    @Test
    void testIterator() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("a")
                list:add("b")
                list:add("c")
                local items = {}
                for item in java.iter(list) do
                    table.insert(items, item)
                end
                return table.concat(items, ",")
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("a,b,c", result.returnValue.tojstring());
    }
    
    @Test
    void testArray() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("x")
                list:add("y")
                local arr = java.array(list)
                return #arr
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals(2, result.returnValue.toint());
    }
    
    @Test
    void testCast() {
        var result = runtime.execute("""
                local HashMap = java.import("java.util.HashMap")
                local map = java.new(HashMap)
                local asMap = java.cast(map, "java.util.Map")
                return java.typeof(asMap)
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("java.util.Map", result.returnValue.tojstring());
    }
    
    @Test
    void testConstructorWithArgs() {
        var result = runtime.execute("""
                local StringBuilder = java.import("java.lang.StringBuilder")
                local sb = java.new(StringBuilder, "hello")
                sb:append(" world")
                return sb:toString()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("hello world", result.returnValue.tojstring());
    }
    
    @Test
    void testMethodChaining() {
        var result = runtime.execute("""
                local StringBuilder = java.import("java.lang.StringBuilder")
                local sb = java.new(StringBuilder)
                sb:append("a")
                sb:append("b")
                sb:append("c")
                return sb:toString()
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("abc", result.returnValue.tojstring());
    }
    
    @Test
    void testDescribe() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local info = java.describe(list)
                return info.class
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("java.util.ArrayList", result.returnValue.tojstring());
    }
    
    @Test
    void testMethodsReflection() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local methods = java.methods(list, "add")
                return #methods > 0
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue.toboolean());
    }
    
    @Test
    void testFieldsReflection() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local fields = java.fields(list)
                return type(fields) == "userdata" or type(fields) == "table"
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
    }
    
    @Test
    void testSupers() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local s = java.supers(list)
                local hierarchy = s.hierarchy
                print("hierarchy type: " .. type(hierarchy))
                return hierarchy
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
    }
    
    @Test
    void testErrorMessages() {
        var result = runtime.execute("""
                local Foo = java.import("nonexistent.Foo")
                """);
        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("not found") || result.error.contains("Class"),
                "Expected class not found error, got: " + result.error);
    }
    
    @Test
    void testSecurityBlocking() {
        var result = runtime.execute("""
                local rt = java.import("java.lang.Runtime")
                """);
        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("blocked") || result.error.contains("security"),
                "Expected security error, got: " + result.error);
    }
    
    @Test
    void testReturnTable() {
        var result = runtime.execute("""
                return {name = "test", value = 42, active = true}
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue.istable());
        assertEquals("test", result.returnValue.get("name").tojstring());
        assertEquals(42, result.returnValue.get("value").toint());
    }
    
    @Test
    void testHashMapOperations() {
        var result = runtime.execute("""
                local HashMap = java.import("java.util.HashMap")
                local map = java.new(HashMap)
                map:put("key1", "value1")
                map:put("key2", "value2")
                return map:get("key1")
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertEquals("value1", result.returnValue.tojstring());
    }
    
    @Test
    void testComplexScenario() {
        var result = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("alpha")
                list:add("beta")
                list:add("gamma")
                
                local results = {}
                for item in java.iter(list) do
                    table.insert(results, string.upper(item))
                end
                
                return {
                    count = list:size(),
                    items = table.concat(results, ";"),
                    empty = list:isEmpty()
                }
                """);
        assertTrue(result.isSuccess(), "Error: " + result.error);
        assertTrue(result.returnValue.istable());
        assertEquals(3, result.returnValue.get("count").toint());
        assertEquals("ALPHA;BETA;GAMMA", result.returnValue.get("items").tojstring());
    }
}
