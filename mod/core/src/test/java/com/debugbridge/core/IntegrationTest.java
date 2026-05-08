package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full integration test: starts the BridgeServer on a random port,
 * connects a WebSocket client, and exercises the protocol.
 */
class IntegrationTest {
    private static BridgeServer server;
    private static int port;
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        port = 19876; // Use a non-default port to avoid conflicts
        PassthroughResolver resolver = new PassthroughResolver("test");
        DirectDispatcher dispatcher = new DirectDispatcher();
        server = new BridgeServer(port, resolver, dispatcher);
        server.start();
        Thread.sleep(500); // Wait for server to start
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void connectClient() throws Exception {
        client = new TestClient(new URI("ws://127.0.0.1:" + port));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS), "Failed to connect");
    }

    @AfterEach
    void disconnectClient() throws Exception {
        if (client != null) client.closeBlocking();
    }

    @Test
    void testStatus() throws Exception {
        JsonObject resp = sendRequest("status", new JsonObject());
        assertTrue(resp.get("success").getAsBoolean());
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("test", result.get("version").getAsString());
        assertEquals("passthrough", result.get("mappingStatus").getAsString());
        assertFalse(result.get("obfuscated").getAsBoolean());
    }

    @Test
    void testExecuteSimpleLua() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "return 1 + 2");

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("number", result.get("type").getAsString());
        assertEquals(3, result.get("value").getAsInt());
    }

    @Test
    void testExecuteWithPrintOutput() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "print('hello from lua')");

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean());
        assertTrue(resp.get("output").getAsString().contains("hello from lua"));
    }

    @Test
    void testExecuteJavaBridge() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("one")
                list:add("two")
                list:add("three")
                return list:size()
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals(3, result.get("value").getAsInt());
    }

    @Test
    void testExecuteReturnJavaObject() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                return java.new(ArrayList)
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("object", result.get("type").getAsString());
        assertEquals("java.util.ArrayList", result.get("className").getAsString());
        assertTrue(result.get("ref").getAsString().startsWith("$ref_"));
    }

    @Test
    void testExecuteReturnTable() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                return {name = "test", value = 42}
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("table", result.get("type").getAsString());
    }

    @Test
    void testExecuteError() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "error('test error')");

        JsonObject resp = sendRequest("execute", payload);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("test error"));
    }

    @Test
    void testPersistentState() throws Exception {
        // Set a variable
        JsonObject payload1 = new JsonObject();
        payload1.addProperty("code", "my_var = 42");
        JsonObject resp1 = sendRequest("execute", payload1);
        assertTrue(resp1.get("success").getAsBoolean());

        // Read it back
        JsonObject payload2 = new JsonObject();
        payload2.addProperty("code", "return my_var");
        JsonObject resp2 = sendRequest("execute", payload2);
        assertTrue(resp2.get("success").getAsBoolean());
        assertEquals(42, resp2.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testReflectionDescribe() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local info = java.describe(list)
                return info.class
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("java.util.ArrayList", result.get("value").getAsString());
    }

    @Test
    void testReflectionMethods() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local methods = java.methods(list, "add")
                local count = 0
                local k = next(methods)
                while k do
                    count = count + 1
                    k = next(methods, k)
                end
                return count
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertTrue(resp.get("result").getAsJsonObject().get("value").getAsInt() > 0);
    }

    @Test
    void testReflectionSupers() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local s = java.supers(list)
                local h = s.hierarchy
                return h
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testReflectionFields() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local f = java.fields(list)
                return f
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testComplexReflectionWorkflow() throws Exception {
        // This simulates what an AI agent would do to explore an unknown object
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                -- Create a HashMap and explore it
                local HashMap = java.import("java.util.HashMap")
                local map = java.new(HashMap)
                map:put("key1", "value1")
                map:put("key2", "value2")

                -- Get type info
                local typeName = java.typeof(map)

                -- List methods containing "get"
                local getMethods = java.methods(map, "get")

                -- Get value
                local val = map:get("key1")

                -- Describe the class
                local desc = java.describe(map)

                return {
                    type = typeName,
                    value = val,
                    classInfo = desc.class,
                    size = map:size()
                }
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testSecurityBlock() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "java.import('java.lang.Runtime')");

        JsonObject resp = sendRequest("execute", payload);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("blocked"));
    }

    @Test
    void testIteratorOverWebSocket() throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", """
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("alpha")
                list:add("beta")
                list:add("gamma")
                local result = {}
                for item in java.iter(list) do
                    table.insert(result, item)
                end
                return table.concat(result, ",")
                """);

        JsonObject resp = sendRequest("execute", payload);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertEquals("alpha,beta,gamma",
                resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testSnapshotWithoutProvider() throws Exception {
        JsonObject resp = sendRequest("snapshot", new JsonObject());
        // Should return an error since we don't have a game state provider in tests
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("provider"));
    }

    // ==================== Helper ====================

    private JsonObject sendRequest(String type, JsonObject payload) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "test_" + System.nanoTime());
        req.addProperty("type", type);
        req.add("payload", payload);

        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response received within 5s");
        return JsonParser.parseString(response).getAsJsonObject();
    }

    /**
     * Simple WebSocket test client that queues received messages.
     */
    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();

        TestClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            responses.offer(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
            ex.printStackTrace();
        }
    }
}
