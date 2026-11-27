package com.p2s.preview;

import com.p2s.ClientSessionState;
import com.p2s.compat.P2SWorldRenderCompat;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws translucent ghost blocks and removal outlines for pending workspace patches. Registered once
 * at client init and reads only {@link ClientPreviewStore}.
 */
public final class GhostBlockRenderer {
    static final int FRAME_BUDGET = 12_000;
    static final double MAX_DISTANCE = 128;
    static final float FOCUS_ALPHA = 0.55f;
    static final float OTHER_ALPHA = 0.2f;

    private GhostBlockRenderer() {
    }

    public static void register() {
        P2SWorldRenderCompat.registerAfterTranslucent(GhostBlockRenderer::render);
    }

    private static void render(P2SWorldRenderCompat.RenderContext context) {
        var files = ClientPreviewStore.files();
        if (files.isEmpty() || ClientPreviewStore.hidden()) {
            return;
        }
        Vec3 cam = context.cameraPos();
        String selected = ClientSessionState.getSelectedWorkspacePath();
        boolean anyFocus = files.stream().anyMatch(file -> file.path().equals(selected));
        double maxSq = MAX_DISTANCE * MAX_DISTANCE;
        int budget = FRAME_BUDGET;

        // Focused file first so it keeps its ghosts when the budget runs out.
        for (int pass = 0; pass < 2; pass++) {
            for (ClientPreviewStore.FilePreview file : files) {
                boolean focus = !anyFocus || file.path().equals(selected);
                if (focus != (pass == 0)) {
                    continue;
                }
                float alpha = focus ? FOCUS_ALPHA : OTHER_ALPHA;
                int[] remove = PreviewColors.remove();
                for (ClientPreviewStore.Ghost ghost : file.ghosts()) {
                    double dx = ghost.pos().getX() + 0.5 - cam.x;
                    double dy = ghost.pos().getY() + 0.5 - cam.y;
                    double dz = ghost.pos().getZ() + 0.5 - cam.z;
                    if (dx * dx + dy * dy + dz * dz > maxSq) {
                        continue;
                    }
                    if (ghost.removal() || ghost.state().getRenderShape() != RenderShape.MODEL) {
                        context.drawLineBox(new AABB(ghost.pos()).deflate(0.02),
                                remove[0] / 255f, remove[1] / 255f, remove[2] / 255f, focus ? 0.9f : 0.35f);
                        continue;
                    }
                    if (budget-- <= 0) {
                        continue;
                    }
                    context.renderGhostBlock(ghost.state(), ghost.pos(), alpha);
                }
                if (focus && anyFocus && file.bounds() != null) {
                    int[] outline = PreviewColors.file();
                    context.drawLineBox(file.bounds().inflate(0.03),
                            outline[0] / 255f, outline[1] / 255f, outline[2] / 255f, 1f);
                }
            }
        }
    }
}
