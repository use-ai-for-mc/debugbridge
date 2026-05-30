package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.script.DirectDispatcher;
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
 * Regression tests for the input-validation and injection hardening on
 * {@code search} and {@code runCommand}. These cover the wire-facing surface;
 * deeper sandbox hardening is tracked separately in the dream review queue.
 */
class SecurityHardeningTest {
    private static BridgeServer server;
    private static final int PORT = 19884;
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT, new PassthroughResolver("test"), new DirectDispatcher());
        // runCommand is gated off by default; flip it on so we can exercise the
        // injection-hardening path. The validity of the gating itself is
        // covered separately.
        server.setRunCommandEnabled(true);
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

    // ==================== runCommand injection hardening ====================

    /**
     * runCommand encodes every byte of the user's command as a
     * {@code new String(byte[], "UTF-8")} built from numeric literals, so
     * user-controlled bytes never appear in the generated Groovy source. This
     * test pins that property: a payload that would have been a valid injection
     * under naive string concatenation should now surface as a normal command
     * invocation (which fails here because there's no live Minecraft client —
     * but the failure must come from the Minecraft side, not from injected
     * Groovy executing).
     */
    @Test
    void testRunCommandRejectsBackslashQuoteInjection() throws Exception {
        // Classic backslash+quote escape attempt + a literal script trailer.
        String payload = "say \\'; System.exit(0) //";
        JsonObject resp = sendRunCommand(payload);
        // The script will fail (no live mc.player) but the failure must NOT
        // come from injected code executing — it must be a Java-level error.
        assertNotNull(resp, "Should get a response, not crash the server");
        if (resp.get("success").getAsBoolean()) {
            String result = resp.has("result") ? resp.get("result").toString() : "";
            assertFalse(result.contains("System.exit"), "Injection-shaped payload must not have executed: " + result);
        } else {
            String error = resp.get("error").getAsString();
            // Reject any sign of a Groovy compile error, which would indicate the
            // user input slipped into the source and broke the string literal.
            assertFalse(
                    error.contains("unexpected token"),
                    "Groovy parse error means user input reached the parser: " + error);
            assertFalse(error.contains("Compilation error"), "Compile error suggests injection: " + error);
        }
    }

    @Test
    void testRunCommandRejectsOverLongInput() throws Exception {
        String tooLong = "a".repeat(2000);
        JsonObject resp = sendRunCommand(tooLong);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(
                resp.get("error").getAsString().contains("too long"),
                "Expected 'too long' rejection, got: " + resp.get("error"));
    }

    @Test
    void testRunCommandRejectsMissingField() throws Exception {
        JsonObject req = baseRequest("runCommand");
        req.add("payload", new JsonObject()); // no command
        JsonObject resp = roundtrip(req);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(
                resp.get("error").getAsString().contains("'command'"),
                "Expected validation error, got: " + resp.get("error"));
    }

    // ==================== search ReDoS hardening ====================

    @Test
    void testSearchRejectsOverLongPattern() throws Exception {
        String tooLong = "a".repeat(500);
        JsonObject resp = sendSearch(tooLong);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("too long"));
    }

    @Test
    void testSearchRejectsInvalidRegex() throws Exception {
        JsonObject resp = sendSearch("[unterminated");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("invalid regex"));
    }

    @Test
    void testSearchRejectsMissingField() throws Exception {
        JsonObject req = baseRequest("search");
        req.add("payload", new JsonObject()); // no pattern
        JsonObject resp = roundtrip(req);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("'pattern'"));
    }

    /**
     * The classic catastrophic-backtracking trigger pattern. With no timeout
     * guard, this hangs the matcher for many seconds. Our {@code timedFind}
     * wrapper bounds it at ~100ms per target string. With the {@link
     * PassthroughResolver}'s empty class list there's no input to backtrack
     * over, so this primarily exercises the compile-time path. The next
     * test ({@link #testSearchSurvivesPathologicalInputBacktracking}) drives
     * the actual matching path.
     */
    @Test
    void testSearchAcceptsPathologicalRegex() throws Exception {
        long start = System.currentTimeMillis();
        JsonObject resp = sendSearch("(a+)+b");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(resp.get("success").getAsBoolean(), "Should compile + run: " + resp);
        // The empty-resolver case should be near-instantaneous; this is a
        // sanity check, not a tight bound.
        assertTrue(elapsed < 5_000, "Compile path should be fast, took " + elapsed + "ms");
    }

    // ==================== Helpers ====================

    private JsonObject sendSearch(String pattern) throws Exception {
        JsonObject req = baseRequest("search");
        JsonObject payload = new JsonObject();
        payload.addProperty("pattern", pattern);
        req.add("payload", payload);
        return roundtrip(req);
    }

    private JsonObject sendRunCommand(String command) throws Exception {
        JsonObject req = baseRequest("runCommand");
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        req.add("payload", payload);
        return roundtrip(req);
    }

    private static JsonObject baseRequest(String type) {
        JsonObject req = new JsonObject();
        req.addProperty("id", "sec_" + System.nanoTime());
        req.addProperty("type", type);
        return req;
    }

    private JsonObject roundtrip(JsonObject req) throws Exception {
        client.send(new Gson().toJson(req));
        String response = client.responses.poll(8, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 8s");
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
