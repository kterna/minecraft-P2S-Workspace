package com.p2s;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the patch that turns the committed script into a staged one, so a whole-file write
 * (v2 {@code edit}/{@code write}/{@code sh}/{@code script}) goes through the same validation,
 * preview and history as a hand-written {@code propose_patch}. The staged script itself stays
 * authoritative; the operations describe it per part.
 */
public final class PatchSynthesizer {
    private static final Gson GSON = new Gson();

    private PatchSynthesizer() {
    }

    public record Synthesis(PatchModels.StructurePatch patch, List<String> warnings, boolean changed) {
    }

    public static Synthesis synthesize(StructureBuilder.VbsScriptV2 committed, StructureBuilder.VbsScriptV2 next,
                                       String committedRevision, String intent) {
        PatchModels.StructurePatch patch = new PatchModels.StructurePatch();
        patch.baseRevision = committedRevision == null ? "" : committedRevision;
        patch.intent = intent == null ? "" : intent;
        List<String> warnings = new ArrayList<>();
        Map<String, StructureBuilder.StructurePart> before = index(committed, warnings, "committed");
        Map<String, StructureBuilder.StructurePart> after = index(next, warnings, "staged");
        boolean changed = false;

        for (Map.Entry<String, StructureBuilder.StructurePart> entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) {
                continue;
            }
            List<StructureBuilder.VbsAction> old = actions(entry.getValue());
            changed = true;
            if (!old.isEmpty()) {
                patch.operations.add(op("delete_part", entry.getKey(), null, null, old, null));
            }
        }
        for (Map.Entry<String, StructureBuilder.StructurePart> entry : after.entrySet()) {
            StructureBuilder.StructurePart part = entry.getValue();
            List<StructureBuilder.VbsAction> now = actions(part);
            StructureBuilder.StructurePart previous = before.get(entry.getKey());
            if (previous == null) {
                changed = true;
                if (!now.isEmpty()) {
                    patch.operations.add(op("insert_part", entry.getKey(), part.priority, now, null, null));
                }
                continue;
            }
            List<StructureBuilder.VbsAction> old = actions(previous);
            boolean sameActions = GSON.toJson(old).equals(GSON.toJson(now));
            if (sameActions && previous.priority == part.priority) {
                continue;
            }
            changed = true;
            if (old.isEmpty()) {
                if (!now.isEmpty()) {
                    patch.operations.add(op("insert_actions", entry.getKey(), part.priority, now, null, null));
                }
            } else if (now.isEmpty()) {
                patch.operations.add(op("delete_part", entry.getKey(), null, null, old, null));
            } else {
                patch.operations.add(op("replace_part", entry.getKey(), part.priority, null, old, now));
            }
        }

        Map<String, String> oldPalette = committed == null || committed.palette == null ? Map.of() : committed.palette;
        Map<String, String> newPalette = next == null || next.palette == null ? Map.of() : next.palette;
        List<PatchModels.PaletteEntry> entries = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(oldPalette.keySet());
        keys.addAll(newPalette.keySet());
        for (String key : keys) {
            String oldValue = oldPalette.get(key);
            String newValue = newPalette.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                PatchModels.PaletteEntry entry = new PatchModels.PaletteEntry();
                entry.key = key;
                entry.oldValue = oldValue;
                entry.newValue = newValue;
                entries.add(entry);
            }
        }
        if (!entries.isEmpty()) {
            changed = true;
            PatchModels.PatchOperation palette = new PatchModels.PatchOperation();
            palette.op = "update_palette";
            palette.entries = entries;
            patch.operations.add(palette);
        }
        if (!changed && !sameOrder(committed, next)) {
            changed = true;
            warnings.add("Only the part order changed");
        }
        return new Synthesis(patch, warnings, changed);
    }

    private static boolean sameOrder(StructureBuilder.VbsScriptV2 a, StructureBuilder.VbsScriptV2 b) {
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        if (a != null && a.structures != null) {
            a.structures.forEach(part -> left.add(part == null ? "" : part.name));
        }
        if (b != null && b.structures != null) {
            b.structures.forEach(part -> right.add(part == null ? "" : part.name));
        }
        return left.equals(right);
    }

    private static Map<String, StructureBuilder.StructurePart> index(StructureBuilder.VbsScriptV2 script, List<String> warnings,
                                                                    String label) {
        Map<String, StructureBuilder.StructurePart> parts = new LinkedHashMap<>();
        if (script == null || script.structures == null) {
            return parts;
        }
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null) {
                continue;
            }
            String name = part.name == null ? "" : part.name.trim();
            if (parts.putIfAbsent(name, part) != null) {
                warnings.add("Duplicate part name '" + name + "' in the " + label + " file; only the first is compared");
            }
        }
        return parts;
    }

    private static List<StructureBuilder.VbsAction> actions(StructureBuilder.StructurePart part) {
        return part == null || part.actions == null ? List.of() : part.actions;
    }

    private static PatchModels.PatchOperation op(String name, String part, Integer priority,
                                                 List<StructureBuilder.VbsAction> add,
                                                 List<StructureBuilder.VbsAction> old,
                                                 List<StructureBuilder.VbsAction> now) {
        PatchModels.PatchOperation op = new PatchModels.PatchOperation();
        op.op = name;
        op.part = part;
        op.priority = priority;
        op.actionsAdd = add == null ? null : new ArrayList<>(add);
        op.oldActions = old == null ? null : new ArrayList<>(old);
        op.newActions = now == null ? null : new ArrayList<>(now);
        return op;
    }
}
