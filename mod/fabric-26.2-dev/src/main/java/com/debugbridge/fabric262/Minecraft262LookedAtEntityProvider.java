package com.debugbridge.fabric262;

import com.debugbridge.core.entity.LookedAtEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Minecraft262LookedAtEntityProvider implements LookedAtEntityProvider {
    @Override
    public Integer getLookedAtEntity(double range) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        
        mc.execute(() -> {
            try {
                LocalPlayer player = mc.player;
                if (player == null || mc.level == null) {
                    future.complete(null);
                    return;
                }
                
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getLookAngle();
                Vec3 end = eye.add(look.scale(range));
                AABB searchBox = player.getBoundingBox()
                        .expandTowards(look.scale(range))
                        .inflate(1.0);
                
                EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                        player,
                        eye,
                        end,
                        searchBox,
                        entity -> !entity.isSpectator() && entity.isPickable(),
                        ProjectileUtil.DEFAULT_ENTITY_HIT_RESULT_MARGIN
                );
                
                future.complete(hit != null ? hit.getEntity().getId() : null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future.get(2, TimeUnit.SECONDS);
    }
}
