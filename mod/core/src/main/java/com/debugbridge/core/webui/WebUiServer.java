package com.debugbridge.core.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the bundled web UI — the Vite build embedded under {@code /webui} in
 * the core jar — over plain HTTP. Static assets only, GET only, and the
 * socket binds to the loopback address, mirroring the bridge's own posture:
 * nothing here is reachable from the network.
 *
 * <p>The UI itself talks to the bridge over its WebSocket; this server's only
 * job is delivering the HTML/JS/CSS so users don't need a checkout of the
 * repo and a Node toolchain. Builds without the embedded assets (e.g. a local
 * Gradle run with the web-ui build skipped) simply don't start it.
 */
public final class WebUiServer {

    private static final Logger LOG = Logger.getLogger("DebugBridge");
    private static final String DEFAULT_RESOURCE_ROOT = "/webui";

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "map", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2");

    private final HttpServer http;
    private final String resourceRoot;

    private WebUiServer(HttpServer http, String resourceRoot) {
        this.http = http;
        this.resourceRoot = resourceRoot;
    }

    /** True when the web UI assets were bundled into this build. */
    public static boolean isBundled() {
        return WebUiServer.class.getResource(DEFAULT_RESOURCE_ROOT + "/index.html") != null;
    }

    /**
     * Start serving on the given loopback port. Returns {@code null} (with a
     * log line) when the assets aren't bundled or the port can't be bound —
     * the web UI is a convenience, never a reason to fail mod startup.
     */
    public static WebUiServer start(int port) {
        return start(port, DEFAULT_RESOURCE_ROOT);
    }

    static WebUiServer start(int port, String resourceRoot) {
        if (WebUiServer.class.getResource(resourceRoot + "/index.html") == null) {
            LOG.info("[DebugBridge] Web UI assets not bundled in this build; UI server not started");
            return null;
        }
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            WebUiServer server = new WebUiServer(http, resourceRoot);
            http.createContext("/", server::handle);
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "debugbridge-webui");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            return server;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[DebugBridge] Could not start web UI server on port " + port, e);
            return null;
        }
    }

    public int getPort() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(
                        exchange,
                        405,
                        "text/plain; charset=utf-8",
                        "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Asset asset = load(exchange.getRequestURI().getPath());
            if (asset == null) {
                respond(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, mimeFor(asset.name), asset.bytes);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * Resolve a request path to a bundled asset. {@code /} serves
     * {@code index.html}; unknown extension-less paths fall back to it too
     * (SPA-style), while missing files with extensions 404. Paths that try to
     * climb out of the resource root are rejected outright.
     */
    private Asset load(String rawPath) throws IOException {
        if (rawPath == null || rawPath.contains("..") || rawPath.contains("\\") || rawPath.contains("//")) {
            return null;
        }
        String path = rawPath.equals("/") ? "/index.html" : rawPath;
        Asset asset = read(path);
        if (asset == null && !path.substring(path.lastIndexOf('/') + 1).contains(".")) {
            asset = read("/index.html");
        }
        return asset;
    }

    private Asset read(String path) throws IOException {
        try (InputStream in = WebUiServer.class.getResourceAsStream(resourceRoot + path)) {
            if (in == null) {
                return null;
            }
            return new Asset(path, in.readAllBytes());
        }
    }

    private static String mimeFor(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return MIME_BY_EXTENSION.getOrDefault(ext, "application/octet-stream");
    }

    private record Asset(String name, byte[] bytes) {}
}
