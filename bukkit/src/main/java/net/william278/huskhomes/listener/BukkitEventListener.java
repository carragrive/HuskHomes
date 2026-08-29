/*
 * This file is part of HuskHomes, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskhomes.listener;

import net.william278.huskhomes.BukkitHuskHomes;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.position.World;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class BukkitEventListener extends EventListener implements Listener {

    // Ticks to wait after an unload before deciding a world was deleted
    private static final long WORLD_DELETE_CHECK_DELAY = 100L;

    protected boolean usePaperEvents = false;

    public BukkitEventListener(@NotNull BukkitHuskHomes plugin) {
        super(plugin);
    }

    @Override
    public void register() {
        getPlugin().getServer().getPluginManager().registerEvents(this, getPlugin());
    }

    // Read any pending cross-server teleport during the handshake, so the join handler doesn't wait on the database
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        getPlugin().prefetchInboundTeleport(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        getPlugin().getOnlineUserMap().remove(event.getPlayer().getUniqueId());
        super.handlePlayerJoin(getPlugin().getOnlineUser(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerLeave(PlayerQuitEvent event) {
        super.handlePlayerLeave(getPlugin().getOnlineUser(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        final World world = BukkitHuskHomes.Adapter.adapt(event.getPlayer().getWorld());
        final var user = getPlugin().getOnlineUser(event.getPlayer());
        final Map<String, World> session = getPlugin().getLastWorldCache().get(user.getUuid());
        if (session == null) {
            return;
        }
        session.put(getPlugin().getServerName(), world);
        getPlugin().runAsync(() -> getPlugin().persistLastWorld(user, session));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        super.handlePlayerDeath(getPlugin().getOnlineUser(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (usePaperEvents) {
            return;
        }
        getPlugin().getOnlineUserMap().remove(event.getPlayer().getUniqueId());
        super.handlePlayerRespawn(getPlugin().getOnlineUser(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        final Player player = event.getPlayer();

        // Return if the disconnecting entity is a Citizens NPC, or if the teleport was naturally caused
        if (player.hasMetadata("NPC")) {
            return;
        }
        if (!(event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND ||
              event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return;
        }

        this.handlePlayerTeleport(
                getPlugin().getOnlineUser(player),
                BukkitHuskHomes.Adapter.adapt(event.getFrom(), getPlugin().getServerName())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTakeDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Cancel warmup on any "hurt" event during warmup, even if damage is absorbed
        if (!getPlugin().isWarmingUp(player.getUniqueId()) || event.getDamage() <= 0) {
            return;
        }
        getPlugin().getWarmupDamagedUsers().add(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerUpdateRespawnLocation(PlayerInteractEvent event) {
        final Settings.CrossServerSettings crossServer = getPlugin().getSettings().getCrossServer();
        if (usePaperEvents || !(crossServer.isEnabled()) && crossServer.isGlobalRespawning()) {
            return;
        }
        if (event.getClickedBlock() == null || !(event.getClickedBlock().getBlockData() instanceof Bed
                                                 || event.getClickedBlock().getBlockData() instanceof RespawnAnchor)) {
            return;
        }

        final Location location = event.getPlayer().getBedSpawnLocation();
        if (location == null) {
            return;
        }

        // Update the player's respawn location
        this.handlePlayerUpdateSpawnPoint(
                getPlugin().getOnlineUser(event.getPlayer()),
                BukkitHuskHomes.Adapter.adapt(location, getPlugin().getServerName())
        );
    }

    // No delete event exists, but a deletion unloads first: still unloaded and folder gone means deleted
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        final World world = BukkitHuskHomes.Adapter.adapt(event.getWorld());
        final File folder = event.getWorld().getWorldFolder();
        getPlugin().runSyncDelayed(() -> {
            if (Bukkit.getWorld(world.getUuid()) != null || folder.exists()) {
                return;
            }
            getPlugin().runAsync(() -> getPlugin().forgetDeletedWorld(world));
        }, null, WORLD_DELETE_CHECK_DELAY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        final Set<UUID> loadedWorlds = getPlugin().getWorlds().stream()
                .map(World::getUuid)
                .collect(Collectors.toUnmodifiableSet());
        getPlugin().runAsync(() -> {
            getPlugin().reconcileLastWorlds(loadedWorlds);
            getPlugin().getBroker().ifPresent(broker -> broker.markReady());
        });
    }

    @Override
    @NotNull
    protected BukkitHuskHomes getPlugin() {
        return (BukkitHuskHomes) super.getPlugin();
    }


}
