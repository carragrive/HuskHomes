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

package net.william278.huskhomes.hook;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.william278.huskhomes.BukkitHuskHomes;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.SavedUser;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

@PluginHook(
        name = "PlaceholderAPI",
        register = PluginHook.Register.ON_ENABLE
)
public class PlaceholderAPIHook extends Hook {

    public PlaceholderAPIHook(@NotNull BukkitHuskHomes plugin) {
        super(plugin);
    }

    @Override
    public void load() {
        new HuskHomesExpansion(
                (BukkitHuskHomes) plugin,
                plugin.getPluginVersion().toStringWithoutMetadata(),
                String.join(", ", ((BukkitHuskHomes) plugin).getPluginMeta().getAuthors())
        ).register();
    }

    @Override
    public void unload() {
    }

    @Getter
    @RequiredArgsConstructor
    public static class HuskHomesExpansion extends PlaceholderExpansion {

        private static final String LAST_WORLD_PARAM = "last_world_";
        private static final String RAW_STATUS_PARAM = "raw_status_";
        private static final String STATUS_PARAM = "status_";
        private static final String STATUS_LOCALE = "server_status_%s";
        private static final String UNKNOWN_VALUE = "none";

        @NotNull
        @Getter(AccessLevel.NONE)
        private final BukkitHuskHomes plugin;
        private final String version;
        private final String author;
        private final String identifier = "huskhomes";

        @Override
        @Nullable
        public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
            if (offlinePlayer == null || !offlinePlayer.isOnline() || offlinePlayer.getPlayer() == null) {
                return "Player not online";
            }

            // Return the requested data
            final OnlineUser player = plugin.getOnlineUser(offlinePlayer.getPlayer());
            if (params.startsWith(LAST_WORLD_PARAM)) {
                return getLastWorld(player, params.substring(LAST_WORLD_PARAM.length()));
            }
            if (params.startsWith(RAW_STATUS_PARAM)) {
                return getServerState(params.substring(RAW_STATUS_PARAM.length())).name();
            }
            if (params.startsWith(STATUS_PARAM)) {
                return getServerStatus(params.substring(STATUS_PARAM.length()));
            }
            return switch (params) {
                case "homes_count" -> String.valueOf(plugin.getManager().homes()
                        .getUserHomes()
                        .getOrDefault(player.getName(), List.of()).size());
                case "max_homes" -> String.valueOf(plugin.getManager().homes().getMaxHomes(player));
                case "max_public_homes" -> String.valueOf(plugin.getManager().homes().getMaxPublicHomes(player));
                case "free_home_slots" -> String.valueOf(plugin.getManager().homes().getFreeHomes(player));
                case "home_slots" -> String.valueOf(plugin.getSavedUser(player)
                        .map(SavedUser::getHomeSlots)
                        .orElse(0));
                case "homes_list" -> String.join(", ", plugin.getManager().homes()
                        .getUserHomes()
                        .getOrDefault(player.getName(), List.of()));
                case "public_homes_count" -> String.valueOf(plugin.getManager().homes()
                        .getPublicHomes()
                        .getOrDefault(player.getName(), List.of()).size());
                case "public_homes_list" -> String.join(", ", plugin.getManager().homes()
                        .getPublicHomes()
                        .getOrDefault(player.getName(), List.of()));
                case "ignoring_tp_requests" -> getBooleanValue(plugin.getManager().requests()
                        .isIgnoringRequests(player));
                default -> null;
            };
        }

        @Override
        public boolean persist() {
            return true;
        }

        @NotNull
        private String getBooleanValue(final boolean bool) {
            return bool ? PlaceholderAPIPlugin.booleanTrue() : PlaceholderAPIPlugin.booleanFalse();
        }

        // %huskhomes_last_world_<server>%; namespaced key where known, else UNKNOWN_VALUE. Read from cache, not DB
        @NotNull
        private String getLastWorld(@NotNull OnlineUser player, @NotNull String server) {
            if (server.isBlank()) {
                return UNKNOWN_VALUE;
            }
            return plugin.getLastWorld(player, server)
                    .map(world -> world.getKey() != null ? world.getKey() : world.getName())
                    .orElse(UNKNOWN_VALUE);
        }

        // %huskhomes_status_<server>%; the state's locale message, falling back to its keyword
        @NotNull
        private String getServerStatus(@NotNull String server) {
            final Broker.ServerState state = getServerState(server);
            return plugin.getLocales()
                    .getRawLocale(String.format(STATUS_LOCALE, state.name().toLowerCase(Locale.ENGLISH)))
                    .orElseGet(state::name);
        }

        // Broker readiness; down, unseen and unrecognised servers all read UNKNOWN
        @NotNull
        private Broker.ServerState getServerState(@NotNull String server) {
            return server.isBlank() ? Broker.ServerState.UNKNOWN : plugin.getServerState(server);
        }

    }

}
