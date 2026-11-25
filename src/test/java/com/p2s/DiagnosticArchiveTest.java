package com.p2s;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticArchiveTest {
    @Test
    void masksSecretsAcrossJsonTextAndEntryNames() throws Exception {
        JsonObject config = JsonParser.parseString("""
                {"apiKey":"configured-secret","maxTokens":4096,"headers":{"Authorization":"Bearer other-secret"}}
                """).getAsJsonObject();
        var archive = new DiagnosticArchive(config, "active-secret");
        var snapshot = JsonParser.parseString("""
                {"content":"password=from-user, configured-secret other-secret active-secret","quoted":"He said \\"abc\\""}
                """);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            archive.writeJson(zip, "config.json", config);
            archive.writeJson(zip, "session.json", snapshot);
            archive.writeText(zip, "workspace/staged/configured-secret.toml", "x-api-key: extra\nBearer another");
            assertThrows(IOException.class, () -> archive.writeText(zip, "workspace/../bad.toml", ""));
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("config.json", zip.getNextEntry().getName());
            var safe = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("***", safe.get("apiKey").getAsString());
            assertEquals(4096, safe.get("maxTokens").getAsInt());
            assertEquals("***", safe.get("headers").getAsString());
            assertEquals("session.json", zip.getNextEntry().getName());
            var json = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("password=***, *** *** ***", json.get("content").getAsString());
            assertEquals("He said \"abc\"", json.get("quoted").getAsString());
            assertEquals("workspace/staged/_redacted_.toml", zip.getNextEntry().getName());
            assertEquals("x-api-key: ***\nBearer ***", new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void filtersLogAndIncludesOnlyRelatedContinuationLines(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("latest.log");
        Files.writeString(log, "[Render/INFO] unrelated\n\tat uninteresting\n[x/ERROR] failure\n"
                + "\tat caller\n[Render/INFO] unrelated\n[x/INFO] (prompt2structure) compacted\n");
        String result = DiagnosticArchive.logExcerpt(log);
        assertTrue(result.contains("failure\n\tat caller"));
        assertTrue(result.contains("compacted"));
        assertFalse(result.contains("unrelated"));
        assertFalse(result.contains("uninteresting"));
    }

    @Test
    void transcriptIncludesToolArgumentsResultsAndClientErrors() {
        JsonObject session = JsonParser.parseString("""
                {"llmHistory":[{"role":"user","content":"build"},{"role":"assistant","tool_calls":[
                    {"id":"a","function":{"name":"read","arguments":"house.toml"}}]},
                    {"role":"tool","tool_call_id":"a","content":"file contents"}],
                 "chatLog":[{"role":"system","text":"request failed","detail":"timeout"}]}
                """).getAsJsonObject();
        String text = DiagnosticArchive.transcript(session);
        for (String s : new String[]{"build","read (a)","house.toml","file contents","request failed","timeout"}) {
            assertTrue(text.contains(s), s);
        }
    }
}
