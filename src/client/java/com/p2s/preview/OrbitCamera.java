package com.p2s.preview;

import com.p2s.ClientSelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * While the P2S panel is open the camera can circle the build: right-drag rotates, the wheel
 * zooms while the right button is held, middle-click goes back to the player's view. The player
 * entity does not move; {@code CameraMixin} only replaces the rendered viewpoint.
 */
public final class OrbitCamera {
    static final double MIN_DISTANCE = 3;
    /** Keeps the camera inside chunks the client is likely to have loaded. */
    static final double MAX_DISTANCE = 64;

    private static boolean active;
    private static boolean dragging;
    private static Vec3 center = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    private static double distance;

    private OrbitCamera() {
    }

    public static boolean active() {
        return active;
    }

    public static boolean dragging() {
        return dragging;
    }

    /** Right button pressed: enter orbit on first use, then follow the drag. */
    public static void beginDrag() {
        if (!active) {
            start();
        }
        dragging = active;
    }

    public static void endDrag() {
        dragging = false;
    }

    public static void drag(double dx, double dy) {
        if (!active) {
            return;
        }
        yaw = Mth.wrapDegrees(yaw + (float) dx * 0.4f);
        pitch = Mth.clamp(pitch + (float) dy * 0.4f, -10f, 89f);
    }

    public static void zoom(double wheel) {
        if (active && wheel != 0) {
            distance = Mth.clamp(distance * (wheel > 0 ? 0.9 : 1.1), MIN_DISTANCE, MAX_DISTANCE);
        }
    }

    /** Back to the player's own view. */
    public static void stop() {
        active = false;
        dragging = false;
    }

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        AABB box = target();
        if (box == null) {
            Vec3 eye = mc.player.getEyePosition();
            box = new AABB(eye, eye).inflate(4);
        }
        center = box.getCenter();
        double extent = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
        distance = Mth.clamp(extent * 1.4 + 4, MIN_DISTANCE, MAX_DISTANCE);
        // Start from the player's side of the build so the first frame is familiar.
        yaw = mc.player.getYRot();
        pitch = 30f;
        active = true;
    }

    /** Pending changes first, then the selection box. */
    static AABB target() {
        AABB pending = ClientPreviewStore.bounds();
        if (pending != null) {
            return pending;
        }
        BlockPos a = ClientSelectionManager.getPos1();
        BlockPos b = ClientSelectionManager.getPos2();
        if (a != null && b != null) {
            return AABB.encapsulatingFullBlocks(a, b);
        }
        return null;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** Camera position: back from the centre along the view direction. */
    public static Vec3 position() {
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        return center.subtract(forward.scale(distance));
    }
}
