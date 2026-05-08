package com.debugbridge.fabric12111;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.block.ClientBlockGlowManager;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingCache;
import com.debugbridge.core.mapping.MappingDownloader;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.FabricMojangResolver;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.protocol.dto.SnapshotDto;
import com.debugbridge.core.protocol.dto.SnapshotPlayerDto;
import com.debugbridge.core.protocol.dto.SnapshotTargetDto;
import com.debugbridge.core.protocol.dto.SnapshotVehicleDto;
import com.debugbridge.core.protocol.dto.SnapshotWorldDto;
import com.debugbridge.core.protocol.dto.Vec3Dto;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugBridgeMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("DebugBridge");
    private static final String MC_VERSION = "1.21.11";

    private static DebugBridgeMod INSTANCE;

    private BridgeConfig config;
    private BridgeServer server;
    private final AtomicBoolean warningShown = new AtomicBoolean(false);
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private boolean needsWarning = false;
    private String startupError = null;
    private String startupInfo = null;  // Info message (e.g., port changed)

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOG.info("[DebugBridge] Initializing for Minecraft {}...", MC_VERSION);

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = BridgeConfig.load(configDir);

        if (config.developerModeAccepted) {
            // Already accepted, start server immediately
            startServer();
        } else {
            // Need to show warning screen - will be triggered by mixin tick
            LOG.info("[DebugBridge] Developer mode not yet accepted, will show warning screen");
            needsWarning = true;
        }
    }

    /**
     * Called by MinecraftClientMixin on each client tick.
     */
    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick(mc);
        }
    }

    private void handleTick(Minecraft mc) {
        // Show startup messages when player is available
        if (startupError != null && mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[DebugBridge] " + startupError).withStyle(s -> s.withColor(0xFF5555)),
                false);
            startupError = null;
        }
        if (startupInfo != null && mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[DebugBridge] " + startupInfo).withStyle(s -> s.withColor(0x55FF55)),
                false);
            startupInfo = null;
        }

        refreshBlockGlow(mc);

        if (!needsWarning) return;

        // Only show once, and only when no screen is open (game is ready)
        if (!warningShown.get() && mc.screen == null && mc.getOverlay() == null) {
            warningShown.set(true);
            mc.setScreen(new DeveloperWarningScreen(config, accepted -> {
                mc.setScreen(null);
                if (accepted) {
                    LOG.info("[DebugBridge] Developer mode accepted by user");
                    startServer();
                } else {
                    LOG.info("[DebugBridge] Developer mode declined, mod disabled");
                }
                needsWarning = false;
            }));
        }
    }

    private void refreshBlockGlow(Minecraft mc) {
        LevelRenderer lr = mc.levelRenderer;
        if (lr == null) return;
        var glowing = ClientBlockGlowManager.snapshot();
        if (glowing.isEmpty()) return;
        for (var p : glowing) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            lr.gameTestBlockHighlightRenderer.highlightPos(pos, pos);
        }
    }

    private static final int PORT_RANGE_START = 9876;
    private static final int PORT_RANGE_END = 9886;

    private void startServer() {
        if (serverStarted.getAndSet(true)) {
            return; // Already started
        }

        MappingResolver resolver = buildResolver();

        Minecraft mc = Minecraft.getInstance();
        ThreadDispatcher dispatcher = new ThreadDispatcher() {
            @Override
            public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
                CompletableFuture<T> future = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                return future.get(timeout, TimeUnit.MILLISECONDS);
            }
        };

        GameStateProvider stateProvider = new Minecraft12111StateProvider();
        ScreenshotProvider screenshotProvider = new Minecraft12111ScreenshotProvider();
        ItemTextureProvider textureProvider = new Minecraft12111ItemTextureProvider();
        Minecraft12111NearbyEntitiesProvider entitiesProvider = new Minecraft12111NearbyEntitiesProvider();
        Minecraft12111NearbyBlocksProvider blocksProvider = new Minecraft12111NearbyBlocksProvider();
        Minecraft12111LookedAtEntityProvider lookedAtProvider = new Minecraft12111LookedAtEntityProvider();

        // Find available port and start server
        int actualPort = startServerOnAvailablePort(config.port, resolver, dispatcher, stateProvider, screenshotProvider);

        if (actualPort == -1) {
            String msg = "Could not bind to any port in range " + PORT_RANGE_START + "-" + PORT_RANGE_END;
            LOG.error("[DebugBridge] {}", msg);
            startupError = msg;
        } else {
            // Register providers (use setters to avoid widening constructor)
            server.setTextureProvider(textureProvider);
            server.setEntitiesProvider(entitiesProvider);
            server.setBlocksProvider(blocksProvider);
            server.setLookedAtEntityProvider(lookedAtProvider);
            server.setChatHistoryProvider(new Minecraft12111ChatHistoryProvider());
            server.setScreenInspectProvider(new Minecraft12111ScreenInspectProvider());
            server.setRunCommandEnabled(config.runCommandEnabled);

            if (actualPort != config.port) {
                startupInfo = "Server started on port " + actualPort + " (default " + config.port + " was in use)";
            }
            LOG.info("[DebugBridge] Server started on port {}", actualPort);
        }
    }

    /**
     * Try to start server on preferred port, scanning range if needed.
     * Returns actual port used, or -1 if all ports occupied.
     */
    private int startServerOnAvailablePort(int preferredPort, MappingResolver resolver,
                                           ThreadDispatcher dispatcher, GameStateProvider stateProvider,
                                           ScreenshotProvider screenshotProvider) {
        int startPort = Math.max(PORT_RANGE_START, Math.min(PORT_RANGE_END, preferredPort));

        // First pass: preferred port -> end of range
        for (int port = startPort; port <= PORT_RANGE_END; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        // Second pass (wraparound): start of range -> preferred port
        for (int port = PORT_RANGE_START; port < startPort; port++) {
            if (tryStartOnPort(port, resolver, dispatcher, stateProvider, screenshotProvider)) {
                return port;
            }
        }

        return -1;
    }

    private boolean tryStartOnPort(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                                   GameStateProvider stateProvider, ScreenshotProvider screenshotProvider) {
        if (!isPortAvailable(port)) {
            LOG.info("[DebugBridge] Port {} is not available", port);
            return false;
        }

        try {
            server = new BridgeServer(port, resolver, dispatcher, stateProvider, screenshotProvider);
            server.setReuseAddr(true);
            server.setGameDir(FabricLoader.getInstance().getGameDir());
            server.start();
            return true;
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to start server on port {}", port, e);
            return false;
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);  // Must be set BEFORE bind
            socket.bind(new InetSocketAddress("127.0.0.1", port));  // Same address as WebSocketServer
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private MappingResolver buildResolver() {
        try {
            MappingCache cache = new MappingCache();
            String proguardContent;

            if (cache.has(MC_VERSION)) {
                LOG.info("[DebugBridge] Loading cached {} mappings...", MC_VERSION);
                proguardContent = cache.load(MC_VERSION);
            } else {
                LOG.info("[DebugBridge] Downloading {} mappings from Mojang...", MC_VERSION);
                MappingDownloader downloader = new MappingDownloader();
                proguardContent = downloader.download(MC_VERSION);
                cache.save(MC_VERSION, proguardContent);
                LOG.info("[DebugBridge] Mappings downloaded and cached.");
            }

            ParsedMappings mappings = ProGuardParser.parse(proguardContent);
            LOG.info("[DebugBridge] Parsed {} classes from mappings.", mappings.classes.size());
            return new FabricMojangResolver(MC_VERSION, mappings, new FabricLoaderNamespaceLookup());
        } catch (Exception e) {
            LOG.error("[DebugBridge] Failed to load mappings, falling back to passthrough", e);
            return new com.debugbridge.core.mapping.PassthroughResolver(MC_VERSION);
        }
    }

    /**
     * Captures game state for the snapshot endpoint.
     */
    private static class Minecraft12111StateProvider implements GameStateProvider {
        @Override
        public SnapshotDto captureSnapshot() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            SnapshotDto snap = new SnapshotDto();

            if (player != null) {
                SnapshotPlayerDto p = new SnapshotPlayerDto();
                p.name = player.getName().getString();
                p.x = player.getX();
                p.y = player.getY();
                p.z = player.getZ();
                p.yaw = player.getYRot();
                p.pitch = player.getXRot();
                p.hotbarSlot = player.getInventory().getSelectedSlot();
                p.health = player.getHealth();
                p.maxHealth = player.getMaxHealth();
                p.food = player.getFoodData().getFoodLevel();
                p.saturation = player.getFoodData().getSaturationLevel();
                p.dimension = player.level().dimension().identifier().toString();
                p.biome = "";  // Stub — see review queue.
                Vec3 vel = player.getDeltaMovement();
                p.velocity = new Vec3Dto(vel.x, vel.y, vel.z);
                Vec3 look = player.getLookAngle();
                p.look = new Vec3Dto(look.x, look.y, look.z);
                Entity vehicle = player.getVehicle();
                if (vehicle != null) {
                    SnapshotVehicleDto v = new SnapshotVehicleDto();
                    v.entityId = vehicle.getId();
                    v.type = vehicle.getClass().getName();
                    p.vehicle = v;
                }
                snap.player = p;
            }
            // No player → snap.player stays null and is omitted on the wire
            // (older code emitted the literal string "not in world" here).

            HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                SnapshotTargetDto t = new SnapshotTargetDto();
                t.type = hit.getType().name().toLowerCase();
                if (hit instanceof BlockHitResult bhr) {
                    BlockPos pos = bhr.getBlockPos();
                    t.x = pos.getX();
                    t.y = pos.getY();
                    t.z = pos.getZ();
                    t.face = bhr.getDirection().name().toLowerCase();
                } else if (hit instanceof EntityHitResult ehr) {
                    t.entityId = ehr.getEntity().getId();
                    t.entityType = ehr.getEntity().getClass().getName();
                }
                snap.target = t;
            }

            if (mc.level != null) {
                SnapshotWorldDto w = new SnapshotWorldDto();
                w.dayTime = mc.level.getDayTime();
                w.isRaining = mc.level.isRaining();
                w.isThundering = mc.level.isThundering();
                snap.world = w;
            }

            snap.fps = mc.getFps();
            snap.version = MC_VERSION;
            return snap;
        }
    }
}
