package com.debugbridge.core.lifecycle;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.mapping.FabricMojangResolver;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.mapping.MappingCache;
import com.debugbridge.core.mapping.MappingDownloader;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.recording.FrameCapturer;
import com.debugbridge.core.recording.RecordingProvider;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.script.ThreadDispatcher;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.session.SessionControlProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import com.debugbridge.core.webui.WebUiServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Version-agnostic lifecycle for the DebugBridge mod entry point. Holds the
 * AtomicBoolean state bookkeeping, port-probe loop, server bootstrap, and
 * tick-time error/info routing that each Fabric module otherwise reimplements
 * verbatim. Per-version subclasses supply the parts that touch the Minecraft
 * API: player message channel, screen open-check, provider instantiation, and
 * mapping resolution.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Subclass calls {@link #initialize()} from its {@code onInitializeClient()}
 *       (Fabric entry point).
 *   <li>Subclass calls {@link #handleTick()} from its mixin tick callback (once
 *       per client tick).
 *   <li>The kernel routes startup messages, opens the warning screen when the
 *       game is ready, and on user accept calls back into the subclass's
 *       {@code create*()} hooks to wire up providers and start the server.
 * </ol>
 *
 * <p>The kernel never imports {@code net.minecraft.*} or {@code net.fabricmc.*};
 * all such references stay on the subclass side of the hooks.
 */
public abstract class AbstractDebugBridgeMod {

    protected static final Logger LOG = Logger.getLogger("DebugBridge");
    protected static final int PORT_RANGE_START = 9876;
    protected static final int PORT_RANGE_END = 9886;
    /** The bundled web UI serves on {@code bridge port + this offset} (9976-9986). */
    protected static final int WEB_UI_PORT_OFFSET = 100;

    protected final AtomicBoolean warningShown = new AtomicBoolean(false);
    protected final AtomicBoolean serverStarted = new AtomicBoolean(false);
    protected BridgeConfig config;
    protected BridgeServer server;
    protected WebUiServer webUiServer;
    protected boolean needsWarning = false;
    protected String startupError = null;
    protected String startupInfo = null;

    /**
     * Subclass calls this from its {@code onInitializeClient()} entry point.
     * Loads config and either starts the server immediately (when developer
     * mode has already been accepted) or arms the warning-screen prompt for
     * the next tick.
     */
    protected final void initialize() {
        LOG.info("[DebugBridge] Initializing for Minecraft " + mcVersion() + "...");
        config = BridgeConfig.load(configDir());
        if (config.developerModeAccepted) {
            startServer();
        } else {
            LOG.info("[DebugBridge] Developer mode not yet accepted, will show warning screen");
            needsWarning = true;
        }
    }

    /**
     * Subclass calls this from its mixin tick callback. Routes pending
     * startup error/info messages to the player, runs the per-version
     * post-tick hook, and shows the developer-mode warning screen when the
     * game is ready.
     */
    protected final void handleTick() {
        if (startupError != null && displayPlayerError(startupError)) {
            startupError = null;
        }
        if (startupInfo != null && displayPlayerInfo(startupInfo)) {
            startupInfo = null;
        }

        onPostTick();

        if (!needsWarning) {
            return;
        }
        if (!warningShown.get() && canShowWarningScreen()) {
            warningShown.set(true);
            showWarningScreen(accepted -> {
                if (accepted) {
                    LOG.info("[DebugBridge] Developer mode accepted by user");
                    startServer();
                } else {
                    LOG.info("[DebugBridge] Developer mode declined, mod disabled");
                }
                needsWarning = false;
            });
        }
    }

    private void startServer() {
        if (serverStarted.getAndSet(true)) {
            return;
        }

        MappingResolver resolver = buildResolver();
        ThreadDispatcher dispatcher = createDispatcher();
        GameStateProvider stateProvider = createStateProvider();
        ScreenshotProvider screenshotProvider = createScreenshotProvider();

        int actualPort =
                startServerOnAvailablePort(config.port, resolver, dispatcher, stateProvider, screenshotProvider);

        if (actualPort == -1) {
            String msg = "Could not bind to any port in range " + PORT_RANGE_START + "-" + PORT_RANGE_END;
            LOG.severe("[DebugBridge] " + msg);
            startupError = msg;
            return;
        }

        server.setEntitiesProvider(createEntitiesProvider());
        server.setBlocksProvider(createBlocksProvider());
        server.setTextureProvider(createTextureProvider());
        server.setLookedAtEntityProvider(createLookedAtEntityProvider());
        server.setChatHistoryProvider(createChatHistoryProvider());
        server.setScreenInspectProvider(createScreenInspectProvider());
        server.setRunCommandEnabled(config.runCommandEnabled);
        SessionControlProvider sessionControl = createSessionControlProvider();
        if (sessionControl != null) {
            server.setSessionControlProvider(sessionControl);
        }
        server.setSessionControlEnabled(config.sessionControlEnabled);

        FrameCapturer frameCapturer = createFrameCapturer();
        Path gd = gameDir();
        if (frameCapturer != null && gd != null) {
            Path recordingsDir = gd.resolve("debugbridge-recordings");
            server.setRecordingProvider(new RecordingProvider(frameCapturer, recordingsDir));
        } else {
            LOG.info("[DebugBridge] Recording provider not registered (no frame capturer or game dir)");
        }

        startWebUi(actualPort);

        StringBuilder info = new StringBuilder();
        if (actualPort != config.port) {
            info.append("Server started on port ")
                    .append(actualPort)
                    .append(" (default ")
                    .append(config.port)
                    .append(" was in use)");
        }
        if (webUiServer != null) {
            if (info.length() > 0) info.append(" — ");
            info.append("Web UI: http://localhost:").append(webUiServer.getPort());
        }
        if (info.length() > 0) {
            startupInfo = info.toString();
        }
        LOG.info("[DebugBridge] Server started on port " + actualPort);
    }

    /**
     * Start the bundled web UI on {@code bridgePort + }{@link #WEB_UI_PORT_OFFSET}.
     * The fixed offset keeps the mapping deterministic both ways: each game
     * instance gets its own UI port, and the served page derives its owning
     * bridge port as {@code location.port - offset} so it connects to the
     * right instance when several run side by side. Best-effort — a missing
     * bundle or occupied port logs and moves on. Tests override to keep unit
     * runs off the network.
     */
    protected void startWebUi(int bridgePort) {
        if (!config.webUiEnabled) {
            LOG.info("[DebugBridge] Web UI disabled by config (web_ui_enabled=false)");
            return;
        }
        webUiServer = WebUiServer.start(bridgePort + WEB_UI_PORT_OFFSET);
        if (webUiServer != null) {
            server.setWebUiPort(webUiServer.getPort());
            LOG.info("[DebugBridge] Web UI at http://localhost:" + webUiServer.getPort());
        }
    }

    private int startServerOnAvailablePort(
            int preferredPort,
            MappingResolver resolver,
            ThreadDispatcher dispatcher,
            GameStateProvider stateProvider,
            ScreenshotProvider screenshotProvider) {
        int startPort = Math.max(PORT_RANGE_START, Math.min(preferredPort, PORT_RANGE_END));
        for (int port = startPort; port <= PORT_RANGE_END; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }
        for (int port = PORT_RANGE_START; port < startPort; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Probes the port and (on success) creates and starts a {@link BridgeServer}.
     * On success, sets {@link #server} and returns {@code true}; on failure
     * (port unavailable or constructor/start threw), returns {@code false} so
     * the caller advances to the next candidate. Tests override this hook to
     * inject a stub server without touching the network.
     */
    protected boolean tryStartOnPort(
            int port,
            MappingResolver resolver,
            ThreadDispatcher dispatcher,
            GameStateProvider stateProvider,
            ScreenshotProvider screenshotProvider) {
        if (!isPortAvailable(port)) {
            LOG.info("[DebugBridge] Port " + port + " is not available");
            return false;
        }
        try {
            server = new BridgeServer(port, resolver, dispatcher, stateProvider, screenshotProvider);
            server.setReuseAddr(true);
            server.setGameDir(gameDir());
            server.start();
            return true;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[DebugBridge] Failed to start server on port " + port, e);
            return false;
        }
    }

    /**
     * Probes whether {@code port} is bind-able by transiently opening a
     * {@link ServerSocket} on 127.0.0.1 with {@code SO_REUSEADDR} set —
     * matching what {@link BridgeServer} does, so the probe and the real
     * bind agree on what counts as "available".
     */
    protected boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Hooks the subclass must implement ----

    /** Minecraft version string (used for log lines + the {@code status} response). */
    protected abstract String mcVersion();

    /** Directory that holds {@code debugbridge.json} (typically {@code .minecraft/config/}). */
    protected abstract Path configDir();

    /** Game run directory — passed through to {@link BridgeServer#setGameDir(Path)}. */
    protected abstract Path gameDir();

    /**
     * Build the mapping resolver. Default: download (or load cached) ProGuard
     * mappings for {@link #mcVersion()}, parse, and wrap them in a {@link
     * FabricMojangResolver} keyed off the subclass's {@link
     * #createNamespaceLookup()}. If that hook returns {@code null} or the
     * mapping pipeline throws, falls back to {@link PassthroughResolver}.
     *
     * <p>Subclasses can override entirely (e.g. unobfuscated snapshot builds
     * that have no Mojang mappings to apply).
     */
    protected MappingResolver buildResolver() {
        FabricNamespaceLookup lookup = createNamespaceLookup();
        if (lookup == null) {
            return new PassthroughResolver(mcVersion());
        }
        try {
            MappingCache cache = new MappingCache();
            String content;
            if (cache.has(mcVersion())) {
                LOG.info("[DebugBridge] Loading cached " + mcVersion() + " mappings...");
                content = cache.load(mcVersion());
            } else {
                LOG.info("[DebugBridge] Downloading " + mcVersion() + " mappings from Mojang...");
                content = new MappingDownloader().download(mcVersion());
                cache.save(mcVersion(), content);
            }
            ParsedMappings mappings = ProGuardParser.parse(content);
            LOG.info("[DebugBridge] Parsed " + mappings.classes.size() + " classes from mappings.");
            return new FabricMojangResolver(mcVersion(), mappings, lookup);
        } catch (Exception e) {
            LOG.warning("[DebugBridge] Failed to load mappings, falling back to passthrough: " + e.getMessage());
            return new PassthroughResolver(mcVersion());
        }
    }

    /**
     * Subclass hook supplying the Fabric-side namespace adapter for the
     * default {@link #buildResolver()}. Return {@code null} to skip mapping
     * download and use {@link PassthroughResolver} (e.g. for unobfuscated
     * snapshot builds).
     */
    protected abstract FabricNamespaceLookup createNamespaceLookup();

    /**
     * Build a thread dispatcher that hops to the game thread. Default impl
     * uses {@link CompletableFuture} + {@link #submitToGameThread(Runnable)};
     * subclasses can override entirely if their version exposes a different
     * dispatch primitive.
     */
    protected ThreadDispatcher createDispatcher() {
        return new ThreadDispatcher() {
            @Override
            public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
                CompletableFuture<T> future = new CompletableFuture<>();
                submitToGameThread(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                return future.get(timeout, TimeUnit.MILLISECONDS);
            }
        };
    }

    /**
     * Submit {@code task} for execution on the Minecraft client thread.
     * Subclasses delegate to the version's {@code Minecraft#execute(Runnable)}
     * (or equivalent). Return must be immediate; the kernel handles the
     * future / timeout in {@link #createDispatcher()}.
     */
    protected abstract void submitToGameThread(Runnable task);

    protected abstract GameStateProvider createStateProvider();

    protected abstract ScreenshotProvider createScreenshotProvider();

    protected abstract ItemTextureProvider createTextureProvider();

    protected abstract NearbyEntitiesProvider createEntitiesProvider();

    protected abstract NearbyBlocksProvider createBlocksProvider();

    protected abstract LookedAtEntityProvider createLookedAtEntityProvider();

    protected abstract ChatHistoryProvider createChatHistoryProvider();

    protected abstract ScreenInspectProvider createScreenInspectProvider();

    protected abstract SessionControlProvider createSessionControlProvider();

    /**
     * Build the per-frame capture primitive for {@code record_video}. Default
     * returns {@code null} which leaves the recording provider unregistered
     * (the endpoint then surfaces a clean "no provider" error). Each
     * production subclass overrides with a {@code Minecraft{Version}FrameCapturer}.
     */
    protected FrameCapturer createFrameCapturer() {
        return null;
    }

    /**
     * Display an error message to the local player. Returns {@code true} if
     * the message was actually shown; {@code false} when the player isn't yet
     * loaded — caller will keep the message and retry on the next tick.
     */
    protected abstract boolean displayPlayerError(String message);

    /** Display an info message; same semantics as {@link #displayPlayerError}. */
    protected abstract boolean displayPlayerInfo(String message);

    /** True when the warning screen can be opened (no other screen/overlay active). */
    protected abstract boolean canShowWarningScreen();

    /**
     * Open the developer-mode warning screen. The supplied callback receives
     * the user's choice ({@code true} = accept, {@code false} = decline) and
     * must be invoked from the game thread once the screen closes. The
     * subclass is responsible for dismissing the screen before invoking the
     * callback.
     */
    protected abstract void showWarningScreen(Consumer<Boolean> onResult);

    /**
     * Per-tick hook between startup-message routing and the warning-screen
     * check. Default no-op; 1.21.11 and 26.2 override to refresh client-side
     * block-glow highlights through the version's {@code LevelRenderer}
     * surface (which moved between versions).
     */
    protected void onPostTick() {
        // no-op
    }

    /**
     * Subclass calls this from its render-tick mixin (TAIL of
     * {@code Minecraft.runTick}). Drives the active recording session
     * forward by one frame, if any; cheap no-op otherwise.
     */
    protected final void handleRenderFrame() {
        if (server == null) return;
        RecordingProvider rp = server.getRecordingProvider();
        if (rp != null) {
            rp.onRenderFrame();
        }
    }

    /**
     * Subclass calls this from its {@code Minecraft.close()} mixin (HEAD).
     * Stops the {@link BridgeServer} so its non-daemon worker pool (selector +
     * decoders + connection-lost checker) doesn't pin the JVM in
     * {@code Threads::destroy_vm()} after the render thread exits. Without
     * this, the MC client-shutdown watchdog eventually {@code abort()}s the
     * process — see {@code Minecraft.close()} in newer snapshots, which have
     * a stricter timeout than 1.21.x.
     */
    protected final void handleClose() {
        WebUiServer ui = webUiServer;
        if (ui != null) {
            webUiServer = null;
            try {
                ui.stop();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[DebugBridge] Web UI server failed to stop cleanly", e);
            }
        }
        BridgeServer s = server;
        if (s == null) return;
        server = null;
        try {
            // 1s grace period — workers idle on a take() will unwind in well
            // under that; longer waits compete with MC's own shutdown watchdog.
            s.stop(1_000);
            LOG.info("[DebugBridge] BridgeServer stopped on client close");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("[DebugBridge] Interrupted while stopping BridgeServer on close");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[DebugBridge] BridgeServer failed to stop cleanly", e);
        } finally {
            s.closeTextureProvider();
        }
    }
}
