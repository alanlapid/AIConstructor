package io.github.alanlapid.aiconstructor.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the AI's list of geometric parts into individual blocks in the build's local frame
 * (x = width, y = up, z = depth, all >= 0). Must run on the main thread (creates BlockData).
 */
public final class BuildPlan {

    private static final int AXIS_BITS = 10;
    private static final int AXIS_MASK = (1 << AXIS_BITS) - 1;

    private final String name;
    private final Map<Long, BlockData> voxels = new LinkedHashMap<>();
    private final Set<String> rejectedBlocks = new LinkedHashSet<>();
    private final Map<String, BlockData> blockCache = new HashMap<>();
    private final int maxSize;
    private final Set<Material> blocked;
    private int partsTotal;
    private int partsSkipped;

    private BuildPlan(String name, int maxSize, Set<Material> blocked) {
        this.name = name;
        this.maxSize = maxSize;
        this.blocked = blocked;
    }

    public static BuildPlan rasterize(JsonObject plan, int maxSize, Set<Material> blocked) {
        String name = plan.has("name") ? plan.get("name").getAsString() : "Build";
        BuildPlan result = new BuildPlan(name, maxSize, blocked);
        JsonArray parts = plan.has("parts") && plan.get("parts").isJsonArray() ? plan.getAsJsonArray("parts") : new JsonArray();
        for (JsonElement el : parts) {
            result.partsTotal++;
            if (!el.isJsonObject() || !result.applyPart(el.getAsJsonObject())) {
                result.partsSkipped++;
            }
        }
        return result;
    }

