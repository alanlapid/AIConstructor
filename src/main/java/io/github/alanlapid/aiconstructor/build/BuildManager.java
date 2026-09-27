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
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

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

    /** A generated build waiting for confirmation. */
    public static final class Pending {
        public final String name;
        public final World world;
        public final List<Placement> blocks;
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        final long createdAt = System.currentTimeMillis();
        BukkitTask preview;

        Pending(String name, World world, List<Placement> blocks) {
            this.name = name;
            this.world = world;
            this.blocks = blocks;
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

    private final AIConstructor plugin;
    private final Set<UUID> generating = new HashSet<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Running> running = new HashMap<>();
    private final Map<UUID, Deque<UndoRecord>> history = new HashMap<>();
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
            if (System.currentTimeMillis() - p.createdAt > expireMs) {
                cancelPending(id);
                messages().send(online, "preview-expired", "name", p.name);
                return;
            }
            drawOutline(online, p);
        }, 0L, 10L);
        return new PendingResult(p, 0);
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
        Pending p = pending.remove(id);
        if (p == null) {
            return false;
        }
        if (p.preview != null) {
            p.preview.cancel();
        }
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
                if (!current.isAir() && block.getState(false) instanceof TileState) {
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
                online.sendActionBar(messages().component("build-progress", "name", p.name,
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
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(pending.keySet())) {
            cancelPending(id);
        }
        for (Running run : running.values()) {
            run.task().cancel();
        }
        running.clear();
    }
}
