package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.store.SessionPersistence;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipOutputStream;

final class ClientDiagnostics {
    private static final AtomicBoolean EXPORTING = new AtomicBoolean();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ClientDiagnostics() {}

    static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("p2s").then(ClientCommandManager.literal("export").executes(ctx -> {
                    start();
                    return 1;
                }))));
    }

    static void start() {
        Minecraft client = Minecraft.getInstance();
        SessionPersistence.SavedSession session = ClientAgentManager.diagnosticSnapshot();
        if (session == null || !session.projectId().equals(ClientSessionState.getProjectId())) {
            report(P2SI18n.tr("message.p2s.export.no_session"));
            return;
        }
        report(P2SI18n.tr("message.p2s.export.start"));
        if (!EXPORTING.compareAndSet(false, true)) return;
        List<ClientSessionState.WorkspaceFileInfo> files = ClientSessionState.getWorkspaceFiles();
        var connection = client.getConnection();
        BooleanSupplier current = () -> connection == client.getConnection()
                && session.projectId().equals(ClientSessionState.getProjectId()) && files.equals(ClientSessionState.getWorkspaceFiles());
        CompletableFuture.supplyAsync(() -> {
            try {
                return export(session, files, current);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((path, error) -> client.execute(() -> {
            EXPORTING.set(false);
            if (client.getConnection() != connection) return;
            if (error == null) {
                report(P2SI18n.tr("message.p2s.export.done", path.toAbsolutePath().toString()));
            } else {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                report(P2SI18n.tr("message.p2s.export.error", cause.getLocalizedMessage()));
            }
        }));
    }

    private static void report(Component message) {
        Minecraft.getInstance().gui.getChat().addMessage(message);
        ClientSessionState.addSystemMessage(message.getString());
    }

    private static Path export(SessionPersistence.SavedSession session, List<ClientSessionState.WorkspaceFileInfo> files,
                               BooleanSupplier current) throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path configDir = loader.getConfigDir();
        Path configPath = configDir.resolve("p2s_client.json");
        JsonObject config = Files.exists(configPath)
                ? JsonParser.parseString(Files.readString(configPath)).getAsJsonObject() : new JsonObject();
        DiagnosticArchive archive = new DiagnosticArchive(config, P2SClientConfig.getApiKey());
        Path dir = Files.createDirectories(configDir.resolve("p2s_exports"));
        var metadata = loader.getModContainer("prompt2structure").orElseThrow().getMetadata();
        String revision = metadata.containsCustomValue("p2s:commit") ? metadata.getCustomValue("p2s:commit").getAsString() : "unknown";
        Path temp = Files.createTempFile(dir, ".export-", ".part");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                JsonObject snapshot = new Gson().toJsonTree(session).getAsJsonObject();
                archive.writeJson(zip, "session.json", snapshot);
                archive.writeText(zip, "transcript.md", DiagnosticArchive.transcript(snapshot));
                archive.writeJson(zip, "config.json", config);
                archive.writeText(zip, "env.txt", "mod=" + version("prompt2structure") + "\nMinecraft=" + version("minecraft")
                        + "\ngit=" + revision + "\nFabric Loader=" + version("fabricloader") + "\ntoolset=" + P2SClientConfig.getToolset() + "\n");
                for (var file : files) {
                    String path = file.path();
                    WorkspaceText committed = readFile(session.projectId(), path, true, current);
                    WorkspaceText staged = committed.hasPending()
                            ? readFile(session.projectId(), path, false, current) : committed;
                    if (!committed.revision().equals(staged.revision())) throw changed();
                    archive.writeText(zip, "workspace/committed/" + path, committed.text());
                    archive.writeText(zip, "workspace/staged/" + path, staged.text());
                }
                archive.writeText(zip, "latest.log", DiagnosticArchive.logExcerpt(loader.getGameDir().resolve("logs/latest.log")));
                if (!current.getAsBoolean()) throw changed();
            }
            Path result = dir.resolve("p2s-" + TIMESTAMP.format(LocalDateTime.now()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".zip");
            return Files.move(temp, result);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private record WorkspaceText(String text, String revision, boolean hasPending) {}

    private static WorkspaceText readFile(String project, String path, boolean committed, BooleanSupplier current) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("project_id", project);
        args.addProperty("path", path);
        args.addProperty("committed", committed);
        StringBuilder text = new StringBuilder();
        String revision = null;
        while (true) {
            args.addProperty("offset", text.length());
            JsonObject reply = ClientToolBridge.call("workspace_text", args, current).join();
            if (!reply.get("ok").getAsBoolean()) throw new IOException(reply.get("error").getAsString());
            String actual = reply.get("committed_revision").getAsString() + "/" + reply.get("staged_revision").getAsString();
            if (revision != null && !revision.equals(actual)) throw changed();
            revision = actual;
            String chunk = reply.get("workspace_toml").getAsString();
            text.append(chunk);
            int length = reply.has("length") ? reply.get("length").getAsInt() : text.length();
            if (text.length() == length) return new WorkspaceText(text.toString(), revision, reply.get("has_pending").getAsBoolean());
            if (text.length() > length || chunk.isEmpty()) throw changed();
        }
    }

    private static IOException changed() {
        return new IOException(P2SI18n.tr("message.p2s.export.changed").getString());
    }

    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
}
