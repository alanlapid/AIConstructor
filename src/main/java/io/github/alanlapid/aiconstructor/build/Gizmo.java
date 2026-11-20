package io.github.alanlapid.aiconstructor.build;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Move handles around a pending build, like the translate gizmo of a 3D editor: a red X, green Y and blue Z arrow
 * made of block displays. Only the owner sees them, and they are never saved with the world.
 */
final class Gizmo {

    static final double LENGTH = 2.5;
    static final double SHAFT = 0.16;
    static final double TIP = 0.6;
    /** Space between the build's bounding box and the start of an arrow. */
    static final double GAP = 0.3;
    /** How close (blocks) the look ray must pass to an arrow to grab it. */
    static final double PICK_RADIUS = 0.55;

    static final String[] AXIS_NAMES = {"X", "Y", "Z"};
    static final String[] AXIS_TAGS = {"red", "green", "blue"};
    private static final Material[] SHAFT_MATERIAL = {Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.BLUE_CONCRETE};
    private static final Material[] TIP_MATERIAL = {Material.RED_WOOL, Material.LIME_WOOL, Material.BLUE_WOOL};
    private static final Color[] GLOW = {Color.RED, Color.LIME, Color.AQUA};
    /** Axis (0 x, 1 y, 2 z) and direction of each arrow. No arrow below the build: it would be underground. */
    private static final int[][] LAYOUT = {{0, 1}, {0, -1}, {1, 1}, {2, 1}, {2, -1}};

    private final Plugin plugin;
    private final World world;
    private final BlockDisplay[] shafts = new BlockDisplay[LAYOUT.length];
    private final BlockDisplay[] tips = new BlockDisplay[LAYOUT.length];
    private int highlighted = -1;

    Gizmo(Plugin plugin, Player owner, BuildManager.Pending p) {
        this.plugin = plugin;
        this.world = p.world;
        for (int i = 0; i < LAYOUT.length; i++) {
            spawn(owner, p, i);
        }
    }

    static int axis(int handle) {
        return LAYOUT[handle][0];
    }

    static Vector axisVector(int axis) {
        return new Vector(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
    }

    /** World position where an arrow starts, just outside the build's bounding box. */
    static Vector base(BuildManager.Pending p, int handle) {
        int axis = LAYOUT[handle][0], sign = LAYOUT[handle][1];
        double cx = (p.minX + p.maxX + 1) / 2.0, cy = (p.minY + p.maxY + 1) / 2.0, cz = (p.minZ + p.maxZ + 1) / 2.0;
        return switch (axis) {
            case 0 -> new Vector(sign > 0 ? p.maxX + 1 + GAP : p.minX - GAP, cy, cz);
            case 1 -> new Vector(cx, sign > 0 ? p.maxY + 1 + GAP : p.minY - GAP, cz);
            default -> new Vector(cx, cy, sign > 0 ? p.maxZ + 1 + GAP : p.minZ - GAP);
        };
    }

    private void spawn(Player owner, BuildManager.Pending p, int i) {
        int axis = LAYOUT[i][0], sign = LAYOUT[i][1];
        Location at = base(p, i).toLocation(world);
        float[] shaftT = new float[3], shaftS = new float[3], tipT = new float[3], tipS = new float[3];
        for (int c = 0; c < 3; c++) {
            if (c == axis) {
                shaftT[c] = (float) (sign > 0 ? 0 : -LENGTH);
                shaftS[c] = (float) LENGTH;
                tipT[c] = (float) (sign * (LENGTH + TIP / 2) - TIP / 2);
            } else {
                shaftT[c] = (float) (-SHAFT / 2);
                shaftS[c] = (float) SHAFT;
                tipT[c] = (float) (-TIP / 2);
            }
            tipS[c] = (float) TIP;
        }
        shafts[i] = part(owner, at, SHAFT_MATERIAL[axis], shaftT, shaftS);
        tips[i] = part(owner, at, TIP_MATERIAL[axis], tipT, tipS);
        if (i == highlighted) {
            glow(i, true);
        }
    }

    private BlockDisplay part(Player owner, Location at, Material material, float[] translation, float[] scale) {
        BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
            // Set before the entity joins the world, so nobody else ever receives it.
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBlock(material.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(translation[0], translation[1], translation[2]), new AxisAngle4f(),
                    new Vector3f(scale[0], scale[1], scale[2]), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(2);
            d.setViewRange(4f);
        });
        owner.showEntity(plugin, display);
        return display;
    }

    /** Moves the arrows to follow the build, respawning any that were unloaded with their chunk. */
    void update(Player owner, BuildManager.Pending p) {
        for (int i = 0; i < LAYOUT.length; i++) {
            if (shafts[i] == null || !shafts[i].isValid() || tips[i] == null || !tips[i].isValid()) {
                removeHandle(i);
                if (owner != null) {
                    spawn(owner, p, i);
                }
                continue;
            }
            Location at = base(p, i).toLocation(world);
            shafts[i].teleport(at);
            tips[i].teleport(at);
        }
    }

    void highlight(int handle) {
        if (highlighted >= 0) {
            glow(highlighted, false);
        }
        highlighted = handle;
        if (handle >= 0) {
            glow(handle, true);
        }
    }

    private void glow(int i, boolean on) {
        for (BlockDisplay d : new BlockDisplay[]{shafts[i], tips[i]}) {
            if (d != null && d.isValid()) {
                d.setGlowing(on);
                d.setGlowColorOverride(on ? GLOW[LAYOUT[i][0]] : null);
            }
        }
    }

    /** The arrow the player is pointing at (nearest along the look ray), or -1. */
    int pick(Player player, BuildManager.Pending p, double reach) {
        if (!player.getWorld().equals(world)) {
            return -1;
        }
        Location eye = player.getEyeLocation();
        Vector e = eye.toVector();
        Vector u = eye.getDirection().normalize();
        int best = -1;
        double bestT = reach;
        for (int i = 0; i < LAYOUT.length; i++) {
            Vector start = base(p, i);
            Vector seg = axisVector(LAYOUT[i][0]).multiply(LAYOUT[i][1] * (LENGTH + TIP));
            double[] hit = raySegment(e, u, start, seg);
            if (hit != null && hit[1] < PICK_RADIUS && hit[0] > 0 && hit[0] < bestT) {
                best = i;
                bestT = hit[0];
            }
        }
        return best;
    }

    /** Closest approach between ray e + t*u (|u| = 1) and segment s + k*v, k in [0, 1]. Returns {t, distance}. */
    private static double[] raySegment(Vector e, Vector u, Vector s, Vector v) {
        Vector w = e.clone().subtract(s);
        double b = u.dot(v), c = v.dot(v), d = u.dot(w), f = v.dot(w);
        double denom = c - b * b;
        double k = denom < 1e-9 ? 0 : (f - b * d) / denom;
        k = Math.clamp(k, 0, 1);
        double t = b * k - d;
        if (t < 0) {
            return null;
        }
        Vector closest = e.clone().add(u.clone().multiply(t)).subtract(s.clone().add(v.clone().multiply(k)));
        return new double[]{t, closest.length()};
    }

    private void removeHandle(int i) {
        if (shafts[i] != null) {
            shafts[i].remove();
            shafts[i] = null;
        }
        if (tips[i] != null) {
            tips[i].remove();
            tips[i] = null;
        }
    }

    void remove() {
        for (int i = 0; i < LAYOUT.length; i++) {
            removeHandle(i);
        }
    }
}
