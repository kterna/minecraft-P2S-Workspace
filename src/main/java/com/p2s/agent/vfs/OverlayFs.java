package com.p2s.agent.vfs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transaction over a {@link VirtualFs}: writes, deletes and moves are kept in memory and
 * reach the base only through {@link #commit()}. {@code sh} and {@code script} run on an
 * overlay so a failed or stopped run leaves no half-edited files.
 */
public final class OverlayFs implements VirtualFs {
    private static final String DELETED = "\u0000deleted";

    private final VirtualFs base;
    private final Map<String, String> changes = new LinkedHashMap<>();
    private final List<String[]> moves = new ArrayList<>();
    /** Targets of pending server-side moves, readable before commit. */
    private final Map<String, String> movedTargets = new LinkedHashMap<>();
    private final long maxBytes;
    private long bytes;

    public OverlayFs(VirtualFs base, long maxBytes) {
        this.base = base;
        this.maxBytes = maxBytes;
    }

    public boolean hasChanges() {
        return !changes.isEmpty() || !moves.isEmpty();
    }

    /** Paths written or deleted so far, in first-touch order. */
    public Set<String> changedPaths() {
        return new LinkedHashSet<>(changes.keySet());
    }

    @Override
    public List<Entry> list(String dir) throws VfsException {
        String normalized = VfsPaths.normalize(dir, true);
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (normalized.isEmpty() || base.isDirectory(normalized)) {
            for (Entry entry : base.list(normalized)) {
                entries.put(entry.path(), entry);
            }
        }
        String prefix = normalized.isEmpty() ? "" : normalized + "/";
        Map<String, String> overlay = new LinkedHashMap<>(movedTargets);
        overlay.putAll(changes);
        for (Map.Entry<String, String> change : overlay.entrySet()) {
            String path = change.getKey();
            if (!path.startsWith(prefix)) {
                continue;
            }
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (DELETED.equals(change.getValue())) {
                if (slash < 0) {
                    entries.remove(path);
                }
                continue;
            }
            if (slash < 0) {
                entries.put(path, new Entry(path, false));
            } else {
                String child = prefix + rest.substring(0, slash);
                entries.putIfAbsent(child, new Entry(child, true));
            }
        }
        if (entries.isEmpty() && !normalized.isEmpty() && !isDirectory(normalized)) {
            throw new VfsException("Not a directory: " + normalized);
        }
        return new ArrayList<>(entries.values());
    }

    @Override
    public String read(String path) throws VfsException {
        String normalized = VfsPaths.normalize(path, false);
        String changed = lookup(normalized);
        if (DELETED.equals(changed)) {
            throw new VfsException("No such file: " + normalized);
        }
        return changed != null ? changed : base.read(normalized);
    }

    @Override
    public String readCommitted(String path) throws VfsException {
        return base.readCommitted(path);
    }

    @Override
    public boolean exists(String path) {
        try {
            String normalized = VfsPaths.normalize(path, false);
            String changed = lookup(normalized);
            if (changed != null) {
                return !DELETED.equals(changed);
            }
            return base.exists(normalized) || isDirectory(normalized);
        } catch (VfsException ex) {
            return false;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        try {
            String normalized = VfsPaths.normalize(path, true);
            if (normalized.isEmpty() || base.isDirectory(normalized)) {
                return true;
            }
            String prefix = normalized + "/";
            for (Map.Entry<String, String> change : changes.entrySet()) {
                if (change.getKey().startsWith(prefix) && !DELETED.equals(change.getValue())) {
                    return true;
                }
            }
            for (String target : movedTargets.keySet()) {
                if (target.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (VfsException ex) {
            return false;
        }
    }

    @Override
    public void write(String path, String content) throws VfsException {
        String normalized = VfsPaths.normalize(path, false);
        if (!VfsPaths.isWorkspaceFile(normalized)) {
            throw new VfsException("Read-only path: " + normalized + " (only workspace/ is writable)");
        }
        if (isDirectory(normalized)) {
            throw new VfsException("Is a directory: " + normalized);
        }
        String text = content == null ? "" : content;
        bytes += text.length();
        if (bytes > maxBytes) {
            throw new VfsException("Write limit exceeded (" + maxBytes + " characters per run)");
        }
        changes.put(normalized, text);
    }

    @Override
    public void delete(String path) throws VfsException {
        String normalized = VfsPaths.normalize(path, false);
        if (!VfsPaths.isWorkspaceFile(normalized)) {
            throw new VfsException("Read-only path: " + normalized);
        }
        if (!exists(normalized)) {
            throw new VfsException("No such file: " + normalized);
        }
        changes.put(normalized, DELETED);
    }

    @Override
    public void move(String from, String to) throws VfsException {
        String source = VfsPaths.normalize(from, false);
        String target = VfsPaths.normalize(to, false);
        if (!VfsPaths.isWorkspaceFile(source) || !VfsPaths.isWorkspaceFile(target)) {
            throw new VfsException("Only workspace files can be moved");
        }
        if (exists(target)) {
            throw new VfsException("Target exists: " + target);
        }
        String content = read(source);
        boolean pristine = !changes.containsKey(source) && base.exists(source);
        if (pristine) {
            // Keep the server record (origin, size, history) by moving it at commit time.
            moves.add(new String[]{source, target});
            changes.put(source, DELETED);
            changes.remove(target);
            movedTargets.put(target, content);
        } else {
            changes.put(target, content);
            changes.put(source, DELETED);
        }
    }

    /** Overlay state of a path: content, {@link #DELETED}, or null when untouched. */
    private String lookup(String normalized) {
        String changed = changes.get(normalized);
        return changed != null ? changed : movedTargets.get(normalized);
    }

    /** Applies moves, then deletes, then final file contents. Returns one line per change. */
    public List<CommitResult> commit() {
        List<CommitResult> results = new ArrayList<>();
        Set<String> moveSources = new LinkedHashSet<>();
        for (String[] move : moves) {
            moveSources.add(move[0]);
            try {
                base.move(move[0], move[1]);
                results.add(new CommitResult(move[1], "moved from " + move[0], true));
            } catch (VfsException ex) {
                results.add(new CommitResult(move[1], ex.getMessage(), false));
            }
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (DELETED.equals(change.getValue()) && !moveSources.contains(change.getKey())) {
                try {
                    base.delete(change.getKey());
                    results.add(new CommitResult(change.getKey(), "deleted", true));
                } catch (VfsException ex) {
                    results.add(new CommitResult(change.getKey(), ex.getMessage(), false));
                }
            }
        }
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (!DELETED.equals(change.getValue())) {
                try {
                    base.write(change.getKey(), change.getValue());
                    results.add(new CommitResult(change.getKey(), "staged", true));
                } catch (VfsException ex) {
                    results.add(new CommitResult(change.getKey(), ex.getMessage(), false));
                }
            }
        }
        changes.clear();
        moves.clear();
        movedTargets.clear();
        return results;
    }

    @Override
    public String toString() {
        return "OverlayFs" + changes.keySet();
    }

    public record CommitResult(String path, String message, boolean ok) {
    }
}
