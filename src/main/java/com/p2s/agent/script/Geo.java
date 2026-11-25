package com.p2s.agent.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Geometry helpers exposed to scripts as {@code geo.*}. Shapes are voxelized in workspace
 * coordinates and compressed into actions: runs along x become {@code line} actions, the
 * remaining blocks become {@code points} actions. Transforms work on action arrays.
 */
public final class Geo {
    /** Hard cap per call so a typo in a radius cannot produce millions of blocks. */
    public static final int MAX_VOXELS = 200_000;
    private static final int POINTS_PER_ACTION = 512;

    private Geo() {
    }

    // ------------------------------------------------------------------ shapes

    public static JsonArray circle(JsonObject o) {
        boolean filled = bool(o, "filled", false);
        return ring(o, filled);
    }

    public static JsonArray disk(JsonObject o) {
        return ring(o, true);
    }

    private static JsonArray ring(JsonObject o, boolean filled) {
        int[] c = vec(o, "center");
        double r = num(o, "radius");
        double thickness = Math.max(1, num(o, "thickness", 1));
        String axis = str(o, "axis", "y");
        Voxels v = new Voxels(str(o, "block", null));
        int ri = (int) Math.ceil(r);
        for (int a = -ri; a <= ri; a++) {
            for (int b = -ri; b <= ri; b++) {
                double d = Math.sqrt(a * a + b * b);
                if (d > r + 0.5) {
                    continue;
                }
                if (!filled && d < r + 0.5 - thickness) {
                    continue;
                }
                switch (axis) {
                    case "x" -> v.add(c[0], c[1] + a, c[2] + b);
                    case "z" -> v.add(c[0] + a, c[1] + b, c[2]);
                    default -> v.add(c[0] + a, c[1], c[2] + b);
                }
            }
        }
        return v.toActions();
    }

    public static JsonArray sphere(JsonObject o) {
        return ball(o, false, bool(o, "hollow", false));
    }

    /** Upper half of a sphere, hollow by default. */
    public static JsonArray dome(JsonObject o) {
        return ball(o, true, bool(o, "hollow", true));
    }

