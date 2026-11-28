package com.p2s.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** World render callback and primitive abstraction for MC 26.2+ (SubmitNodeCollector custom geometry). */
public final class P2SWorldRenderCompat {
    private P2SWorldRenderCompat() {
    }

    public interface RenderContext {
        Vec3 cameraPos();

        void drawLineBox(AABB worldBox, float r, float g, float b, float a);

        void renderGhostBlock(BlockState state, BlockPos pos, float alpha);
    }

    public static void registerAfterTranslucent(Consumer<RenderContext> renderer) {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> renderWithContext(context, renderer));
    }

    private static void renderWithContext(LevelRenderContext context, Consumer<RenderContext> renderer) {
        if (context.submitNodeCollector() == null || context.poseStack() == null || context.levelState() == null
                || context.levelState().cameraRenderState == null) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        if (cam == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        var modelSet = mc.getModelManager().getBlockStateModelSet();
        BlockAndTintGetter blockGetter = mc.level != null ? mc.level : BlockAndTintGetter.EMPTY;

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        renderer.accept(new RenderContext() {
            @Override
            public Vec3 cameraPos() {
                return cam;
            }

            @Override
            public void drawLineBox(AABB worldBox, float r, float g, float b, float a) {
                collector.submitCustomGeometry(pose, RenderTypes.lines(),
                        (poseEntry, vc) -> drawEdges(poseEntry, vc, worldBox, r, g, b, a));
            }

            @Override
            public void renderGhostBlock(BlockState state, BlockPos pos, float alpha) {
                BlockStateModel model = modelSet.get(state);
                pose.pushPose();
                pose.translate(pos.getX() + 0.01, pos.getY() + 0.01, pos.getZ() + 0.01);
                pose.scale(0.98f, 0.98f, 0.98f);
                collector.submitCustomGeometry(pose, RenderTypes.translucentMovingBlock(), (poseEntry, vc) -> {
                    ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, false, mc.getBlockColors());
                    blockRenderer.tesselateBlock(
                            (qx, qy, qz, quad, quadInstance) -> {
                                for (int i = 0; i < 4; i++) {
                                    int c = quadInstance.getColor(i);
                                    int a = Math.round(((c >>> 24) & 0xff) * alpha);
                                    quadInstance.setColor(i, (a << 24) | (c & 0x00ffffff));
                                }
                                quadInstance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
                                quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                                vc.putBakedQuad(poseEntry, quad, quadInstance);
                            },
                            0f, 0f, 0f, blockGetter, pos, state, model, state.getSeed(pos)
                    );
                });
                pose.popPose();
            }
        });
        pose.popPose();
    }

    private static void drawEdges(PoseStack.Pose pose, VertexConsumer vc, AABB b, float r, float g, float bl, float a) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        edge(pose, vc, x0, y0, z0, x1, y0, z0, r, g, bl, a);
        edge(pose, vc, x0, y0, z0, x0, y1, z0, r, g, bl, a);
        edge(pose, vc, x0, y0, z0, x0, y0, z1, r, g, bl, a);
        edge(pose, vc, x1, y0, z0, x1, y1, z0, r, g, bl, a);
        edge(pose, vc, x1, y0, z0, x1, y0, z1, r, g, bl, a);
        edge(pose, vc, x0, y1, z0, x1, y1, z0, r, g, bl, a);
        edge(pose, vc, x0, y1, z0, x0, y1, z1, r, g, bl, a);
        edge(pose, vc, x0, y0, z1, x1, y0, z1, r, g, bl, a);
        edge(pose, vc, x0, y0, z1, x0, y1, z1, r, g, bl, a);
        edge(pose, vc, x1, y1, z0, x1, y1, z1, r, g, bl, a);
        edge(pose, vc, x1, y0, z1, x1, y1, z1, r, g, bl, a);
        edge(pose, vc, x0, y1, z1, x1, y1, z1, r, g, bl, a);
    }

    private static void edge(PoseStack.Pose pose, VertexConsumer vc,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = len > 0 ? dx / len : 0f;
        float ny = len > 0 ? dy / len : 1f;
        float nz = len > 0 ? dz / len : 0f;
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(1.5f);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(1.5f);
    }
}
