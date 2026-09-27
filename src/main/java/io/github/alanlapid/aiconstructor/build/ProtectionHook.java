package io.github.alanlapid.aiconstructor.build;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/** Checks whether a player may build at the positions of a generated build. */
public interface ProtectionHook {

    /** Number of blocks the player is not allowed to place. 0 means the whole build is allowed. */
    int countDenied(Player player, World world, List<BuildManager.Placement> blocks);

    ProtectionHook NONE = (player, world, blocks) -> 0;
}
