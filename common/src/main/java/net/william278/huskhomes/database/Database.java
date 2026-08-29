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

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.Server;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.SavedPosition;
import net.william278.huskhomes.position.Warp;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.teleport.Teleport;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.SavedUser;
import net.william278.huskhomes.user.User;
import net.william278.huskhomes.util.TransactionResolver;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@AllArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class Database {

    protected HuskHomes plugin;

    @Getter
    @Setter
    private boolean loaded;

    protected Database(@NotNull HuskHomes plugin) {
        this.plugin = plugin;
    }

    /**
     * Get the schema statements from the schema file.
     *
     * @return the {@link #format formatted} schema statements
     */
    @NotNull
    protected final String[] getScript(@NotNull String name) {
        name = (name.startsWith("database/") ? "" : "database/") + name + (name.endsWith(".sql") ? "" : ".sql");
        try (InputStream file = Objects.requireNonNull(plugin.getResource(name), "Invalid script %s".formatted(name))) {
            @Language("SQL") final String schema = new String(file.readAllBytes(), StandardCharsets.UTF_8);
            // Drivers reject the empty statement a trailing separator leaves behind
            return Arrays.stream(format(schema).split(";"))
                    .filter(statement -> !statement.isBlank())
                    .toArray(String[]::new);
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to load database schema", e);
        }
        return new String[0];
    }

    protected abstract void executeScript(@NotNull Connection connection, @NotNull String name) throws SQLException;

    /**
     * Format a string for use in an SQL query.
     *
     * @param statement The SQL statement to format
     * @return The formatted SQL statement
     */
    @NotNull
    protected final String format(@NotNull @Language("SQL") String statement) {
        final Pattern pattern = Pattern.compile("%(\\w+)%");
        final Matcher matcher = pattern.matcher(statement);
        final StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            final Table table = Table.match(matcher.group(1));
            matcher.appendReplacement(sb, plugin.getSettings().getDatabase().getTableName(table));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Backup a flat file database.
     *
     * @param file the file to back up
     */
    protected final void backupFlatFile(@NotNull Path file) {
        if (!file.toFile().exists()) {
            return;
        }

        final Path backup = file.getParent().resolve(String.format("%s.bak", file.getFileName().toString()));
        try {
            final File backupFile = backup.toFile();
            if (!backupFile.exists() || backupFile.delete()) {
                Files.copy(file, backup);
            }
        } catch (IOException e) {
            plugin.log(Level.WARNING, "Failed to backup flat file database", e);
        }
    }

    /**
     * Initialize the database connection.
     *
     * @throws IllegalStateException if the database initialization fails
     */
    public abstract void initialize() throws IllegalStateException;

    /**
     * Check if the database has been created.
     *
     * @return {@code true} if the database has been created; {@code false} otherwise
     */
    public abstract boolean isCreated();

    /**
     * Perform database migrations.
     *
     * @param connection the database connection
     * @throws SQLException if an SQL error occurs during migration
     */
    protected final void performMigrations(@NotNull Connection connection, @NotNull Type type) throws SQLException {
        final int currentVersion = getSchemaVersion();
        final int latestVersion = Migration.getLatestVersion();
        if (currentVersion < latestVersion) {
            plugin.log(Level.INFO, "Performing database migrations (Target version: v" + latestVersion + ")");
            int appliedVersion = currentVersion;
            for (Migration migration : Migration.getOrderedMigrations()) {
                if (!migration.isSupported(type)) {
                    continue;
                }
                if (migration.getVersion() > currentVersion) {
                    if (isMigrationApplied(connection, migration)) {
                        appliedVersion = migration.getVersion();
                        continue;
                    }
                    try {
                        plugin.log(Level.INFO, "Performing database migration: " + migration.getMigrationName()
                                               + " (v" + migration.getVersion() + ")");
                        executeScript(connection, "migrations/%s-%s-%s.sql".formatted(
                                migration.getVersion(),
                                type.name().toLowerCase(Locale.ENGLISH),
                                migration.getMigrationName()
                        ));
                        appliedVersion = migration.getVersion();
                    } catch (SQLException e) {
                        if (isMigrationApplied(connection, migration)) {
                            appliedVersion = migration.getVersion();
                            continue;
                        }
                        if (appliedVersion >= 0) {
                            setSchemaVersion(appliedVersion);
                        }
                        throw e;
                    }
                }
            }
            setSchemaVersion(appliedVersion);
            plugin.log(Level.INFO, "Completed database migration (Target version: v" + appliedVersion + ")");
        }
    }

    private boolean isMigrationApplied(@NotNull Connection connection, @NotNull Migration migration)
            throws SQLException {
        final String table = plugin.getSettings().getDatabase().getTableName(Table.LAST_WORLD_DATA);
        return switch (migration) {
            case ADD_LAST_WORLD_UUID -> hasColumn(connection, table, "world_uuid");
            case ADD_LAST_WORLD_INDEX -> hasIndex(connection, table, table + "_server_world");
            case ADD_LAST_WORLD_USER_FK -> hasForeignKey(
                    connection,
                    table,
                    "user_uuid",
                    plugin.getSettings().getDatabase().getTableName(Table.PLAYER_DATA)
            );
            default -> false;
        };
    }

    private boolean hasColumn(@NotNull Connection connection, @NotNull String table, @NotNull String column)
            throws SQLException {
        for (String candidate : identifierCandidates(table)) {
            try (ResultSet result = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, candidate, null)) {
                while (result.next()) {
                    if (column.equalsIgnoreCase(result.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasForeignKey(@NotNull Connection connection, @NotNull String table, @NotNull String column,
                                  @NotNull String referencedTable) throws SQLException {
        for (String candidate : identifierCandidates(table)) {
            try (ResultSet result = connection.getMetaData().getImportedKeys(
                    connection.getCatalog(), null, candidate)) {
                while (result.next()) {
                    if (column.equalsIgnoreCase(result.getString("FKCOLUMN_NAME"))
                            && referencedTable.equalsIgnoreCase(result.getString("PKTABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasIndex(@NotNull Connection connection, @NotNull String table, @NotNull String index)
            throws SQLException {
        for (String candidate : identifierCandidates(table)) {
            try (ResultSet result = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, candidate, false, false)) {
                while (result.next()) {
                    if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @NotNull
    private Set<String> identifierCandidates(@NotNull String identifier) {
        return new LinkedHashSet<>(List.of(
                identifier,
                identifier.toUpperCase(Locale.ENGLISH),
                identifier.toLowerCase(Locale.ENGLISH)
        ));
    }

    /**
     * Get the database schema version.
     *
     * @return the database schema version
     */
    public abstract int getSchemaVersion();

    /**
     * Set the database schema version.
     *
     * @param version the database schema version
     * @throws SQLException if the version could not be persisted
     */
    public abstract void setSchemaVersion(int version) throws SQLException;

    /**
     * <b>(Internal use only)</b> - Sets a position to the position table in the database.
     *
     * @param position   The {@link Position} to set
     * @param connection SQL connection
     * @return The newly inserted row ID
     * @throws SQLException if an SQL exception occurs doing this
     */
    protected abstract int setPosition(@NotNull Position position, @NotNull Connection connection) throws SQLException;

    /**
     * <b>(Internal use only)</b> - Updates position data.
     *
     * @param positionId ID of the position to update
     * @param position   the Position.at
     * @param connection SQL connection
     * @throws SQLException if an SQL exception occurs doing this
     */
    @ApiStatus.Internal
    protected abstract void updatePosition(int positionId, @NotNull Position position,
                                           @NotNull Connection connection) throws SQLException;

    /**
     * <b>(Internal use only)</b> - Sets a {@link SavedPosition} to the database.
     *
     * @param position   The {@link SavedPosition} to set
     * @param connection SQL connection
     * @return The newly inserted row ID
     * @throws SQLException if an SQL exception occurs doing this
     */
    @ApiStatus.Internal
    protected abstract int setSavedPosition(@NotNull SavedPosition position,
                                            @NotNull Connection connection) throws SQLException;

    /**
     * <b>(Internal use only)</b> - Updates a saved position metadata.
     *
     * @param savedPositionId ID of the metadata to update
     * @param savedPosition   the new saved position
     * @param connection      SQL connection
     * @throws SQLException if an SQL exception occurs doing this
     */
    @ApiStatus.Internal
    protected abstract void updateSavedPosition(int savedPositionId, @NotNull SavedPosition savedPosition,
                                                @NotNull Connection connection) throws SQLException;

    /**
     * Ensure a {@link User} has a {@link SavedUser} entry in the database and that their username is up-to-date.
     *
     * @param user The {@link User} to ensure
     */
    public abstract void ensureUser(@NotNull User user);

    // Skip the lookup ensureUser does when the join batch already read the row and nothing needs writing.
    // Returns whether it wrote, in which case the batch's copy of the row is stale
    public final boolean ensureUser(@NotNull User user, @NotNull JoinData joinData) {
        if (joinData.getUser().filter(saved -> saved.getUsername().equals(user.getName())).isPresent()) {
            return false;
        }
        ensureUser(user);
        return true;
    }

    /**
     * Get {@link SavedUser} for a user by their Minecraft username (<i>case-insensitive</i>).
     *
     * @param name Username of the {@link SavedUser} to get (<i>case-insensitive</i>)
     * @return An optional with the {@link SavedUser} present if they exist
     */
    public abstract Optional<SavedUser> getUser(@NotNull String name);

    /**
     * Get {@link SavedUser} for a user by their Minecraft account {@link UUID}.
     *
     * @param uuid Minecraft account {@link UUID} of the {@link SavedUser} to get
     * @return An optional with the {@link SavedUser} present if they exist
     */
    public abstract Optional<SavedUser> getUser(@NotNull UUID uuid);

    /**
     * Delete a {@link SavedUser} from the database.
     *
     * @param uuid The {@link UUID} of the {@link SavedUser} to delete
     */
    public abstract void deleteUser(@NotNull UUID uuid);

    /**
     * Get the currently active cooldown of a {@link User} for a specific {@link TransactionResolver.Action}.
     *
     * @return An optional with the {@link Instant} the cooldown expires, or empty if the {@link User} has no cooldown
     */
    public abstract Optional<Instant> getCooldown(@NotNull TransactionResolver.Action action, @NotNull User user);

    @NotNull
    protected final Instant readCooldownExpiry(@NotNull ResultSet resultSet,
                                               @NotNull TransactionResolver.Action action) throws SQLException {
        final Instant expiry = resultSet.getTimestamp("end_timestamp").toInstant();
        final long duration = plugin.getSettings().getCooldowns().getCooldown(action);
        if (duration <= 0) {
            return expiry;
        }
        final Instant configuredExpiry = resultSet.getTimestamp("start_timestamp").toInstant().plusSeconds(duration);
        return configuredExpiry.isBefore(expiry) ? configuredExpiry : expiry;
    }

    /**
     * Set the cooldown of a {@link User} for a specific {@link TransactionResolver.Action}.
     *
     * @param action         The {@link TransactionResolver.Action} to set the cooldown for
     * @param user           The {@link User} to set the cooldown for
     * @param cooldownExpiry The {@link Instant} the cooldown expires at
     */
    public abstract void setCooldown(@NotNull TransactionResolver.Action action, @NotNull User user,
                                     @NotNull Instant cooldownExpiry);

    /**
     * Remove the cooldown of a {@link User} for a specific {@link TransactionResolver.Action}.
     *
     * @param action The {@link TransactionResolver.Action} to remove the cooldown for
     * @param user   The {@link User} to remove the cooldown for
     */
    public abstract void removeCooldown(@NotNull TransactionResolver.Action action, @NotNull User user);

    /**
     * Get a list of {@link Home}s set by a {@link User}.
     *
     * @param user {@link User} to get the homes of
     * @return A future returning void when complete
     */
    public abstract List<Home> getHomes(@NotNull User user);

    /**
     * Get a list of all {@link Warp}s that have been set.
     *
     * @return A list containing all {@link Warp}s
     */
    public abstract List<Warp> getWarps();

    /**
     * Get a list of publicly-set {@link Warp}s on <i>this {@link Server server}</i>.
     *
     * @param plugin The plugin instance
     * @return A list containing all {@link Warp}s set on this server
     */
    @NotNull
    public final List<Warp> getLocalWarps(@NotNull HuskHomes plugin) {
        return getWarps().stream()
                .filter(warp -> warp.getServer().equals(plugin.getServerName()))
                .collect(Collectors.toList());
    }

    /**
     * Get a list of all publicly-set {@link Home}s.
     *
     * @return A list containing all publicly-set {@link Home}s
     */
    public abstract List<Home> getPublicHomes();

    /**
     * Get a list of publicly-set {@link Home}s with a specific name.
     *
     * @param name            The name of the home
     * @param caseInsensitive Whether the name lookup query should be case-insensitive
     * @return A list containing all publicly-set {@link Home}s with the specified name
     */
    public abstract List<Home> getPublicHomes(@NotNull String name, boolean caseInsensitive);

    /**
     * Get a list of publicly-set {@link Home}s with a specific name.
     *
     * @param name The name of the home
     * @return A list containing all publicly-set {@link Home}s with the specified name
     * @apiNote Whether lookup is case-sensitive is determined by the {@code general.case_insensitive_names} setting
     */
    public List<Home> getPublicHomes(@NotNull String name) {
        return getPublicHomes(name, plugin.getSettings().getGeneral().getNames().isCaseInsensitive());
    }


    /**
     * Get a list of publicly-set {@link Home}s on <i>this {@link Server server}</i>.
     *
     * @param plugin The plugin instance
     * @return A list containing all publicly-set {@link Home}s on this server
     */
    @NotNull
    public final List<Home> getLocalPublicHomes(@NotNull HuskHomes plugin) {
        return getPublicHomes().stream()
                .filter(home -> home.getServer().equals(plugin.getServerName()))
                .collect(Collectors.toList());
    }

    /**
     * Get a {@link Home} with the given name, set by the given {@link User}.
     *
     * @param user     The {@link User} who set the home
     * @param homeName The name of the home to get
     * @return An optional with the {@link Home} present if it exists
     * @apiNote Whether lookup is case-sensitive is determined by the {@code general.case_insensitive_names} setting
     */
    public final Optional<Home> getHome(@NotNull User user, @NotNull String homeName) {
        return this.getHome(user, homeName, plugin.getSettings().getGeneral().getNames().isCaseInsensitive());
    }

    /**
     * Get a {@link Home} with the given name, set by the given {@link User}.
     *
     * @param user            The {@link User} who set the home
     * @param homeName        The name of the home to get
     * @param caseInsensitive Whether the name lookup query should be case-insensitive
     * @return An optional with the {@link Home} present if it exists
     */
    public abstract Optional<Home> getHome(@NotNull User user, @NotNull String homeName, boolean caseInsensitive);

    /**
     * Get a {@link Home} by its {@link UUID unique ID}.
     *
     * @param uuid the {@link UUID} of the home to get
     * @return An optional with the {@link Home} present if it exists
     */
    public abstract Optional<Home> getHome(@NotNull UUID uuid);

    /**
     * Get a {@link Warp} with the given name.
     *
     * @param warpName The name of the warp to get
     * @return An optional with the {@link Warp} present if it exists
     * @apiNote Whether lookup is case-insensitive is determined by the {@code general.case_insensitive_names} setting
     */
    public final Optional<Warp> getWarp(@NotNull String warpName) {
        return getWarp(warpName, plugin.getSettings().getGeneral().getNames().isCaseInsensitive());
    }

    /**
     * Get a {@link Warp} with the given name.
     *
     * @param warpName        The name of the warp to get
     * @param caseInsensitive Whether the search should be case-insensitive
     * @return An optional with the {@link Warp} present if it exists
     */
    public abstract Optional<Warp> getWarp(@NotNull String warpName, boolean caseInsensitive);

    /**
     * Get a {@link Warp} by its {@link UUID unique ID}.
     *
     * @param uuid the {@link UUID} of the warp to get
     * @return An optional with the {@link Warp} present if it exists
     */
    public abstract Optional<Warp> getWarp(@NotNull UUID uuid);

    /**
     * Get the current {@link Teleport} being executed by the specified {@link OnlineUser}.
     *
     * @param onlineUser The {@link OnlineUser} to check
     * @return An optional with the {@link Teleport} present if they are teleporting cross-server
     */
    public abstract Optional<Teleport> getCurrentTeleport(@NotNull OnlineUser onlineUser);

    // Read a pending cross-server teleport by UUID, for a user who isn't online yet
    public abstract Optional<PendingTeleport> getPendingTeleport(@NotNull UUID uuid);

    // Everything the join handler needs, over a single pooled connection instead of one checkout per read
    @NotNull
    public abstract JoinData getJoinData(@NotNull User user);

    // A joining user's stored row, last worlds and homes. A null user has never been saved before
    public record JoinData(@Nullable SavedUser user, @NotNull Map<String, World> lastWorlds,
                           @NotNull List<Home> homes) {

        @NotNull
        public static JoinData empty() {
            return new JoinData(null, Map.of(), List.of());
        }

        @NotNull
        public Optional<SavedUser> getUser() {
            return Optional.ofNullable(user);
        }
    }

    // Read a pending teleport row; shared by every driver
    @NotNull
    protected final Optional<PendingTeleport> readPendingTeleport(@NotNull ResultSet resultSet) throws SQLException {
        if (!resultSet.next()) {
            return Optional.empty();
        }
        return Optional.of(new PendingTeleport(
                Position.at(
                        resultSet.getDouble("x"),
                        resultSet.getDouble("y"),
                        resultSet.getDouble("z"),
                        resultSet.getFloat("yaw"),
                        resultSet.getFloat("pitch"),
                        World.from(
                                resultSet.getString("world_name"),
                                UUID.fromString(resultSet.getString("world_uuid"))
                        ),
                        resultSet.getString("server_name")
                ),
                Teleport.Type.getTeleportType(resultSet.getInt("type")).orElse(Teleport.Type.TELEPORT)
        ));
    }

    // A stored cross-server teleport, before it is bound to an online user
    public record PendingTeleport(@NotNull Position target, @NotNull Teleport.Type type) {
    }

    /**
     * Updates a user in the database with new {@link SavedUser}.
     *
     * @param savedUser The {@link SavedUser} to update
     */
    public abstract void updateUserData(@NotNull SavedUser savedUser);

    /**
     * Sets or clears the current {@link Teleport} being executed by a {@link User}.
     *
     * @param user     The {@link User} to set the current teleport of.
     *                 Pass as {@code null} to clear the player's current teleport
     * @param teleport The {@link Teleport} to set as their current cross-server teleport
     */
    public abstract void setCurrentTeleport(@NotNull User user, @Nullable Teleport teleport);

    /**
     * Clears the current {@link Teleport} being executed by a {@link User}.
     *
     * @param user The {@link User} to clear the current teleport of
     */
    public final void clearCurrentTeleport(@NotNull User user) {
        this.setCurrentTeleport(user, null);
    }

    /**
     * Get the last teleport {@link Position} of a specified {@link User}.
     *
     * @param user The {@link User} to check
     * @return An optional with the {@link Position} present if it has been set
     */
    public abstract Optional<Position> getLastPosition(@NotNull User user);

    /**
     * Sets the last teleport {@link Position} of a {@link User}.
     *
     * @param user     The {@link User} to set the last position of
     * @param position The {@link Position} to set as their last position
     */
    public abstract void setLastPosition(@NotNull User user, @NotNull Position position);

    /**
     * Get the offline {@link Position} of a specified {@link User}.
     *
     * @param user The {@link User} to check
     * @return An optional with the {@link Position} present if it has been set
     */
    public abstract Optional<Position> getOfflinePosition(@NotNull User user);

    /**
     * Sets the offline {@link Position} of a {@link User}.
     *
     * @param user     The {@link User} to set the offline position of
     * @param position The {@link Position} to set as their offline position
     */
    public abstract void setOfflinePosition(@NotNull User user, @NotNull Position position);

    // Get a user's last world on each server, by server ID
    @NotNull
    public abstract Map<String, World> getLastWorlds(@NotNull User user);

    // Set a user's last world on a server, replacing any previous entry
    public abstract void setLastWorld(@NotNull User user, @NotNull String server, @NotNull World world);

    // Delete every entry for a deleted world on a server
    public abstract void deleteLastWorlds(@NotNull String server, @NotNull UUID worldUuid);

    // Delete a server's entries except those for the given worlds, returning the row count. Rows with no
    // stored world UUID can't be validated, so they go too
    public abstract int deleteLastWorldsExcept(@NotNull String server, @NotNull Collection<UUID> worldUuids);

    // Build a `?` placeholder list for an SQL IN clause
    @NotNull
    protected final String getPlaceholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Placeholder count must be positive");
        }
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    // Read a last world row. Rows predating world UUIDs get a nil UUID, which matches no world
    @NotNull
    protected final World readLastWorld(@NotNull ResultSet resultSet) throws SQLException {
        final String worldUuid = resultSet.getString("world_uuid");
        return World.from(
                resultSet.getString("world_name"),
                worldUuid == null ? new UUID(0, 0) : UUID.fromString(worldUuid),
                null,
                resultSet.getString("world_key")
        );
    }

    /**
     * Get the respawn {@link Position} of a specified {@link User}.
     *
     * @param user The {@link User} to check
     * @return An optional with the {@link Position} present if it has been set
     */
    public abstract Optional<Position> getRespawnPosition(@NotNull User user);

    /**
     * Sets or clears the respawn {@link Position} of a {@link User}.
     *
     * @param user     The {@link User} to set the respawn position of
     * @param position The {@link Position} to set as their respawn position
     *                 Pass as {@code null} to clear the player's current respawn position.
     */
    public abstract void setRespawnPosition(@NotNull User user, @Nullable Position position);

    /**
     * Sets or updates a {@link Home} into the home data table on the database.
     *
     * @param home The {@link Home} to set - or update - in the database.
     */
    public abstract void saveHome(@NotNull Home home);

    /**
     * Sets or updates a {@link Warp} into the warp data table on the database.
     *
     * @param warp The {@link Warp} to set - or update - in the database.
     */
    public abstract void saveWarp(@NotNull Warp warp);

    /**
     * Deletes a {@link Home} by the given unique id from the home table on the database.
     *
     * @param uuid {@link UUID} of the home to delete
     */
    public abstract void deleteHome(@NotNull UUID uuid);

    /**
     * Deletes all {@link Home}s of a {@link User} from the home table on the database.
     *
     * @param user The {@link User} to delete all homes of
     * @return An integer; the number of deleted homes
     */
    public abstract int deleteAllHomes(@NotNull User user);

    /**
     * Deletes all {@link Home}s on a specific world and server (given by name) from the home table on the database.
     *
     * @param worldName  The name of the world to delete homes from
     * @param serverName The name of the server to delete homes from
     * @return An integer; the number of deleted homes
     */
    public abstract int deleteAllHomes(@NotNull String worldName, @NotNull String serverName);

    /**
     * Deletes a {@link Warp} by the given unique id from the warp table on the database.
     *
     * @param uuid {@link UUID} of the warp to delete
     */
    public abstract void deleteWarp(@NotNull UUID uuid);

    /**
     * Deletes all {@link Warp}s set on the database table.
     *
     * @return An integer; the number of deleted warps
     */
    public abstract int deleteAllWarps();

    /**
     * Deletes all {@link Warp}s on a specific world and server (given by name) from the warp table on the database.
     *
     * @param worldName  The name of the world to delete warps from
     * @param serverName The name of the server to delete warps from
     * @return An integer; the number of deleted warps
     */
    public abstract int deleteAllWarps(@NotNull String worldName, @NotNull String serverName);

    /**
     * Close the database connection.
     */
    public abstract void close();

    /**
     * Identifies types of databases.
     */
    @Getter
    @AllArgsConstructor
    public enum Type {
        MYSQL("MySQL", "mysql"),
        MARIADB("MariaDB", "mariadb"),
        SQLITE("SQLite", "sqlite"),
        H2("H2", "h2"),
        POSTGRESQL("PostgreSQL", "postgresql");

        private final String displayName;
        private final String protocol;
    }

    /**
     * Represents the names of tables in the database.
     */
    @Getter
    @AllArgsConstructor
    public enum Table {
        META_DATA("huskhomes_metadata"),
        PLAYER_DATA("huskhomes_users"),
        PLAYER_COOLDOWNS_DATA("huskhomes_user_cooldowns"),
        POSITION_DATA("huskhomes_position_data"),
        SAVED_POSITION_DATA("huskhomes_saved_positions"),
        HOME_DATA("huskhomes_homes"),
        WARP_DATA("huskhomes_warps"),
        TELEPORT_DATA("huskhomes_teleports"),
        LAST_WORLD_DATA("huskhomes_last_worlds");

        @NotNull
        private final String defaultName;

        @NotNull
        public static Database.Table match(@NotNull String placeholder) throws IllegalArgumentException {
            return Table.valueOf(placeholder.toUpperCase(Locale.ENGLISH));
        }

        @NotNull
        public String getDefaultName() {
            return defaultName;
        }

        @NotNull
        public static Map<Table, String> getConfigMap() {
            return Arrays.stream(values()).collect(
                    Collectors.toMap(t -> t, Table::getDefaultName, (a, b) -> b, TreeMap::new)
            );
        }

    }

    /**
     * Represents database migrations that need to be run.
     */
    public enum Migration {
        ADD_METADATA_TABLE(
                0, "add_metadata_table",
                Type.MYSQL, Type.MARIADB, Type.POSTGRESQL, Type.SQLITE, Type.H2
        ),
        ADD_LAST_WORLDS_TABLE(
                1, "add_last_worlds_table",
                Type.MYSQL, Type.MARIADB, Type.POSTGRESQL, Type.SQLITE, Type.H2
        ),
        ADD_LAST_WORLD_UUID(
                2, "add_last_world_uuid",
                Type.MYSQL, Type.MARIADB, Type.POSTGRESQL, Type.SQLITE, Type.H2
        ),
        ADD_LAST_WORLD_INDEX(
                3, "add_last_world_index",
                Type.MYSQL, Type.MARIADB, Type.POSTGRESQL, Type.SQLITE, Type.H2
        ),
        ADD_LAST_WORLD_USER_FK(
                4, "add_last_world_user_fk",
                Type.MYSQL, Type.MARIADB, Type.POSTGRESQL, Type.SQLITE, Type.H2
        );

        private final int version;
        private final String migrationName;
        private final Type[] supportedTypes;

        Migration(int version, @NotNull String migrationName, @NotNull Type... supportedTypes) {
            this.version = version;
            this.migrationName = migrationName;
            this.supportedTypes = supportedTypes;
        }

        private int getVersion() {
            return version;
        }

        private String getMigrationName() {
            return migrationName;
        }

        private boolean isSupported(@NotNull Type type) {
            return Arrays.stream(supportedTypes).anyMatch(supportedType -> supportedType == type);
        }

        @NotNull
        public static List<Migration> getOrderedMigrations() {
            return Arrays.stream(Migration.values())
                    .sorted(Comparator.comparingInt(Migration::getVersion))
                    .collect(Collectors.toList());
        }

        public static int getLatestVersion() {
            return getOrderedMigrations().get(getOrderedMigrations().size() - 1).getVersion();
        }

    }

}
