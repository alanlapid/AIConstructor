package io.github.alanlapid.aiconstructor.build;

import io.github.alanlapid.aiconstructor.AIConstructor;
import io.github.alanlapid.aiconstructor.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player state: generation in progress, pending preview, running placement and undo history. */
public final class BuildManager implements Listener {

    /** One block to place, in world coordinates. */
    public record Placement(int x, int y, int z, BlockData data) {
    }

    /** A generated build waiting for confirmation. It can be moved until it is confirmed. */
    public static final class Pending {
        public final String name;
        public final World world;
        public List<Placement> blocks;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        /** Total distance moved from where the build first appeared. */
        public int offsetX, offsetY, offsetZ;
        /** Last time the player touched it (created or moved); the preview expires from here. */
        long touchedAt = System.currentTimeMillis();
        BukkitTask preview;
        boolean ghostShown;
        /** Offset the ghost blocks were last drawn at; differs from offset* while a drag is in progress. */
        int ghostOffsetX, ghostOffsetY, ghostOffsetZ;
        boolean ghostDirty;
        int previewTicks;
        Gizmo gizmo;

        Pending(String name, World world, List<Placement> blocks) {
            this.name = name;
            this.world = world;
            this.blocks = blocks;
            recomputeBounds();
        }

        void recomputeBounds() {
            int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE;
            int x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
            for (Placement p : blocks) {
                x1 = Math.min(x1, p.x());
                y1 = Math.min(y1, p.y());
                z1 = Math.min(z1, p.z());
                x2 = Math.max(x2, p.x());
                y2 = Math.max(y2, p.y());
                z2 = Math.max(z2, p.z());
            }
            minX = x1;
            minY = y1;
            minZ = z1;
            maxX = x2;
            maxY = y2;
            maxZ = z2;
        }
    }

    /** Outcome of moving a pending build. */
    public enum MoveResult { MOVED, NOTHING_PENDING, OUT_OF_WORLD, TOO_FAR, PROTECTED }

    public record MoveOutcome(MoveResult result, int deniedBlocks) {
    }

    /** Result of placing a plan in front of a player. */
    public record PendingResult(Pending pending, int deniedBlocks) {
    }

    /** Why a player may not start a generation now: a message key and its placeholders. */
    public record Denial(String key, Object... args) {
    }

    record UndoRecord(String name, World world, List<Placement> originals) {
    }

    record Running(BukkitTask task, UndoRecord record) {
    }

    /** A handle being dragged: the build follows the player's look, projected onto the handle's axis. */
    private static final class Drag {
        final int axis;
        /** A point on the axis line through the grabbed handle (the line does not change as the build slides). */
        final Vector origin;
        final double grabParam;
        int moved;
        int failedTarget = Integer.MIN_VALUE;
        int ticks;
        BukkitTask task;

        Drag(int axis, Vector origin, double grabParam) {
            this.axis = axis;
            this.origin = origin;
            this.grabParam = grabParam;
        }
    }

    /** Largest jump (blocks) per tick while dragging, so looking almost along the axis cannot fling the build. */
    private static final int MAX_DRAG_STEP = 8;
    /** One click can arrive as several events (block dig + arm swing) within this window. */
    private static final long GRAB_DEBOUNCE_MS = 200;

    private final AIConstructor plugin;
    private final Set<UUID> generating = new HashSet<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Running> running = new HashMap<>();
    private final Map<UUID, Deque<UndoRecord>> history = new HashMap<>();
    private final Map<UUID, Drag> drags = new HashMap<>();
    private final Map<UUID, Long> lastGrabToggle = new HashMap<>();
    private final Map<UUID, Long> lastGeneration = new HashMap<>();
    private final Map<UUID, Integer> dailyCount = new HashMap<>();
    private LocalDate countDay = LocalDate.now();
    private ProtectionHook protection = ProtectionHook.NONE;

    public BuildManager(AIConstructor plugin) {
        this.plugin = plugin;
    }

