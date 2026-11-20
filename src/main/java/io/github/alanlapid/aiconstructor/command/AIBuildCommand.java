package io.github.alanlapid.aiconstructor.command;

import io.github.alanlapid.aiconstructor.AIConstructor;
import io.github.alanlapid.aiconstructor.ai.ImageData;
import io.github.alanlapid.aiconstructor.ai.PlanGenerator;
import io.github.alanlapid.aiconstructor.ai.ProviderException;
import io.github.alanlapid.aiconstructor.build.BuildManager;
import io.github.alanlapid.aiconstructor.build.BuildPlan;
import io.github.alanlapid.aiconstructor.util.ImageDownloader;
import io.github.alanlapid.aiconstructor.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** /aibuild. Subcommands also accept Spanish names (confirmar, cancelar, deshacer, estado, recargar, foto). */
public final class AIBuildCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("photo", "confirm", "cancel", "undo", "status", "reload");

    private final AIConstructor plugin;

    public AIBuildCommand(AIConstructor plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : normalize(args[0]);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("aiconstructor.admin")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.reloadAll();
            msg().send(sender, "reload-done");
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("aiconstructor.use")) {
            msg().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            msg().sendPlain(player, "help", "label", label);
            return true;
        }
        BuildManager builds = plugin.builds();
        UUID id = player.getUniqueId();
        switch (sub) {
            case "confirm" -> {
                if (!builds.confirm(player)) {
                    msg().send(player, "nothing-pending");
                }
            }
            case "cancel" -> {
                if (builds.stopRunning(id)) {
                    msg().send(player, "placement-stopped");
                } else if (builds.cancelPending(id)) {
                    msg().send(player, "preview-cancelled");
                } else {
                    msg().send(player, "nothing-to-cancel");
                }
            }
            case "undo" -> {
                if (builds.isRunning(id)) {
                    msg().send(player, "undo-running");
                    return true;
                }
                String name = builds.undo(player);
                if (name == null) {
                    msg().send(player, "nothing-to-undo");
                } else {
                    msg().send(player, "undoing", "name", name);
                }
            }
            case "status" -> status(player);
            case "photo" -> {
                if (!player.hasPermission("aiconstructor.photo")) {
                    msg().send(player, "no-permission");
                    return true;
                }
                if (!plugin.getConfig().getBoolean("provider.vision", true)) {
                    msg().send(player, "photo-unsupported");
                    return true;
                }
                if (args.length < 2) {
                    msg().send(player, "photo-usage", "label", label);
                    return true;
                }
                generate(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)), args[1]);
            }
            default -> generate(player, String.join(" ", args), null);
        }
        return true;
    }

    private static String normalize(String sub) {
        return switch (sub.toLowerCase(Locale.ROOT)) {
            case "confirm", "confirmar" -> "confirm";
            case "cancel", "cancelar" -> "cancel";
            case "undo", "deshacer" -> "undo";
            case "status", "estado" -> "status";
            case "reload", "recargar" -> "reload";
            case "photo", "foto" -> "photo";
            default -> "";
        };
    }

    private void generate(Player player, String prompt, String imageUrl) {
        int maxLength = plugin.getConfig().getInt("limits.max-prompt-length", 500);
        if (prompt.length() > maxLength) {
            msg().send(player, "prompt-too-long", "max", maxLength);
            return;
        }
        if (plugin.getConfig().getStringList("disabled-worlds").contains(player.getWorld().getName())) {
            msg().send(player, "world-disabled");
            return;
        }
        if (!plugin.isProviderConfigured()) {
            msg().send(player, "missing-key");
            return;
        }
        BuildManager builds = plugin.builds();
        BuildManager.Denial denial = builds.checkCanGenerate(player);
        if (denial != null) {
            msg().send(player, denial.key(), denial.args());
            return;
        }
        builds.startGenerating(player);
        UUID id = player.getUniqueId();
        msg().send(player, imageUrl != null ? "thinking-photo" : "thinking");
        PlanGenerator generator = plugin.generator();
        int maxImageMb = plugin.getConfig().getInt("limits.max-image-mb", 8);

        PlanGenerator.Progress progress = new PlanGenerator.Progress() {
            @Override
            public void trying(String model, int attempt, int totalAttempts) {
                onMain(id, p -> p.sendActionBar(msg().component("trying", "model", model, "attempt", attempt, "total", totalAttempts)));
            }

            @Override
            public void failed(String model, ProviderException.Reason reason) {
                String reasonText = msg().raw("reason-" + reason.name().toLowerCase(Locale.ROOT));
                onMain(id, p -> msg().sendPlain(p, "attempt-failed", "model", model, "reason", reasonText));
            }
        };

        String who = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlanGenerator.Result result = null;
            String failure = null;
            try {
                ImageData image = imageUrl == null ? null : ImageDownloader.download(imageUrl, maxImageMb);
                result = generator.generate(prompt, image, progress);
            } catch (PlanGenerator.GenerationException e) {
                failure = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "Interrupted.";
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Unexpected error generating for " + who + ": " + e);
                failure = "Unexpected error: " + e.getMessage();
            }
            PlanGenerator.Result finalResult = result;
            String finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> onGenerated(id, prompt, finalResult, finalFailure));
        });
    }

    /** Runs an action for the player on the main thread, if they are still online. */
    private void onMain(UUID id, Consumer<Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                action.accept(p);
            }
        });
    }

    private void onGenerated(UUID id, String prompt, PlanGenerator.Result result, String failure) {
        BuildManager builds = plugin.builds();
        builds.finishGenerating(id);
        Player player = Bukkit.getPlayer(id);
        if (failure != null) {
            builds.refundGeneration(id);
            if (player != null) {
                msg().send(player, "generation-failed", "error", failure);
                msg().sendPlain(player, "refund-note");
            }
            return;
        }
        if (player == null) {
            return;
        }
        int maxSize = Math.clamp(plugin.getConfig().getInt("limits.max-size", 48), 4, 256);
        BuildPlan plan = BuildPlan.rasterize(result.plan(), maxSize, blockedMaterials());
        int maxBlocks = plugin.getConfig().getInt("limits.max-blocks", 40000);
        if (plan.voxels().size() > maxBlocks) {
            msg().send(player, "too-many-blocks", "blocks", plan.voxels().size(), "max", maxBlocks);
            return;
        }
        BuildManager.PendingResult created = builds.createPending(player, plan);
        if (created.deniedBlocks() > 0) {
            msg().send(player, "protected-area", "blocks", created.deniedBlocks());
            return;
        }
        BuildManager.Pending pending = created.pending();
        if (pending == null) {
            String sample = result.plan().has("parts") && result.plan().get("parts").isJsonArray()
                    && !result.plan().getAsJsonArray("parts").isEmpty()
                    ? result.plan().getAsJsonArray("parts").get(0).toString() : result.plan().toString();
            plugin.getLogger().warning("No usable blocks from " + result.model() + " (" + plan.partsSkipped() + "/"
                    + plan.partsTotal() + " parts skipped). First part: "
                    + (sample.length() > 300 ? sample.substring(0, 300) + "..." : sample));
            msg().send(player, "no-valid-blocks");
            return;
        }
        plugin.getLogger().info(player.getName() + " generated '" + plan.name() + "' (" + pending.blocks.size() + " blocks) with "
                + result.model() + " in " + result.millis() / 1000.0 + "s. Prompt: " + prompt);

        msg().send(player, "preview",
                "name", plan.name(),
                "sx", pending.maxX - pending.minX + 1,
                "sy", pending.maxY - pending.minY + 1,
                "sz", pending.maxZ - pending.minZ + 1,
                "blocks", pending.blocks.size(),
                "parts", (plan.partsTotal() - plan.partsSkipped()) + "/" + plan.partsTotal(),
                "materials", topMaterials(pending),
                "expire", plugin.getConfig().getLong("limits.pending-expire-seconds", 180));
        if (!plan.rejectedBlocks().isEmpty()) {
            msg().sendPlain(player, "rejected-blocks", "blocks", String.join(", ", plan.rejectedBlocks()));
        }
    }

    private static String topMaterials(BuildManager.Pending pending) {
        Map<Material, Integer> count = new HashMap<>();
        for (BuildManager.Placement p : pending.blocks) {
            count.merge(p.data().getMaterial(), 1, Integer::sum);
        }
        return count.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> e.getKey().getKey().getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private Set<Material> blockedMaterials() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("blocked-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                set.add(m);
            }
        }
        return set;
    }

    private void status(Player player) {
        BuildManager builds = plugin.builds();
        UUID id = player.getUniqueId();
        BuildManager.Pending pending = builds.getPending(id);
        int limit = builds.dailyLimit(player);
        PlanGenerator generator = plugin.generator();
        msg().send(player, "status",
                "used", builds.usedToday(id),
                "limit", limit == Integer.MAX_VALUE ? msg().raw("unlimited") : String.valueOf(limit),
                "undo", builds.undoCount(id),
                "generating", msg().raw(builds.isGenerating(id) ? "yes" : "no"),
                "placing", msg().raw(builds.isRunning(id) ? "yes" : "no"),
                "pending", pending == null ? msg().raw("none") : pending.name,
                "provider", generator.provider().name(),
                "models", String.join(", ", generator.models()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(start)).toList();
        }
        return List.of();
    }
}
