package com.debugbridge.fabric119;

import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.protocol.dto.BlockDetailsDto;
import com.debugbridge.core.protocol.dto.BlockItemDto;
import com.debugbridge.core.protocol.dto.BlockSummaryDto;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native nearby-blocks query for Minecraft 1.19. Walks loaded chunks within
 * range, collects block entities (signs, chests, banners, etc.), and returns
 * type-specific summary fields where applicable.
 */
public class Minecraft119NearbyBlocksProvider implements NearbyBlocksProvider {

    private static String previewFor(BlockEntity be) {
        if (be instanceof SignBlockEntity sign) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                var msg = sign.getMessage(i, false);
                String s = msg == null ? "" : msg.getString();
                if (!s.isEmpty()) {
                    if (!sb.isEmpty()) sb.append(" / ");
                    sb.append(s);
                }
            }
            return !sb.isEmpty() ? sb.toString() : null;
        }
        if (be instanceof Container container) {
            int filled = 0;
            int size = container.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack s = container.getItem(i);
                if (s != null && !s.isEmpty()) filled++;
            }
            return filled + " / " + size;
        }
        return null;
    }

    @Override
    public List<BlockSummaryDto> getNearbyBlocks(double range, int limit) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<List<BlockSummaryDto>> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.player == null || mc.level == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                double px = mc.player.getX();
                double py = mc.player.getY();
                double pz = mc.player.getZ();
                double rangeSq = range * range;

                int chunkRadius = (int) Math.ceil(range / 16.0);
                int playerChunkX = (int) Math.floor(px) >> 4;
                int playerChunkZ = (int) Math.floor(pz) >> 4;

                List<Entry> entries = new ArrayList<>();
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        LevelChunk chunk;
                        try {
                            chunk = mc.level.getChunk(playerChunkX + dx, playerChunkZ + dz);
                        } catch (Exception e) {
                            continue;
                        }
                        if (chunk == null) continue;

                        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                            BlockPos pos = e.getKey();
                            double bx = pos.getX() + 0.5;
                            double by = pos.getY() + 0.5;
                            double bz = pos.getZ() + 0.5;
                            double distSq = (bx - px) * (bx - px)
                                    + (by - py) * (by - py)
                                    + (bz - pz) * (bz - pz);
                            if (distSq <= rangeSq) {
                                entries.add(new Entry(pos, e.getValue(), Math.sqrt(distSq)));
                            }
                        }
                    }
                }

                entries.sort(Comparator.comparingDouble(en -> en.distance));

                List<BlockSummaryDto> out = new ArrayList<>(Math.min(limit, entries.size()));
                int count = 0;
                for (Entry en : entries) {
                    if (count >= limit) break;
                    BlockSummaryDto dto = new BlockSummaryDto();
                    dto.x = en.pos.getX();
                    dto.y = en.pos.getY();
                    dto.z = en.pos.getZ();
                    dto.distance = Math.round(en.distance * 10.0) / 10.0;
                    dto.type = en.blockEntity.getClass().getName();
                    dto.blockId = Registry.BLOCK
                        .getKey(en.blockEntity.getBlockState().getBlock()).toString();
                    dto.preview = previewFor(en.blockEntity);
                    out.add(dto);
                    count++;
                }
                future.complete(out);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    @Override
    public BlockDetailsDto getBlockDetails(int x, int y, int z) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<BlockDetailsDto> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.level == null) {
                    future.complete(null);
                    return;
                }
                BlockPos pos = new BlockPos(x, y, z);
                BlockEntity be = mc.level.getBlockEntity(pos);
                if (be == null) {
                    future.complete(null);
                    return;
                }

                BlockDetailsDto dto = new BlockDetailsDto();
                dto.x = x;
                dto.y = y;
                dto.z = z;
                dto.type = be.getClass().getName();
                dto.blockId = Registry.BLOCK.getKey(be.getBlockState().getBlock()).toString();

                if (be instanceof SignBlockEntity sign) {
                    String[] lines = new String[4];
                    for (int i = 0; i < 4; i++) {
                        var msg = sign.getMessage(i, false);
                        lines[i] = msg == null ? "" : msg.getString();
                    }
                    dto.signLines = Arrays.asList(lines);
                    // 1.19 has no back-side text or waxing concept; signLinesBack
                    // and isWaxed remain null and drop on the wire.
                }

                if (be instanceof Container container) {
                    int size = container.getContainerSize();
                    List<BlockItemDto> items = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = container.getItem(i);
                        if (stack == null || stack.isEmpty()) continue;
                        BlockItemDto item = new BlockItemDto();
                        item.slot = i;
                        item.itemId = Registry.ITEM.getKey(stack.getItem()).toString();
                        item.count = stack.getCount();
                        if (stack.hasCustomHoverName()) {
                            item.name = stack.getHoverName().getString();
                        }
                        if (stack.getMaxDamage() > 0) {
                            item.damage = stack.getDamageValue();
                            item.maxDamage = stack.getMaxDamage();
                        }
                        items.add(item);
                    }
                    dto.items = items;
                    dto.containerSize = size;
                }

                future.complete(dto);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    private record Entry(BlockPos pos, BlockEntity blockEntity, double distance) {
    }
}