    /** Re-evaluates optional integrations after a config (re)load. */
    public void reload() {
        protection = ProtectionHook.NONE;
        if (plugin.getConfig().getBoolean("protection.worldguard", true)
                && Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                protection = new WorldGuardHook();
                plugin.getLogger().info("WorldGuard found: builds will respect regions.");
            } catch (LinkageError e) {
                plugin.getLogger().warning("Could not hook into WorldGuard: " + e);
            }
        }
    }

    private Messages messages() {
        return plugin.messages();
    }

    // ---- limits -------------------------------------------------------------------------------------------------

    public Denial checkCanGenerate(Player player) {
        UUID id = player.getUniqueId();
        if (generating.contains(id)) {
            return new Denial("busy-generating");
        }
        if (running.containsKey(id)) {
            return new Denial("busy-placing");
        }
        if (player.hasPermission("aiconstructor.bypass.limits")) {
            return null;
        }
        long cooldownMs = plugin.getConfig().getLong("limits.cooldown-seconds", 30) * 1000L;
        Long last = lastGeneration.get(id);
        if (last != null && System.currentTimeMillis() - last < cooldownMs) {
            long left = (cooldownMs - (System.currentTimeMillis() - last) + 999) / 1000;
            return new Denial("cooldown", "seconds", left);
        }
        int limit = dailyLimit(player);
        if (usedToday(id) >= limit) {
            return new Denial("daily-limit", "limit", limit);
        }
        return null;
    }

    /** Highest aiconstructor.limit.&lt;n&gt; permission, or the configured default. Integer.MAX_VALUE when unlimited. */
    public int dailyLimit(Player player) {
        if (player.hasPermission("aiconstructor.bypass.limits")) {
            return Integer.MAX_VALUE;
        }
        int limit = plugin.getConfig().getInt("limits.daily-limit", 10);
        for (PermissionAttachmentInfo perm : player.getEffectivePermissions()) {
            String node = perm.getPermission();
            if (perm.getValue() && node.startsWith("aiconstructor.limit.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(node.substring("aiconstructor.limit.".length())));
                } catch (NumberFormatException ignored) {
                    // not a numeric limit node
                }
            }
        }
        return limit;
    }

    public void startGenerating(Player player) {
        UUID id = player.getUniqueId();
        generating.add(id);
        lastGeneration.put(id, System.currentTimeMillis());
        rollDay();
        dailyCount.merge(id, 1, Integer::sum);
    }

    /** Gives back the daily use and cooldown when the AI failed, so failures do not eat the player's quota. */
    public void refundGeneration(UUID id) {
        dailyCount.computeIfPresent(id, (k, v) -> v > 1 ? v - 1 : null);
        lastGeneration.remove(id);
    }

    public void finishGenerating(UUID id) {
        generating.remove(id);
    }

    public int usedToday(UUID id) {
        rollDay();
        return dailyCount.getOrDefault(id, 0);
    }

    private void rollDay() {
        if (!LocalDate.now().equals(countDay)) {
            countDay = LocalDate.now();
            dailyCount.clear();
        }
    }

    public boolean isGenerating(UUID id) {
        return generating.contains(id);
    }

    // ---- preview ------------------------------------------------------------------------------------------------

    /**
     * Places the plan in front of the player (rotated to where they look), checks protections and starts the
     * particle preview. The pending build is null when the plan produced no blocks or hits protected blocks.
     */
    public PendingResult createPending(Player player, BuildPlan plan) {
        cancelPending(player.getUniqueId());
        Map<Long, BlockData> voxels = plan.voxels();
        if (voxels.isEmpty()) {
            return new PendingResult(null, 0);
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (long k : voxels.keySet()) {
            minX = Math.min(minX, BuildPlan.kx(k));
            maxX = Math.max(maxX, BuildPlan.kx(k));
            minY = Math.min(minY, BuildPlan.ky(k));
            minZ = Math.min(minZ, BuildPlan.kz(k));
        }
        double centerX = (minX + maxX) / 2.0;

        BlockFace facing = player.getFacing();
        StructureRotation rotation = switch (facing) {
            case WEST -> StructureRotation.CLOCKWISE_90;
            case NORTH -> StructureRotation.CLOCKWISE_180;
            case EAST -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
        Location feet = player.getLocation();
        int ox = feet.getBlockX(), oy = feet.getBlockY(), oz = feet.getBlockZ();
        World world = player.getWorld();
        int minWorldY = world.getMinHeight(), maxWorldY = world.getMaxHeight() - 1;

        List<Placement> blocks = new ArrayList<>(voxels.size());
        for (Map.Entry<Long, BlockData> e : voxels.entrySet()) {
            long k = e.getKey();
            // Local frame centred on the player's line of sight, starting 3 blocks ahead.
            int lx = (int) Math.round(BuildPlan.kx(k) - centerX);
            int lz = BuildPlan.kz(k) - minZ + 3;
            int[] r = rotate(lx, lz, rotation);
            // The lowest layer always sits at the player's feet, even if the AI left y = 0 empty.
            int wy = oy + BuildPlan.ky(k) - minY;
            if (wy < minWorldY || wy > maxWorldY) {
                continue;
            }
            BlockData data = e.getValue().clone();
            if (rotation != StructureRotation.NONE) {
                data.rotate(rotation);
            }
            blocks.add(new Placement(ox + r[0], wy, oz + r[1], data));
        }
        if (blocks.isEmpty()) {
            return new PendingResult(null, 0);
        }
        if (!player.hasPermission("aiconstructor.bypass.protection")) {
            int denied = protection.countDenied(player, world, blocks);
            if (denied > 0) {
                return new PendingResult(null, denied);
            }
        }
        // Bottom-up, so the build visibly grows from the ground.
        blocks.sort(Comparator.comparingInt(Placement::y).thenComparingInt(Placement::z).thenComparingInt(Placement::x));

        Pending p = new Pending(plan.name(), world, blocks);
        UUID id = player.getUniqueId();
        pending.put(id, p);
        long expireMs = plugin.getConfig().getLong("limits.pending-expire-seconds", 180) * 1000L;
        p.preview = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || pending.get(id) != p) {
                p.preview.cancel();
                return;
            }
            if (System.currentTimeMillis() - p.touchedAt > expireMs) {
                cancelPending(id);
                messages().send(online, "preview-expired", "name", p.name);
                return;
            }
            drawOutline(online, p);
            // Fake blocks vanish when the client reloads a chunk or a real block updates: resend every 3 s.
            if (++p.previewTicks % 6 == 0 && !drags.containsKey(id)) {
                refreshGhost(online, p);
            }
            if (p.gizmo != null) {
                p.gizmo.update(online, p);
            }
        }, 0L, 10L);
        refreshGhost(player, p);
        if (plugin.getConfig().getBoolean("preview.handles", true)) {
            p.gizmo = new Gizmo(plugin, player, p);
        }
        return new PendingResult(p, 0);
    }

    /**
     * Shifts the pending build by (dx, dy, dz) blocks. The move is refused, and the build stays where it was, if
     * it would leave the world height, go further than preview.max-move-distance from where it first appeared,
     * or land on blocks the player cannot build on.
     */
    public MoveOutcome move(Player player, int dx, int dy, int dz) {
        return move(player, dx, dy, dz, true);
    }

    /** As {@link #move(Player, int, int, int)}; with redrawGhost false the ghost is only marked stale. */
    private MoveOutcome move(Player player, int dx, int dy, int dz, boolean redrawGhost) {
        UUID id = player.getUniqueId();
        Pending p = pending.get(id);
        if (p == null) {
            return new MoveOutcome(MoveResult.NOTHING_PENDING, 0);
        }
        int maxMove = Math.max(0, plugin.getConfig().getInt("preview.max-move-distance", 64));
        if (Math.abs((long) p.offsetX + dx) > maxMove || Math.abs((long) p.offsetY + dy) > maxMove
                || Math.abs((long) p.offsetZ + dz) > maxMove) {
            return new MoveOutcome(MoveResult.TOO_FAR, 0);
        }
        if (p.minY + dy < p.world.getMinHeight() || p.maxY + dy > p.world.getMaxHeight() - 1) {
            return new MoveOutcome(MoveResult.OUT_OF_WORLD, 0);
        }
        List<Placement> moved = new ArrayList<>(p.blocks.size());
        for (Placement pl : p.blocks) {
            moved.add(new Placement(pl.x() + dx, pl.y() + dy, pl.z() + dz, pl.data()));
        }
        if (!player.hasPermission("aiconstructor.bypass.protection")) {
            int denied = protection.countDenied(player, p.world, moved);
            if (denied > 0) {
                return new MoveOutcome(MoveResult.PROTECTED, denied);
            }
        }
        p.blocks = moved;
        p.offsetX += dx;
        p.offsetY += dy;
        p.offsetZ += dz;
        p.recomputeBounds();
        p.touchedAt = System.currentTimeMillis();
        if (redrawGhost) {
            refreshGhost(player, p);
        } else {
            p.ghostDirty = true;
        }
        if (p.gizmo != null) {
            p.gizmo.update(player, p);
        }
        drawOutline(player, p);
        return new MoveOutcome(MoveResult.MOVED, 0);
    }

    // ---- ghost blocks: client-side fake blocks only the owner sees; the world is never changed -------------------

    /**
     * Redraws the ghost at the build's current position in one batch: real blocks go back where the ghost was last
     * drawn, then the ghost is drawn where the build is now (overlapping positions simply get the ghost).
     */
    private void refreshGhost(Player player, Pending p) {
        if (!plugin.getConfig().getBoolean("preview.ghost-blocks", true) || !player.getWorld().equals(p.world)) {
            return;
        }
        Map<Long, BlockState> changes = new HashMap<>(p.blocks.size() * 4);
        if (p.ghostShown) {
            restoreInto(changes, p);
        }
        for (Placement pl : p.blocks) {
            if (p.world.isChunkLoaded(pl.x() >> 4, pl.z() >> 4)) {
                // A snapshot of the real block, changed only in the copy sent to this player.
                BlockState state = p.world.getBlockAt(pl.x(), pl.y(), pl.z()).getState();
                state.setBlockData(pl.data());
                changes.put(key(pl.x(), pl.y(), pl.z()), state);
            }
        }
        player.sendBlockChanges(changes.values());
        p.ghostShown = true;
        p.ghostDirty = false;
        p.ghostOffsetX = p.offsetX;
        p.ghostOffsetY = p.offsetY;
        p.ghostOffsetZ = p.offsetZ;
    }

    /** Sends the player the real blocks again where the ghost was last drawn. */
    private static void clearGhost(Player player, Pending p) {
        if (!p.ghostShown || player == null || !player.getWorld().equals(p.world)) {
            return;
        }
        Map<Long, BlockState> changes = new HashMap<>(p.blocks.size() * 2);
        restoreInto(changes, p);
        player.sendBlockChanges(changes.values());
        p.ghostShown = false;
    }

    private static void restoreInto(Map<Long, BlockState> changes, Pending p) {
        int sx = p.ghostOffsetX - p.offsetX, sy = p.ghostOffsetY - p.offsetY, sz = p.ghostOffsetZ - p.offsetZ;
        for (Placement pl : p.blocks) {
            int x = pl.x() + sx, y = pl.y() + sy, z = pl.z() + sz;
            if (p.world.isChunkLoaded(x >> 4, z >> 4)) {
                changes.put(key(x, y, z), p.world.getBlockAt(x, y, z).getState());
            }
        }
    }

    /** Packs a block position into one long (26 bits x, 12 bits y, 26 bits z), like vanilla BlockPos. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- drag handles -------------------------------------------------------------------------------------------

    /** Grabs a handle: from now on the build follows the player's look along that handle's axis. */
    private void startDrag(Player player, Pending p, int handle) {
        int axis = Gizmo.axis(handle);
        Vector origin = Gizmo.base(p, handle);
        Double grab = axisParam(player, origin, axis);
        if (grab == null) {
            return;
        }
        UUID id = player.getUniqueId();
        Drag drag = new Drag(axis, origin, grab);
        p.gizmo.highlight(handle);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
        drag.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickDrag(id, drag), 1L, 1L);
        drags.put(id, drag);
        sendDragBar(player, p, drag);
    }

    private void tickDrag(UUID id, Drag drag) {
        Player player = Bukkit.getPlayer(id);
        Pending p = pending.get(id);
        if (player == null || p == null || drags.get(id) != drag || !player.getWorld().equals(p.world)) {
            endDrag(id);
            return;
        }
        drag.ticks++;
        Double param = axisParam(player, drag.origin, drag.axis);
        if (param != null) {
            int target = (int) Math.round(param - drag.grabParam);
            int step = Math.clamp(target - drag.moved, -MAX_DRAG_STEP, MAX_DRAG_STEP);
            // Do not retry a refused position every tick; wait until the player points somewhere else.
            if (step != 0 && target != drag.failedTarget) {
                int[] delta = new int[3];
                delta[drag.axis] = step;
                MoveOutcome outcome = move(player, delta[0], delta[1], delta[2], false);
                if (outcome.result() == MoveResult.MOVED) {
                    drag.moved += step;
                    drag.failedTarget = Integer.MIN_VALUE;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.8f);
                    sendDragBar(player, p, drag);
                } else {
                    drag.failedTarget = target;
                    String key = switch (outcome.result()) {
                        case OUT_OF_WORLD -> "move-out-of-world";
                        case TOO_FAR -> "move-too-far";
                        default -> "move-protected";
                    };
                    messages().sendActionBar(player, messages().component(key, "blocks", outcome.deniedBlocks(),
                            "max", Math.max(0, plugin.getConfig().getInt("preview.max-move-distance", 64))));
                }
            }
        }
        // Resending thousands of fake blocks every tick is too much; catch up a few times per second instead.
        if (p.ghostDirty && drag.ticks % 3 == 0) {
            refreshGhost(player, p);
        }
        if (drag.ticks % 20 == 0) {
            sendDragBar(player, p, drag);
        }
    }

    private void sendDragBar(Player player, Pending p, Drag drag) {
        String tag = Gizmo.AXIS_TAGS[drag.axis];
        String axis = "<" + tag + "><b>" + Gizmo.AXIS_NAMES[drag.axis] + "</b></" + tag + ">";
        messages().sendActionBar(player, messages().component("dragging", "axis", new Messages.Raw(axis),
                "x", p.minX, "y", p.minY, "z", p.minZ,
                "ox", signed(p.offsetX), "oy", signed(p.offsetY), "oz", signed(p.offsetZ)));
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : String.valueOf(n);
    }

    /** Drops the handle being dragged, if any. */
    public boolean endDrag(UUID id) {
        Drag drag = drags.remove(id);
        if (drag == null) {
            return false;
        }
        drag.task.cancel();
        Pending p = pending.get(id);
        Player player = Bukkit.getPlayer(id);
        if (p != null) {
            if (p.gizmo != null) {
                p.gizmo.highlight(-1);
            }
            if (player != null && p.ghostDirty) {
                refreshGhost(player, p);
            }
        }
        if (player != null) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 0.9f);
        }
        return true;
    }

    /**
     * Where along the axis line (origin + s * axis) the player's look ray passes closest: the "mouse position"
     * projected onto the axis, as in a 3D editor. Null when looking (almost) along the axis or away from it.
     */
    private static Double axisParam(Player player, Vector origin, int axis) {
        Location eye = player.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        Vector a = Gizmo.axisVector(axis);
        Vector w = origin.clone().subtract(eye.toVector());
        double b = a.dot(d);
        double denom = 1 - b * b;
        if (denom < 0.02) {
            return null;
        }
        double ad = a.dot(w), dw = d.dot(w);
        double rayT = (dw - b * ad) / denom;
        if (rayT <= 0) {
            return null;
        }
        return (b * dw - ad) / denom;
    }

    /** Rotates local (x, z) the same way StructureRotation rotates block states. */
    private static int[] rotate(int x, int z, StructureRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private static void drawOutline(Player player, Pending p) {
        if (!player.getWorld().equals(p.world)) {
            return;
        }
        double x1 = p.minX, y1 = p.minY, z1 = p.minZ;
        double x2 = p.maxX + 1, y2 = p.maxY + 1, z2 = p.maxZ + 1;
        double[][] corners = {{x1, z1}, {x2, z1}, {x2, z2}, {x1, z2}};
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i], b = corners[(i + 1) % 4];
            edge(player, a[0], y1, a[1], b[0], y1, b[1]);
            edge(player, a[0], y2, a[1], b[0], y2, b[1]);
            edge(player, a[0], y1, a[1], a[0], y2, a[1]);
        }
    }

    private static void edge(Player player, double x1, double y1, double z1, double x2, double y2, double z2) {
        double len = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        int steps = Math.max(1, (int) Math.ceil(len));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            player.spawnParticle(Particle.END_ROD, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0, 0, 0, 0);
        }
    }

    public boolean cancelPending(UUID id) {
        endDrag(id);
        Pending p = pending.remove(id);
        if (p == null) {
            return false;
        }
        if (p.gizmo != null) {
            p.gizmo.remove();
        }
        if (p.preview != null) {
            p.preview.cancel();
        }
        clearGhost(Bukkit.getPlayer(id), p);
        return true;
    }

    public Pending getPending(UUID id) {
        return pending.get(id);
    }

    // ---- placement ----------------------------------------------------------------------------------------------

    /** Starts placing the pending build. Returns false if there was nothing pending. */
    public boolean confirm(Player player) {
        UUID id = player.getUniqueId();
        Pending p = pending.get(id);
        if (p == null) {
            return false;
        }
        cancelPending(id);
        int perTick = Math.max(1, plugin.getConfig().getInt("limits.blocks-per-tick", 150));
        List<Placement> originals = new ArrayList<>();
        UndoRecord record = new UndoRecord(p.name, p.world, originals);
        int total = p.blocks.size();
        int[] index = {0};
        int[] skipped = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(total, index[0] + perTick);
            for (; index[0] < end; index[0]++) {
                Placement pl = p.blocks.get(index[0]);
                Block block = p.world.getBlockAt(pl.x(), pl.y(), pl.z());
                Material current = block.getType();
                // Never overwrite chests, furnaces, signs, etc.
                if (!current.isAir() && block.getState() instanceof TileState) {
                    skipped[0]++;
                    continue;
                }
                BlockData before = block.getBlockData();
                if (before.equals(pl.data())) {
                    continue;
                }
                originals.add(new Placement(pl.x(), pl.y(), pl.z(), before));
                block.setBlockData(pl.data(), false);
            }
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                messages().sendActionBar(online, messages().component("build-progress", "name", p.name,
                        "percent", 100L * index[0] / total, "done", index[0], "total", total));
            }
            if (index[0] >= total) {
                Running self = running.remove(id);
                if (self != null) {
                    self.task().cancel();
                }
                pushUndo(id, record);
                if (online != null) {
                    String extra = skipped[0] > 0 ? messages().raw("protected-skipped", "count", skipped[0]) : "";
                    messages().send(online, "build-done", "name", p.name, "blocks", originals.size(),
                            "skipped", new Messages.Raw(extra));
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
                }
            }
        }, 1L, 1L);
        running.put(id, new Running(task, record));
        return true;
    }

    /** Stops a placement in progress; what was already placed stays and can be undone. */
    public boolean stopRunning(UUID id) {
        Running run = running.remove(id);
        if (run == null) {
            return false;
        }
        run.task().cancel();
        pushUndo(id, run.record());
        return true;
    }

    public boolean isRunning(UUID id) {
        return running.containsKey(id);
    }

    private void pushUndo(UUID id, UndoRecord record) {
        int max = Math.max(1, plugin.getConfig().getInt("limits.undo-history", 5));
        Deque<UndoRecord> stack = history.computeIfAbsent(id, k -> new ArrayDeque<>());
        stack.push(record);
        while (stack.size() > max) {
            stack.removeLast();
        }
    }

    public int undoCount(UUID id) {
        Deque<UndoRecord> stack = history.get(id);
        return stack == null ? 0 : stack.size();
    }

    /** Restores the blocks overwritten by the player's last build. Returns its name, or null if nothing to undo. */
    public String undo(Player player) {
        Deque<UndoRecord> stack = history.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        UndoRecord record = stack.pop();
        List<Placement> list = record.originals();
        int perTick = Math.max(1, plugin.getConfig().getInt("limits.blocks-per-tick", 150)) * 2;
        int[] index = {list.size() - 1};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.max(-1, index[0] - perTick);
            for (; index[0] > end; index[0]--) {
                Placement pl = list.get(index[0]);
                record.world().getBlockAt(pl.x(), pl.y(), pl.z()).setBlockData(pl.data(), false);
            }
            if (index[0] < 0) {
                self[0].cancel();
            }
        }, 1L, 1L);
        return record.name();
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelPending(event.getPlayer().getUniqueId());
        lastGrabToggle.remove(event.getPlayer().getUniqueId());
    }

    /** Left-click grabs the handle the player points at, or drops the one being dragged. */
    @EventHandler(priority = EventPriority.LOW)
    public void onLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (event.getHand() != EquipmentSlot.HAND
                || (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean dragging = drags.containsKey(id);
        int handle = -1;
        Pending p = pending.get(id);
        if (!dragging) {
            if (p == null || p.gizmo == null) {
                return;
            }
            handle = p.gizmo.pick(player, p, plugin.getConfig().getDouble("preview.handle-reach", 64));
            if (handle < 0) {
                return;
            }
        }
        event.setCancelled(true);
        // One click can arrive as several events (block dig + arm swing): treat them as one.
        long now = System.currentTimeMillis();
        Long last = lastGrabToggle.get(id);
        if (last != null && now - last < GRAB_DEBOUNCE_MS) {
            return;
        }
        lastGrabToggle.put(id, now);
        if (dragging) {
            endDrag(id);
        } else {
            startDrag(player, p, handle);
        }
    }

    /** The click that grabs or drops a handle must not also break the block behind it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long last = lastGrabToggle.get(id);
        if (drags.containsKey(id) || (last != null && System.currentTimeMillis() - last < GRAB_DEBOUNCE_MS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            endDrag(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        endDrag(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(drags.keySet())) {
            endDrag(id);
        }
        for (UUID id : new ArrayList<>(pending.keySet())) {
            cancelPending(id);
        }
        for (Running run : running.values()) {
            run.task().cancel();
        }
        running.clear();
    }
}
