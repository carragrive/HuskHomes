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

package net.william278.huskhomes.database;

import de.exlll.configlib.YamlConfigurations;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.ConfigProvider;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.position.PositionMeta;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.user.SavedUser;
import net.william278.huskhomes.user.User;
import net.william278.huskhomes.util.TransactionResolver;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Database#getJoinData(User)}, which reads a joining user's row, last worlds and homes over one
 * connection, and the {@link Database#ensureUser(User, Database.JoinData)} overload that reuses what it read.
 */
class JoinDataTests {

    private static final UUID USER_UUID = UUID.fromString("3e77a270-bed6-3e5a-a4c6-df0b526d3bac");
    private static final User USER = User.of(USER_UUID, "Player");

    @TempDir
    private Path configDirectory;
    private Database database;

    @BeforeEach
    void setUp() {
        database = new H2Database(stubPlugin(configDirectory));
        database.initialize();
        assertTrue(database.isLoaded(), "database failed to initialize");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void readsTheUserRowLastWorldsAndHomes() {
        database.ensureUser(USER);
        database.setLastWorld(USER, "serverB", World.from("world_nether", UUID.randomUUID(), null,
                "minecraft:the_nether"));
        database.saveHome(home("castle"));
        database.saveHome(home("tower"));

        final Database.JoinData joinData = database.getJoinData(USER);

        assertTrue(joinData.getUser().isPresent());
        assertEquals("Player", joinData.getUser().map(SavedUser::getUsername).orElseThrow());
        assertEquals(Map.of("serverB", "minecraft:the_nether"), joinData.lastWorlds().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getKey())));
        assertEquals(List.of("castle", "tower"), joinData.homes().stream().map(Home::getName).sorted().toList());
    }

    @Test
    void matchesReadingEachTableSeparately() {
        database.ensureUser(USER);
        database.setLastWorld(USER, "serverB", World.from("world", UUID.randomUUID()));
        database.saveHome(home("castle"));

        final Database.JoinData joinData = database.getJoinData(USER);

        assertEquals(database.getUser(USER_UUID).orElseThrow().getUsername(),
                joinData.getUser().orElseThrow().getUsername());
        assertEquals(database.getLastWorlds(USER).keySet(), joinData.lastWorlds().keySet());
        assertEquals(database.getHomes(USER).stream().map(Home::getName).toList(),
                joinData.homes().stream().map(Home::getName).toList());
    }

    @Test
    void returnsAnEmptyResultForAUserWhoHasNeverJoined() {
        final Database.JoinData joinData = database.getJoinData(User.of(UUID.randomUUID(), "Stranger"));

        assertNull(joinData.user());
        assertTrue(joinData.getUser().isEmpty());
        assertTrue(joinData.lastWorlds().isEmpty());
        assertTrue(joinData.homes().isEmpty());
    }

    @Test
    void readsAKnownUserWithNoLastWorldsOrHomes() {
        database.ensureUser(USER);

        final Database.JoinData joinData = database.getJoinData(USER);

        assertTrue(joinData.getUser().isPresent());
        assertTrue(joinData.lastWorlds().isEmpty());
        assertTrue(joinData.homes().isEmpty());
    }

    @Test
    void ensureUserSkipsTheWriteWhenTheBatchedRowIsCurrent() {
        database.ensureUser(USER);

        assertFalse(database.ensureUser(USER, database.getJoinData(USER)));
    }

    @Test
    void ensureUserWritesForAUserTheBatchDidNotFind() {
        final Database.JoinData joinData = database.getJoinData(USER);
        assertTrue(joinData.getUser().isEmpty());

        assertTrue(database.ensureUser(USER, joinData));
        assertEquals("Player", database.getUser(USER_UUID).orElseThrow().getUsername());
    }

    @Test
    void ensureUserWritesWhenTheNameHasChangedSinceTheBatchRead() {
        database.ensureUser(USER);
        final Database.JoinData stale = database.getJoinData(USER);
        final User renamed = User.of(USER_UUID, "Renamed");

        assertTrue(database.ensureUser(renamed, stale));
        assertEquals("Renamed", database.getUser(USER_UUID).orElseThrow().getUsername());
    }

    @Test
    void capsExistingCooldownAtTheConfiguredDuration() {
        database.ensureUser(USER);
        final Instant before = Instant.now();
        database.setCooldown(TransactionResolver.Action.RANDOM_TELEPORT, USER, before.plusSeconds(3600));

        final Instant expiry = database.getCooldown(TransactionResolver.Action.RANDOM_TELEPORT, USER).orElseThrow();

        assertTrue(expiry.isAfter(before.plusSeconds(599)));
        assertTrue(expiry.isBefore(before.plusSeconds(601)));
    }

    @NotNull
    private Home home(@NotNull String name) {
        return Home.from(
                0, 64, 0, 0, 0,
                World.from("world", UUID.randomUUID()),
                "serverA",
                PositionMeta.from(name, "", Instant.now(), null),
                UUID.randomUUID(),
                USER,
                false
        );
    }

    /**
     * A {@link HuskHomes} that answers only what the database needs: its config directory, the settings it reads
     * table names from, and the bundled schema scripts. Everything else is unused here and returns a default.
     */
    @NotNull
    private static HuskHomes stubPlugin(@NotNull Path configDirectory) {
        final Settings settings = YamlConfigurations.read(
                new ByteArrayInputStream("language: en-gb".getBytes(StandardCharsets.UTF_8)),
                Settings.class,
                ConfigProvider.YAML_CONFIGURATION_PROPERTIES.build()
        );
        final InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getSettings" -> settings;
            case "getConfigDirectory" -> configDirectory;
            case "getResource" -> resource((String) args[0]);
            case "getServerName" -> "serverA";
            default -> defaultValue(method.getReturnType());
        };
        return (HuskHomes) Proxy.newProxyInstance(
                JoinDataTests.class.getClassLoader(), new Class<?>[]{HuskHomes.class}, handler
        );
    }

    private static InputStream resource(@NotNull String name) {
        return JoinDataTests.class.getClassLoader().getResourceAsStream(name);
    }

    private static Object defaultValue(@NotNull Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        return type == boolean.class ? false : type == long.class ? 0L : type == double.class ? 0d : 0;
    }
}
