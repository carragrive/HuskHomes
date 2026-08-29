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

package net.william278.huskhomes.util;

import net.william278.huskhomes.BukkitHuskHomes;
import net.william278.huskhomes.config.RtpOptions;
import net.william278.huskhomes.position.Location;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.user.BukkitUser;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface BukkitSavePositionProvider extends SavePositionProvider {

    @Override
    default CompletableFuture<Optional<Location>> findSafeGroundLocation(@NotNull Location location) {
        return findSafeGroundLocation(location, new RtpOptions());
    }

    @Override
    default CompletableFuture<Optional<Location>> findSafeGroundLocation(@NotNull Location location,
                                                                          @NotNull RtpOptions options) {
        final org.bukkit.Location bukkitLocation = BukkitHuskHomes.Adapter.adapt(location);
        if (bukkitLocation == null || bukkitLocation.getWorld() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Ensure the location is within the world border
        if (!bukkitLocation.getWorld().getWorldBorder().isInside(bukkitLocation)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return BukkitPaperCompat.getChunkAtAsync(getPlugin(), bukkitLocation.getWorld(), bukkitLocation)
                .thenApply(Chunk::getChunkSnapshot)
                .thenApply(snapshot -> findSafeLocationNear(
                        location,
                        snapshot,
                        Math.max(bukkitLocation.getWorld().getMinHeight() + 1, options.getMinY()),
                        Math.min(
                                bukkitLocation.getWorld().getMaxHeight() - getClearanceHeight(options),
                                options.getMaxY()
                        ),
                        options
                ));
    }

    @Override
    default CompletableFuture<Boolean> prepareRtpDestination(@NotNull OnlineUser user, @NotNull Position position,
                                                              @NotNull RtpOptions options) {
        final org.bukkit.Location location = BukkitHuskHomes.Adapter.adapt(position);
        if (location == null || location.getWorld() == null || !(user instanceof BukkitUser bukkitUser)) {
            return CompletableFuture.completedFuture(false);
        }

        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final BukkitHuskHomes plugin = (BukkitHuskHomes) getPlugin();
        plugin.getScheduler().regionSpecificScheduler(location).run(() -> {
            try {
                result.complete(prepareRtpDestination(bukkitUser, location, options));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    /**
     * Search for a safe ground location near the given location.
     *
     * @param location The location to search around
     * @param chunk    The chunk snapshot to search
     * @param minY     The minimum Y value of the world
     * @param maxY     The maximum Y value of the world
     * @param options RTP clearance and underground-search options
     * @return An optional safe location, within 4 blocks of the given location
     */
    private Optional<Location> findSafeLocationNear(@NotNull Location location, @NotNull ChunkSnapshot chunk,
                                                    int minY, int maxY, @NotNull RtpOptions options) {
        if (minY > maxY) {
            return Optional.empty();
        }
        final int chunkX = ((int) Math.floor(location.getX())) & 0xF;
        final int chunkZ = ((int) Math.floor(location.getZ())) & 0xF;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                final int x = chunkX + dx;
                final int z = chunkZ + dz;
                if (x < 0 || x >= 16 || z < 0 || z >= 16) {
                    continue;
                }

                final Optional<Integer> y = getY(chunk, minY, maxY, x, z, options);
                if (y.isPresent()) {
                    double locx = Math.floor(location.getX()) + dx + 0.5d;
                    double locz = Math.floor(location.getZ()) + dz + 0.5d;
                    return Optional.of(Location.at(
                            locx,
                            y.get(),
                            locz,
                            location.getWorld()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSafeLocation(@NotNull ChunkSnapshot chunk, int x, int y, int z,
                                   @NotNull RtpOptions options) {
        final Material blockType = chunk.getBlockType(x, y - 1, z);
        if (!isBlockSafeForStanding(blockType.getKey().toString())) {
            return false;
        }
        if (!options.getClearance().isEnabled()) {
            return isBlockSafeForOccupation(chunk.getBlockType(x, y, z).getKey().toString())
                    && isBlockSafeForOccupation(chunk.getBlockType(x, y + 1, z).getKey().toString());
        }

        final RtpOptions.Clearance clearance = options.getClearance();
        final int radius = clearance.getWidth() / 2;
        if (x - radius < 0 || x + radius >= 16 || z - radius < 0 || z + radius >= 16) {
            return false;
        }
        for (int dy = 0; dy < clearance.getHeight(); dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isExcludedCorner(dx, dz, radius, clearance)) {
                        continue;
                    }
                    final Material material = chunk.getBlockType(x + dx, y + dy, z + dz);
                    if (!isBlockSafeForOccupation(material.getKey().toString()) && !isClearable(material)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private Optional<Integer> getY(@NotNull ChunkSnapshot chunk, int minY, int maxY, int x, int z,
                                   @NotNull RtpOptions options) {
        final int surfaceY = Math.min(chunk.getHighestBlockYAt(x, z) + 1, maxY);
        if (!options.isAllowUnderground()) {
            return surfaceY >= minY && isSafeLocation(chunk, x, surfaceY, z, options)
                    ? Optional.of(surfaceY)
                    : Optional.empty();
        }
        for (int y = surfaceY; y >= minY; y--) {
            if (isSafeLocation(chunk, x, y, z, options)) {
                return Optional.of(y);
            }
        }
        return Optional.empty();
    }

    private boolean prepareRtpDestination(@NotNull BukkitUser user, @NotNull org.bukkit.Location location,
                                          @NotNull RtpOptions options) {
        final int x = location.getBlockX();
        final int y = location.getBlockY();
        final int z = location.getBlockZ();
        final Block floor = location.getWorld().getBlockAt(x, y - 1, z);
        if (!isBlockSafeForStanding(floor.getType().getKey().toString())) {
            return false;
        }

        final RtpOptions.Clearance clearance = options.getClearance();
        if (!clearance.isEnabled()) {
            return isBlockSafeForOccupation(location.getWorld().getBlockAt(x, y, z).getType().getKey().toString())
                    && isBlockSafeForOccupation(
                            location.getWorld().getBlockAt(x, y + 1, z).getType().getKey().toString()
            );
        }

        final List<Block> blocks = getClearanceBlocks(location, clearance);
        if (blocks == null) {
            return false;
        }
        if (clearance.isRespectProtection()) {
            for (Block block : blocks) {
                final BlockBreakEvent event = new BlockBreakEvent(block, user.getPlayer());
                event.setDropItems(false);
                event.setExpToDrop(0);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return false;
                }
            }
        }
        for (Block block : blocks) {
            if (!isClearable(block.getType()) || block.getState() instanceof TileState) {
                return false;
            }
        }
        blocks.forEach(block -> block.setType(Material.AIR, false));
        return true;
    }

    private List<Block> getClearanceBlocks(@NotNull org.bukkit.Location location,
                                           @NotNull RtpOptions.Clearance clearance) {
        final List<Block> blocks = new ArrayList<>();
        final int radius = clearance.getWidth() / 2;
        for (int dy = 0; dy < clearance.getHeight(); dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isExcludedCorner(dx, dz, radius, clearance)) {
                        continue;
                    }
                    final Block block = location.getWorld().getBlockAt(
                            location.getBlockX() + dx,
                            location.getBlockY() + dy,
                            location.getBlockZ() + dz
                    );
                    if (isBlockSafeForOccupation(block.getType().getKey().toString())) {
                        continue;
                    }
                    if (!isClearable(block.getType()) || block.getState() instanceof TileState) {
                        return null;
                    }
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private boolean isClearable(@NotNull Material material) {
        return material.isBlock() && material.getHardness() >= 0
                && isBlockSafeForStanding(material.getKey().toString());
    }

    private boolean isExcludedCorner(int dx, int dz, int radius, @NotNull RtpOptions.Clearance clearance) {
        return radius > 0 && !clearance.isIncludeCorners() && Math.abs(dx) == radius && Math.abs(dz) == radius;
    }

    private int getClearanceHeight(@NotNull RtpOptions options) {
        return options.getClearance().isEnabled() ? options.getClearance().getHeight() : 2;
    }
}
