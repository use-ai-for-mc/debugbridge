package com.debugbridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Reads config from .minecraft/config/debugbridge.json (or a provided path).
 * Falls back to sensible defaults if the file doesn't exist.
 */
public class BridgeConfig {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_PORT = 9876;
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final long DEFAULT_SCRIPT_MAX_EXECUTION_TIME_MS = 5000;
    private static final int MIN_BRIDGE_PORT = 9876;
    private static final int MAX_BRIDGE_PORT = 9886;

    public int port = DEFAULT_PORT;

    @SerializedName("timeout_ms")
    public long timeoutMs = DEFAULT_TIMEOUT_MS;

    @SerializedName("max_results")
    public int maxResults = DEFAULT_MAX_RESULTS;

    public transient long scriptMaxExecutionTimeMs = DEFAULT_SCRIPT_MAX_EXECUTION_TIME_MS;

    /**
     * Whether the user has acknowledged this is a developer tool.
     * Must be true for the mod to activate.
     */
    @SerializedName("developer_mode_accepted")
    public boolean developerModeAccepted = false;

    /**
     * Slash-command execution via the bridge. Off by default — the runtime
     * still runs whatever Groovy a connected client sends, so flipping this on
     * only widens the surface for anyone authorized to drive the bridge.
     */
    @SerializedName("run_command_enabled")
    public boolean runCommandEnabled = false;

    /**
     * Session control (disconnect / joinServer / quit) via the bridge. Off by
     * default — these endpoints can tear down the user's play session or close
     * the client, so they're opt-in for automation setups only.
     */
    @SerializedName("session_control_enabled")
    public boolean sessionControlEnabled = false;

    /**
     * Serve the bundled web UI over loopback HTTP (bridge port + 100). On by
     * default: it's static assets only, and the page can't do anything the
     * already-running WebSocket doesn't allow.
     */
    @SerializedName("web_ui_enabled")
    public boolean webUiEnabled = true;

    private ScriptConfig script;

    // Legacy key from the pre-Groovy runtime. Accepted on load, never written.
    private ScriptConfig lua;

    /**
     * Path to the config file, set when loaded.
     */
    private transient Path configFile;

    /**
     * Load config from a directory (e.g. .minecraft/config/).
     */
    public static BridgeConfig load(Path configDir) {
        Path file = configDir.resolve("debugbridge.json");

        if (!Files.exists(file)) {
            BridgeConfig config = new BridgeConfig();
            config.configFile = file;
            LOG.info("[DebugBridge] No config file at " + file + ", using defaults (port 9876)");
            return config;
        }
        try {
            String json = Files.readString(file);
            BridgeConfig config = GSON.fromJson(json, BridgeConfig.class);
            if (config == null) config = new BridgeConfig();
            config.configFile = file;
            config.applyNestedScriptConfig();
            config.validate();
            LOG.info("[DebugBridge] Config loaded from " + file + " (port " + config.port + ")");
            return config;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LOG.warning("[DebugBridge] Failed to read config: " + e.getMessage() + ", using defaults");
            BridgeConfig fallback = new BridgeConfig();
            fallback.configFile = file;
            return fallback;
        }
    }

    /**
     * Save the current config to the config file.
     */
    public void save() {
        if (configFile == null) {
            LOG.warning("[DebugBridge] Cannot save config: no file path set");
            return;
        }
        try {
            validate();
            Files.writeString(configFile, GSON.toJson(serializableCopy()));
            LOG.info("[DebugBridge] Config saved to " + configFile);
        } catch (IOException e) {
            LOG.warning("[DebugBridge] Failed to save config: " + e.getMessage());
        }
    }

    private void applyNestedScriptConfig() {
        ScriptConfig nested = script != null ? script : lua;
        if (nested != null && nested.maxExecutionTimeMs != null) {
            scriptMaxExecutionTimeMs = nested.maxExecutionTimeMs;
        }
    }

    private BridgeConfig serializableCopy() {
        BridgeConfig copy = new BridgeConfig();
        copy.port = port;
        copy.timeoutMs = timeoutMs;
        copy.maxResults = maxResults;
        copy.scriptMaxExecutionTimeMs = scriptMaxExecutionTimeMs;
        copy.developerModeAccepted = developerModeAccepted;
        copy.runCommandEnabled = runCommandEnabled;
        copy.sessionControlEnabled = sessionControlEnabled;
        copy.webUiEnabled = webUiEnabled;
        copy.script = new ScriptConfig(scriptMaxExecutionTimeMs);
        return copy;
    }

    private void validate() {
        port = validateIntRange("port", port, MIN_BRIDGE_PORT, MAX_BRIDGE_PORT, DEFAULT_PORT);
        timeoutMs = validatePositiveLong("timeout_ms", timeoutMs, DEFAULT_TIMEOUT_MS);
        maxResults = validatePositiveInt("max_results", maxResults, DEFAULT_MAX_RESULTS);
        scriptMaxExecutionTimeMs = validatePositiveLong(
                "script.max_execution_time_ms", scriptMaxExecutionTimeMs, DEFAULT_SCRIPT_MAX_EXECUTION_TIME_MS);
    }

    private static int validateIntRange(String name, int value, int min, int max, int fallback) {
        if (value >= min && value <= max) return value;
        LOG.warning("[DebugBridge] Invalid config " + name + "=" + value + ", using " + fallback);
        return fallback;
    }

    private static int validatePositiveInt(String name, int value, int fallback) {
        if (value > 0) return value;
        LOG.warning("[DebugBridge] Invalid config " + name + "=" + value + ", using " + fallback);
        return fallback;
    }

    private static long validatePositiveLong(String name, long value, long fallback) {
        if (value > 0) return value;
        LOG.warning("[DebugBridge] Invalid config " + name + "=" + value + ", using " + fallback);
        return fallback;
    }

    private static class ScriptConfig {
        @SerializedName("max_execution_time_ms")
        Long maxExecutionTimeMs;

        ScriptConfig() {}

        ScriptConfig(long maxExecutionTimeMs) {
            this.maxExecutionTimeMs = maxExecutionTimeMs;
        }
    }
}