    private static JsonArray ball(JsonObject o, boolean upperHalf, boolean hollow) {
        int[] c = vec(o, "center");
        double r = num(o, "radius");
        double thickness = Math.max(1, num(o, "thickness", 1));
        Voxels v = new Voxels(str(o, "block", null));
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = upperHalf ? 0 : -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > r + 0.5 || (hollow && d < r + 0.5 - thickness)) {
                        continue;
                    }
                    v.add(c[0] + dx, c[1] + dy, c[2] + dz);
                }
            }
        }
        return v.toActions();
    }

    public static JsonArray cylinder(JsonObject o) {
        int[] base = vec(o, "base");
        double r = num(o, "radius");
        int height = (int) num(o, "height");
        boolean hollow = bool(o, "hollow", false);
        double thickness = Math.max(1, num(o, "thickness", 1));
        String axis = str(o, "axis", "y");
        Voxels v = new Voxels(str(o, "block", null));
        int ri = (int) Math.ceil(r);
        for (int h = 0; h < height; h++) {
            for (int a = -ri; a <= ri; a++) {
                for (int b = -ri; b <= ri; b++) {
                    double d = Math.sqrt(a * a + b * b);
                    if (d > r + 0.5 || (hollow && d < r + 0.5 - thickness)) {
                        continue;
                    }
                    switch (axis) {
                        case "x" -> v.add(base[0] + h, base[1] + a, base[2] + b);
                        case "z" -> v.add(base[0] + a, base[1] + b, base[2] + h);
                        default -> v.add(base[0] + a, base[1] + h, base[2] + b);
                    }
                }
            }
        }
        return v.toActions();
    }

    /** Cone standing on {@code base} (center of the bottom disk), tip at base.y + height - 1. */
    public static JsonArray cone(JsonObject o) {
        int[] base = vec(o, "base");
        double r = num(o, "radius");
        int height = (int) num(o, "height");
        boolean hollow = bool(o, "hollow", false);
        Voxels v = new Voxels(str(o, "block", null));
        int ri = (int) Math.ceil(r);
        for (int h = 0; h < height; h++) {
            double layer = height <= 1 ? r : r * (1.0 - (double) h / (height - 1));
            for (int a = -ri; a <= ri; a++) {
                for (int b = -ri; b <= ri; b++) {
                    double d = Math.sqrt(a * a + b * b);
                    if (d > layer + 0.5 || (hollow && d < layer - 0.5)) {
                        continue;
                    }
                    v.add(base[0] + a, base[1] + h, base[2] + b);
                }
            }
        }
        return v.toActions();
    }

    public static JsonArray torus(JsonObject o) {
        int[] c = vec(o, "center");
        double major = num(o, "major");
        double minor = num(o, "minor");
        Voxels v = new Voxels(str(o, "block", null));
        int extent = (int) Math.ceil(major + minor);
        int up = (int) Math.ceil(minor);
        for (int dx = -extent; dx <= extent; dx++) {
            for (int dy = -up; dy <= up; dy++) {
                for (int dz = -extent; dz <= extent; dz++) {
                    double q = Math.sqrt(dx * dx + dz * dz) - major;
                    if (q * q + dy * dy <= (minor + 0.5) * (minor + 0.5)) {
                        v.add(c[0] + dx, c[1] + dy, c[2] + dz);
                    }
                }
            }
        }
        return v.toActions();
    }

    /** Helix around {@code center}, rising {@code height} blocks over {@code turns} turns. */
    public static JsonArray spiral(JsonObject o) {
        int[] c = vec(o, "center");
        double r = num(o, "radius");
        int height = (int) num(o, "height");
        double turns = num(o, "turns", 1);
        int width = (int) Math.max(1, num(o, "width", 1));
        Voxels v = new Voxels(str(o, "block", null));
        int steps = Math.max(8, (int) Math.ceil(2 * Math.PI * r * turns * 2) + height * 2);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double angle = t * turns * 2 * Math.PI;
            int y = c[1] + (int) Math.floor(t * (height - 1) + 1e-9);
            for (int w = 0; w < width; w++) {
                double rr = r - w;
                if (rr < 0) {
                    break;
                }
                v.add(c[0] + (int) Math.round(Math.cos(angle) * rr), y, c[2] + (int) Math.round(Math.sin(angle) * rr));
            }
        }
        return v.toActions();
    }

    /** Bezier curve through 2-4 control points (linear, quadratic or cubic). */
    public static JsonArray bezier(JsonObject o) {
        List<int[]> pts = points(o, "points");
        if (pts.size() < 2 || pts.size() > 4) {
            throw new IllegalArgumentException("bezier needs 2 to 4 points");
        }
        Voxels v = new Voxels(str(o, "block", null));
        double length = 0;
        for (int i = 1; i < pts.size(); i++) {
            length += dist(pts.get(i - 1), pts.get(i));
        }
        int steps = Math.max(2, (int) Math.ceil(length * 3));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double[] p = deCasteljau(pts, t);
            v.add((int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2]));
        }
        return v.toActions();
    }

    /**
     * Semi-elliptic arch between two feet at the same height. {@code height} is the rise above
     * the feet; {@code thickness} widens it along the span's horizontal normal.
     */
    public static JsonArray arch(JsonObject o) {
        int[] from = vec(o, "from");
        int[] to = vec(o, "to");
        double rise = num(o, "height");
        int thickness = (int) Math.max(1, num(o, "thickness", 1));
        Voxels v = new Voxels(str(o, "block", null));
        double half = dist(new int[]{from[0], 0, from[2]}, new int[]{to[0], 0, to[2]}) / 2.0;
        boolean alongX = Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]);
        int steps = Math.max(8, (int) Math.ceil((half + rise) * 6));
        for (int i = 0; i <= steps; i++) {
            double angle = Math.PI * i / steps;
            double t = (1 - Math.cos(angle)) / 2;
            double x = from[0] + (to[0] - from[0]) * t;
            double z = from[2] + (to[2] - from[2]) * t;
            double y = from[1] + Math.sin(angle) * rise;
            for (int w = 0; w < thickness; w++) {
                int nx = alongX ? 0 : w;
                int nz = alongX ? w : 0;
                v.add((int) Math.round(x) + nx, (int) Math.round(y), (int) Math.round(z) + nz);
            }
        }
        return v.toActions();
    }

    /** Compress {@code [[x,y,z,block?], ...]} into actions; {@code block} is the default block. */
    public static JsonArray fromVoxels(JsonArray voxels, String block) {
        Voxels v = new Voxels(block);
        for (JsonElement element : voxels) {
            JsonArray p = element.getAsJsonArray();
            String b = p.size() > 3 ? p.get(3).getAsString() : null;
            v.add(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt(), b);
        }
        return v.toActions();
    }

    // ------------------------------------------------------------------ transforms

    public static JsonArray translate(JsonArray actions, int[] d) {
        return mapCoords(actions, p -> new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]}, null);
    }

    /** Rotate around the vertical axis through {@code pivot} by quarter turns (clockwise seen from above). */
    public static JsonArray rotateY(JsonArray actions, int quarterTurns, int[] pivot) {
        int turns = Math.floorMod(quarterTurns, 4);
        int px = pivot == null ? 0 : pivot[0];
        int pz = pivot == null ? 0 : pivot[2];
        return mapCoords(actions, p -> {
            int x = p[0] - px;
            int z = p[2] - pz;
            for (int i = 0; i < turns; i++) {
                int nx = -z;
                z = x;
                x = nx;
            }
            return new int[]{x + px, p[1], z + pz};
        }, facing -> rotateFacing(facing, turns));
    }

    /** Mirror across the plane {@code axis = at} (x or z, or y). */
    public static JsonArray mirror(JsonArray actions, String axis, double at) {
        int index = switch (axis == null ? "x" : axis) {
            case "y" -> 1;
            case "z" -> 2;
            default -> 0;
        };
        return mapCoords(actions, p -> {
            int[] q = p.clone();
            q[index] = (int) Math.round(2 * at - p[index]);
            return q;
        }, facing -> mirrorFacing(facing, index));
    }

    /** Integer upscale around the origin; points become boxes of size factor³. */
    public static JsonArray scale(JsonArray actions, int factor) {
        if (factor < 1 || factor > 8) {
            throw new IllegalArgumentException("scale factor must be 1..8");
        }
        JsonArray out = new JsonArray();
        for (JsonElement element : actions) {
            JsonObject action = element.getAsJsonObject().deepCopy();
            String type = str(action, "type", "");
            if ("points".equals(type)) {
                for (int[] p : points(action, "at")) {
                    JsonObject box = new JsonObject();
                    box.addProperty("type", "box");
                    box.addProperty("block", str(action, "block", ""));
                    box.add("from", arr(p[0] * factor, p[1] * factor, p[2] * factor));
                    box.add("to", arr(p[0] * factor + factor - 1, p[1] * factor + factor - 1, p[2] * factor + factor - 1));
                    copyFacing(action, box);
                    out.add(box);
                }
                continue;
            }
            int[] from = vec(action, "from");
            int[] to = vec(action, "to");
            int[] lo = {Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])};
            int[] hi = {Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])};
            if ("line".equals(type)) {
                action.addProperty("type", "box");
                action.addProperty("mode", "solid");
            }
            action.add("from", arr(lo[0] * factor, lo[1] * factor, lo[2] * factor));
            action.add("to", arr(hi[0] * factor + factor - 1, hi[1] * factor + factor - 1, hi[2] * factor + factor - 1));
            out.add(action);
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private interface CoordMap {
        int[] apply(int[] p);
    }

    private interface FacingMap {
        String apply(String facing);
    }

    private static JsonArray mapCoords(JsonArray actions, CoordMap map, FacingMap facingMap) {
        JsonArray out = new JsonArray();
        for (JsonElement element : actions) {
            JsonObject action = element.getAsJsonObject().deepCopy();
            for (String key : new String[]{"from", "to"}) {
                if (action.has(key) && action.get(key).isJsonArray()) {
                    int[] p = map.apply(vec(action, key));
                    action.add(key, arr(p[0], p[1], p[2]));
                }
            }
            if (action.has("at") && action.get("at").isJsonArray()) {
                JsonArray at = new JsonArray();
                for (int[] p : points(action, "at")) {
                    int[] q = map.apply(p);
                    at.add(arr(q[0], q[1], q[2]));
                }
                action.add("at", at);
            }
            if (facingMap != null && action.has("facing") && action.get("facing").isJsonPrimitive()) {
                action.addProperty("facing", facingMap.apply(action.get("facing").getAsString()));
            }
            out.add(action);
        }
        return out;
    }

    private static final String[] COMPASS = {"north", "east", "south", "west"};

    static String rotateFacing(String facing, int turns) {
        for (int i = 0; i < COMPASS.length; i++) {
            if (COMPASS[i].equalsIgnoreCase(facing)) {
                return COMPASS[(i + turns) % 4];
            }
        }
        return facing;
    }

    static String mirrorFacing(String facing, int axisIndex) {
        String f = facing == null ? "" : facing.toLowerCase();
        return switch (axisIndex) {
            case 0 -> f.equals("east") ? "west" : f.equals("west") ? "east" : facing;
            case 2 -> f.equals("north") ? "south" : f.equals("south") ? "north" : facing;
            default -> f.equals("up") ? "down" : f.equals("down") ? "up" : facing;
        };
    }

    private static void copyFacing(JsonObject from, JsonObject to) {
        if (from.has("facing")) {
            to.add("facing", from.get("facing").deepCopy());
        }
    }

    private static double[] deCasteljau(List<int[]> pts, double t) {
        List<double[]> work = new ArrayList<>();
        for (int[] p : pts) {
            work.add(new double[]{p[0], p[1], p[2]});
        }
        while (work.size() > 1) {
            List<double[]> next = new ArrayList<>();
            for (int i = 0; i + 1 < work.size(); i++) {
                double[] a = work.get(i);
                double[] b = work.get(i + 1);
                next.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
            work = next;
        }
        return work.getFirst();
    }

    private static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static JsonArray arr(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    static int[] vec(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray() || o.getAsJsonArray(key).size() != 3) {
            throw new IllegalArgumentException(key + " must be [x, y, z]");
        }
        JsonArray a = o.getAsJsonArray(key);
        return new int[]{(int) Math.round(a.get(0).getAsDouble()), (int) Math.round(a.get(1).getAsDouble()),
                (int) Math.round(a.get(2).getAsDouble())};
    }

    private static List<int[]> points(JsonObject o, String key) {
        List<int[]> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement element : o.getAsJsonArray(key)) {
                JsonArray a = element.getAsJsonArray();
                out.add(new int[]{(int) Math.round(a.get(0).getAsDouble()), (int) Math.round(a.get(1).getAsDouble()),
                        (int) Math.round(a.get(2).getAsDouble())});
            }
        }
        return out;
    }

    private static double num(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " is required");
        }
        double value = o.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 512) {
            throw new IllegalArgumentException(key + " must be between 0 and 512");
        }
        return value;
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? num(o, key) : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** Voxel set compressed into line/points actions, one group per block. */
    static final class Voxels {
        private final String defaultBlock;
        private final Map<String, java.util.Set<Long>> byBlock = new LinkedHashMap<>();
        private int count;

        Voxels(String defaultBlock) {
            this.defaultBlock = defaultBlock;
        }

        void add(int x, int y, int z) {
            add(x, y, z, null);
        }

        void add(int x, int y, int z, String block) {
            String b = block == null || block.isBlank() ? defaultBlock : block;
            if (b == null || b.isBlank()) {
                throw new IllegalArgumentException("block is required");
            }
            if (byBlock.computeIfAbsent(b, k -> new java.util.HashSet<>()).add(pack(x, y, z))) {
                if (++count > MAX_VOXELS) {
                    throw new IllegalArgumentException("Shape exceeds " + MAX_VOXELS + " blocks; reduce its size");
                }
            }
        }

        JsonArray toActions() {
            JsonArray actions = new JsonArray();
            for (Map.Entry<String, java.util.Set<Long>> entry : byBlock.entrySet()) {
                String block = entry.getKey();
                Map<Long, List<Integer>> rows = new TreeMap<>();
                for (long packed : entry.getValue()) {
                    int[] p = unpack(packed);
                    rows.computeIfAbsent(rowKey(p[1], p[2]), k -> new ArrayList<>()).add(p[0]);
                }
                List<int[]> singles = new ArrayList<>();
                for (Map.Entry<Long, List<Integer>> row : rows.entrySet()) {
                    int y = (int) (row.getKey() >> 32);
                    int z = (int) (long) row.getKey().intValue();
                    List<Integer> xs = row.getValue();
                    xs.sort(Comparator.naturalOrder());
                    int start = xs.getFirst();
                    int prev = start;
                    for (int i = 1; i <= xs.size(); i++) {
                        boolean end = i == xs.size() || xs.get(i) != prev + 1;
                        if (end) {
                            if (prev > start) {
                                JsonObject line = new JsonObject();
                                line.addProperty("type", "line");
                                line.addProperty("block", block);
                                line.add("from", arr(start, y, z));
                                line.add("to", arr(prev, y, z));
                                actions.add(line);
                            } else {
                                singles.add(new int[]{start, y, z});
                            }
                            if (i < xs.size()) {
                                start = xs.get(i);
                                prev = start;
                            }
                        } else {
                            prev = xs.get(i);
                        }
                    }
                }
                for (int i = 0; i < singles.size(); i += POINTS_PER_ACTION) {
                    JsonObject points = new JsonObject();
                    points.addProperty("type", "points");
                    points.addProperty("block", block);
                    JsonArray at = new JsonArray();
                    for (int[] p : singles.subList(i, Math.min(singles.size(), i + POINTS_PER_ACTION))) {
                        at.add(arr(p[0], p[1], p[2]));
                    }
                    points.add("at", at);
                    actions.add(points);
                }
            }
            return actions;
        }

        private static long rowKey(int y, int z) {
            return ((long) y << 32) | (z & 0xffffffffL);
        }

        private static long pack(int x, int y, int z) {
            return ((long) (x & 0x1fffff) << 42) | ((long) (y & 0x1fffff) << 21) | (z & 0x1fffff);
        }

        private static int[] unpack(long v) {
            return new int[]{signExtend((int) (v >>> 42) & 0x1fffff), signExtend((int) (v >>> 21) & 0x1fffff),
                    signExtend((int) v & 0x1fffff)};
        }

        private static int signExtend(int v) {
            return (v << 11) >> 11;
        }
    }
}
