package com.debugbridge.fabric12111;

import com.debugbridge.core.block.ClientBlockGlowManager;
import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.lifecycle.AbstractDebugBridgeMod;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.protocol.dto.*;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.function.Consumer;

public class DebugBridgeMod extends AbstractDebugBridgeMod implements ClientModInitializer {
    private static final String MC_VERSION = "1.21.11";
    private static DebugBridgeMod INSTANCE;
    
    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick();
        }
    }
    
    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        initialize();
    }
    
    @Override
    protected String mcVersion() {
        return MC_VERSION;
    }
    
    @Override
    protected Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
    
    @Override
    protected Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
    
    @Override
    protected FabricNamespaceLookup createNamespaceLookup() {
        return new FabricLoaderNamespaceLookup();
    }
    
    @Override
    protected void submitToGameThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }
    
    @Override
    protected GameStateProvider createStateProvider() {
        return new Minecraft12111StateProvider();
    }
    
    @Override
    protected ScreenshotProvider createScreenshotProvider() {
        return new Minecraft12111ScreenshotProvider();
    }
    
    @Override
    protected ItemTextureProvider createTextureProvider() {
        return new Minecraft12111ItemTextureProvider();
    }
    
    @Override
    protected NearbyEntitiesProvider createEntitiesProvider() {
        return new Minecraft12111NearbyEntitiesProvider();
    }
    
    @Override
    protected NearbyBlocksProvider createBlocksProvider() {
        return new Minecraft12111NearbyBlocksProvider();
    }
    
    @Override
    protected LookedAtEntityProvider createLookedAtEntityProvider() {
        return new Minecraft12111LookedAtEntityProvider();
    }
    
    @Override
    protected ChatHistoryProvider createChatHistoryProvider() {
        return new Minecraft12111ChatHistoryProvider();
    }
    
    @Override
    protected ScreenInspectProvider createScreenInspectProvider() {
        return new Minecraft12111ScreenInspectProvider();
    }
    
    @Override
    protected boolean displayPlayerError(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.displayClientMessage(
                Component.literal("[DebugBridge] " + message).withStyle(s -> s.withColor(0xFF5555)),
                false);
        return true;
    }
    
    @Override
    protected boolean displayPlayerInfo(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.displayClientMessage(
                Component.literal("[DebugBridge] " + message).withStyle(s -> s.withColor(0x55FF55)),
                false);
        return true;
    }
    
    @Override
    protected boolean canShowWarningScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.getOverlay() == null;
    }
    
    @Override
    protected void showWarningScreen(Consumer<Boolean> onResult) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DeveloperWarningScreen(config, accepted -> {
            mc.setScreen(null);
            onResult.accept(accepted);
        }));
    }
    
    @Override
    protected void onPostTick() {
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer lr = mc.levelRenderer;
        if (lr == null) return;
        var glowing = ClientBlockGlowManager.snapshot();
        if (glowing.isEmpty()) return;
        for (var p : glowing) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            lr.gameTestBlockHighlightRenderer.highlightPos(pos, pos);
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
