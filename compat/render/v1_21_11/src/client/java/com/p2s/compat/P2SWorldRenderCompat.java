package com.p2s.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** World render callback and primitive abstraction for MC 1.21.11+ (world.WorldRenderEvents + RenderTypes). */
public final class P2SWorldRenderCompat {
    private P2SWorldRenderCompat() {
    }

    public interface RenderContext {
        Vec3 cameraPos();

        void drawLineBox(AABB worldBox, float r, float g, float b, float a);

        void renderGhostBlock(BlockState state, BlockPos pos, float alpha);
    }

    public static void registerAfterTranslucent(Consumer<RenderContext> renderer) {
        WorldRenderEvents.END_MAIN.register(context -> renderWithContext(context, renderer));
    }

    private static void renderWithContext(WorldRenderContext context, Consumer<RenderContext> renderer) {
        if (context.consumers() == null || context.matrices() == null || context.worldState() == null
                || context.worldState().cameraRenderState == null) {
            return;
        }
        Vec3 cam = context.worldState().cameraRenderState.pos;
        if (cam == null) {
            return;
        }
        PoseStack pose = context.matrices();
        MultiBufferSource buffers = context.consumers();
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        renderer.accept(new RenderContext() {
            private VertexConsumer lineBuffer;
            private MultiBufferSource lastAlphaBuffers;
            private float lastAlpha = -1f;

            @Override
            public Vec3 cameraPos() {
                return cam;
            }

            @Override
            public void drawLineBox(AABB worldBox, float r, float g, float b, float a) {
                if (lineBuffer == null) {
                    lineBuffer = buffers.getBuffer(RenderTypes.lines());
                }
                drawEdges(pose.last(), lineBuffer, worldBox, r, g, b, a);
            }

            @Override
            public void renderGhostBlock(BlockState state, BlockPos pos, float alpha) {
                if (lastAlphaBuffers == null || Float.compare(lastAlpha, alpha) != 0) {
                    lastAlpha = alpha;
                    lastAlphaBuffers = alphaBuffers(buffers, alpha);
                }
                pose.pushPose();
                pose.translate(pos.getX() + 0.01, pos.getY() + 0.01, pos.getZ() + 0.01);
                pose.scale(0.98f, 0.98f, 0.98f);
                blocks.renderSingleBlock(state, pose, lastAlphaBuffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
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

    /** Routes every block layer to the translucent buffer and scales vertex alpha. */
    private static MultiBufferSource alphaBuffers(MultiBufferSource buffers, float alpha) {
        return type -> new AlphaVertexConsumer(buffers.getBuffer(RenderTypes.translucentMovingBlock()), alpha);
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
        public VertexConsumer setColor(int argb) {
            int a = Math.round(((argb >>> 24) & 0xff) * alpha);
            delegate.setColor((a << 24) | (argb & 0x00ffffff));
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

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
