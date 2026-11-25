package com.p2s;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

final class AgentPrompt {
    // Length and SHA-256 of the verbatim text blocks shipped in ModConfig and P2SDefaults.
    // Matching a known prefix also preserves personal instructions appended below a default.
    private static final Map<Integer, String> HISTORICAL_DEFAULTS = Map.ofEntries(
            Map.entry(774, "fc54cbe2671e42a9f11776943937885b5bb7fa96ea3062e1960b34b4aec3e313"),
            Map.entry(857, "3a9cae67ec39dff30c09489e5fe694a26618e0e11cacf0982b643cfbb9dd0ba6"),
            Map.entry(871, "0a12a6cd98d821ff3394e52e80a6cbca450a21d36cf1d741fd80c8bf8aae0a31"),
            Map.entry(986, "9590941f978a457a393acc27aa4ac7ac7dea8c2feee44be89a131d9ffa327947"),
            Map.entry(1179, "f65a0a94a5163e6896ec9a45ad5906a7de84cf5dfcb3708858b56986c21dd7de"),
            Map.entry(1186, "71995a624f625c4217a86b9adaab1c48d5d02b4ec0200b7b07ba64ad9a19fac0"),
            Map.entry(1191, "27925a48c6dbec4dfbd022ca0d1d30c0d141b11eab7b4971f91a839ed10f2904"),
            Map.entry(1276, "8eeba7a21631c0ac4f69eaa59c34051e0032205917ab22268ee4f3118f340606"),
            Map.entry(1402, "74ba782175f666562a89eb36eed2f5f7e85abeb6adc97e9dbd303b7393bbbdce"),
            Map.entry(1404, "806a3af850d2c414fe7b8cdcac51b90937ced3e85c6f8ff0153034f454eb515a"),
            Map.entry(1444, "7deb45782e8915c40d5c80d10a86b326b1d48eb8cb3ae5fdcb1a3e36f9bf05df"),
            Map.entry(1463, "cc62c38e58942ad3c90a2af593f61bde2c371e980294899b3c43401936be8a76")
    );
    private static final Pattern REMOVED = Pattern.compile(
            "(?i)\\b(read_workspace_state|upsert_part)\\b|fill\\s*[/|、]\\s*frame\\s*[/|、]\\s*set");

    private AgentPrompt() {}

    static String migrate(String saved) {
        String value = saved == null ? "" : saved.strip().replace("\r\n", "\n");
        for (var entry : HISTORICAL_DEFAULTS.entrySet()) {
            int end = entry.getKey();
            if (value.length() >= end && (value.length() == end || Character.isWhitespace(value.charAt(end)))
                    && hash(value.substring(0, end)).equals(entry.getValue())) {
                return value.substring(end).strip();
            }
        }
        return REMOVED.matcher(value).find() ? "" : value;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException(unsupported);
        }
    }

    static String compose(boolean v2, String extra) {
        String base = v2 ? "You are a Minecraft building agent. Use legal Java Edition block IDs and keep edits scoped to the player's task."
                : P2SDefaults.DEFAULT_SYSTEM_PROMPT.strip();
        return extra == null || extra.isBlank() ? base : base + "\n\n## Player instructions\n" + extra;
    }
}
