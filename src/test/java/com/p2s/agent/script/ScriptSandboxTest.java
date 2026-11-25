package com.p2s.agent.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.core.AgentTool;
import com.p2s.agent.core.AgentToolResult;
import com.p2s.agent.vfs.MemoryFs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSandboxTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> threads = ConcurrentHashMap.newKeySet();
    private final MemoryFs fs = new MemoryFs().put("workspace/house.toml", MemoryFs.sampleToml());

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static AgentTool tool(String name, AgentTool.ExecutionMode mode, Function<JsonObject, AgentToolResult> body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JsonObject declaration() {
                return JsonParser.parseString("{\"type\":\"function\",\"function\":{\"name\":\"" + name
                        + "\",\"description\":\"test tool\"}}").getAsJsonObject();
            }

            @Override
            public ExecutionMode executionMode() {
                return mode;
            }

            @Override
            public AgentToolResult execute(String id, JsonObject args, AbortSignal signal) {
                return body.apply(args);
            }
        };
    }

    private List<AgentTool> tools() {
        return List.of(
                tool("read", AgentTool.ExecutionMode.PARALLEL, args -> {
                    threads.add(Thread.currentThread().getName());
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty("ok", true);
                    payload.addProperty("text", "T:" + args.get("path").getAsString());
                    return AgentToolResult.of(payload);
                }),
                tool("fail", AgentTool.ExecutionMode.SEQUENTIAL, args -> {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("ok", false);
                    payload.addProperty("error", "boom");
                    return AgentToolResult.of(payload);
                }));
    }

    private ScriptSandbox.Result run(String source) {
        return run(source, new HashMap<>(), AbortSignal.create());
    }

    private ScriptSandbox.Result run(String source, Map<String, com.google.gson.JsonElement> store, AbortSignal signal) {
        return ScriptSandbox.run(source, tools(), fs, store, signal, pool, ScriptSandbox.Options.defaults());
    }

    @Test
    void returnsTextAndCallsTools() {
        var result = run("text('a'); return tools.read({path: 'x'}).text");
        assertTrue(result.ok(), result.error());
        assertEquals("a\nT:x", result.output());
        assertEquals(1, result.calls().size());
    }

    @Test
    void toolFailuresAreCatchableErrors() {
        var caught = run("try { tools.fail({}) } catch (e) { return 'caught: ' + e.message }");
        assertEquals("caught: fail: boom", caught.output());
        var uncaught = run("tools.fail({})");
        assertFalse(uncaught.ok());
        assertTrue(uncaught.error().contains("boom"));
        assertFalse(uncaught.calls().getFirst().ok());
    }

    @Test
    void batchRunsReadOnlyCallsConcurrently() {
        var result = run("const t = Date.now(); const r = batch([{tool:'read',args:{path:'a'}},{tool:'read',args:{path:'b'}},"
                + "{tool:'read',args:{path:'c'}},{tool:'nope',args:{}}]); return r.map(x => x.ok ? x.result.text : 'E').join(',')"
                + " + ' fast=' + ((Date.now() - t) < 400)");
        assertEquals("T:a,T:b,T:c,E fast=true", result.output());
        assertTrue(threads.size() > 1);
    }

    @Test
    void promiseAllStyleScriptsStillWork() {
        assertEquals("T:1T:2", run("const [a, b] = await Promise.all([tools.read({path:'1'}), tools.read({path:'2'})]); return a.text + b.text").output());
    }

    @Test
    void timeoutCannotBeCaughtByTheScript() {
        var result = run("// @options: {\"timeout_ms\": 1000}\ntry { while (true) {} } catch (e) { return 'caught' }");
        assertFalse(result.ok());
        assertEquals("Script timed out", result.error());
    }

    @Test
    void stopFromAnotherThreadEndsTheScriptAndCommitsNothing() {
        AbortSignal signal = AbortSignal.create();
        pool.execute(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            signal.abort("Stopped by user");
        });
        var result = run("fs.write('workspace/house.toml', fs.read('workspace/house.toml')); while (true) {}", new HashMap<>(), signal);
        assertFalse(result.ok());
        assertEquals("Stopped by user", result.error());
        assertTrue(fs.log.isEmpty());
    }

    @Test
    void fileWritesAreOneTransaction() {
        var failed = run("const d = ws.load('workspace/house.toml'); d.parts = []; ws.save('workspace/house.toml', d); throw new Error('nope')");
        assertFalse(failed.ok());
        assertTrue(fs.log.isEmpty(), "a failing script must not stage anything");

        var ok = run("const d = ws.load('workspace/house.toml');"
                + " d.parts.push({name: 'dome', priority: 2, actions: geo.dome({center: [2, 5, 2], radius: 3, block: 'minecraft:glass'})});"
                + " ws.save('workspace/house.toml', d); return d.parts.length");
        assertTrue(ok.ok(), ok.error());
        assertEquals("3", ok.output());
        assertEquals(List.of("write workspace/house.toml"), fs.log);
        assertTrue(fs.files.get("workspace/house.toml").contains("name = \"dome\""));
    }

    @Test
    void readOnlyRootsAndJavaAreUnreachable() {
        assertTrue(run("fs.write('skills/x/SKILL.md', 'hi')").error().contains("Read-only"));
        assertFalse(run("return java.lang.System.getProperty('user.home')").ok());
        assertEquals("undefined", run("return typeof Packages").output());
    }

    @Test
    void storeIsKeptOnlyForSuccessfulScripts() {
        Map<String, com.google.gson.JsonElement> store = new HashMap<>();
        var saved = run("store('k', {a: 1}); return load('k').a", store, AbortSignal.create());
        assertEquals("1", saved.output());
        assertEquals(1, saved.storeUpdates().get("k").getAsJsonObject().get("a").getAsInt());
        var failed = run("store('k2', 1); throw new Error('x')", store, AbortSignal.create());
        assertTrue(failed.storeUpdates().isEmpty());
    }

    @Test
    void loopClosuresAreFixedInsideTheSandbox() {
        assertEquals("0,1,2", run("const fs2 = []; for (let i = 0; i < 3; i++) { fs2.push(() => i) } return fs2.map(f => f()).join()").output());
    }

    @Test
    void exitEndsSuccessfully() {
        var result = run("text('before'); exit(); text('after')");
        assertTrue(result.ok());
        assertEquals("before", result.output());
    }

    @Test
    void payloadListsNestedCalls() {
        JsonObject payload = run("tools.read({path:'a'}); try { tools.fail({}) } catch (e) {} return 'done'").toPayload(10);
        assertEquals(2, payload.get("nested_call_count").getAsInt());
        assertEquals(1, payload.get("nested_call_failures").getAsInt());
        assertEquals("Script completed", payload.get("status").getAsString());
    }
}
