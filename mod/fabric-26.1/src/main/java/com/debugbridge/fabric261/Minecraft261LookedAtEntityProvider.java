package com.debugbridge.fabric261;

import com.debugbridge.core.entity.LookedAtEntityProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public class Minecraft261LookedAtEntityProvider implements LookedAtEntityProvider {
    @Override
    public Integer getLookedAtEntity(double range) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return collectLookedAtEntity(mc, range);
        }

        CompletableFuture<Integer> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                future.complete(collectLookedAtEntity(mc, range));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(2, TimeUnit.SECONDS);
    }

    private Integer collectLookedAtEntity(Minecraft mc, double range) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        AABB searchBox =
                player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player,
                eye,
                end,
                searchBox,
                entity -> !entity.isSpectator() && entity.isPickable(),
                eye.distanceToSqr(end));

        return hit != null ? hit.getEntity().getId() : null;
    }
}
