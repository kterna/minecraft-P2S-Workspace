package com.p2s.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** World render callback and primitive abstraction for MC 1.21-1.21.1. */
public final class P2SWorldRenderCompat {
    private P2SWorldRenderCompat() {
    }

    public interface RenderContext {
        Vec3 cameraPos();

        void drawLineBox(AABB worldBox, float r, float g, float b, float a);

        void renderGhostBlock(BlockState state, BlockPos pos, float alpha);
    }

    public static void registerAfterTranslucent(Consumer<RenderContext> renderer) {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> renderWithContext(context, renderer));
    }

    private static void renderWithContext(WorldRenderContext context, Consumer<RenderContext> renderer) {
        if (context.consumers() == null) {
            return;
        }
        Vec3 cam = context.camera().getPosition();
        PoseStack pose = context.matrixStack();
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
                    lineBuffer = buffers.getBuffer(RenderType.lines());
                }
                LevelRenderer.renderLineBox(pose, lineBuffer, worldBox, r, g, b, a);
            }

            @Override
            public void renderGhostBlock(BlockState state, BlockPos pos, float alpha) {
                if (lastAlphaBuffers == null || Float.compare(lastAlpha, alpha) != 0) {
                    lastAlpha = alpha;
                    lastAlphaBuffers = alphaBuffers(buffers, alpha);
                }
                pose.pushPose();
                // Slightly smaller than a block so it does not z-fight with the block it replaces.
                pose.translate(pos.getX() + 0.01, pos.getY() + 0.01, pos.getZ() + 0.01);
                pose.scale(0.98f, 0.98f, 0.98f);
                blocks.renderSingleBlock(state, pose, lastAlphaBuffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        });
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
