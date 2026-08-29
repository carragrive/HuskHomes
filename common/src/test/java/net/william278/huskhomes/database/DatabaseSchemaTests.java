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

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaTests {

    private static final String LAST_WORLD_TABLE = Database.Table.LAST_WORLD_DATA.getDefaultName();
    private static final String PLAYER_TABLE = Database.Table.PLAYER_DATA.getDefaultName();

    @ParameterizedTest(name = "{0} fresh schema")
    @MethodSource("dialects")
    void freshSchemaCreatesLastWorldStructures(@NotNull Dialect dialect) throws SQLException, IOException {
        try (Connection connection = dialect.open()) {
            executeResource(connection, "database/%s_schema.sql".formatted(dialect.name()));
            assertLastWorldSchema(connection);
            assertPlayerDeleteCascades(connection);
        }
    }

    @ParameterizedTest(name = "{0} migrations")
    @MethodSource("dialects")
    void migrationsCreateLastWorldStructures(@NotNull Dialect dialect) throws SQLException, IOException {
        try (Connection connection = dialect.open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE %s (uuid %s PRIMARY KEY, username VARCHAR(16) NOT NULL)"
                    .formatted(PLAYER_TABLE, dialect.uuidType()));
            executeResource(connection, "database/migrations/1-%s-add_last_worlds_table.sql".formatted(dialect.name()));
            executeResource(connection, "database/migrations/2-%s-add_last_world_uuid.sql".formatted(dialect.name()));
            executeResource(connection, "database/migrations/3-%s-add_last_world_index.sql".formatted(dialect.name()));
            executeResource(connection, "database/migrations/3-%s-add_last_world_index.sql".formatted(dialect.name()));
            assertLastWorldSchema(connection);
            assertPlayerDeleteCascades(connection);
        }
    }

    @ParameterizedTest(name = "{0} foreign-key migration")
    @MethodSource("dialects")
    void foreignKeyMigrationDeletesOrphansAndAddsCascade(@NotNull Dialect dialect)
            throws SQLException, IOException {
        try (Connection connection = dialect.open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE %s (uuid %s PRIMARY KEY, username VARCHAR(16) NOT NULL)"
                    .formatted(PLAYER_TABLE, dialect.uuidType()));
            statement.execute("""
                    CREATE TABLE %s (
                        user_uuid CHAR(36) NOT NULL,
                        server_name VARCHAR(255) NOT NULL,
                        world_name VARCHAR(255) NOT NULL,
                        world_uuid CHAR(36),
                        world_key VARCHAR(255),
                        PRIMARY KEY (user_uuid, server_name)
                    )
                    """.formatted(LAST_WORLD_TABLE));
            statement.execute(("INSERT INTO %s (user_uuid, server_name, world_name, world_uuid) "
                               + "VALUES ('00000000-0000-0000-0000-00000000c0de', 'server', 'world', "
                               + "'00000000-0000-0000-0000-00000000dead')").formatted(LAST_WORLD_TABLE));
            executeResource(connection, "database/migrations/3-%s-add_last_world_index.sql".formatted(dialect.name()));
            executeResource(connection,
                    "database/migrations/4-%s-add_last_world_user_fk.sql".formatted(dialect.name()));

            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + LAST_WORLD_TABLE)) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            assertLastWorldSchema(connection);
            assertPlayerDeleteCascades(connection);
        }
    }

    private static void assertLastWorldSchema(@NotNull Connection connection) throws SQLException {
        boolean worldUuidFound = false;
        boolean indexFound = false;
        for (String table : new String[]{LAST_WORLD_TABLE, LAST_WORLD_TABLE.toUpperCase(Locale.ENGLISH)}) {
            try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, null)) {
                while (columns.next()) {
                    if ("world_uuid".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        worldUuidFound = true;
                    }
                }
            }
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
                while (indexes.next()) {
                    if ((LAST_WORLD_TABLE + "_server_world").equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        indexFound = true;
                    }
                }
            }
        }
        assertTrue(worldUuidFound);
        assertTrue(indexFound);
    }

    private static void assertPlayerDeleteCascades(@NotNull Connection connection) throws SQLException {
        final UUID player = UUID.randomUUID();
        final UUID world = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO %s (uuid, username) VALUES ('%s', 'Player')"
                    .formatted(PLAYER_TABLE, player));
            statement.execute(("INSERT INTO %s (user_uuid, server_name, world_name, world_uuid) "
                               + "VALUES ('%s', 'server', 'world', '%s')")
                    .formatted(LAST_WORLD_TABLE, player, world));
            statement.execute("DELETE FROM %s WHERE uuid = '%s'".formatted(PLAYER_TABLE, player));
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + LAST_WORLD_TABLE)) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private static void executeResource(@NotNull Connection connection, @NotNull String resource)
            throws IOException, SQLException {
        try (InputStream stream = Objects.requireNonNull(
                DatabaseSchemaTests.class.getClassLoader().getResourceAsStream(resource), resource)) {
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (Database.Table table : Database.Table.values()) {
                script = script.replace("%%%s%%".formatted(table.name().toLowerCase(Locale.ENGLISH)),
                        table.getDefaultName());
            }
            try (Statement statement = connection.createStatement()) {
                for (String sql : Arrays.stream(script.split(";")).filter(value -> !value.isBlank()).toList()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private static Stream<Arguments> dialects() {
        return Stream.of(
                Arguments.of(new Dialect("sqlite", "jdbc:sqlite::memory:", "char(36)")),
                Arguments.of(new Dialect("h2", "jdbc:h2:mem:" + UUID.randomUUID(), "UUID"))
        );
    }

    private record Dialect(@NotNull String name, @NotNull String url, @NotNull String uuidType) {

        @NotNull
        private Connection open() throws SQLException {
            final Connection connection = DriverManager.getConnection(url);
            if (name.equals("sqlite")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
            }
            return connection;
        }

        @Override
        public String toString() {
            return name;
        }

    }

}
