package com.p2s.agent.vfs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** In-memory {@link VirtualFs} for tests; {@link #committed} plays the approved version. */
public final class MemoryFs implements VirtualFs {
    public final TreeMap<String, String> files = new TreeMap<>();
    public final Map<String, String> committed = new LinkedHashMap<>();
    public final List<String> log = new ArrayList<>();

    public MemoryFs put(String path, String content) {
        files.put(path, content);
        committed.put(path, content);
        return this;
    }

    @Override
    public List<Entry> list(String dir) throws VfsException {
        String normalized = VfsPaths.normalize(dir, true);
        if (normalized.isEmpty()) {
            return List.of(new Entry("workspace", true), new Entry("skills", true), new Entry("agents", true));
        }
        String prefix = normalized + "/";
        Set<String> seen = new LinkedHashSet<>();
        List<Entry> out = new ArrayList<>();
        for (String file : files.keySet()) {
            if (!file.startsWith(prefix)) {
                continue;
            }
            String rest = file.substring(prefix.length());
            int slash = rest.indexOf('/');
            String child = slash < 0 ? file : prefix + rest.substring(0, slash);
            if (seen.add(child)) {
                out.add(new Entry(child, slash >= 0));
            }
        }
        return out;
    }

    @Override
    public String read(String path) throws VfsException {
        String value = files.get(VfsPaths.normalize(path, false));
        if (value == null) {
            throw new VfsException("No such file: " + path);
        }
        return value;
    }

    @Override
    public String readCommitted(String path) throws VfsException {
        String value = committed.get(VfsPaths.normalize(path, false));
        if (value == null) {
            throw new VfsException("No committed version: " + path);
        }
        return value;
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(path);
    }

    @Override
    public boolean isDirectory(String path) {
        if (path.isEmpty() || VfsPaths.ROOTS.contains(path)) {
            return true;
        }
        for (String file : files.keySet()) {
            if (file.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void write(String path, String content) {
        files.put(path, content);
        log.add("write " + path);
    }

    @Override
    public void delete(String path) {
        files.remove(path);
        log.add("delete " + path);
    }

    @Override
    public void move(String from, String to) {
        files.put(to, files.remove(from));
        log.add("move " + from + " " + to);
    }

    /** A small valid workspace file. */
    public static String sampleToml() {
        return """
                format_version = 1

                [workspace]
                name = "house"
                type = "manual"

                [[part]]
                name = "walls"
                priority = 0

                [[part.action]]
                type = "box"
                block = "minecraft:oak_planks"
                from = [0, 0, 0]
                to = [4, 3, 4]
                mode = "walls"

                [[part]]
                name = "roof"
                priority = 1

                [[part.action]]
                type = "plane"
                block = "minecraft:oak_slab"
                from = [0, 4, 0]
                to = [4, 4, 4]
                axis = "y"
                """;
    }
}
