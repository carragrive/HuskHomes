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

package net.william278.huskhomes.user;

import me.clip.placeholderapi.PlaceholderAPI;
import net.william278.huskhomes.BukkitHuskHomesPlugin;
import net.william278.huskhomes.network.PluginMessageBroker;
import net.william278.huskhomes.position.Location;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.teleport.TeleportationException;
import net.william278.huskhomes.util.BukkitPaperCompat;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bukkit's implementation of an {@link OnlineUser}.
 */
public class BukkitUser extends OnlineUser {

    private static final String VANISHED_META_KEY = "vanished";
    private final NamespacedKey INVULNERABLE_KEY = new NamespacedKey((BukkitHuskHomesPlugin) plugin, "invulnerable");
    private final Player bukkitPlayer;

    private BukkitUser(@NotNull Player bukkitPlayer, @NotNull BukkitHuskHomesPlugin plugin) {
        super(bukkitPlayer.getUniqueId(), bukkitPlayer.getName(), plugin);
        this.bukkitPlayer = bukkitPlayer;
    }

    @NotNull
    @ApiStatus.Internal
    public static BukkitUser adapt(@NotNull Player player, @NotNull BukkitHuskHomesPlugin plugin) {
        return new BukkitUser(player, plugin);
    }

    @NotNull
    public Player getPlayer() {
        return bukkitPlayer;
    }

    @Override
    public Position getPosition() {
        return readOnOwner(() -> BukkitHuskHomesPlugin.Adapter.adapt(bukkitPlayer.getLocation(), plugin.getServerName()));
    }

    @Override
    public Optional<Position> getBedSpawnPosition() {
        return readOnOwner(() -> Optional.ofNullable(bukkitPlayer.getBedSpawnLocation())
                .map(loc -> BukkitHuskHomesPlugin.Adapter.adapt(loc, plugin.getServerName())));
    }

    @Override
    public double getHealth() {
        return readOnOwner(bukkitPlayer::getHealth);
    }

    @Override
    public boolean isPermissionSet(@NotNull String node) {
        return bukkitPlayer.isPermissionSet(node);
    }

    @Override
    public boolean hasPermission(@NotNull String node) {
        return bukkitPlayer.hasPermission(node);
    }

    @Override
    @NotNull
    public Map<String, Boolean> getPermissions() {
        return bukkitPlayer.getEffectivePermissions().stream()
                .collect(Collectors.toMap(
                        PermissionAttachmentInfo::getPermission,
                        PermissionAttachmentInfo::getValue, (a, b) -> b
                ));
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<String>> resolvePlaceholder(@NotNull String input) {
        if (!plugin.isDependencyAvailable("PlaceholderAPI")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return plugin.supplySync(() -> Optional.ofNullable(PlaceholderAPI.setPlaceholders(bukkitPlayer, input)), this);
    }

    @Override
    public CompletableFuture<Void> dismount() {
        return plugin.supplySync(() -> {
            bukkitPlayer.leaveVehicle();
            bukkitPlayer.eject();
            return null;
        }, this);
    }

    @Override
    public void teleportLocally(@NotNull Location target, boolean async) throws TeleportationException {
        // Ensure the location is valid (world exists, coordinates are within the world border)
        final org.bukkit.Location location = BukkitHuskHomesPlugin.Adapter.adapt(target);
        if (location.getWorld() == null) {
            throw new TeleportationException(TeleportationException.Type.WORLD_NOT_FOUND, plugin);
        }
        if (!location.getWorld().getWorldBorder().isInside(location)) {
            throw new TeleportationException(TeleportationException.Type.ILLEGAL_TARGET_COORDINATES, plugin);
        }

        plugin.runSync(() -> {
            bukkitPlayer.leaveVehicle();
            bukkitPlayer.eject();
            bukkitPlayer.setFallDistance(0f);
            if (async || ((BukkitHuskHomesPlugin) plugin).getScheduler().isUsingFolia()) {
                BukkitPaperCompat.teleportAsync(bukkitPlayer, location);
                return;
            }
            bukkitPlayer.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }, this);
    }

    @Override
    public boolean isMoving() {
        return readOnOwner(() -> bukkitPlayer.getVelocity().lengthSquared() >= 0.01);
    }

    @Override
    public void sendPluginMessage(byte[] message) {
        plugin.runSync(() -> bukkitPlayer.sendPluginMessage((BukkitHuskHomesPlugin) plugin,
                PluginMessageBroker.BUNGEE_CHANNEL_ID, message), this);
    }

    @Override
    public boolean isVanished() {
        return bukkitPlayer.hasMetadata(VANISHED_META_KEY) && bukkitPlayer.getMetadata(VANISHED_META_KEY).stream()
                .map(MetadataValue::asBoolean).findFirst().orElse(false);
    }

    @Override
    public boolean hasInvulnerability() {
        return readOnOwner(() -> markedAsInvulnerable || bukkitPlayer.getPersistentDataContainer()
                .has(INVULNERABLE_KEY, PersistentDataType.INTEGER));
    }

    @Override
    public void handleInvulnerability() {
        if (!Bukkit.isOwnedByCurrentRegion(bukkitPlayer)) {
            plugin.runSync(this::handleInvulnerability, this);
            return;
        }
        final long invulnerableTicks = 20L * plugin.getSettings().getGeneral().getTeleportInvulnerabilityTime();
        if (invulnerableTicks <= 0) {
            return;
        }
        markedAsInvulnerable = true;
        bukkitPlayer.getPersistentDataContainer().set(INVULNERABLE_KEY, PersistentDataType.INTEGER, 1);
        bukkitPlayer.setInvulnerable(true);
        plugin.runSyncDelayed(this::removeInvulnerabilityIfPermitted, this, invulnerableTicks);
    }

    @Override
    public void removeInvulnerabilityIfPermitted() {
        if (!Bukkit.isOwnedByCurrentRegion(bukkitPlayer)) {
            plugin.runSync(this::removeInvulnerabilityIfPermitted, this);
            return;
        }
        if (this.hasInvulnerability()) {
            bukkitPlayer.setInvulnerable(false);
        }
        bukkitPlayer.getPersistentDataContainer().remove(INVULNERABLE_KEY);
        markedAsInvulnerable = false;
    }

    /**
     * Check if the teleporter can teleport.
     *
     * @return true if the teleport may complete.
     */
    @Override
    public boolean isValid() {
        return plugin.isEnabled() && plugin.getOnlineUserMap().get(getUuid()) == this && getHealth() > 0;
    }

    private <T> T readOnOwner(Supplier<T> read) {
        if (Bukkit.isOwnedByCurrentRegion(bukkitPlayer)) {
            return read.get();
        }
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Player state must be read on the player's owning region");
        }
        final CompletableFuture<T> result = plugin.supplySync(read, this);
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading player state", error);
        } catch (TimeoutException error) {
            result.cancel(false);
            throw new IllegalStateException("Timed out waiting for the player's owning region", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Could not read player state", error.getCause());
        }
    }
}
