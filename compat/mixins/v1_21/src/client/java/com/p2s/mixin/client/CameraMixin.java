package com.p2s.mixin.client;

import com.p2s.preview.OrbitCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the rendered viewpoint while {@link OrbitCamera} is active; the player does not move. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("TAIL"))
    private void p2s$orbit(BlockGetter level, Entity entity, boolean detachedView, boolean mirrored,
                           float partialTick, CallbackInfo info) {
        if (!OrbitCamera.active()) {
            return;
        }
        setRotation(OrbitCamera.yaw(), OrbitCamera.pitch());
        setPosition(OrbitCamera.position());
        // Render the player's body and hide the first-person hand.
        detached = true;
    }
}
