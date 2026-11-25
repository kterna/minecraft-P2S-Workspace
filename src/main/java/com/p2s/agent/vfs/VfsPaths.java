package com.p2s.agent.vfs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Path rules of the virtual file system. */
public final class VfsPaths {
    public static final String WORKSPACE = "workspace";
    public static final String SKILLS = "skills";
    public static final String AGENTS = "agents";
    public static final Set<String> ROOTS = Set.of(WORKSPACE, SKILLS, AGENTS);

    private VfsPaths() {
    }

    /**
     * Normalizes separators, drops "./" and leading "/", and rejects ".." and unknown roots.
     * {@code allowRoot} accepts "" (the top level listing).
     */
    public static String normalize(String raw, boolean allowRoot) throws VfsException {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new VfsException("Path must not contain '..': " + raw);
            }
            parts.add(part);
        }
        String normalized = String.join("/", parts);
        if (normalized.isEmpty()) {
            if (allowRoot) {
                return "";
            }
            throw new VfsException("Path is required");
        }
        if (!ROOTS.contains(parts.getFirst())) {
            throw new VfsException("Unknown root '" + parts.getFirst() + "'. Paths start with workspace/, skills/ or agents/");
        }
        return normalized;
    }

    public static String root(String normalized) {
        int slash = normalized.indexOf('/');
        return slash < 0 ? normalized : normalized.substring(0, slash);
    }

    public static boolean isWorkspaceFile(String normalized) {
        return normalized.startsWith(WORKSPACE + "/");
    }

    public static String parent(String normalized) {
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    /** Glob with {@code *}, {@code ?} and {@code **}; matched against the whole path. */
    public static boolean globMatches(String glob, String path) {
        return path.matches(globToRegex(glob));
    }

    public static boolean hasGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    public static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        regex.append("/?");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }
}
