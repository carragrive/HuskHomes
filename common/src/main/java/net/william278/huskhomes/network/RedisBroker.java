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

package net.william278.huskhomes.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.AllArgsConstructor;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.Task;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.Pool;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static net.william278.huskhomes.config.Settings.CrossServerSettings.RedisSettings;

/**
 * Redis PubSub broker implementation.
 */
public class RedisBroker extends PluginMessageBroker {

    private static final int STATUS_TTL_SECONDS = 15;
    private static final long STATUS_HEARTBEAT_TICKS = 100L;

    private final Subscriber subscriber;
    private final UUID instanceId = UUID.randomUUID();
    private final Map<String, StatusLease> serverStates = new ConcurrentHashMap<>();
    private final Object statusLock = new Object();
    private Task.Repeating heartbeatTask;
    private volatile ServerState state = ServerState.STARTING;
    private volatile boolean closed;

    public RedisBroker(@NotNull HuskHomes plugin) {
        super(plugin);
        this.subscriber = new Subscriber(this, getSubChannelId());
    }

    @Blocking
    @Override
    public void initialize() throws IllegalStateException {
        // Initialize plugin message channels
        super.initialize();

        // Establish a connection with the Redis server
        final Pool<Jedis> jedisPool = getJedisPool(plugin.getSettings().getCrossServer().getRedis());
        try {
            jedisPool.getResource().ping();
        } catch (JedisException e) {
            throw new IllegalStateException("Failed to establish connection with Redis. "
                                            + "Please check the supplied credentials in the config file", e);
        }

        // Subscribe using a thread (rather than a task)
        subscriber.enable(jedisPool);
        final Thread thread = new Thread(subscriber::subscribe, "huskhomes:redis_subscriber");
        thread.setDaemon(true);
        thread.start();

        subscriber.claimStatus(state, instanceId);
        this.heartbeatTask = plugin.getRepeatingTask(this::publishStatus, STATUS_HEARTBEAT_TICKS);
        heartbeatTask.run();
    }

    @NotNull
    private static Pool<Jedis> getJedisPool(@NotNull RedisSettings settings) {
        // Get the Redis connection settings
        final String password = settings.getPassword();
        final String host = settings.getHost();
        final int port = settings.getPort();
        final int database = settings.getDatabase();
        final int timeout = settings.getTimeout();
        final boolean useSSL = settings.isUseSsl();

        // Create the jedis pool
        final JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxIdle(0);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);

        // Check if sentinels are to be used
        final RedisSettings.SentinelSettings sentinel = settings.getSentinel();
        Set<String> redisSentinelNodes = new HashSet<>(sentinel.getNodes());
        if (!redisSentinelNodes.isEmpty()) {
            final String sentinelPassword = sentinel.getPassword();
            return new JedisSentinelPool(
                    sentinel.getMasterName(),
                    redisSentinelNodes,
                    config,
                    timeout,
                    password.isEmpty() ? null : password,
                    sentinelPassword.isEmpty() ? null : sentinelPassword,
                    database
            );
        }

