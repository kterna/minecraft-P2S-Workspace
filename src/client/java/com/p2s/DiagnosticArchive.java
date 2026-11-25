package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** All archive entries pass through the same redactor, including workspace text and log lines. */
final class DiagnosticArchive {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MASK = "***";
    private static final String FIELD = "(?:[\\w-]*(?:api[_-]?key|access[_-]?key|private[_-]?key|secret|password|credential|(?:access|refresh|session|auth)[_-]?token)[\\w-]*|key|token|authorization|cookie|set-cookie)";
    private static final Pattern NAME = Pattern.compile(FIELD, Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)((?:^|(?<=[^\\w-]))[\"']?" + FIELD
            + "[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\]++)*+\"|'(?:\\\\.|[^'\\\\]++)*+'|(?:Bearer |Basic )?[^\\s,;&}]+)");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic) +[A-Za-z0-9._~+/=-]+");
    private static final Pattern TOKEN = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{12,}|AIza[A-Za-z0-9_-]{30,}|(?:AKIA|ASIA)[A-Z0-9]{16})\\b");
    private static final Pattern URL_USER = Pattern.compile("(?i)(https?://)[^/@\\s]+@");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern COOKIE = Pattern.compile("(?i)(?<![\\w-])((?:set-cookie|cookie)[\"']?\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern HEADERS = Pattern.compile("(?i)(?<![\\w-])headers[\"']?\\s*[:=]\\s*(?=[{\\[])");
    private static final Pattern STACK = Pattern.compile("^\\s*(?:at |Caused by:|Suppressed:|\\.\\.\\. \\d+ more).*");
    private static final int LOG_INPUT_BYTES = 2 * 1024 * 1024;
    private static final int LOG_OUTPUT_CHARS = 96 * 1024;
    private final List<String> secrets;

    DiagnosticArchive(JsonObject config, String activeKey) {
        Set<String> values = new HashSet<>();
        if (activeKey != null && !activeKey.isBlank()) values.add(activeKey);
        collect(config, false, values);
        secrets = values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private static void collect(JsonElement value, boolean sensitive, Set<String> values) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(e -> collect(e.getValue(),
                    sensitive || NAME.matcher(e.getKey()).matches() || e.getKey().equalsIgnoreCase("headers"), values));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(item -> collect(item, sensitive, values));
        } else if (sensitive && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String s = value.getAsString();
            if (!s.isBlank()) values.add(s);
            if (s.matches("(?i)(Bearer|Basic) +.+")) values.add(s.substring(s.indexOf(' ') + 1).trim());
        }
    }

    String clean(String text) {
        String trimmed = text.strip();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (parsed.isJsonObject() || parsed.isJsonArray()) {
                    JsonElement redacted = clean(parsed);
                    return redacted.equals(parsed) ? text : redacted.toString();
                }
            } catch (JsonParseException ignored) {
                // User instructions and TOML can also start and end with brackets.
            }
        }
        for (String secret : secrets) text = text.replace(secret, MASK);
        text = PRIVATE_KEY.matcher(text).replaceAll(MASK);
        text = COOKIE.matcher(text).replaceAll("$1***");
        text = maskHeaderBlocks(text);
        text = AUTH.matcher(text).replaceAll("$1 ***");
        text = ASSIGNMENT.matcher(text).replaceAll("$1***");
        text = TOKEN.matcher(text).replaceAll(MASK);
        return URL_USER.matcher(text).replaceAll("$1***@");
    }

    /** Log messages may prefix a JSON/header map with a timestamp or other ordinary text. */
    private static String maskHeaderBlocks(String text) {
        var header = HEADERS.matcher(text);
        if (!header.find()) return text;
        StringBuilder out = new StringBuilder();
        int copied = 0;
        do {
            int at = header.end();
            out.append(text, copied, at).append(MASK);
            int depth = 0;
            char quote = 0;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (quote != 0) {
                    if (c == '\\' && at < text.length()) at++;
                    else if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'') quote = c;
                else if (c == '{' || c == '[') depth++;
                else if ((c == '}' || c == ']') && --depth == 0) break;
            }
            copied = at;
        } while (header.find(copied));
        return out.append(text, copied, text.length()).toString();
    }

    JsonElement clean(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject obj = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(e -> {
                String key = e.getKey();
                if (NAME.matcher(key).matches() || key.equalsIgnoreCase("headers")) obj.addProperty(clean(key), MASK);
                else obj.add(clean(key), clean(e.getValue()));
            });
            return obj;
        }
        if (value.isJsonArray()) {
            JsonArray arr = new JsonArray();
            value.getAsJsonArray().forEach(item -> arr.add(clean(item)));
            return arr;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return new JsonPrimitive(clean(value.getAsString()));
        return value;
    }

    void writeSession(ZipOutputStream zip, JsonObject session) throws IOException {
        JsonObject redacted = clean(session).getAsJsonObject();
        writeEntry(zip, "session.json", JSON.toJson(redacted));
        writeEntry(zip, "transcript.md", transcript(redacted));
    }

    void writeJson(ZipOutputStream zip, String file, JsonElement data) throws IOException {
        // Sanitize values before serialization, so quotes in user content cannot damage the JSON.
        writeEntry(zip, file, JSON.toJson(clean(data)));
    }

    void writeText(ZipOutputStream zip, String file, String text) throws IOException {
        writeEntry(zip, file, clean(text));
    }

    private void writeEntry(ZipOutputStream zip, String file, String text) throws IOException {
        String name = clean(file).replace(MASK, "_redacted_");
        for (String part : name.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0
                    || part.indexOf(':') >= 0 || part.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Invalid diagnostic entry path");
            }
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String transcript(JsonObject session) {
        StringBuilder out = new StringBuilder("# P2S conversation\n");
        for (JsonElement item : session.getAsJsonArray("llmHistory")) {
            JsonObject msg = item.getAsJsonObject();
            String role = field(msg, "role");
            if (role.equals("system")) continue;
            out.append("\n## ").append(role).append('\n');
            if (msg.has("tool_call_id")) out.append("Call ID: ").append(field(msg, "tool_call_id")).append('\n');
            out.append(field(msg, "content")).append('\n');
            if (msg.has("p2sRawAssistant") && msg.get("p2sRawAssistant").isJsonObject()) {
                String error = field(msg.getAsJsonObject("p2sRawAssistant"), "errorMessage");
                if (!error.isBlank()) out.append("Error: ").append(error).append('\n');
            }
            if (msg.has("tool_calls")) {
                for (JsonElement call : msg.getAsJsonArray("tool_calls")) {
                    JsonObject tool = call.getAsJsonObject();
                    JsonObject fn = tool.getAsJsonObject("function");
                    out.append("\n### ").append(field(fn, "name")).append(" (").append(field(tool, "id"))
                            .append(")\n\n").append(field(fn, "arguments")).append('\n');
                }
            }
        }
        out.append("\n## Client messages and errors\n");
        for (JsonElement item : session.getAsJsonArray("chatLog")) {
            JsonObject msg = item.getAsJsonObject();
            out.append("\n### ").append(field(msg, "role")).append('\n');
            out.append(field(msg, "text")).append('\n').append(field(msg, "detail")).append('\n');
        }
        return out.toString();
    }

    private static String field(JsonObject obj, String name) {
        JsonElement v = obj == null ? null : obj.get(name);
        if (v == null || v.isJsonNull()) return "";
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    static String logExcerpt(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "(latest.log unavailable)\n";
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int count = (int) Math.min(size, LOG_INPUT_BYTES);
            channel.position(size - count);
            ByteBuffer bytes = ByteBuffer.allocate(count);
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            String tail = StandardCharsets.UTF_8.decode(bytes.flip()).toString();
            if (size > count) {
                int firstLine = tail.indexOf('\n');
                tail = firstLine < 0 ? "" : tail.substring(firstLine + 1);
            }
            boolean includeStack = false;
            StringBuilder out = new StringBuilder();
            for (String line : tail.split("\\R")) {
                boolean relevant = line.contains("prompt2structure") || line.contains("com.p2s.")
                        || line.contains("p2s-client") || line.contains("/WARN]") || line.contains("/ERROR]");
                boolean continuation = includeStack && STACK.matcher(line).matches();
                includeStack = relevant || continuation;
                if (includeStack) out.append(line).append('\n');
            }
            if (out.length() > LOG_OUTPUT_CHARS) {
                int cut = out.indexOf("\n", out.length() - LOG_OUTPUT_CHARS);
                // Drop whole lines rather than publishing a fragment of a credential.
                return out.substring(cut + 1);
            }
            return out.toString();
        }
    }
}
