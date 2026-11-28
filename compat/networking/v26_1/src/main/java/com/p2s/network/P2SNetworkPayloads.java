package com.p2s.network;

import com.p2s.P2SMod;
import com.p2s.P2SNetworkConstants;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class P2SNetworkPayloads {
    private static boolean REGISTERED = false;
    private static final Map<Class<?>, Entry<?>> BY_CLASS = new ConcurrentHashMap<>();

    public record Wrapper<T extends P2SPacket>(CustomPacketPayload.Type<Wrapper<T>> payloadType, T packet)
            implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Wrapper<T>> type() {
            return payloadType;
        }
    }

    public record Entry<T extends P2SPacket>(
            Class<T> packetClass,
            CustomPacketPayload.Type<Wrapper<T>> type,
            StreamCodec<FriendlyByteBuf, Wrapper<T>> codec
    ) {
    }

    public static final Entry<C2SSetSelectionPayload> C2S_SET_SELECTION = create(
            C2SSetSelectionPayload.class, P2SNetworkConstants.C2S_SET_SELECTION_PATH, C2SSetSelectionPayload::read);
    public static final Entry<C2SSessionActionPayload> C2S_SESSION_ACTION = create(
            C2SSessionActionPayload.class, P2SNetworkConstants.C2S_SESSION_ACTION_PATH, C2SSessionActionPayload::read);
    public static final Entry<C2SToolBridgePayload> C2S_TOOL_BRIDGE = create(
            C2SToolBridgePayload.class, P2SNetworkConstants.C2S_TOOL_BRIDGE_PATH, C2SToolBridgePayload::read);

    public static final Entry<S2CSelectionSyncPayload> S2C_SELECTION_SYNC = create(
            S2CSelectionSyncPayload.class, P2SNetworkConstants.S2C_SELECTION_SYNC_PATH, S2CSelectionSyncPayload::read);
    public static final Entry<S2CChatResponsePayload> S2C_CHAT_RESPONSE = create(
            S2CChatResponsePayload.class, P2SNetworkConstants.S2C_CHAT_RESPONSE_PATH, S2CChatResponsePayload::read);
    public static final Entry<S2CSessionSyncPayload> S2C_SESSION_SYNC = create(
            S2CSessionSyncPayload.class, P2SNetworkConstants.S2C_SESSION_SYNC_PATH, S2CSessionSyncPayload::read);
    public static final Entry<S2CBuildProgressPayload> S2C_BUILD_PROGRESS = create(
            S2CBuildProgressPayload.class, P2SNetworkConstants.S2C_BUILD_PROGRESS_PATH, S2CBuildProgressPayload::read);
    public static final Entry<S2CPatchPreviewPayload> S2C_PATCH_PREVIEW = create(
            S2CPatchPreviewPayload.class, P2SNetworkConstants.S2C_PATCH_PREVIEW_PATH, S2CPatchPreviewPayload::read);
    public static final Entry<S2CToolBridgePayload> S2C_TOOL_BRIDGE = create(
            S2CToolBridgePayload.class, P2SNetworkConstants.S2C_TOOL_BRIDGE_PATH, S2CToolBridgePayload::read);

    private P2SNetworkPayloads() {
    }

    private static <T extends P2SPacket> Entry<T> create(
            Class<T> packetClass,
            String path,
            Function<P2SPacketBuf, T> reader
    ) {
        Identifier id = Identifier.fromNamespaceAndPath(P2SMod.MOD_ID, path);
        CustomPacketPayload.Type<Wrapper<T>> type = new CustomPacketPayload.Type<>(id);
        StreamCodec<FriendlyByteBuf, Wrapper<T>> codec = StreamCodec.of(
                (buf, wrapper) -> wrapper.packet().write(adapt(buf)),
                buf -> new Wrapper<>(type, reader.apply(adapt(buf)))
        );
        Entry<T> entry = new Entry<>(packetClass, type, codec);
        BY_CLASS.put(packetClass, entry);
        return entry;
    }

    public static synchronized void register() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        registerC2S(C2S_SET_SELECTION);
        registerC2S(C2S_SESSION_ACTION);
        registerC2S(C2S_TOOL_BRIDGE);

        registerS2C(S2C_SELECTION_SYNC);
        registerS2C(S2C_CHAT_RESPONSE);
        registerS2C(S2C_SESSION_SYNC);
        registerS2C(S2C_BUILD_PROGRESS);
        registerS2C(S2C_PATCH_PREVIEW);
        registerS2C(S2C_TOOL_BRIDGE);
    }

    private static <T extends P2SPacket> void registerC2S(Entry<T> entry) {
        PayloadTypeRegistry.serverboundPlay().register(entry.type(), entry.codec());
    }

    private static <T extends P2SPacket> void registerS2C(Entry<T> entry) {
        PayloadTypeRegistry.clientboundPlay().register(entry.type(), entry.codec());
    }

    @SuppressWarnings("unchecked")
    public static <T extends P2SPacket> Entry<T> entryFor(Class<T> packetClass) {
        Entry<T> entry = (Entry<T>) BY_CLASS.get(packetClass);
        if (entry == null) {
            throw new IllegalArgumentException("Unregistered packet type: " + packetClass.getName());
        }
        return entry;
    }

    @SuppressWarnings("unchecked")
    public static <T extends P2SPacket> Wrapper<T> wrap(T packet) {
        Entry<T> entry = entryFor((Class<T>) packet.getClass());
        return new Wrapper<>(entry.type(), packet);
    }

    private static P2SPacketBuf adapt(FriendlyByteBuf buf) {
        return new P2SPacketBuf() {
            @Override
            public void writeBoolean(boolean value) {
                buf.writeBoolean(value);
            }

            @Override
            public boolean readBoolean() {
                return buf.readBoolean();
            }

            @Override
            public void writeInt(int value) {
                buf.writeInt(value);
            }

            @Override
            public int readInt() {
                return buf.readInt();
            }

            @Override
            public void writeVarInt(int value) {
                buf.writeVarInt(value);
            }

            @Override
            public int readVarInt() {
                return buf.readVarInt();
            }

            @Override
            public void writeUtf(String value, int maxLength) {
                buf.writeUtf(value, maxLength);
            }

            @Override
            public String readUtf(int maxLength) {
                return buf.readUtf(maxLength);
            }

            @Override
            public void writeBlockPos(BlockPos pos) {
                buf.writeBlockPos(pos);
            }

            @Override
            public BlockPos readBlockPos() {
                return buf.readBlockPos();
            }
        };
    }
}
