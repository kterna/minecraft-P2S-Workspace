package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.compat.P2SClientNetworkCompat;
import com.p2s.network.C2SToolBridgePayload;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class ClientToolBridge {
    private static final Gson GSON = new Gson();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong CONNECTION_EPOCH = new AtomicLong();
    private static final Map<String, PendingRequest> PENDING = new ConcurrentHashMap<>();
    /** 8000 characters stay below the packet limit even when every character takes 3 UTF-8 bytes. */
    private static final int UPLOAD_CHUNK_CHARS = 8000;

    private ClientToolBridge() {
    }

    public static CompletableFuture<JsonObject> call(String toolName, JsonObject args) {
        return call(toolName, args, () -> true);
    }

    public static CompletableFuture<JsonObject> call(String toolName, JsonObject args, BooleanSupplier isActive) {
        return callForAgent(toolName, args, isActive, null);
    }

    // A null actor denotes the player's own UI. An empty actor denotes the main agent.
    static CompletableFuture<JsonObject> callForAgent(String toolName, JsonObject args, BooleanSupplier isActive, String actor) {
        String requestId = Long.toString(NEXT_ID.incrementAndGet(), 36);
        long epoch = CONNECTION_EPOCH.get();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PendingRequest pending = new PendingRequest(future,
                () -> epoch == CONNECTION_EPOCH.get() && isActive.getAsBoolean());
        PENDING.put(requestId, pending);
        future.orTimeout(20, TimeUnit.SECONDS).whenComplete((ignored, ex) -> PENDING.remove(requestId, pending));

        JsonObject payload = args == null ? new JsonObject() : args.deepCopy();
        if ("propose_patch".equals(toolName) || "stage_workspace_file".equals(toolName)) {
            payload.add("runtime_settings", P2SClientConfig.patchRuntimeSettingsPayload());
        }
        String argumentsJson = GSON.toJson(payload);
        // A serverbound custom payload is limited to about 32 KB: split large arguments and let
        // the server reassemble them before the real call (see BridgeUploads).
        java.util.List<String> chunks = new java.util.ArrayList<>();
        if (argumentsJson.length() > UPLOAD_CHUNK_CHARS) {
            String uploadId = requestId + "-" + Long.toString(System.nanoTime(), 36);
            int total = (argumentsJson.length() + UPLOAD_CHUNK_CHARS - 1) / UPLOAD_CHUNK_CHARS;
            if (total > 128) {
                future.completeExceptionally(new IllegalArgumentException("Tool arguments too large ("
                        + argumentsJson.length() + " characters)"));
                return future;
            }
            for (int i = 0; i < total; i++) {
                JsonObject chunk = new JsonObject();
                chunk.addProperty("upload_id", uploadId);
                chunk.addProperty("index", i);
                chunk.addProperty("total", total);
                chunk.addProperty("data", argumentsJson.substring(i * UPLOAD_CHUNK_CHARS,
                        Math.min(argumentsJson.length(), (i + 1) * UPLOAD_CHUNK_CHARS)));
                chunks.add(GSON.toJson(chunk));
            }
            JsonObject reference = new JsonObject();
            reference.addProperty("__p2s_upload", uploadId);
            argumentsJson = GSON.toJson(reference);
        }
        String finalArgumentsJson = argumentsJson;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            future.completeExceptionally(new IllegalStateException("Minecraft client is unavailable"));
            return future;
        }
        mc.execute(() -> {
            try {
                if (future.isDone()) {
                    return;
                }
                if (!pending.isActive().getAsBoolean()) {
                    throw new CancellationException("Tool request belongs to an inactive agent run");
                }
                if (!ClientServerBridge.canUseToolBridge()) {
                    throw ClientServerBridge.missingServerException();
                }
                if (actor != null) {
                    SubagentManager.CLAIMS.checkMutation(ClientSessionState.getProjectId(), actor, toolName,
                            payload, ClientSessionState.getSelectedWorkspacePath());
                }
                // Packets on one connection arrive in order, so chunks precede the call.
                for (String chunk : chunks) {
                    P2SClientNetworkCompat.send(new C2SToolBridgePayload(
                            requestId + "-chunk", "__p2s_upload_chunk", chunk));
                }
                P2SClientNetworkCompat.send(new C2SToolBridgePayload(
                        requestId, toolName == null ? "" : toolName, finalArgumentsJson));
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    public static void onDisconnect() {
        CONNECTION_EPOCH.incrementAndGet();
        PENDING.forEach((id, pending) -> pending.future().completeExceptionally(
                new CancellationException("Client disconnected")));
    }

    public static void onToolResponse(String requestId, boolean ok, String responseJson, String error) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        PendingRequest pending = PENDING.remove(requestId);
        if (pending == null) {
            return;
        }
        CompletableFuture<JsonObject> future = pending.future();
        try {
            if (!pending.isActive().getAsBoolean()) {
                throw new CancellationException("Tool response belongs to an inactive agent run");
            }
            if (!ok) {
                throw new IllegalStateException(error == null ? "Tool bridge failed" : error);
            }
            JsonObject payload = JsonParser.parseString(responseJson == null || responseJson.isBlank() ? "{}" : responseJson).getAsJsonObject();
            future.complete(payload);
        } catch (Exception ex) {
            future.completeExceptionally(ex);
        }
    }

    private record PendingRequest(CompletableFuture<JsonObject> future, BooleanSupplier isActive) {
    }
}
