package com.p2s.agent.vfs;

import java.util.ArrayList;
import java.util.List;

/**
 * The file view shared by v2 tools, {@code sh} and {@code script}. Paths are rooted at
 * {@code workspace/}, {@code skills/} or {@code agents/}; see {@link VfsPaths}. Writes to
 * workspace files never touch the world: implementations stage them for player approval.
 */
public interface VirtualFs {
    /** Direct children of a directory ("" lists the roots). */
    List<Entry> list(String dir) throws VfsException;

    /** Current text; for workspace files this is the staged version when one exists. */
    String read(String path) throws VfsException;

    /** The committed (approved) text; equals {@link #read} for read-only roots. */
    default String readCommitted(String path) throws VfsException {
        return read(path);
    }

    boolean exists(String path);

    boolean isDirectory(String path);

    void write(String path, String content) throws VfsException;

    void delete(String path) throws VfsException;

    void move(String from, String to) throws VfsException;

    /** All files below a directory, depth first, in listing order. */
    default List<String> walk(String dir) throws VfsException {
        List<String> files = new ArrayList<>();
        walkInto(VfsPaths.normalize(dir, true), files, 0);
        return files;
    }

    private void walkInto(String dir, List<String> out, int depth) throws VfsException {
        if (depth > 32) {
            return;
        }
        for (Entry entry : list(dir)) {
            if (entry.directory()) {
                walkInto(entry.path(), out, depth + 1);
            } else {
                out.add(entry.path());
            }
        }
    }

    record Entry(String path, boolean directory) {
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }
}
