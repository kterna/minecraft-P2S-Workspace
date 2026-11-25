package com.p2s.agent.script;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.vfs.OverlayFs;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.vfs.VirtualFs;
import com.p2s.agent.vfs.WorkspaceDocs;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code script} tool runtime (counterpart of pi's codemode): model-written JavaScript in a
 * Rhino sandbox with no Java access. Scripts call other tools synchronously through
 * {@code tools.*}, run independent calls concurrently with {@code batch}, and edit files through
 * {@code fs}/{@code ws}/{@code geo}. File writes form one transaction committed only when the
 * script succeeds.
 */
public final class ScriptSandbox {
    public static final int MAX_NESTED_CALLS = 256;
    public static final int MAX_ACTIONS_PER_SAVE = 20_000;
    public static final long MAX_WRITE_CHARS = 4_000_000L;
    public static final int MAX_STORE_VALUE_CHARS = 262_144;
    public static final int MAX_STORE_TOTAL_CHARS = 1_048_576;
    private static final Gson GSON = new Gson();
    private static final String DEADLINE_KEY = "p2s.deadline";
    private static final String SIGNAL_KEY = "p2s.signal";
    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "p2s-script-timer");
        thread.setDaemon(true);
        return thread;
    });
    private static final ContextFactory FACTORY = new SandboxFactory();

    private ScriptSandbox() {
    }

    /** Limits of one run; the script's {@code // @options:} line may change them within bounds. */
    public record Options(long timeoutMs, int maxOutputChars) {
        public static Options defaults() {
            return new Options(30_000, 40_000);
        }
    }

    public record NestedCall(String tool, boolean ok, String error, long millis) {
    }

    public record Result(boolean ok, String output, String console, String error, long wallMillis,
                         List<NestedCall> calls, List<OverlayFs.CommitResult> commits,
                         Map<String, JsonElement> storeUpdates) {
        /** Tool payload returned to the model. */
        public JsonObject toPayload(int maxCallsListed) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", ok);
            payload.addProperty("tool", "script");
            payload.addProperty("status", ok ? "Script completed" : "Script failed");
            payload.addProperty("wall_ms", wallMillis);
            payload.addProperty("output", output);
            if (!console.isEmpty()) {
                payload.addProperty("console", console);
            }
            if (!ok) {
                payload.addProperty("error", error);
            }
            JsonArray nested = new JsonArray();
            int failed = 0;
            for (NestedCall call : calls) {
                if (!call.ok()) {
                    failed++;
                }
                if (nested.size() < maxCallsListed) {
                    JsonObject item = new JsonObject();
                    item.addProperty("tool", call.tool());
                    item.addProperty("ok", call.ok());
                    if (!call.ok()) {
                        item.addProperty("error", truncate(call.error(), 300));
                    }
                    nested.add(item);
                }
            }
            payload.addProperty("nested_call_count", calls.size());
            payload.addProperty("nested_call_failures", failed);
            payload.add("nested_calls", nested);
            if (!commits.isEmpty()) {
                JsonArray files = new JsonArray();
                for (OverlayFs.CommitResult commit : commits) {
                    JsonObject item = new JsonObject();
                    item.addProperty("path", commit.path());
                    item.addProperty("ok", commit.ok());
                    item.addProperty("result", commit.message());
                    files.add(item);
                }
                payload.add("files", files);
            }
            return payload;
        }
    }

    /**
     * @param tools    tools the script may call (already filtered by profile and excluding
     *                 interaction tools, {@code script} and {@code sh})
     * @param fs       base file system; writes go through an overlay committed on success
     * @param store    values saved by earlier scripts of this session (read only here)
     * @param executor pool for {@code batch} calls of PARALLEL tools
     */
    public static Result run(String rawSource, List<AgentTool> tools, VirtualFs fs, Map<String, JsonElement> store,
                             AbortSignal signal, Executor executor, Options defaults) {
        long started = System.nanoTime();
        State state = new State(tools, new OverlayFs(fs, MAX_WRITE_CHARS), store, signal, executor);
        Options options;
        String source;
        try {
            options = parseOptions(rawSource, defaults);
            source = ScriptSourceRewriter.rewrite(stripOptionsLine(rawSource));
        } catch (ScriptSourceRewriter.RejectedScriptException | IllegalArgumentException ex) {
            return state.result(false, ex.getMessage(), started, options(defaults));
        }
        state.maxOutput = options.maxOutputChars();

        // Tools see a signal that also fires at the deadline, so a slow call cannot outlive the script.
        AbortSignal scriptSignal = AbortSignal.create();
        AbortSignal.Registration parent = signal.onAbort(() -> scriptSignal.abort(signal.reason()));
        var timer = TIMERS.schedule(() -> scriptSignal.abort("Script timed out"), options.timeoutMs(), TimeUnit.MILLISECONDS);
        state.toolSignal = scriptSignal;
        try (Context cx = FACTORY.enterContext()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            cx.setInstructionObserverThreshold(10_000);
            cx.setMaximumInterpreterStackDepth(1_000);
            cx.setClassShutter(className -> false);
            cx.putThreadLocal(DEADLINE_KEY, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.timeoutMs()));
            cx.putThreadLocal(SIGNAL_KEY, scriptSignal);
            ScriptableObject scope = cx.initSafeStandardObjects();
            state.install(cx, scope);
            cx.evaluateString(scope, PRELUDE, "prelude", 1, null);
            Object value = cx.evaluateString(scope, "(function(){ " + source + "\n})()", "script", 1, null);
            if (!Undefined.isUndefined(value) && value != null) {
                state.text(cx, scope, value);
            }
            return state.commitAndFinish(started);
        } catch (ExitSignal exit) {
            return state.commitAndFinish(started);
        } catch (StopSignal stop) {
            return state.result(false, stop.getMessage(), started, options);
        } catch (JavaScriptException ex) {
            return state.result(false, describeJsError(ex), started, options);
        } catch (EcmaError | EvaluatorException ex) {
            return state.result(false, ex.details() + " (line " + ex.lineNumber() + ")", started, options);
        } catch (RhinoException ex) {
            return state.result(false, ex.getMessage(), started, options);
        } catch (RuntimeException ex) {
            return state.result(false, ex.getClass().getSimpleName() + ": " + ex.getMessage(), started, options);
        } finally {
            timer.cancel(false);
            parent.close();
        }
    }

    private static Options options(Options defaults) {
        return defaults == null ? Options.defaults() : defaults;
    }

    /** {@code // @options: {"timeout_ms": 60000, "max_output_chars": 20000}} on the first line. */
    static Options parseOptions(String source, Options defaults) {
        Options base = options(defaults);
        String first = source == null ? "" : source.lines().findFirst().orElse("").trim();
        if (!first.startsWith("// @options:")) {
            return base;
        }
        try {
            JsonObject json = JsonParser.parseString(first.substring("// @options:".length())).getAsJsonObject();
            long timeout = json.has("timeout_ms") ? json.get("timeout_ms").getAsLong() : base.timeoutMs();
            int output = json.has("max_output_chars") ? json.get("max_output_chars").getAsInt() : base.maxOutputChars();
            return new Options(Math.max(1_000, Math.min(120_000, timeout)), Math.max(1_000, Math.min(100_000, output)));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid // @options line: " + ex.getMessage());
        }
    }

    private static String stripOptionsLine(String source) {
        String text = source == null ? "" : source;
        if (text.stripLeading().startsWith("// @options:")) {
            int newline = text.indexOf('\n');
            // Keep the line break so line numbers in errors stay the same.
            return newline < 0 ? "" : text.substring(newline);
        }
        return text;
    }

    private static String describeJsError(JavaScriptException ex) {
        Object value = ex.getValue();
        String message = null;
        if (value instanceof Scriptable error) {
            Object m = ScriptableObject.getProperty(error, "message");
            if (m != Scriptable.NOT_FOUND && m != null && !(m instanceof Scriptable)) {
                message = String.valueOf(m);
            }
        }
        if (message == null) {
            message = Context.toString(value);
        }
        return message + (ex.lineNumber() > 0 ? " (line " + ex.lineNumber() + ")" : "");
    }

    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    /** Keeps the start and the end of long output. */
    static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int head = limit * 2 / 3;
        int tail = limit - head;
        return text.substring(0, head) + "\n...[" + (text.length() - limit) + " characters omitted]...\n"
                + text.substring(text.length() - tail);
    }

    private static final String PRELUDE = """
            var console = { log: function () { __console(Array.prototype.slice.call(arguments)); } };
            console.info = console.warn = console.error = console.debug = console.log;
            Promise.all = function (xs) { return Array.prototype.slice.call(xs); };
            Promise.allSettled = function (xs) {
              return Array.prototype.slice.call(xs).map(function (v) { return { status: 'fulfilled', value: v }; });
            };
            (function () {
              var repeat = String.prototype.repeat;
              String.prototype.repeat = function (n) {
                if (this.length * n > 4000000) { throw new RangeError('repeat result too large'); }
                return repeat.call(this, n);
              };
            })();
            """;

    /** Ends the script successfully ({@code exit()}). */
    private static final class ExitSignal extends Error {
        ExitSignal() {
            super("exit", null, false, false);
        }
    }

    /** Timeout, Stop or memory guard; a Java Error so scripts cannot catch it. */
    private static final class StopSignal extends Error {
        StopSignal(String message) {
            super(message, null, false, false);
        }
    }

    private static final class SandboxFactory extends ContextFactory {
        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object deadline = cx.getThreadLocal(DEADLINE_KEY);
            if (deadline instanceof Long until && System.nanoTime() > until) {
                throw new StopSignal("Script timed out");
            }
            if (cx.getThreadLocal(SIGNAL_KEY) instanceof AbortSignal signal && signal.isAborted()) {
                throw new StopSignal(signal.reason().isBlank() ? "Script stopped" : signal.reason());
            }
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            if (used > runtime.maxMemory() * 0.9) {
                throw new StopSignal("Script stopped: the game is low on memory; process less data per script");
            }
        }
    }

    /** Per-run state and host functions. */
    private static final class State {
        private final Map<String, AgentTool> tools = new LinkedHashMap<>();
        private final OverlayFs fs;
        private final Map<String, JsonElement> store;
        private final Map<String, JsonElement> storeUpdates = new LinkedHashMap<>();
        private final AbortSignal signal;
        private final Executor executor;
        private final StringBuilder output = new StringBuilder();
        private final List<String> console = new ArrayList<>();
        private final List<NestedCall> calls = new ArrayList<>();
        private final AtomicInteger callIds = new AtomicInteger();
        private AbortSignal toolSignal;
        private int maxOutput = Options.defaults().maxOutputChars();
        private int outputItems;
        private Function jsonParse;
        private Function jsonStringify;

        State(List<AgentTool> tools, OverlayFs fs, Map<String, JsonElement> store, AbortSignal signal, Executor executor) {
            for (AgentTool tool : tools) {
                this.tools.put(tool.name(), tool);
            }
            this.fs = fs;
            this.store = store == null ? Map.of() : store;
            this.signal = signal;
            this.executor = executor;
            this.toolSignal = signal;
        }

        void install(Context cx, ScriptableObject scope) {
            Scriptable json = (Scriptable) ScriptableObject.getProperty(scope, "JSON");
            jsonParse = (Function) ScriptableObject.getProperty(json, "parse");
            jsonStringify = (Function) ScriptableObject.getProperty(json, "stringify");

            Scriptable toolsObject = cx.newObject(scope);
            for (String name : tools.keySet()) {
                define(toolsObject, scope, jsIdentifier(name), 1, (c, s, t, args) -> {
                    try {
                        return toJs(c, s, callTool(name, args.length > 0 ? toJsonObject(c, s, args[0]) : new JsonObject()));
                    } catch (ToolFailure failure) {
                        // A real JS Error, so scripts can catch it and read e.message.
                        throw jsError(c, s, failure.getMessage());
                    }
                });
            }
            ScriptableObject.putProperty(scope, "tools", toolsObject);
            define(scope, scope, "batch", 1, (c, s, t, args) -> toJs(c, s, batch(toJsonArray(c, s, arg(args, 0)))));
            define(scope, scope, "text", 1, (c, s, t, args) -> {
                text(c, s, arg(args, 0));
                return Undefined.instance;
            });
            define(scope, scope, "exit", 0, (c, s, t, args) -> {
                throw new ExitSignal();
            });
            define(scope, scope, "__console", 1, (c, s, t, args) -> {
                List<String> parts = new ArrayList<>();
                for (JsonElement element : toJsonArray(c, s, arg(args, 0))) {
                    parts.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
                }
                if (console.size() < 2_000) {
                    console.add(String.join(" ", parts));
                }
                return Undefined.instance;
            });
            define(scope, scope, "store", 2, (c, s, t, args) -> {
                String key = Context.toString(arg(args, 0));
                Object value = arg(args, 1);
                if (Undefined.isUndefined(value)) {
                    storeUpdates.put(key, null);
                } else {
                    JsonElement element = toJson(c, s, value);
                    if (element.toString().length() > MAX_STORE_VALUE_CHARS) {
                        throw jsError(c, s, "store value too large (max " + MAX_STORE_VALUE_CHARS + " characters)");
                    }
                    storeUpdates.put(key, element);
                    if (storeSize() > MAX_STORE_TOTAL_CHARS) {
                        throw jsError(c, s, "store is full (max " + MAX_STORE_TOTAL_CHARS + " characters)");
                    }
                }
                return Undefined.instance;
            });
            define(scope, scope, "load", 1, (c, s, t, args) -> {
                String key = Context.toString(arg(args, 0));
                JsonElement value = storeUpdates.containsKey(key) ? storeUpdates.get(key) : store.get(key);
                return value == null ? Undefined.instance : toJs(c, s, value);
            });
            JsonArray allTools = new JsonArray();
            for (AgentTool tool : tools.values()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", tool.name());
                item.addProperty("description", description(tool));
                allTools.add(item);
            }
            ScriptableObject.putProperty(scope, "ALL_TOOLS", toJs(cx, scope, allTools));
            define(scope, scope, "describeTool", 1, (c, s, t, args) -> {
                AgentTool tool = tools.get(Context.toString(arg(args, 0)));
                if (tool == null) {
                    return Undefined.instance;
                }
                JsonObject function = tool.declaration().getAsJsonObject("function");
                return toJs(c, s, function == null ? new JsonObject() : function);
            });
            installFs(cx, scope);
            installWs(cx, scope);
            installGeo(cx, scope);
        }

        private void installFs(Context cx, ScriptableObject scope) {
            Scriptable fsObject = cx.newObject(scope);
            define(fsObject, scope, "read", 1, (c, s, t, args) -> fileOp(c, s, () -> fs.read(Context.toString(arg(args, 0)))));
            define(fsObject, scope, "readCommitted", 1,
                    (c, s, t, args) -> fileOp(c, s, () -> fs.readCommitted(Context.toString(arg(args, 0)))));
            define(fsObject, scope, "write", 2, (c, s, t, args) -> fileOp(c, s, () -> {
                fs.write(Context.toString(arg(args, 0)), Context.toString(arg(args, 1)));
                return Undefined.instance;
            }));
            define(fsObject, scope, "exists", 1, (c, s, t, args) -> fs.exists(Context.toString(arg(args, 0))));
            define(fsObject, scope, "delete", 1, (c, s, t, args) -> fileOp(c, s, () -> {
                fs.delete(Context.toString(arg(args, 0)));
                return Undefined.instance;
            }));
            define(fsObject, scope, "move", 2, (c, s, t, args) -> fileOp(c, s, () -> {
                fs.move(Context.toString(arg(args, 0)), Context.toString(arg(args, 1)));
                return Undefined.instance;
            }));
            define(fsObject, scope, "list", 1, (c, s, t, args) -> fileOp(c, s, () -> {
                JsonArray entries = new JsonArray();
                for (VirtualFs.Entry entry : fs.list(args.length > 0 ? Context.toString(args[0]) : "")) {
                    JsonObject item = new JsonObject();
                    item.addProperty("path", entry.path());
                    item.addProperty("dir", entry.directory());
                    entries.add(item);
                }
                return toJs(c, s, entries);
            }));
            ScriptableObject.putProperty(scope, "fs", fsObject);
        }

        private void installWs(Context cx, ScriptableObject scope) {
            Scriptable wsObject = cx.newObject(scope);
            define(wsObject, scope, "load", 1, (c, s, t, args) -> fileOp(c, s,
                    () -> toJs(c, s, WorkspaceDocs.parse(fs.read(Context.toString(arg(args, 0)))))));
            define(wsObject, scope, "save", 2, (c, s, t, args) -> fileOp(c, s, () -> {
                JsonObject document = toJsonObject(c, s, arg(args, 1));
                int actions = countActions(document);
                if (actions > MAX_ACTIONS_PER_SAVE) {
                    throw new VfsException("Document has " + actions + " actions (max " + MAX_ACTIONS_PER_SAVE
                            + "); use geo shapes, which merge runs into lines, or split into several files");
                }
                fs.write(Context.toString(arg(args, 0)), WorkspaceDocs.print(document));
                return Undefined.instance;
            }));
            ScriptableObject.putProperty(scope, "ws", wsObject);
        }

        private void installGeo(Context cx, ScriptableObject scope) {
            Scriptable geo = cx.newObject(scope);
            for (String shape : List.of("circle", "disk", "sphere", "dome", "cylinder", "cone", "torus", "spiral",
                    "bezier", "arch")) {
                define(geo, scope, shape, 1, (c, s, t, args) -> geoOp(c, s, () -> {
                    JsonObject o = toJsonObject(c, s, arg(args, 0));
                    return switch (shape) {
                        case "circle" -> Geo.circle(o);
                        case "disk" -> Geo.disk(o);
                        case "sphere" -> Geo.sphere(o);
                        case "dome" -> Geo.dome(o);
                        case "cylinder" -> Geo.cylinder(o);
                        case "cone" -> Geo.cone(o);
                        case "torus" -> Geo.torus(o);
                        case "spiral" -> Geo.spiral(o);
                        case "bezier" -> Geo.bezier(o);
                        default -> Geo.arch(o);
                    };
                }));
            }
            define(geo, scope, "translate", 2, (c, s, t, args) -> geoOp(c, s,
                    () -> Geo.translate(toJsonArray(c, s, arg(args, 0)), vec(c, s, arg(args, 1)))));
            define(geo, scope, "rotateY", 3, (c, s, t, args) -> geoOp(c, s, () -> Geo.rotateY(toJsonArray(c, s, arg(args, 0)),
                    (int) Context.toNumber(arg(args, 1)), args.length > 2 && !Undefined.isUndefined(args[2]) ? vec(c, s, args[2]) : null)));
            define(geo, scope, "mirror", 3, (c, s, t, args) -> geoOp(c, s, () -> Geo.mirror(toJsonArray(c, s, arg(args, 0)),
                    Context.toString(arg(args, 1)), Context.toNumber(arg(args, 2)))));
            define(geo, scope, "scale", 2, (c, s, t, args) -> geoOp(c, s,
                    () -> Geo.scale(toJsonArray(c, s, arg(args, 0)), (int) Context.toNumber(arg(args, 1)))));
            define(geo, scope, "fromVoxels", 2, (c, s, t, args) -> geoOp(c, s, () -> Geo.fromVoxels(
                    toJsonArray(c, s, arg(args, 0)), args.length > 1 && !Undefined.isUndefined(args[1]) ? Context.toString(args[1]) : null)));
            ScriptableObject.putProperty(scope, "geo", geo);
        }

        // ---------------------------------------------------------------- tool calls

        private JsonObject callTool(String name, JsonObject args) {
            AgentTool tool = tools.get(name);
            if (calls.size() >= MAX_NESTED_CALLS) {
                throw new StopSignal("Script exceeded " + MAX_NESTED_CALLS + " tool calls");
            }
            long started = System.nanoTime();
            AgentToolResult result;
            try {
                result = tool.execute("script-" + callIds.incrementAndGet(), args, toolSignal);
            } catch (AbortSignal.AbortedException ex) {
                throw new StopSignal(ex.getMessage());
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                record(name, false, message, started);
                throw new ToolFailure(name + ": " + message, null);
            }
            if (toolSignal.isAborted()) {
                throw new StopSignal(toolSignal.reason());
            }
            JsonObject payload = result.content();
            if (result.isError()) {
                String message = payload.has("error") ? payload.get("error").getAsString() : "failed";
                record(name, false, message, started);
                throw new ToolFailure(name + ": " + message, payload);
            }
            record(name, true, "", started);
            return payload;
        }

        private synchronized void record(String name, boolean ok, String error, long started) {
            calls.add(new NestedCall(name, ok, error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }

        /** {@code [{tool, args}]} → {@code [{ok, result | error}]}; adjacent PARALLEL tools run together. */
        private JsonArray batch(JsonArray requests) {
            List<JsonObject> items = new ArrayList<>();
            for (JsonElement element : requests) {
                items.add(element.isJsonObject() ? element.getAsJsonObject() : new JsonObject());
            }
            if (calls.size() + items.size() > MAX_NESTED_CALLS) {
                throw new StopSignal("Script exceeded " + MAX_NESTED_CALLS + " tool calls");
            }
            JsonObject[] results = new JsonObject[items.size()];
            int index = 0;
            while (index < items.size()) {
                int end = index + 1;
                if (isParallel(items.get(index))) {
                    while (end < items.size() && isParallel(items.get(end))) {
                        end++;
                    }
                }
                List<CompletableFuture<JsonObject>> running = new ArrayList<>();
                for (int i = index; i < end; i++) {
                    JsonObject item = items.get(i);
                    if (end - index > 1 && executor != null) {
                        try {
                            running.add(CompletableFuture.supplyAsync(() -> batchItem(item), executor));
                        } catch (RejectedExecutionException ex) {
                            running.add(CompletableFuture.completedFuture(batchError("Tool execution queue is full")));
                        }
                    } else {
                        running.add(CompletableFuture.completedFuture(batchItem(item)));
                    }
                }
                for (int i = 0; i < running.size(); i++) {
                    try {
                        results[index + i] = running.get(i).join();
                    } catch (java.util.concurrent.CompletionException ex) {
                        if (ex.getCause() instanceof StopSignal stop) {
                            throw stop;
                        }
                        results[index + i] = batchError(String.valueOf(ex.getCause()));
                    }
                }
                if (toolSignal.isAborted()) {
                    throw new StopSignal(toolSignal.reason());
                }
                index = end;
            }
            JsonArray out = new JsonArray();
            for (JsonObject result : results) {
                out.add(result);
            }
            return out;
        }

        private boolean isParallel(JsonObject item) {
            AgentTool tool = tools.get(stringOf(item, "tool"));
            return tool != null && tool.executionMode() == AgentTool.ExecutionMode.PARALLEL;
        }

        private JsonObject batchItem(JsonObject item) {
            String name = stringOf(item, "tool");
            if (!tools.containsKey(name)) {
                return batchError("Unknown or unavailable tool: " + name);
            }
            JsonObject args = item.has("args") && item.get("args").isJsonObject() ? item.getAsJsonObject("args") : new JsonObject();
            try {
                JsonObject result = callTool(name, args);
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                ok.add("result", result);
                return ok;
            } catch (ToolFailure failure) {
                return batchError(failure.getMessage());
            }
        }

        private static JsonObject batchError(String message) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", message);
            return error;
        }

        // ---------------------------------------------------------------- output and results

        void text(Context cx, Scriptable scope, Object value) {
            String text;
            if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
                text = Context.toString(value);
            } else {
                Object json = jsonStringify.call(cx, scope, scope, new Object[]{value, null, 2});
                text = Undefined.isUndefined(json) ? Context.toString(value) : Context.toString(json);
            }
            if (++outputItems > 100_000) {
                throw new StopSignal("Too many text() calls");
            }
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(text);
            if (output.length() > 16_000_000) {
                throw new StopSignal("Script output exceeded 16000000 characters; write large data to a file");
            }
        }

        Result commitAndFinish(long started) {
            List<OverlayFs.CommitResult> commits = fs.hasChanges() ? fs.commit() : List.of();
            boolean ok = commits.stream().allMatch(OverlayFs.CommitResult::ok);
            String error = ok ? "" : "Some files could not be staged; see files[]";
            return new Result(ok, clip(output.toString(), maxOutput), String.join("\n", console), error,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), List.copyOf(calls), commits,
                    ok ? Map.copyOf(nonNullUpdates()) : Map.of());
        }

        Result result(boolean ok, String error, long started, Options options) {
            return new Result(ok, clip(output.toString(), options.maxOutputChars()), String.join("\n", console),
                    error == null ? "" : error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    List.copyOf(calls), List.of(), Map.of());
        }

        private Map<String, JsonElement> nonNullUpdates() {
            // Deletions are kept as JsonNull so the caller can remove the key.
            Map<String, JsonElement> updates = new LinkedHashMap<>();
            storeUpdates.forEach((key, value) -> updates.put(key, value == null ? com.google.gson.JsonNull.INSTANCE : value));
            return updates;
        }

        private int storeSize() {
            Map<String, JsonElement> merged = new LinkedHashMap<>(store);
            storeUpdates.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
            int total = 0;
            for (Map.Entry<String, JsonElement> entry : merged.entrySet()) {
                total += entry.getKey().length() + entry.getValue().toString().length();
            }
            return total;
        }

        // ---------------------------------------------------------------- conversions

        @FunctionalInterface
        private interface FileBody {
            Object run() throws VfsException;
        }

        @FunctionalInterface
        private interface GeoBody {
            JsonArray run();
        }

        private Object fileOp(Context cx, Scriptable scope, FileBody body) {
            try {
                return body.run();
            } catch (VfsException ex) {
                throw jsError(cx, scope, ex.getMessage());
            }
        }

        private Object geoOp(Context cx, Scriptable scope, GeoBody body) {
            try {
                return toJs(cx, scope, body.run());
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException ex) {
                throw jsError(cx, scope, "geo: " + ex.getMessage());
            }
        }

        private Object toJs(Context cx, Scriptable scope, JsonElement value) {
            if (value == null || value.isJsonNull()) {
                return null;
            }
            return jsonParse.call(cx, scope, scope, new Object[]{GSON.toJson(value)});
        }

        private JsonElement toJson(Context cx, Scriptable scope, Object value) {
            if (Undefined.isUndefined(value) || value == null) {
                return com.google.gson.JsonNull.INSTANCE;
            }
            Object json = jsonStringify.call(cx, scope, scope, new Object[]{value});
            if (Undefined.isUndefined(json)) {
                return com.google.gson.JsonNull.INSTANCE;
            }
            return JsonParser.parseString(Context.toString(json));
        }

        private JsonObject toJsonObject(Context cx, Scriptable scope, Object value) {
            JsonElement json = toJson(cx, scope, value);
            if (json.isJsonNull()) {
                return new JsonObject();
            }
            if (!json.isJsonObject()) {
                throw jsError(cx, scope, "expected an object argument");
            }
            return json.getAsJsonObject();
        }

        private JsonArray toJsonArray(Context cx, Scriptable scope, Object value) {
            JsonElement json = toJson(cx, scope, value);
            if (!json.isJsonArray()) {
                throw jsError(cx, scope, "expected an array argument");
            }
            return json.getAsJsonArray();
        }

        private int[] vec(Context cx, Scriptable scope, Object value) {
            JsonArray array = toJsonArray(cx, scope, value);
            if (array.size() != 3) {
                throw jsError(cx, scope, "expected [x, y, z]");
            }
            return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        }

        private static Object arg(Object[] args, int index) {
            return args.length > index ? args[index] : Undefined.instance;
        }

        private static RuntimeException jsError(Context cx, Scriptable scope, String message) {
            Scriptable error = cx.newObject(scope, "Error", new Object[]{message});
            return new JavaScriptException(error, "script", 0);
        }

        private static void define(Scriptable target, Scriptable scope, String name, int arity, SerializableCallable body) {
            ScriptableObject.putProperty(target, name, new LambdaFunction(scope, name, arity, body));
        }

        private static String description(AgentTool tool) {
            JsonObject function = tool.declaration().getAsJsonObject("function");
            return function != null && function.has("description") ? function.get("description").getAsString() : "";
        }

        private static String stringOf(JsonObject object, String key) {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
        }

        private static int countActions(JsonObject document) {
            int total = 0;
            if (document.has("parts") && document.get("parts").isJsonArray()) {
                for (JsonElement part : document.getAsJsonArray("parts")) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("actions")
                            && part.getAsJsonObject().get("actions").isJsonArray()) {
                        total += part.getAsJsonObject().getAsJsonArray("actions").size();
                    }
                }
            }
            return total;
        }

        private static String jsIdentifier(String name) {
            return name.replaceAll("[^A-Za-z0-9_$]", "_");
        }
    }

    /** A nested tool call failed; surfaces in the script as a catchable Error. */
    private static final class ToolFailure extends RuntimeException {
        ToolFailure(String message, JsonObject payload) {
            super(message);
        }
    }
}
