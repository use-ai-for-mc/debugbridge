package com.debugbridge.core.webui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Wire tests for the embedded web UI server against the test-only asset root
 * ({@code src/test/resources/webui-test/}) so they don't depend on the real
 * Vite build being present. Port 0 = ephemeral, keeping runs parallel-safe.
 */
class WebUiServerTest {

    private static WebUiServer server;
    private static HttpClient client;

    @BeforeAll
    static void start() {
        server = WebUiServer.start(0, "/webui-test");
        assertNotNull(server, "server should start on an ephemeral port");
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop();
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesIndexAtRoot() throws Exception {
        var resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DebugBridge test UI"));
        assertEquals(
                "text/html; charset=utf-8",
                resp.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void servesAssetsWithTheirMimeType() throws Exception {
        var resp = get("/assets/app.js");
        assertEquals(200, resp.statusCode());
        assertEquals(
                "text/javascript; charset=utf-8",
                resp.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void missingFileWithExtensionIs404() throws Exception {
        assertEquals(404, get("/assets/missing.js").statusCode());
    }

    @Test
    void extensionLessPathFallsBackToIndex() throws Exception {
        var resp = get("/some/spa/route");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("DebugBridge test UI"));
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        // %2e%2e decodes to ".." in the URI path — the guard must catch the
        // decoded form before it reaches classloader resolution.
        var resp = get("/assets/%2e%2e/index.html");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void nonGetIs405() throws Exception {
        var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    @Test
    void startReturnsNullWhenAssetsAreNotBundled() {
        assertNull(WebUiServer.start(0, "/no-such-root"));
    }
}
