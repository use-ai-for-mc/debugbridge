package com.debugbridge.fabric262;

import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.lifecycle.AbstractDebugBridgeMod;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.protocol.dto.SnapshotDto;
import com.debugbridge.core.protocol.dto.SnapshotPlayerDto;
import com.debugbridge.core.protocol.dto.SnapshotTargetDto;
import com.debugbridge.core.protocol.dto.SnapshotVehicleDto;
import com.debugbridge.core.protocol.dto.SnapshotWorldDto;
import com.debugbridge.core.protocol.dto.Vec3Dto;
import com.debugbridge.core.recording.FrameCapturer;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.session.SessionControlProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.text.TextLinks;
import com.debugbridge.core.texture.ItemTextureProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class DebugBridgeMod extends AbstractDebugBridgeMod implements ClientModInitializer {
    private static final String MC_VERSION = "26.2";
    private static DebugBridgeMod INSTANCE;

    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleTick();
        }
    }

    public static void onRenderFrame(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleRenderFrame();
        }
    }

    public static void onClientClose(Minecraft mc) {
        if (INSTANCE != null) {
            INSTANCE.handleClose();
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
        // 26.2 ships unobfuscated, so the kernel falls back to
        // PassthroughResolver — no Mojang mappings needed.
        return null;
    }

    @Override
    protected void submitToGameThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    protected GameStateProvider createStateProvider() {
        return new Minecraft262StateProvider();
    }

    @Override
    protected ScreenshotProvider createScreenshotProvider() {
        return new Minecraft262ScreenshotProvider();
    }

    @Override
    protected FrameCapturer createFrameCapturer() {
        return new Minecraft262FrameCapturer();
    }

    @Override
    protected ItemTextureProvider createTextureProvider() {
        return new Minecraft262ItemTextureProvider();
    }

    @Override
    protected NearbyEntitiesProvider createEntitiesProvider() {
        return new Minecraft262NearbyEntitiesProvider();
    }

    @Override
    protected NearbyBlocksProvider createBlocksProvider() {
        return new Minecraft262NearbyBlocksProvider();
    }

    @Override
    protected LookedAtEntityProvider createLookedAtEntityProvider() {
        return new Minecraft262LookedAtEntityProvider();
    }

    @Override
    protected ChatHistoryProvider createChatHistoryProvider() {
        return new Minecraft262ChatHistoryProvider();
    }

    @Override
    protected ScreenInspectProvider createScreenInspectProvider() {
        return new Minecraft262ScreenInspectProvider();
    }

    @Override
    protected SessionControlProvider createSessionControlProvider() {
        return new Minecraft262SessionControlProvider();
    }

    @Override
    protected boolean displayPlayerError(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.sendSystemMessage(playerMessage(message, 0xFF5555));
        return true;
    }

    @Override
    protected boolean displayPlayerInfo(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.sendSystemMessage(playerMessage(message, 0x55FF55));
        return true;
    }

    /**
     * "[DebugBridge] " + message in {@code color}, with any http(s) URLs
     * (notably the startup "Web UI: http://localhost:NNNN") rendered as
     * clickable, underlined links.
     */
    private static Component playerMessage(String message, int color) {
        MutableComponent root = Component.literal("[DebugBridge] ");
        for (TextLinks.Segment seg : TextLinks.split(message)) {
            if (seg.isLink()) {
                ClickEvent open;
                try {
                    // Same record-based ClickEvent API as 1.21.x.
                    open = new ClickEvent.OpenUrl(URI.create(seg.text()));
                } catch (IllegalArgumentException e) {
                    root.append(Component.literal(seg.text()));
                    continue;
                }
                root.append(Component.literal(seg.text())
                        .withStyle(
                                s -> s.withColor(0x55FFFF).withUnderlined(true).withClickEvent(open)));
            } else {
                root.append(Component.literal(seg.text()));
            }
        }
        return root.withStyle(s -> s.withColor(color));
    }

    @Override
    protected boolean canShowWarningScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gui.screen() == null && mc.gui.overlay() == null;
    }

    @Override
    protected void showWarningScreen(Consumer<Boolean> onResult) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new DeveloperWarningScreen(config, accepted -> {
            mc.gui.setScreen(null);
            onResult.accept(accepted);
        }));
    }

    private static class Minecraft262StateProvider implements GameStateProvider {
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
                p.biome = ""; // Stub — see review queue.
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
                w.dayTime = mc.level.getOverworldClockTime();
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
