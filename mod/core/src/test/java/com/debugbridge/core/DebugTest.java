package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.junit.jupiter.api.Test;

class DebugTest {
    
    @Test
    void debugMethodCall() {
        PassthroughResolver resolver = new PassthroughResolver("test");
        DirectDispatcher dispatcher = new DirectDispatcher();
        ObjectRefStore refs = new ObjectRefStore();
        LuaRuntime runtime = new LuaRuntime(resolver, dispatcher, refs);
        
        // Step by step
        var r1 = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                print("typeof ArrayList: " .. type(ArrayList))
                print("tostring: " .. tostring(ArrayList))
                return ArrayList
                """);
        System.out.println("Step 1 output: " + r1.output);
        System.out.println("Step 1 error: " + r1.error);
        System.out.println("Step 1 result: " + r1.returnValue);
        
        var r2 = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                print("typeof list: " .. type(list))
                print("tostring list: " .. tostring(list))
                return list
                """);
        System.out.println("Step 2 output: " + r2.output);
        System.out.println("Step 2 error: " + r2.error);
        
        var r3 = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                print("list type: " .. type(list))
                local addFn = list.add
                print("addFn type: " .. type(addFn))
                print("addFn tostring: " .. tostring(addFn))
                """);
        System.out.println("Step 3 output: " + r3.output);
        System.out.println("Step 3 error: " + r3.error);
        
        var r4 = runtime.execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local addFn = list.add
                addFn("hello")
                return list:size()
                """);
        System.out.println("Step 4 output: " + r4.output);
        System.out.println("Step 4 error: " + r4.error);
        if (r4.returnValue != null) System.out.println("Step 4 result: " + r4.returnValue);
    }
}
