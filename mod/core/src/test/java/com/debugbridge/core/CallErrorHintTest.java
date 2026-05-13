package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

/**
 * Regression tests for the actionable error messages added when Lua tries to
 * call a Java userdata as if it were a function. These are the most common
 * mistakes in practice — Mojang 1.21.x has many fields that shadow same-named
 * getter methods, and users coming from Python/JS expect class(args) to work
 * as a constructor.
 */
class CallErrorHintTest {
    private static BridgeServer server;
    private static final int PORT = 19883;
    private TestClient client;

    /**
     * Public helper class used as the target of call-as-method tests.
     *
     * <p>Both {@code inner} and {@code only} are fields of type {@link Inner}.
     * {@code inner} is shadowed by a same-named getter method — this mirrors
     * the Mojang {@code Entity.level} / {@code Entity.level()} pattern.
     * {@code only} is field-only.
     *
     * <p>Under {@link com.debugbridge.core.lua.JavaObjectWrapper}'s field-first
     * resolution, bare access ({@code o.inner} / {@code o.only}) returns the
     * field value. Calling syntax behaves differently for the two:
     * <ul>
     *   <li>{@code o:only()} — no same-name method on the parent, so this
     *       still hits the actionable error path. See
     *       {@link #testCallingFieldAsMethodGivesActionableError()}.</li>
     *   <li>{@code o:inner()} — the field shadows a same-name getter. The
     *       parens signal a method call, so {@code invoke()} dispatches
     *       {@link Outer#inner()} on the parent instead of erroring. See
     *       {@link #testShadowedFieldCallDispatchesSameNameMethod()}.</li>
     * </ul>
     */
    public static class Outer {
        public final Inner inner = new Inner();
        public final Inner only = new Inner();

        @SuppressWarnings("unused") // Accessed reflectively through the bridge
        public Inner inner() {
            return inner;
        }
    }

    public static class Inner {
        public final int value = 42;
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT, new PassthroughResolver("test"), new DirectDispatcher());
        server.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void connect() throws Exception {
        client = new TestClient(new URI("ws://127.0.0.1:" + PORT));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
    }

    @AfterEach
    void disconnect() throws Exception {
        if (client != null) client.closeBlocking();
    }

    // ==================== obj:field() mistake ====================

    @Test
    void testCallingFieldAsMethodGivesActionableError() throws Exception {
        // `only` is field-only (no shadowing method), so calling it as a method
        // takes the field-then-call path that produces the actionable error.
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            o:only()
            """);
        assertFalse(resp.get("success").getAsBoolean(), "Should fail");
        String error = resp.get("error").getAsString();
        System.out.println("obj:field() error:\n" + error);

        // Must identify this as a Java-object-call, not LuaJ's generic mumble.
        assertTrue(error.contains("Java object"), "Error should mention it's a Java object, got: " + error);
        // Must name the field and the parent type.
        assertTrue(error.contains("only"), "Error should mention the field name 'only', got: " + error);
        assertTrue(
                error.contains("Outer") || error.contains("CallErrorHintTest"),
                "Error should mention the parent type, got: " + error);
        // Must mention that it's a FIELD (not a method).
        assertTrue(error.contains("FIELD") || error.contains("field"), "Error should say it's a field, got: " + error);
        // Must give the exact corrected syntax.
        assertTrue(
                error.contains("obj.only") || error.contains(".only."),
                "Error should suggest obj.only.<sub> syntax, got: " + error);
    }

    @Test
    void testCallingFieldAsMethodDetectsColonCall() throws Exception {
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            o:only()
            """);
        String error = resp.get("error").getAsString();
        // The colon-call detection should fire because the first "arg" passed
        // to the invoked wrapper is the parent Outer wrapper.
        assertTrue(
                error.contains("obj:") || error.contains("obj.only"),
                "Should explain the colon-call desugaring, got: " + error);
    }

    @Test
    void testFieldChainAfterFieldAccessStillWorks() throws Exception {
        // Field chaining (the suggested fix) should still work normally on a
        // non-shadowed field.
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            return o.only.value
            """);
        assertTrue(resp.get("success").getAsBoolean(), "Field chaining should work: " + resp);
        assertEquals(42, resp.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testShadowedFieldCallDispatchesSameNameMethod() throws Exception {
        // Field-first resolution returns the field value at `o.inner`. When
        // the user writes `o:inner()`, the parens are an unambiguous signal
        // that they want a method call — invoke() recovers by dispatching
        // the same-name method on the parent (Outer.inner()) instead of
        // failing. This is the Mojang `Entity.level()` /
        // `ClientPacketListener.registryAccess()` pattern.
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            return o:inner().value
            """);
        assertTrue(
                resp.get("success").getAsBoolean(), "Colon-call on shadowed field should dispatch the method: " + resp);
        assertEquals(42, resp.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testShadowedFieldDotCallAlsoDispatchesSameNameMethod() throws Exception {
        // The `()` is what signals "call a method", not the colon. Dot-call
        // through a stashed reference (`local f = o.inner; f()`) should also
        // recover, since invoke() can still see the origin and reach the
        // parent through the weak ref.
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            return o.inner().value
            """);
        assertTrue(
                resp.get("success").getAsBoolean(), "Dot-call on shadowed field should dispatch the method: " + resp);
        assertEquals(42, resp.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testShadowedFieldChainStillResolvesField() throws Exception {
        // The disambiguation form should keep working on a shadowed field too:
        // `o.inner.value` reads the field, no method call involved.
        JsonObject resp = execute("""
            local Outer = java.import("com.debugbridge.core.CallErrorHintTest$Outer")
            local o = java.new(Outer)
            return o.inner.value
            """);
        assertTrue(resp.get("success").getAsBoolean(), "Shadowed-field chaining should work: " + resp);
        assertEquals(42, resp.get("result").getAsJsonObject().get("value").getAsInt());
    }

    // ==================== class(args) mistake ====================

    @Test
    void testCallingClassWrapperAsConstructorGivesActionableError() throws Exception {
        JsonObject resp = execute("""
            local ArrayList = java.import("java.util.ArrayList")
            local list = ArrayList()
            """);
        assertFalse(resp.get("success").getAsBoolean(), "Should fail");
        String error = resp.get("error").getAsString();
        System.out.println("Class() error:\n" + error);

        assertTrue(error.contains("ArrayList"), "Should name the class, got: " + error);
        assertTrue(error.contains("java.new"), "Should tell the user to use java.new, got: " + error);
    }

    @Test
    void testCallingClassWrapperWithArgsGivesActionableError() throws Exception {
        JsonObject resp = execute("""
            local ArrayList = java.import("java.util.ArrayList")
            local list = ArrayList(10)
            """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("java.new"), "Should recommend java.new, got: " + error);
    }

    // ==================== Helpers ====================

    private JsonObject execute(String code) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "hint_" + System.nanoTime());
        req.addProperty("type", "execute");
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        req.add("payload", payload);

        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 5s");
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();

        TestClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake h) {}

        @Override
        public void onMessage(String msg) {
            responses.offer(msg);
        }

        @Override
        public void onClose(int c, String r, boolean rem) {}

        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }
    }
}
