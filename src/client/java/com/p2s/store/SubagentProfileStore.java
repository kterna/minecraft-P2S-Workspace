package com.p2s.store;

import com.p2s.P2SMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SubagentProfileStore {
    public static final String DEFAULT_PROFILE_ID = "general-planner";
    private static final Path PROFILE_ROOT = FabricLoader.getInstance().getConfigDir()
            .resolve("p2s_skills")
            .resolve(".agent");
    private static final String DEFAULT_PROFILE_TEMPLATE_ROOT = "/p2s_default_profiles";
    private static final List<String> DEFAULT_PROFILE_TEMPLATE_FILES = List.of(
            "general-planner.json",
            "block-id-searcher.json",
            "patch-planner.json"
    );
    // SHA-256 of previously shipped, unmodified templates (normalized line endings).
    // Load the current template in memory for these only; never overwrite player-edited files.
    private static final java.util.Map<String, Set<String>> OLD_TEMPLATES = java.util.Map.of(
            "general-planner.json", Set.of(
                    "15bd6efae967f3541f028407facd0a7636fa7b5263b7d6630db9bf32f7a49dc3",
                    "88511741d3082715d88a6c58312196a4c55468dca41028fb45c92c7ea66fd42d",
                    "bb72d3a9a51157274b16b7e740beb1ad822225f6ec01b07f1b0f7d130249cf2c"),
            "patch-planner.json", Set.of(
                    "6cb2c1ac83e77bf55842ee806afe2ead8216c52ff98b4e6082ff1fd609c66908",
                    "2eaaa32ee152ea74ad3b4b7fff1f8894ef04cea6b2cbe8bb10abd8a736ab57cc",
                    "cbccef6885b28c080eb801101c39a4f22d790a61f91c022af4ffafd1c24d2aa4",
                    "21032e9f25e447ccbe9f14919f3c3ceddbb196c0d20823c2c1d5cfd894199cfc",
                    "b2cea22da11ce174181e0ee00affc890df0fb393450d28ac3b3f337ef3d273f2")
    );
    private static final int DEFAULT_MAX_LOOPS = 30;
    private static final int DEFAULT_TIMEOUT_SECONDS = 45;
    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "list_skills",
            "read_skill",
            "read_subdoc",
            "search_skill",
            "get_project_state",
            "read_workspace_file",
            "create_workspace_file",
            "rename_workspace_file",
            "delete_workspace_file",
            "propose_patch",
            "search_block_ids",
            // v2 tool names (legacy names above are mapped to v2 when toolset = v2)
            "ls", "read", "grep", "find", "edit", "write", "set_part", "move", "delete",
            "project_info", "inspect_world", "update_plan", "task_status", "script", "sh"
    );

    private SubagentProfileStore() {
    }

    public static synchronized List<ProfileSummary> listProfileSummaries() {
        ensureDefaults();
        List<ProfileSummary> result = new ArrayList<>();
        for (SubagentProfile profile : listProfilesInternal()) {
            result.add(new ProfileSummary(
                    profile.id(),
                    profile.name(),
                    profile.description(),
                    profile.enabled(),
                    profile.allowedTools().size()
            ));
        }
        result.sort(Comparator.comparing(ProfileSummary::id));
        return result;
    }

    public static synchronized SubagentProfile resolveProfile(String requestedId) {
        ensureDefaults();
        String normalized = normalizeId(requestedId);
        if (normalized.isBlank()) {
            normalized = DEFAULT_PROFILE_ID;
        }
        SubagentProfile fallback = null;
        for (SubagentProfile profile : listProfilesInternal()) {
            if (profile.id().equals(DEFAULT_PROFILE_ID)) {
                fallback = profile;
            }
            if (profile.id().equals(normalized)) {
                return profile;
            }
        }
        return fallback;
    }

    public static synchronized SubagentProfile getProfile(String id) {
        String normalized = normalizeId(id);
        if (normalized.isBlank()) {
            return null;
        }
        ensureDefaults();
        for (SubagentProfile profile : listProfilesInternal()) {
            if (profile.id().equals(normalized)) {
                return profile;
            }
        }
        return null;
    }

    public static Set<String> supportedTools() {
        return SUPPORTED_TOOLS;
    }

    private static List<SubagentProfile> listProfilesInternal() {
        ensureDir(PROFILE_ROOT);
        List<SubagentProfile> profiles = new ArrayList<>();
        try (var stream = Files.list(PROFILE_ROOT)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json")) {
                    continue;
                }
                SubagentProfile profile = parseProfile(file);
                if (profile != null) {
                    profiles.add(profile);
                }
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed listing subagent profiles in {}: {}", PROFILE_ROOT, e.getMessage());
        }
        profiles.sort(Comparator.comparing(SubagentProfile::id));
        return profiles;
    }

    private static SubagentProfile parseProfile(Path file) {
        try {
            String text = Files.readString(file);
            String fileName = file.getFileName().toString();
            Set<String> oldHashes = OLD_TEMPLATES.get(fileName);
            if (oldHashes != null) {
                byte[] bytes = text.replace("\r\n", "\n").strip().getBytes(StandardCharsets.UTF_8);
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if (oldHashes.contains(hash)) text = loadDefaultProfileTemplate(fileName);
            }
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String fromFileName = stripJsonExt(file.getFileName().toString());
            String id = normalizeId(getString(root, "id", fromFileName));
            if (id.isBlank()) {
                return null;
            }

            String name = getString(root, "name", id);
            String description = getString(root, "description", "");
            String systemPrompt = getString(root, "system_prompt", "");
            boolean enabled = getBoolean(root, "enabled", true);
            int maxLoops = clamp(getInt(root, "max_loops", DEFAULT_MAX_LOOPS), 1, 30);
            int timeoutSeconds = clamp(getInt(root, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS), 5, 300);

            List<String> allowedTools = readStringArray(root.get("allowed_tools"));
            List<String> filteredAllowedTools = new ArrayList<>();
            for (String tool : allowedTools) {
                if (SUPPORTED_TOOLS.contains(tool)) {
                    filteredAllowedTools.add(tool);
                }
            }
            if (filteredAllowedTools.isEmpty()) {
                filteredAllowedTools = defaultAllowedTools(id);
            }
            if (!filteredAllowedTools.contains("read_subdoc")
                    && (filteredAllowedTools.contains("list_skills")
                    || filteredAllowedTools.contains("read_skill")
                    || filteredAllowedTools.contains("search_skill"))) {
                filteredAllowedTools.add("read_subdoc");
            }

            return new SubagentProfile(
                    id,
                    name.isBlank() ? id : name,
                    description,
                    systemPrompt,
                    List.copyOf(filteredAllowedTools),
                    maxLoops,
                    timeoutSeconds,
                    enabled
            );
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed parsing subagent profile {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static void ensureDefaults() {
        ensureDir(PROFILE_ROOT);
        for (String fileName : DEFAULT_PROFILE_TEMPLATE_FILES) {
            Path path = PROFILE_ROOT.resolve(fileName);
            if (Files.exists(path)) {
                continue;
            }
            try {
                String content = loadDefaultProfileTemplate(fileName);
                if (!content.endsWith("\n")) {
                    content = content + "\n";
                }
                Files.writeString(path, content);
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Failed writing default subagent profile {}: {}", path, e.getMessage());
            }
        }
    }

    private static String loadDefaultProfileTemplate(String fileName) {
        String resourcePath = DEFAULT_PROFILE_TEMPLATE_ROOT + "/" + fileName;
        try (InputStream in = SubagentProfileStore.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Missing default subagent profile template: " + resourcePath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading default subagent profile template: " + resourcePath, e);
        }
    }

    private static List<String> defaultAllowedTools(String profileId) {
        String id = normalizeId(profileId);
        if ("block-id-searcher".equals(id)) {
            return new ArrayList<>(List.of("search_block_ids", "list_skills", "read_skill", "read_subdoc", "search_skill"));
        }
        if ("patch-planner".equals(id)) {
            return new ArrayList<>(List.of(
                    "get_project_state", "read_workspace_file", "search_block_ids", "propose_patch",
                    "list_skills", "read_skill", "read_subdoc", "search_skill"
            ));
        }
        return new ArrayList<>(List.of(
                "list_skills", "read_skill", "read_subdoc", "search_skill",
                "get_project_state", "read_workspace_file", "search_block_ids"
        ));
    }

    private static List<String> readStringArray(JsonElement element) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) {
                    continue;
                }
                String value = normalizeId(entry.getAsString());
                if (!value.isBlank()) {
                    items.add(value);
                }
            }
        }
        return new ArrayList<>(items);
    }

    private static String getString(JsonObject root, String key, String fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            String value = root.get(key).getAsString();
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int getInt(JsonObject root, String key, int fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String stripJsonExt(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    private static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9_-]+", "-");
        value = value.replaceAll("-{2,}", "-");
        value = value.replaceAll("^-+", "");
        value = value.replaceAll("-+$", "");
        return value;
    }

    private static void ensureDir(Path dir) {
        try {
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed creating directory " + dir + ": " + e.getMessage(), e);
        }
    }

    public record SubagentProfile(
            String id,
            String name,
            String description,
            String systemPrompt,
            List<String> allowedTools,
            int maxLoops,
            int timeoutSeconds,
            boolean enabled
    ) {
    }

    public record ProfileSummary(
            String id,
            String name,
            String description,
            boolean enabled,
            int toolCount
    ) {
    }
}
