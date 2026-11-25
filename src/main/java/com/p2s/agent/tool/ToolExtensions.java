package com.p2s.agent.tool;

import com.google.gson.JsonObject;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.vfs.VirtualFs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tool registration for other mods (pi: {@code registerTool}). Declare an entrypoint
 * {@code "p2s-tools"} in fabric.mod.json whose class implements {@link Provider}. Registered
 * tools join the v2 catalog after the built-ins, are callable from scripts, and follow the
 * scheduling rules of their declared effects (no effects = read-only, may run in parallel).
 */
public final class ToolExtensions {
    public static final String ENTRYPOINT = "p2s-tools";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{1,47}");
    private static final Map<String, Registered> TOOLS = new ConcurrentHashMap<>();
    private static final List<String> ORDER = new ArrayList<>();

    private ToolExtensions() {
    }

    /** Implemented by an extension mod's entrypoint class. */
    public interface Provider {
        void register(Registrar registrar);
    }

    public interface Registrar {
        /**
         * @param declaration OpenAI function declaration {@code {type:"function", function:{name, description, parameters}}}
         * @param effects     what the tool changes; empty for read-only tools
         */
        void tool(JsonObject declaration, java.util.Set<ToolDefinition.Effect> effects, Handler handler);
    }

    @FunctionalInterface
    public interface Handler {
        /** Return {@code {"ok": true, ...}} or {@code {"ok": false, "error": "..."}}; throwing also reports an error. */
        JsonObject execute(Context context, JsonObject arguments) throws Exception;
    }

    /** What an extension tool may use. Workspace writes through {@link #fs()} are staged for approval. */
    public interface Context {
        VirtualFs fs();

        /** Calls a server tool-bridge action of this mod (e.g. workspace_text, inspect_world). */
        JsonObject callServer(String tool, JsonObject arguments);

        String sessionId();

        AbortSignal signal();
    }

    record Registered(ToolDefinition definition, Handler handler, String owner) {
    }

    /** Registers one tool; used by {@link #load} and tests. */
    public static synchronized void register(String owner, JsonObject declaration, java.util.Set<ToolDefinition.Effect> effects,
                                             Handler handler) {
        if (declaration == null || !declaration.has("function") || !declaration.getAsJsonObject("function").has("name")) {
            throw new IllegalArgumentException("Tool declaration needs function.name");
        }
        String name = declaration.getAsJsonObject("function").get("name").getAsString();
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid tool name '" + name + "' (lowercase, digits, _)");
        }
        for (ToolDefinition builtin : ToolCatalogV2.builtin()) {
            if (builtin.name().equals(name)) {
                throw new IllegalArgumentException("Tool name '" + name + "' is reserved by a built-in tool");
            }
        }
        if (TOOLS.containsKey(name)) {
            throw new IllegalArgumentException("Tool '" + name + "' is already registered by " + TOOLS.get(name).owner());
        }
        TOOLS.put(name, new Registered(new ToolDefinition(name, true, effects == null ? java.util.Set.of() : effects,
                declaration), handler, owner == null ? "unknown" : owner));
        ORDER.add(name);
    }

    /** Loads every {@code p2s-tools} entrypoint; failures are logged and skip that provider only. */
    public static void load() {
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance();
            for (var entry : container.getEntrypointContainers(ENTRYPOINT, Provider.class)) {
                String owner = entry.getProvider().getMetadata().getId();
                try {
                    entry.getEntrypoint().register((declaration, effects, handler) -> register(owner, declaration, effects, handler));
                } catch (RuntimeException ex) {
                    com.p2s.P2SMod.LOGGER.warn("Tool extension from {} failed to register: {}", owner, ex.getMessage());
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            com.p2s.P2SMod.LOGGER.warn("Loading tool extensions failed: {}", ex.getMessage());
        }
    }

    public static synchronized List<ToolDefinition> definitions() {
        List<ToolDefinition> out = new ArrayList<>();
        for (String name : ORDER) {
            out.add(TOOLS.get(name).definition());
        }
        return out;
    }

    public static ToolDefinition definition(String name) {
        Registered registered = TOOLS.get(name);
        return registered == null ? null : registered.definition();
    }

    public static Handler handler(String name) {
        Registered registered = TOOLS.get(name);
        return registered == null ? null : registered.handler();
    }

    /** For tests. */
    static synchronized void clear() {
        TOOLS.clear();
        ORDER.clear();
    }
}
