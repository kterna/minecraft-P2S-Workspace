package com.p2s;

import com.google.gson.*;
import com.p2s.store.SessionPersistence;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Test companion. Only stubs the control surface; state and actions come from the running game. */
public final class AcceptanceBridge implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    private static final String READY = ".p2s-acceptance-ready.json";
    private static final String MARKER = ".p2s-acceptance-world-fixture";
    private static final int MAX_BODY = 8192;
    private Path fixture;
    private String bearer;
    private HttpServer server;
    private ExecutorService worker;

    @Override public void onInitializeClient() {
        String optIn = System.getProperty("p2s.acceptance.gameDir", "");
        if (!Boolean.getBoolean("p2s.acceptance.enabled") || optIn.isBlank()) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            try {
                Path real = mc.gameDirectory.toPath().toRealPath();
                if (!real.equals(Path.of(optIn).toRealPath()) || !Files.isRegularFile(real.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) return;
                fixture = real;
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
                worker = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task,"P2S acceptance observer");
                    thread.setDaemon(true);
                    return thread;
                });
                server.setExecutor(worker);
                server.createContext("/", ex -> handle(mc,ex));
                server.start();
                JsonObject info = new JsonObject();
                info.addProperty("pid",ProcessHandle.current().pid());
                info.addProperty("baseUrl","http://127.0.0.1:"+server.getAddress().getPort());
                info.addProperty("token",bearer);
                Path tmp = Files.createTempFile(real, ".p2s-ready-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    Files.writeString(tmp,JSON.toJson(info));
                    Files.move(tmp,real.resolve(READY),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
            } catch (Exception e) {
                stop();
                throw new IllegalStateException("Cannot initialize isolated acceptance observer",e);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
    }

    private void stop() {
        if (server != null) server.stop(0);
        if (worker != null) worker.shutdownNow();
        if (fixture != null) try { Files.deleteIfExists(fixture.resolve(READY)); } catch(IOException ignored) { }
    }

    private void handle(Minecraft mc, HttpExchange ex) {
        int status = 200;
        JsonObject result;
        try {
            String supplied = ex.getRequestHeaders().getFirst("Authorization");
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress() || ex.getRequestHeaders().containsKey("Origin")
                    || supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                    ("Bearer "+bearer).getBytes(StandardCharsets.UTF_8))) throw new Problem(403,"unauthorized");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/state") && method.equals("GET")) {
                result = onClient(mc, () -> state(mc)).get(8,TimeUnit.SECONDS);
            } else if (path.equals("/world/block") && method.equals("GET")) {
                Map<String,String> query = new HashMap<>();
                String raw = Objects.requireNonNullElse(ex.getRequestURI().getRawQuery(), "");
                for(String pair : raw.split("&")) {
                    String[] p = pair.split("=", 2);
                    if(p.length==2) query.put(p[0],p[1]);
                }
                BlockPos pos = new BlockPos(coord(query.get("x")),coord(query.get("y")),coord(query.get("z")));
                result = onClient(mc, () -> block(mc,pos)).thenCompose(f -> f).get(12,TimeUnit.SECONDS);
            } else if (path.equals("/action") && method.equals("POST")) {
                byte[] bytes=ex.getRequestBody().readNBytes(MAX_BODY+1);
                if(bytes.length>MAX_BODY) throw new Problem(413,"request too large");
                JsonObject body = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                result = onClient(mc, () -> action(mc,body)).thenCompose(f->f).get(25,TimeUnit.SECONDS);
            } else { throw new Problem(404,"unknown path or method"); }
        } catch (Exception e) {
            Throwable t=e;
            while(t instanceof ExecutionException || t instanceof CompletionException) t=t.getCause();
            status=t instanceof Problem p ? p.status : t instanceof TimeoutException ? 504
                    : t instanceof JsonParseException || t instanceof IllegalArgumentException || t instanceof IllegalStateException ? 400 : 500;
            result=object(false);
            result.addProperty("error",t instanceof Problem || t instanceof TimeoutException ? Objects.toString(t.getMessage(),"timeout")
                    : t.getClass().getSimpleName());
        }
        try(ex) {
            byte[] out=JSON.toJson(result).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control","no-store");
            ex.sendResponseHeaders(status,out.length);
            ex.getResponseBody().write(out);
        } catch(IOException ignored) { }
    }

    private static <T> CompletableFuture<T> onClient(Minecraft mc, Supplier<T> task) {
        return CompletableFuture.supplyAsync(task,mc);
    }

    private boolean isolated(Minecraft mc) {
        var integrated=mc.getSingleplayerServer();
        if (integrated==null || mc.player==null || mc.level==null || integrated.isPublished()) return false;
        try {
            Path saves=fixture.resolve("saves");
            if (!Files.isDirectory(saves,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(fixture.resolve(MARKER),LinkOption.NOFOLLOW_LINKS)) return false;
            Path world=integrated.getWorldPath(LevelResource.ROOT).toRealPath();
            return world.getParent()!=null && world.getParent().equals(saves.toRealPath());
        } catch(IOException e) { return false; }
    }

    private static JsonObject object(boolean ok) {JsonObject o=new JsonObject();o.addProperty("ok",ok);return o;}
    private static class Problem extends RuntimeException {
        final int status;
        Problem(int status,String message){ super(message); this.status=status; }
    }
    private static JsonArray coords(BlockPos p) {
        if(p==null) return null;
        JsonArray a=new JsonArray();a.add(p.getX());a.add(p.getY());a.add(p.getZ());return a;
    }

    private JsonObject state(Minecraft mc) {
        boolean ready=isolated(mc);
        if (mc.level!=null && !ready) throw new Problem(409,"instance is outside the singleplayer fixture");
        JsonObject r=object(true);
        var mod=FabricLoader.getInstance().getModContainer("prompt2structure").orElseThrow();
        r.addProperty("modVersion",mod.getMetadata().getVersion().getFriendlyString());
        var names=mod.getOrigin().getPaths().stream().map(p->p.getFileName().toString()).toList();
        r.addProperty("artifact",names.toString());
        r.add("artifactNames",JSON.toJsonTree(names));
        r.addProperty("namespace",FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace());
        r.addProperty("development",FabricLoader.getInstance().isDevelopmentEnvironment());
        try {
            var location=P2SMod.class.getProtectionDomain().getCodeSource().getLocation();
            r.addProperty("codeSourceName",Path.of(location.toURI()).getFileName().toString());
        } catch(Exception ignored) { r.addProperty("codeSourceName",""); }
        r.addProperty("mcVersion",SharedConstants.getCurrentVersion().getName());
        r.addProperty("screen",mc.screen==null?"":mc.screen.getClass().getName());
        r.addProperty("guiWidth",mc.getWindow().getGuiScaledWidth());r.addProperty("guiHeight",mc.getWindow().getGuiScaledHeight());
        r.addProperty("windowX",mc.getWindow().getX());r.addProperty("windowY",mc.getWindow().getY());
        r.addProperty("windowWidth",mc.getWindow().getWidth());r.addProperty("windowHeight",mc.getWindow().getHeight());
        r.addProperty("worldReady",ready);r.addProperty("bridgeReady",ready && ClientServerBridge.hasRequiredServer());
        JsonArray widgets=new JsonArray();
        if(mc.screen!=null) for(var child:mc.screen.children()) {
            if(!(child instanceof AbstractWidget w)) continue;
            var e=new JsonObject();
            e.addProperty("type",w.getClass().getSimpleName());
            e.addProperty("label",w instanceof EditBox ? "" : clean(w.getMessage().getString()));
            e.addProperty("x",w.getX());e.addProperty("y",w.getY());e.addProperty("width",w.getWidth());e.addProperty("height",w.getHeight());
            e.addProperty("active",w.active);e.addProperty("visible",w.visible);e.addProperty("focused",w.isFocused());widgets.add(e);
        }
        r.add("widgets",widgets);
        if(!ready) return r;
        var p=mc.player.blockPosition();var player=new JsonObject();
        player.addProperty("x",p.getX());player.addProperty("y",p.getY());player.addProperty("z",p.getZ());r.add("player",player);
        r.add("pos1",coords(ClientSelectionManager.getPos1()));r.add("pos2",coords(ClientSelectionManager.getPos2()));
        r.addProperty("projectId",ClientSessionState.getProjectId());r.addProperty("projectName",ClientSessionState.getProjectName());
        r.addProperty("sessionId",ClientSessionState.getSessionId());r.addProperty("busy",ClientAgentManager.isAgentRunning());
        r.addProperty("status",ClientSessionState.getStatus());r.addProperty("runtimeState",ClientSessionState.getRuntimeState());
        r.addProperty("turnCount",ClientSessionState.getTurnCount());r.addProperty("selectedPath",ClientSessionState.getSelectedWorkspacePath());
        r.addProperty("pendingPath",ClientSessionState.getPendingPath());r.add("pendingPaths",JSON.toJsonTree(ClientSessionState.getPendingWorkspacePaths()));
        r.add("workspacePaths",JSON.toJsonTree(ClientSessionState.getWorkspaceFiles().stream().map(ClientSessionState.WorkspaceFileInfo::path).toList()));
        r.add("checkpoints",JSON.toJsonTree(ClientSessionState.getCheckpoints()));r.add("planItems",JSON.toJsonTree(ClientSessionState.getPlanItems()));
        r.add("choice",JSON.toJsonTree(ClientSessionState.getPendingChoice()));
        String last="";
        if(localModel()) for(var m:ClientSessionState.getMessages()) if(P2SI18n.isAssistantRole(m.role())) last=m.text();
        r.addProperty("lastAssistant",clean(last));
        return r;
    }

    private static String clean(String s) {
        if(s==null) return "";
        String key=P2SClientConfig.getApiKey();
        if(key!=null && !key.isBlank()) s=s.replace(key,"***");
        return s.length()<=240?s:s.substring(0,240);
    }

    private static boolean localModel() {
        try {
            URI uri=URI.create(P2SClientConfig.getApiUrl());
            return List.of("http","https").contains(uri.getScheme()) && List.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
        } catch(RuntimeException e){return false;}
    }

    private static int coord(String text) {
        if(text==null || !text.matches("-?[0-9]{1,8}")) throw new Problem(400,"invalid coordinate");
        return Integer.parseInt(text);
    }
    private static BlockPos position(JsonObject body,String field) {
        JsonArray a=body.getAsJsonArray(field);
        if(a==null || a.size()!=3) throw new Problem(400,"expected three coordinates for "+field);
        return new BlockPos(coord(a.get(0).getAsString()),coord(a.get(1).getAsString()),coord(a.get(2).getAsString()));
    }
    private static void nearby(Minecraft mc,BlockPos pos) {
        BlockPos p=mc.player.blockPosition();
        if(Math.abs((long)p.getX()-pos.getX())>96 || Math.abs((long)p.getY()-pos.getY())>96 || Math.abs((long)p.getZ()-pos.getZ())>96
                || mc.level.isOutsideBuildHeight(pos) || !mc.level.hasChunkAt(pos)) throw new Problem(400,"position outside loaded 96-block fixture radius");
    }
    private CompletableFuture<JsonObject> block(Minecraft mc,BlockPos pos) {
        if(!isolated(mc)) throw new Problem(409,"singleplayer fixture not ready");
        nearby(mc,pos);
        var dim=mc.level.dimension();
        String client=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
        var integrated=mc.getSingleplayerServer();
        return integrated.submit(()->{
            var level=integrated.getLevel(dim);
            if(level==null || !level.hasChunkAt(pos)) throw new Problem(409,"server chunk is not loaded");
            JsonObject r=object(true);r.addProperty("block",BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            r.addProperty("clientBlock",client);r.addProperty("x",pos.getX());r.addProperty("y",pos.getY());r.addProperty("z",pos.getZ());
            return r;
        });
    }

    private CompletableFuture<JsonObject> action(Minecraft mc,JsonObject body) {
        if(!isolated(mc) || !ClientServerBridge.hasRequiredServer()) throw new Problem(409,"singleplayer fixture or P2S server bridge not ready");
        String a=field(body,"action");
        if(Set.of("message","choice","compact","patch.apply","patch.apply_all","patch.discard").contains(a) && !localModel())
            throw new Problem(409,"set a loopback model endpoint in the fixture first");
        JsonObject result=object(true);result.addProperty("accepted",true);
        switch(a){
            case "selection" -> {
                BlockPos p1=position(body,"pos1"),p2=position(body,"pos2");nearby(mc,p1);nearby(mc,p2);
                if(Math.abs(p1.getX()-p2.getX())>32 || Math.abs(p1.getY()-p2.getY())>32 || Math.abs(p1.getZ()-p2.getZ())>32)
                    throw new Problem(400,"fixture selection exceeds 33 blocks on one axis");
                ClientSelectionManager.onLeftClick(p1);ClientSelectionManager.onRightClick(p2);
            }
            case "project.create","project.open" -> {
                boolean create=a.equals("project.create");
                JsonObject args=new JsonObject();
                String field=create?"name":"id";args.addProperty(field,field(body,field));
                return ClientToolBridge.call(create?"create_project":"open_project",args).thenApplyAsync(receipt -> {
                    if(!isolated(mc)) throw new Problem(409,"fixture closed");
                    boolean ok=receipt.has("ok")&&receipt.get("ok").getAsBoolean();
                    JsonObject r=object(ok);
                    JsonObject safe=new JsonObject();
                    for(String k:List.of("ok","id","project_id","name","error","error_key","workspace_count")) if(receipt.has(k)) safe.add(k,receipt.get(k));
                    r.add("receipt",safe);
                    if(ok)ClientAgentManager.onProjectChanged();
                    return r;
                },mc);
            }
            case "workspace.select" -> {
                String path=field(body,"path");
                if(!ClientSessionState.setSelectedWorkspacePath(path))throw new Problem(409,"workspace does not exist");
                JsonObject p=new JsonObject();p.addProperty("path",path);send("session_select_workspace",p.toString());
            }
            case "patch.apply","patch.discard" -> {
                String path=field(body,"path");
                if(!ClientSessionState.hasPendingPatch() || !path.equals(ClientSessionState.getPendingPath()) || !path.equals(ClientSessionState.getSelectedWorkspacePath()))
                    throw new Problem(409,"select workspace and wait for pendingPath before reviewing");
                if(a.equals("patch.apply")) ClientAgentManager.submitPatchApply(); else ClientAgentManager.submitPatchDiscard(optional(body,"reason"));
            }
            case "patch.apply_all" -> {
                if(!ClientSessionState.hasAnyPendingPatch()) throw new Problem(409,"nothing staged");
                ClientAgentManager.submitPatchApplyAll();
            }
            case "choice" -> {
                var c=ClientSessionState.getPendingChoice();String id=field(body,"id");
                if(c==null || c.options().stream().noneMatch(o->id.equals(o.id()))) throw new Problem(409,"no such pending choice option");
                ClientAgentManager.submitChoiceSelection(id);
            }
            case "message" -> ClientAgentManager.submitUserMessage(field(body,"text"));
            case "stop" -> ClientAgentManager.stopCurrentRun();
            case "compact" -> ClientAgentManager.submitManualCompact();
            case "export" -> ClientDiagnostics.start();
            case "undo","redo" -> send(a,"");
            case "checkpoint.create" -> {
                JsonObject payload=new JsonObject();payload.addProperty("label",field(body,"label"));send("create_checkpoint",payload.toString());
            }
            case "session.new" -> ClientAgentManager.newSession();
            case "session.restore" -> {
                String id=field(body,"id");
                if(!id.matches("[a-zA-Z0-9_-]{1,80}") || SessionPersistence.listSessions(ClientSessionState.getProjectId()).stream().noneMatch(v->id.equals(v.id())))
                    throw new Problem(409,"unknown saved session in this project");
                ClientAgentManager.restoreSession(id);
            }
            default -> throw new Problem(400,"unknown action");
        }
        return CompletableFuture.completedFuture(result);
    }
    private static void send(String action,String payload){
        if(!ClientServerBridge.sendSessionAction(action,payload))throw new Problem(409,"server bridge unavailable");
    }
    private static String optional(JsonObject body,String field) {
        if(!body.has(field) || !body.get(field).isJsonPrimitive()) return "";
        String s=body.get(field).getAsString().trim();
        if(s.length()>2000) throw new Problem(400,"field too long");
        return s;
    }
    private static String field(JsonObject body,String field){String s=optional(body,field);if(s.isEmpty())throw new Problem(400,"missing "+field);return s;}
}
