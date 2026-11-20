package io.github.alanlapid.aiconstructor.build;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/** WorldGuard region check (also covers ProtectionStones, which uses WorldGuard regions). Only loaded when WorldGuard is present. */
final class WorldGuardHook implements ProtectionHook {

    @Override
    public int countDenied(Player player, World world, List<BuildManager.Placement> blocks) {
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, BukkitAdapter.adapt(world))) {
            return 0;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        int denied = 0;
        Location location = new Location(world, 0, 0, 0);
        for (BuildManager.Placement p : blocks) {
            location.setX(p.x());
            location.setY(p.y());
            location.setZ(p.z());
            if (!query.testBuild(BukkitAdapter.adapt(location), localPlayer, Flags.BLOCK_PLACE)) {
                denied++;
            }
        }
        return denied;
    }
}