        // Otherwise, use the standard Jedis pool
        return new JedisPool(
                config,
                host,
                port,
                timeout,
                password.isEmpty() ? null : password,
                database,
                useSSL
        );
    }

    @Override
    protected void send(@NotNull Message message, @Nullable OnlineUser sender) {
        plugin.runAsync(() -> subscriber.send(message));
    }

    @Override
    @NotNull
    public ServerState getServerState(@NotNull String server) {
        if (server.equals(getServer())) {
            return state;
        }
        final StatusLease lease = serverStates.get(server);
        if (lease == null || lease.expiresAt() <= System.currentTimeMillis()) {
            serverStates.remove(server, lease);
            return ServerState.UNKNOWN;
        }
        return lease.state();
    }

    @Override
    public void markReady() {
        this.state = ServerState.READY;
        publishStatus();
    }

    @Override
    public boolean canSendWithoutPlayer() {
        return true;
    }

    private void publishStatus() {
        synchronized (statusLock) {
            if (!closed && subscriber.refreshStatus(state, instanceId)) {
                updateServerStatus(getServer(), state, instanceId);
            }
        }
    }

    // Record a server's readiness. A remote server becoming ready, or returning under a new instance ID, restarted
    private void updateServerStatus(@NotNull String server, @NotNull ServerState state, @NotNull UUID instance) {
        final StatusLease previous = serverStates.put(server, new StatusLease(
                state,
                instance,
                System.currentTimeMillis() + STATUS_TTL_SECONDS * 1000L
        ));
        if (server.equals(getServer()) || state != ServerState.READY) {
            return;
        }
        if (previous == null || previous.state() != ServerState.READY || !previous.instance().equals(instance)) {
            plugin.handleServerReady(server);
        }
    }

    @Override
    @Blocking
    public void close() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        this.state = ServerState.STOPPING;
        synchronized (statusLock) {
            if (closed) {
                return;
            }
            subscriber.refreshStatus(state, instanceId);
            closed = true;
        }
        super.close();
        subscriber.disable();
    }

    private record StatusLease(@NotNull ServerState state, @NotNull UUID instance, long expiresAt) {
    }


    @AllArgsConstructor
    private static class Subscriber extends JedisPubSub {
        private static final int RECONNECTION_TIME = 8000;

        private final RedisBroker broker;
        private final String channel;

        private Pool<Jedis> jedisPool;
        private boolean enabled;
        private boolean reconnected;
        private final AtomicBoolean statusWriteFailed = new AtomicBoolean();

        private Subscriber(@NotNull RedisBroker broker, @NotNull String channel) {
            this.broker = broker;
            this.channel = channel;
        }

        private void enable(@NotNull Pool<Jedis> jedisPool) {
            this.jedisPool = jedisPool;
            this.enabled = true;
        }

        @Blocking
        private void disable() {
            this.enabled = false;
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
            this.unsubscribe();
        }

        @Blocking
        public void send(@NotNull Message message) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(channel, broker.plugin.getGson().toJson(message));
            }
        }

        private void claimStatus(@NotNull ServerState state, @NotNull UUID instanceId) {
            writeStatus(state, instanceId, true);
        }

        private boolean refreshStatus(@NotNull ServerState state, @NotNull UUID instanceId) {
            return writeStatus(state, instanceId, false);
        }

        private boolean writeStatus(@NotNull ServerState state, @NotNull UUID instanceId, boolean claim) {
            final String key = "%s:server:%s:status".formatted(channel, broker.getServer());
            final String value = "%s|%s".formatted(instanceId, state);
            try (Jedis jedis = jedisPool.getResource()) {
                final boolean updated;
                if (claim) {
                    jedis.setex(key, STATUS_TTL_SECONDS, value);
                    updated = true;
                } else {
                    final Object result = jedis.eval("""
                            local current = redis.call('get', KEYS[1])
                            if not current or string.sub(current, 1, string.len(ARGV[1])) == ARGV[1] then
                                redis.call('setex', KEYS[1], ARGV[2], ARGV[3])
                                return 1
                            end
                            return 0
                            """, 1, key, instanceId.toString(), Integer.toString(STATUS_TTL_SECONDS), value);
                    updated = Long.valueOf(1L).equals(result);
                }
                if (updated) {
                    final JsonObject status = new JsonObject();
                    status.addProperty("server", broker.getServer());
                    status.addProperty("state", state.name());
                    status.addProperty("instance", instanceId.toString());
                    jedis.publish(statusChannel(), status.toString());
                }
                if (statusWriteFailed.getAndSet(false)) {
                    broker.plugin.log(Level.INFO, "Redis server readiness publishing recovered");
                }
                return updated;
            } catch (JedisException e) {
                if (statusWriteFailed.compareAndSet(false, true)) {
                    broker.plugin.log(Level.WARNING, "Failed to publish Redis server readiness", e);
                }
                return false;
            }
        }

        @Blocking
        private void subscribe() {
            while (enabled && !Thread.interrupted() && jedisPool != null && !jedisPool.isClosed()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    if (reconnected) {
                        broker.plugin.log(Level.INFO, "Redis connection is alive again");
                    }

                    // Subscribe to channel and lock the thread
                    jedis.subscribe(this, channel, statusChannel());
                } catch (Throwable t) {
                    // Thread was unlocked due error
                    onThreadUnlock(t);
                }
            }
        }

        private void onThreadUnlock(@NotNull Throwable t) {
            if (!enabled) {
                return;
            }

            if (reconnected) {
                broker.plugin.log(Level.WARNING, "Redis Server connection lost. Attempting reconnect in %ss..."
                        .formatted(RECONNECTION_TIME / 1000), t);
            }
            try {
                this.unsubscribe();
            } catch (Throwable ignored) {
                // empty catch
            }

            // Make an instant subscribe if occurs any error on initialization
            if (!reconnected) {
                reconnected = true;
            } else {
                try {
                    Thread.sleep(RECONNECTION_TIME);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void onMessage(@NotNull String channel, @NotNull String encoded) {
            if (channel.equals(statusChannel())) {
                try {
                    final JsonObject status = JsonParser.parseString(encoded).getAsJsonObject();
                    broker.updateServerStatus(
                            status.get("server").getAsString(),
                            ServerState.valueOf(status.get("state").getAsString()),
                            UUID.fromString(status.get("instance").getAsString())
                    );
                } catch (RuntimeException e) {
                    broker.plugin.log(Level.WARNING, "Received invalid Redis server readiness update");
                }
                return;
            }

            final Message message;
            try {
                message = broker.plugin.getMessageFromJson(encoded);
            } catch (Exception e) {
                broker.plugin.log(Level.WARNING, "Failed to decode message from Redis: " + e.getMessage());
                return;
            }

            if (message.getTargetType() == Message.TargetType.PLAYER) {
                broker.plugin.getOnlineUsers().stream()
                        .filter(online -> message.getTarget().equals(Message.TARGET_ALL)
                                          || online.getName().equals(message.getTarget()))
                        .forEach(receiver -> broker.handle(receiver, message));
                return;
            }

            if (message.getTarget().equals(broker.plugin.getServerName())
                || message.getTarget().equals(Message.TARGET_ALL)) {

                if (message.getType() == Message.MessageType.REQUEST_RTP_LOCATION) {
                    broker.handleRtpRequestLocation(message);
                    return;
                }

                broker.plugin.getOnlineUsers().stream()
                        .findAny()
                        .ifPresent(receiver -> broker.handle(receiver, message));
            }
        }

        @NotNull
        private String statusChannel() {
            return channel + ":server_status";
        }
    }

}
