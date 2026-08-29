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

package net.william278.huskhomes.random;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.RtpOptions;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.position.Location;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.World;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * A random teleport engine that uses a Gaussian normal distribution to generate random positions.
 */
public final class NormalDistributionEngine extends RandomTeleportEngine {

    private final float mean;
    private final float standardDeviation;

    public NormalDistributionEngine(@NotNull HuskHomes plugin) {
        super(plugin, "Normal Distribution");
        this.mean = plugin.getSettings().getRtp().getDistribution().getMean();
        this.standardDeviation = plugin.getSettings().getRtp().getDistribution().getStandardDeviation();

        if (plugin.getSettings().getRtp().getMode() == Settings.RtpSettings.Mode.CROSS_SERVER
                && (!plugin.getSettings().getCrossServer().isEnabled()
                || plugin.getSettings().getCrossServer().getBrokerType() != Broker.Type.REDIS)) {
            plugin.log(Level.WARNING, "Cross-server /rtp destinations require cross-server mode with a REDIS broker.");
        }
        if (plugin.getSettings().getRtp().usesPlaceholderConditions()
                && !plugin.isDependencyAvailable("PlaceholderAPI")) {
            plugin.log(Level.WARNING, "RTP placeholder conditions will not match because PlaceholderAPI is unavailable.");
        }
    }

    /**
     * Generate a {@link Location} through a randomized normally distributed radius and random angle using the mean and
     * standard deviation, about the origin position.
     *
     * @param origin The origin position
     * @return A generated location
     */
    @NotNull
    public static Location generateLocation(@NotNull Location origin, float mean, float standardDeviation,
                                            float spawnRadius, float maxRadius) {
        return generateLocation(origin, mean, standardDeviation, spawnRadius, maxRadius, RtpOptions.Direction.RANDOM);
    }

    @NotNull
    public static Location generateLocation(@NotNull Location origin, float mean, float standardDeviation,
                                            float spawnRadius, float maxRadius,
                                            @NotNull RtpOptions.Direction direction) {
        // Generate random values
        final float radius = getDistributedRadius(mean, standardDeviation, spawnRadius, maxRadius);
        final double angle = getRandomAngle(direction);

        // Calculate corresponding x and z
        final float z = (float) (radius * Math.cos(angle));
        final float x = (float) (radius * Math.sin(angle));

        return Location.at(
                Math.round(origin.getX()) + x,
                128d,
                Math.round(origin.getZ()) + z,
                origin.getWorld()
        );
    }

    /**
     * Generate a safe ground-level {@link Location} through a randomized normally-distributed radius and random angle.
     *
     * @param world The world to generate the location in
     * @return A generated location
     */
    private CompletableFuture<Optional<Location>> generateSafeLocation(@NotNull World world,
                                                                         @NotNull RtpOptions options) {
        return plugin.findSafeGroundLocation(generateLocation(
                getCenterPoint(world), mean, standardDeviation,
                options.getMinRadius(), options.getMaxRadius(), options.getDirection()
        ), options);
    }

    /**
     * Generates a normally distributed radius between the spawnRadius and the maximum radius value,
     * using the provided standard deviation and mean.
     *
     * @return the generated radius
     */
    private static float getDistributedRadius(float mean, float standardDeviation,
                                              float spawnRadius, float maxRadius) {
        double value = (new Random().nextGaussian() * mean + standardDeviation) * maxRadius;
        if (value < spawnRadius || value > maxRadius) {
            return getDistributedRadius(mean, standardDeviation, spawnRadius, maxRadius);
        }
        return (float) value;
    }

    /**
     * Generates a random angle in radians for the requested direction quadrant.
     *
     * @return a random angle in radians
     */
    private static double getRandomAngle(@NotNull RtpOptions.Direction direction) {
        if (direction == RtpOptions.Direction.RANDOM) {
            return Math.random() * Math.PI * 2d;
        }
        final double center = switch (direction) {
            case NORTH -> Math.PI;
            case SOUTH -> 0d;
            case EAST -> Math.PI / 2d;
            case WEST -> Math.PI * 1.5d;
            case RANDOM -> throw new IllegalStateException("RANDOM direction was handled above");
        };
        return center + ((Math.random() - 0.5d) * (Math.PI / 2d));
    }

    @Override
    public CompletableFuture<Optional<Position>> getRandomPosition(@NotNull World world, @NotNull String[] args) {
        return getRandomPosition(world, args, new RtpOptions());
    }

    @Override
    public CompletableFuture<Optional<Position>> getRandomPosition(@NotNull World world, @NotNull String[] args,
                                                                    @NotNull RtpOptions options) {
        return plugin.supplyAsync(() -> {
            Optional<Location> location = generateSafeLocation(world, options).join();
            int attempts = 0;
            while (location.isEmpty()) {
                location = generateSafeLocation(world, options).join();
                if (attempts >= maxAttempts) {
                    return Optional.empty();
                }
                attempts++;
            }
            return location.map(resolved -> Position.at(resolved, plugin.getServerName()));
        });
    }
}
