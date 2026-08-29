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

package net.william278.huskhomes;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import de.exlll.configlib.ConfigurationException;
import net.kyori.adventure.key.Key;
import net.william278.huskhomes.api.BaseHuskHomesAPI;
import net.william278.huskhomes.command.Command;
import net.william278.huskhomes.command.CommandProvider;
import net.william278.huskhomes.config.ConfigProvider;
import net.william278.huskhomes.config.RtpOptions;
import net.william278.huskhomes.config.Server;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.database.DatabaseProvider;
import net.william278.huskhomes.event.EventDispatcher;
import net.william278.huskhomes.hook.HookProvider;
import net.william278.huskhomes.hook.PluginHook;
import net.william278.huskhomes.listener.ListenerProvider;
import net.william278.huskhomes.manager.ManagerProvider;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.network.BrokerProvider;
import net.william278.huskhomes.network.Message;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.random.RandomTeleportProvider;
import net.william278.huskhomes.user.ConsoleUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.UserProvider;
import net.william278.huskhomes.util.*;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Represents a cross-platform instance of the plugin.
 */
public interface HuskHomes extends Task.Supplier, EventDispatcher, SavePositionProvider, TransactionResolver,
        ConfigProvider, DatabaseProvider, BrokerProvider, MetaProvider, HookProvider, RandomTeleportProvider,
        AudiencesProvider, UserProvider, TextValidator, ManagerProvider, ListenerProvider, CommandProvider,
        GsonProvider, DumpProvider {

    // Share of the connection pool pre-login lookups may occupy; the rest stays free for gameplay
    int INBOUND_PREFETCH_POOL_SHARE = 3;

    @NotNull
    Map<String, RtpOptions> getPendingRtpOptions();

    default void rememberPendingRtpOptions(@NotNull String username, @NotNull RtpOptions options) {
        final String key = username.toLowerCase(Locale.ENGLISH);
        getPendingRtpOptions().put(key, options);
        runAsyncDelayed(() -> getPendingRtpOptions().remove(key, options), 20L * 60L);
    }

    @NotNull
    default Optional<RtpOptions> takePendingRtpOptions(@NotNull String username) {
        return Optional.ofNullable(getPendingRtpOptions().remove(username.toLowerCase(Locale.ENGLISH)));
    }

    // Check huskhomes.server.<server_id>, if cross_server.permission_restrict_servers is on.
    // Must run on the user's own server; permissions aren't resolvable remotely
    default boolean canAccessServer(@NotNull OnlineUser user, @NotNull String server) {
        if (!getSettings().getCrossServer().isPermissionRestrictServers()
                || server.equals(getServerName())) {
            return true;
        }
        return user.hasPermission(String.format("huskhomes.server.%s", server.toLowerCase(Locale.ENGLISH)))
                || user.hasPermission("huskhomes.server.*");
    }

    @NotNull
    default Broker.ServerState getServerState(@NotNull String server) {
        if (server.equals(getServerName())) {
            return Broker.ServerState.READY;
        }
        return getBroker().map(broker -> broker.getServerState(server)).orElse(Broker.ServerState.UNKNOWN);
    }

    // Pre-login lookups, by user UUID. An empty Optional records that the lookup ran and found nothing, so the
    // join handler knows not to repeat it
    @NotNull
    Map<UUID, Optional<Database.PendingTeleport>> getPendingInboundTeleports();

    // Pre-login lookups currently in flight
    @NotNull
    AtomicInteger getInboundPrefetchCount();

    // Concurrent pre-login lookups allowed, as a share of the configured pool. Read per call, so a reload applies
    default int getInboundPrefetchLimit() {
        return Math.max(1, getSettings().getDatabase().getPoolOptions().getSize() / INBOUND_PREFETCH_POOL_SHARE);
    }

    // Read a connecting user's pending teleport during the login handshake, while there is time to spare.
    // Under a burst of joins this gives up rather than holding up logins; the join handler then reads it instead
    @Blocking
    default void prefetchInboundTeleport(@NotNull UUID uuid) {
        if (!getSettings().getCrossServer().isEnabled()) {
            return;
        }
        final AtomicInteger inFlight = getInboundPrefetchCount();
        if (inFlight.incrementAndGet() > getInboundPrefetchLimit()) {
            inFlight.decrementAndGet();
            return;
        }
        try {
            final Optional<Database.PendingTeleport> pending = getDatabase().getPendingTeleport(uuid);
            getPendingInboundTeleports().put(uuid, pending);
            // Drop it if they never make it through login
            runAsyncDelayed(() -> getPendingInboundTeleports().remove(uuid, pending), 20L * 60L);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // The pre-login lookup's result: null if it never ran, an empty Optional if it ran and found nothing
    @Nullable
    default Optional<Database.PendingTeleport> takePendingInboundTeleport(@NotNull UUID uuid) {
        return getPendingInboundTeleports().remove(uuid);
    }

    // Last world per server for each online user, by user UUID; loaded on join so lookups never hit the database
    @NotNull
    Map<UUID, Map<String, World>> getLastWorldCache();

    // Open a cache session for a joining user
    @NotNull
    default Map<String, World> beginLastWorldCache(@NotNull OnlineUser user) {
        final Map<String, World> session = Maps.newConcurrentMap();
        getLastWorldCache().put(user.getUuid(), session);
        return session;
    }

    @Blocking
    default Optional<Map<String, World>> cacheLastWorlds(@NotNull OnlineUser user,
                                                         @NotNull Map<String, World> session,
                                                         @NotNull Map<String, World> stored) {
        final Map<String, World> worlds = Maps.newConcurrentMap();
        worlds.putAll(stored);

        worlds.putAll(session);
        return getLastWorldCache().replace(user.getUuid(), session, worlds)
                ? Optional.of(worlds) : Optional.empty();
    }

    // Write this server's world from the session; serialized so a stale async write can't win
    @Blocking
    default void persistLastWorld(@NotNull OnlineUser user, @NotNull Map<String, World> session) {
        synchronized (session) {
            if (getLastWorldCache().get(user.getUuid()) != session) {
                return;
            }
            Optional.ofNullable(session.get(getServerName()))
                    .ifPresent(world -> getDatabase().setLastWorld(user, getServerName(), world));
        }
    }

    // Drop entries for worlds this server no longer has. Catches deletions while down, which fire no unload event
    @Blocking
    default void reconcileLastWorlds(@NotNull Set<UUID> loadedWorlds) {
        final int pruned = getDatabase().deleteLastWorldsExcept(getServerName(), loadedWorlds);
        getLastWorldCache().values().forEach(worlds -> worlds.entrySet()
                .removeIf(entry -> entry.getKey().equals(getServerName())
                        && !loadedWorlds.contains(entry.getValue().getUuid())));
        if (pruned > 0) {
            log(Level.INFO, String.format("Pruned %s last world entr%s for worlds that no longer exist",
                    pruned, pruned == 1 ? "y" : "ies"));
            propagateLastWorldInvalidation();
        }
    }

    // Drop rows and cached entries for a world deleted from this server
    @Blocking
    default void forgetDeletedWorld(@NotNull World world) {
        getDatabase().deleteLastWorlds(getServerName(), world.getUuid());
        getLastWorldCache().values().forEach(worlds -> worlds.entrySet()
                .removeIf(entry -> entry.getKey().equals(getServerName())
                        && entry.getValue().getUuid().equals(world.getUuid())));
        log(Level.INFO, String.format("Forgot last world entries for deleted world %s", world.getName()));
        propagateLastWorldInvalidation();
    }

    // Tell other servers our last worlds changed; their caches only refill on join, so they'd otherwise go stale
    default void propagateLastWorldInvalidation() {
        getBroker().ifPresent(broker -> {
            final OnlineUser sender = getOnlineUsers().stream().findAny().orElse(null);
            if (sender == null && !broker.canSendWithoutPlayer()) {
                return;
            }
            Message.builder()
                    .type(Message.MessageType.INVALIDATE_LAST_WORLDS)
                    .target(Message.TARGET_ALL, Message.TargetType.SERVER)
                    .build().send(broker, sender);
        });
    }

    // A server reconciles its worlds before marking itself ready, so on ready: drop what we cached, then re-read
    default void handleServerReady(@NotNull String server) {
        if (server.equals(getServerName())) {
            return;
        }
        getLastWorldCache().values().forEach(worlds -> worlds.remove(server));
        runAsync(() -> refreshLastWorlds(server));
    }

    // Re-read a server's stored last worlds into the cache; a pruned entry is removed, not left stale
    @Blocking
    default void refreshLastWorlds(@NotNull String server) {
        if (server.equals(getServerName())) {
            return;
        }
        for (OnlineUser user : getOnlineUsers()) {
            final Map<String, World> cached = getLastWorldCache().get(user.getUuid());
            if (cached == null) {
                continue;
            }
            final World world = getDatabase().getLastWorlds(user).get(server);
            if (world == null) {
                cached.remove(server);
            } else {
                cached.put(server, world);
            }
        }
    }

    // Get the world a user was last in on a server
    default Optional<World> getLastWorld(@NotNull OnlineUser user, @NotNull String server) {
        // Cache is only written on join and quit, so use the live world for this server
        if (server.equals(getServerName())) {
            return Optional.of(user.getPosition().getWorld());
        }
        if (getBroker().map(broker -> broker.getServerState(server) != Broker.ServerState.READY).orElse(false)) {
            return Optional.empty();
        }
        return Optional.ofNullable(getLastWorldCache().get(user.getUuid())).map(worlds -> worlds.get(server));
    }

    // Find which server a globally-online user is connected to
    default Optional<String> findUserServer(@NotNull String username) {
        if (getOnlineUser(username).isPresent()) {
            return Optional.of(getServerName());
        }
        return getGlobalUserList().entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(u -> u.getName().equalsIgnoreCase(username)))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Load plugin systems.
     *
     * @since 4.8
     */
    default void load() {
        try {
            loadConfigs();
            setHooks(Sets.newHashSet());
            loadHooks(PluginHook.Register.ON_LOAD);
            registerHooks(PluginHook.Register.ON_LOAD);
        } catch (ConfigurationException e) {
            log(Level.SEVERE, "Failed to load the HuskHomes config.yml file! HuskHomes will be disabled.\n" +
                    "Please regenerate your HuskHomes config.yml file (delete it and restart your server.)", e);
            disablePlugin();
            return;
        } catch (Throwable e) {
            log(Level.SEVERE, "An error occurred whilst loading HuskHomesX", e);
            disablePlugin();
            return;
        }
        log(Level.INFO, String.format("Successfully loaded HuskHomesX v%s", getPluginVersion()));
    }

    /**
     * Enable all plugin systems.
     *
     * @since 4.8
     */
    default void enable() {
        try {
            loadDatabase();
            loadBroker();
            loadManager();
            loadRandomTeleportEngine();
            loadListeners();
            loadHooks(PluginHook.Register.ON_ENABLE);
            registerHooks(PluginHook.Register.ON_ENABLE);
            loadAPI();
        } catch (Throwable e) {
            log(Level.SEVERE, "An error occurred whilst enabling HuskHomesX", e);
            disablePlugin();
            return;
        }
        log(Level.INFO, String.format("Successfully enabled HuskHomesX v%s", getPluginVersion()));
        loadAfterLoadHooks();

    }

    /**
     * Shutdown plugin subsystems.
     *
     * @since 4.8
     */
    default void shutdown() {
        log(Level.INFO, String.format("Disabling HuskHomesX v%s...", getPluginVersion()));
        try {
            unloadHooks(PluginHook.Register.values());
            closeBroker();
            closeDatabase();
            cancelTasks();
            unloadAPI();
        } catch (Throwable e) {
            log(Level.SEVERE, "An error occurred whilst disabling HuskHomesX", e);
        }
        log(Level.INFO, String.format("Successfully disabled HuskHomesX v%s", getPluginVersion()));
    }

    /**
     * Register the API instance.
     *
     * @since 4.8
     */
    void loadAPI();

    /**
     * Unregister the API instance.
     *
     * @since 4.8
     */
    default void unloadAPI() {
        BaseHuskHomesAPI.unregister();
    }

    /**
     * Disable the plugin.
     *
     * @since 4.8
     */
    void disablePlugin();

    /**
     * Get the user representing the server console.
     *
     * @return the {@link ConsoleUser}
     */
    @NotNull
    ConsoleUser getConsole();

    /**
     * The canonical spawn {@link Position} of this server, if it has been set.
     *
     * @return the {@link Position} of the spawn, or an empty {@link Optional} if it has not been set
     */
    default Optional<Position> getSpawn() {
        final Settings.CrossServerSettings crossServer = getSettings().getCrossServer();
        return crossServer.isEnabled() && crossServer.getGlobalSpawn().isEnabled()
                ? getDatabase().getWarp(crossServer.getGlobalSpawn().getWarpName()).map(warp -> (Position) warp)
                : getServerSpawn().map(spawn -> spawn.getPosition(getServerName()));
    }

    /**
     * Update the spawn position of a world on the server.
     *
     * @param position The new spawn world and coordinates.
     */
    void setWorldSpawn(@NotNull Position position);

    void setServerName(@NotNull Server serverName);

    /**
     * Returns a resource read from the plugin resources folder.
     *
     * @param name the name of the resource
     * @return the resource read as an {@link InputStream}
     */
    @Nullable
    InputStream getResource(@NotNull String name);

    /**
     * Returns a list of worlds on the server.
     *
     * @return a list of worlds on the server
     */
    @NotNull
    List<World> getWorlds();

    /**
     * Find a loaded world by its platform name or namespaced key (e.g. {@code minecraft:overworld}). A query with
     * no namespace is resolved against the {@code minecraft} namespace.
     *
     * @param query the world name or namespaced key to look for
     * @return the matching world, if one is loaded
     */
    default Optional<World> findWorld(@NotNull String query) {
        return getWorlds().stream().filter(world -> world.matches(query)).findFirst();
    }

    /**
     * Returns a list of enabled commands.
     *
     * @return A list of registered and enabled {@link Command}s
     */
    @NotNull
    List<Command> getCommands();

    default <T extends Command> Optional<T> getCommand(@NotNull Class<T> type) {
        return getCommands().stream()
                .filter(command -> command.getClass() == type)
                .findFirst()
                .map(type::cast);
    }

    @NotNull
    Set<UUID> getCurrentlyOnWarmup();

    /**
     * Returns a set of users who have taken damage during a teleport warmup.
     *
     * @return a set of damaged users on warmup
     * @since 4.9.11
     */
    @NotNull
    Set<UUID> getWarmupDamagedUsers();

    /**
     * Returns if the given user is currently warming up to teleport to a home.
     *
     * @param userUuid The user to check.
     * @return If the user is currently warming up.
     */
    default boolean isWarmingUp(@NotNull UUID userUuid) {
        return this.getCurrentlyOnWarmup().contains(userUuid);
    }

    /**
     * Returns if the given user has taken damage during their teleport warmup.
     *
     * @param userUuid The user to check.
     * @return {@code true} if the user has taken damage while warming up
     * @since 4.9.11
     */
    default boolean hasTakenWarmupDamage(@NotNull UUID userUuid) {
        return getWarmupDamagedUsers().contains(userUuid);
    }

    /**
     * Log a message to the console.
     *
     * @param level      the level to log at
     * @param message    the message to log
     * @param exceptions any exceptions to log
     */
    void log(@NotNull Level level, @NotNull String message, Throwable... exceptions);

    /**
     * Create a resource key namespaced with the plugin id.
     *
     * @param data the string ID elements to join
     * @return the key
     */
    @NotNull
    default Key getKey(@NotNull String... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("Cannot create a key with no data");
        }
        @Subst("foo") final String joined = String.join("/", data);
        return Key.key("huskhomes", joined);
    }

}
