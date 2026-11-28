package com.p2s.mixin.client;

import com.p2s.preview.OrbitCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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
    protected abstract void setPosition(double x, double y, double z);

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void p2s$orbit(DeltaTracker deltaTracker, CallbackInfo info) {
        if (!OrbitCamera.active()) {
            return;
        }
        setRotation(OrbitCamera.yaw(), OrbitCamera.pitch());
        var pos = OrbitCamera.position();
        setPosition(pos.x, pos.y, pos.z);
        // Render the player's body and hide the first-person hand.
        detached = true;
    }
}
