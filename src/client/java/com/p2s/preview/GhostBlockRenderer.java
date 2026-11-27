package com.p2s.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.p2s.ClientSessionState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws pending changes into the real world: blocks to place as translucent models, blocks to
 * remove as boxes, and the file under review outlined. Other files are drawn fainter.
 */
public final class GhostBlockRenderer {
    /** Ghost models drawn per frame; the file under review is drawn first. */
    static final int FRAME_BUDGET = 12_000;
    static final double MAX_DISTANCE = 128;
    static final float FOCUS_ALPHA = 0.55f;
    static final float OTHER_ALPHA = 0.2f;

    private GhostBlockRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GhostBlockRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        var files = ClientPreviewStore.files();
        if (files.isEmpty() || ClientPreviewStore.hidden() || context.consumers() == null) {
            return;
        }
        Vec3 cam = context.camera().getPosition();
        PoseStack pose = context.matrixStack();
        MultiBufferSource buffers = context.consumers();
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        String selected = ClientSessionState.getSelectedWorkspacePath();
        boolean anyFocus = files.stream().anyMatch(file -> file.path().equals(selected));
        double maxSq = MAX_DISTANCE * MAX_DISTANCE;
        int budget = FRAME_BUDGET;

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        // Focused file first so it keeps its ghosts when the budget runs out.
        for (int pass = 0; pass < 2; pass++) {
            for (ClientPreviewStore.FilePreview file : files) {
                boolean focus = !anyFocus || file.path().equals(selected);
                if (focus != (pass == 0)) {
                    continue;
                }
                float alpha = focus ? FOCUS_ALPHA : OTHER_ALPHA;
                int[] remove = PreviewColors.remove();
                VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                MultiBufferSource ghostBuffers = alphaBuffers(buffers, alpha);
                for (ClientPreviewStore.Ghost ghost : file.ghosts()) {
                    double dx = ghost.pos().getX() + 0.5 - cam.x;
                    double dy = ghost.pos().getY() + 0.5 - cam.y;
                    double dz = ghost.pos().getZ() + 0.5 - cam.z;
                    if (dx * dx + dy * dy + dz * dz > maxSq) {
                        continue;
                    }
                    if (ghost.removal() || ghost.state().getRenderShape() != RenderShape.MODEL) {
                        LevelRenderer.renderLineBox(pose, lines, new AABB(ghost.pos()).deflate(0.02),
                                remove[0] / 255f, remove[1] / 255f, remove[2] / 255f, focus ? 0.9f : 0.35f);
                        continue;
                    }
                    if (budget-- <= 0) {
                        continue;
                    }
                    pose.pushPose();
                    // Slightly smaller than a block so it does not z-fight with the block it replaces.
                    pose.translate(ghost.pos().getX() + 0.01, ghost.pos().getY() + 0.01, ghost.pos().getZ() + 0.01);
                    pose.scale(0.98f, 0.98f, 0.98f);
                    blocks.renderSingleBlock(ghost.state(), pose, ghostBuffers, LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                }
                if (focus && anyFocus && file.bounds() != null) {
                    int[] outline = PreviewColors.file();
                    LevelRenderer.renderLineBox(pose, lines, file.bounds().inflate(0.03),
                            outline[0] / 255f, outline[1] / 255f, outline[2] / 255f, 1f);
                }
            }
        }
        pose.popPose();
    }

    /** Routes every block layer to the translucent buffer and scales vertex alpha. */
    private static MultiBufferSource alphaBuffers(MultiBufferSource buffers, float alpha) {
        return type -> new AlphaVertexConsumer(buffers.getBuffer(RenderType.translucent()), alpha);
    }

    private record AlphaVertexConsumer(VertexConsumer delegate, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