    private boolean applyPart(JsonObject p) {
        // Models do not always follow the schema; accept the field names they commonly use instead.
        String shape = firstStr(p, "shape", "type", "kind");
        String blockId = firstStr(p, "block", "material", "block_id");
        if (shape == null || blockId == null) {
            return false;
        }
        boolean carve = isAir(blockId);
        BlockData data = carve ? null : parseBlock(blockId);
        if (!carve && data == null) {
            return false;
        }
        boolean hollow = p.has("hollow") && p.get("hollow").isJsonPrimitive() && p.get("hollow").getAsBoolean();
        int[] from = vec(p, "from");
        int[] to = vec(p, "to");
        int[] center = vec(p, "center");
        switch (shape.toLowerCase(Locale.ROOT)) {
            case "fill", "hollow_box", "walls" -> {
                if (from == null) {
                    return false;
                }
                if (to == null) {
                    to = from;
                }
                box(from, to, shape.equalsIgnoreCase("fill") ? 0 : shape.equalsIgnoreCase("walls") ? 2 : 1, data);
            }
            case "block" -> {
                int[] at = from != null ? from : center;
                if (at == null) {
                    return false;
                }
                set(at[0], at[1], at[2], data);
            }
            case "line" -> {
                if (from == null || to == null) {
                    return false;
                }
                line(from, to, data);
            }
            case "cylinder" -> {
                if (center == null) {
                    center = from;
                }
                if (center == null) {
                    return false;
                }
                double radius = num(p, "radius", 1);
                int height = (int) num(p, "height", 1);
                cylinder(center, radius, height, hollow, data);
            }
            case "sphere" -> {
                if (center == null) {
                    center = from;
                }
                if (center == null) {
                    return false;
                }
                sphere(center, num(p, "radius", 1), hollow, data);
            }
            case "pyramid" -> {
                if (from == null || to == null) {
                    return false;
                }
                pyramid(from, to, hollow, data);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- shapes -------------------------------------------------------------------------------------------------

    /** mode 0 = solid, 1 = shell (all 6 faces), 2 = vertical walls only. */
    private void box(int[] a, int[] b, int mode, BlockData data) {
        int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]);
        int y1 = Math.min(a[1], b[1]), y2 = Math.max(a[1], b[1]);
        int z1 = Math.min(a[2], b[2]), z2 = Math.max(a[2], b[2]);
        x2 = Math.min(x2, x1 + maxSize);
        y2 = Math.min(y2, y1 + maxSize);
        z2 = Math.min(z2, z1 + maxSize);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    boolean edgeXZ = x == x1 || x == x2 || z == z1 || z == z2;
                    boolean edgeY = y == y1 || y == y2;
                    if (mode == 0 || (mode == 1 && (edgeXZ || edgeY)) || (mode == 2 && edgeXZ)) {
                        set(x, y, z, data);
                    }
                }
            }
        }
    }

    private void line(int[] a, int[] b, BlockData data) {
        int dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        int steps = Math.min(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))), maxSize * 2);
        if (steps == 0) {
            set(a[0], a[1], a[2], data);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            set((int) Math.round(a[0] + dx * t), (int) Math.round(a[1] + dy * t), (int) Math.round(a[2] + dz * t), data);
        }
    }

    private void cylinder(int[] c, double radius, int height, boolean hollow, BlockData data) {
        radius = Math.min(Math.max(radius, 0.5), maxSize / 2.0);
        height = Math.min(Math.max(height, 1), maxSize);
        int r = (int) Math.ceil(radius);
        for (int y = c[1]; y < c[1] + height; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!inCircle(dx, dz, radius)) {
                        continue;
                    }
                    if (hollow && inCircle(dx + 1, dz, radius) && inCircle(dx - 1, dz, radius)
                            && inCircle(dx, dz + 1, radius) && inCircle(dx, dz - 1, radius)) {
                        continue;
                    }
                    set(c[0] + dx, y, c[2] + dz, data);
                }
            }
        }
    }

    private void sphere(int[] c, double radius, boolean hollow, BlockData data) {
        radius = Math.min(Math.max(radius, 0.5), maxSize / 2.0);
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!inSphere(dx, dy, dz, radius)) {
                        continue;
                    }
                    if (hollow && inSphere(dx + 1, dy, dz, radius) && inSphere(dx - 1, dy, dz, radius)
                            && inSphere(dx, dy + 1, dz, radius) && inSphere(dx, dy - 1, dz, radius)
                            && inSphere(dx, dy, dz + 1, radius) && inSphere(dx, dy, dz - 1, radius)) {
                        continue;
                    }
                    set(c[0] + dx, c[1] + dy, c[2] + dz, data);
                }
            }
        }
    }

    private void pyramid(int[] a, int[] b, boolean hollow, BlockData data) {
        int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]);
        int z1 = Math.min(a[2], b[2]), z2 = Math.max(a[2], b[2]);
        int y = a[1];
        for (int layer = 0; x1 + layer <= x2 - layer && z1 + layer <= z2 - layer && layer <= maxSize; layer++) {
            int lx1 = x1 + layer, lx2 = x2 - layer, lz1 = z1 + layer, lz2 = z2 - layer;
            boolean top = lx1 + 1 > lx2 - 1 || lz1 + 1 > lz2 - 1;
            for (int x = lx1; x <= lx2; x++) {
                for (int z = lz1; z <= lz2; z++) {
                    boolean edge = x == lx1 || x == lx2 || z == lz1 || z == lz2;
                    if (!hollow || edge || top) {
                        set(x, y + layer, z, data);
                    }
                }
            }
        }
    }

    private static boolean inCircle(int dx, int dz, double r) {
        return dx * dx + dz * dz <= (r + 0.5) * (r + 0.5);
    }

    private static boolean inSphere(int dx, int dy, int dz, double r) {
        return dx * dx + dy * dy + dz * dz <= (r + 0.5) * (r + 0.5);
    }

    // ---- voxel storage ------------------------------------------------------------------------------------------

    /** data == null means "air": removes a block placed by an earlier part (never digs into the world). */
    private void set(int x, int y, int z, BlockData data) {
        if (x < 0 || y < 0 || z < 0 || x >= maxSize || y >= maxSize || z >= maxSize) {
            return;
        }
        long key = key(x, y, z);
        if (data == null) {
            voxels.remove(key);
        } else {
            voxels.put(key, data);
        }
    }

    public static long key(int x, int y, int z) {
        return x | ((long) y << AXIS_BITS) | ((long) z << (2 * AXIS_BITS));
    }

    public static int kx(long key) {
        return (int) (key & AXIS_MASK);
    }

    public static int ky(long key) {
        return (int) ((key >> AXIS_BITS) & AXIS_MASK);
    }

    public static int kz(long key) {
        return (int) ((key >> (2 * AXIS_BITS)) & AXIS_MASK);
    }

    // ---- parsing helpers ----------------------------------------------------------------------------------------

    private static boolean isAir(String id) {
        String base = normalize(id);
        int bracket = base.indexOf('[');
        if (bracket >= 0) {
            base = base.substring(0, bracket);
        }
        return base.equals("air") || base.equals("cave_air") || base.equals("void_air");
    }

    private static String normalize(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private BlockData parseBlock(String raw) {
        String id = normalize(raw);
        if (blockCache.containsKey(id)) {
            return blockCache.get(id);
        }
        BlockData data = tryCreate(id);
        if (data == null && id.contains("[")) {
            // Invalid block state: keep the block, drop the states.
            data = tryCreate(id.substring(0, id.indexOf('[')));
        }
        if (data != null && (!data.getMaterial().isBlock() || blocked.contains(data.getMaterial()))) {
            data = null;
        }
        if (data == null) {
            rejectedBlocks.add(id);
        }
        blockCache.put(id, data);
        return data;
    }

    private static BlockData tryCreate(String id) {
        try {
            return Bukkit.createBlockData("minecraft:" + id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstStr(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static double num(JsonObject o, String k, double def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** [x, y, z], or {"x": .., "y": .., "z": ..} which some models write instead. */
    private static int[] vec(JsonObject o, String k) {
        if (o.has(k) && o.get(k).isJsonObject()) {
            JsonObject v = o.getAsJsonObject(k);
            if (!v.has("x") || !v.has("y") || !v.has("z")) {
                return null;
            }
            try {
                return new int[]{v.get("x").getAsInt(), v.get("y").getAsInt(), v.get("z").getAsInt()};
            } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException e) {
                return null;
            }
        }
        if (!o.has(k) || !o.get(k).isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(k);
        if (a.size() < 3) {
            return null;
        }
        try {
            return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
        } catch (NumberFormatException | IllegalStateException e) {
            return null;
        }
    }

    // ---- accessors ----------------------------------------------------------------------------------------------

    public String name() {
        return name;
    }

    public Map<Long, BlockData> voxels() {
        return voxels;
    }

    public Set<String> rejectedBlocks() {
        return rejectedBlocks;
    }

    public int partsTotal() {
        return partsTotal;
    }

    public int partsSkipped() {
        return partsSkipped;
    }
}
