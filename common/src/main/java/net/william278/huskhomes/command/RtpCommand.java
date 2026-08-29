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

package net.william278.huskhomes.command;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.RtpOptions;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.network.Message;
import net.william278.huskhomes.network.Payload;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.teleport.Teleport;
import net.william278.huskhomes.user.CommandUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.TransactionResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public class RtpCommand extends Command implements UserListTabCompletable {

    protected RtpCommand(@NotNull HuskHomes plugin) {
        super(List.of("rtp"), "[destination] [player]", plugin);

        // Register huskhomes.command.rtp.<destination> per destination
        final Map<String, Boolean> permissions = new HashMap<>(Map.of("other", true));
        plugin.getSettings().getRtp().getDestinations().keySet()
                .forEach(id -> permissions.put(id.toLowerCase(Locale.ENGLISH), true));
        addAdditionalPermissions(permissions);
    }

    @Override
    public void execute(@NotNull CommandUser executor, @NotNull String[] args) {
        if (args.length > 2) {
            sendSyntaxError(executor);
            return;
        }

        final Optional<OnlineUser> teleporter = args.length == 2
                ? plugin.getOnlineUser(args[1])
                : executor instanceof OnlineUser online ? Optional.of(online) : Optional.empty();
        if (teleporter.isEmpty()) {
            if (args.length == 2) {
                plugin.getLocales().getLocale("error_player_not_found", args[1]).ifPresent(executor::sendMessage);
            } else {
                sendSyntaxError(executor);
            }
            return;
        }
        if (!executor.equals(teleporter.get()) && !executor.hasPermission(getPermission("other"))) {
            plugin.getLocales().getLocale("error_no_permission").ifPresent(executor::sendMessage);
            return;
        }

        final Settings.RtpSettings settings = plugin.getSettings().getRtp();
        if (args.length == 0 && !settings.hasDefaultDestination()) {
            plugin.getLocales().getLocale("error_rtp_no_destination_specified").ifPresent(executor::sendMessage);
            return;
        }

        final Optional<Map.Entry<String, Settings.RtpSettings.Destination>> destination = args.length == 0
                ? settings.getDefault()
                : settings.findDestination(args[0]);
        if (destination.isEmpty()) {
            plugin.getLocales().getLocale("error_invalid_world", args.length == 0 ? "" : args[0])
                    .ifPresent(executor::sendMessage);
            return;
        }

        // Console bypasses the destination permission
        if (executor instanceof OnlineUser && !canUseDestination(teleporter.get(), destination.get().getKey())) {
            plugin.getLocales().getLocale("error_no_permission").ifPresent(executor::sendMessage);
            return;
        }

        destination.get().getValue().selectProfile(teleporter.get()).whenComplete((profile, error) -> {
            if (error != null) {
                plugin.log(Level.SEVERE, "Failed to evaluate RTP profile conditions", error);
                plugin.getLocales().getLocale("error_rtp_randomization_timeout").ifPresent(executor::sendMessage);
                return;
            }

            // Nothing matched; the default profile rejected them
            if (profile.isEmpty()) {
                plugin.getLocales().getLocale("error_rtp_conditions_not_met", destination.get().getKey())
                        .ifPresent(executor::sendMessage);
                return;
            }
            plugin.runAsync(() -> executeRtp(
                    executor,
                    teleporter.get(),
                    destination.get().getValue(),
                    profile.get().getOptions()
            ));
        });
    }

    @Nullable
    @Override
    public List<String> suggest(@NotNull CommandUser user, @NotNull String[] args) {
        return switch (args.length) {
            case 0, 1 -> plugin.getSettings().getRtp().getDestinations().keySet().stream()
                    .filter(id -> canUseDestination(user, id))
                    .sorted().toList();
            case 2 -> user.hasPermission(getPermission("other")) ? getUsernameList() : List.of();
            default -> List.of();
        };
    }

    // Check huskhomes.command.rtp.<destination>
    private boolean canUseDestination(@NotNull CommandUser user, @NotNull String destinationId) {
        return hasPermission(user, destinationId.toLowerCase(Locale.ENGLISH));
    }

    private void executeRtp(@NotNull CommandUser executor, @NotNull OnlineUser teleporter,
                            @NotNull Settings.RtpSettings.Destination destination, @NotNull RtpOptions options) {
        if (!plugin.validateTransaction(teleporter, TransactionResolver.Action.RANDOM_TELEPORT)) {
            return;
        }

        plugin.getLocales().getLocale("teleporting_random_generation").ifPresent(teleporter::sendMessage);
        final boolean crossServer = plugin.getSettings().getRtp().getMode()
                == Settings.RtpSettings.Mode.CROSS_SERVER;
        final String targetServer = destination.getServer().isBlank()
                ? plugin.getServerName()
                : destination.getServer();
        if (crossServer && !targetServer.equals(plugin.getServerName())) {
            requestRemotePosition(executor, teleporter, destination, targetServer, options);
            return;
        }

        final Optional<World> world = plugin.findWorld(destination.getWorld());
        if (world.isEmpty()) {
            plugin.getLocales().getLocale("error_invalid_world", destination.getWorld())
                    .ifPresent(executor::sendMessage);
            return;
        }
        performLocalRtp(teleporter, executor, world.get(), options);
    }

    private void requestRemotePosition(@NotNull CommandUser executor, @NotNull OnlineUser teleporter,
                                       @NotNull Settings.RtpSettings.Destination destination,
                                       @NotNull String targetServer, @NotNull RtpOptions options) {
        if (!plugin.getSettings().getCrossServer().isEnabled()
                || plugin.getSettings().getCrossServer().getBrokerType() != Broker.Type.REDIS
                || plugin.getBroker().isEmpty()) {
            plugin.log(Level.WARNING, "Cannot use remote RTP destination '" + targetServer
                    + "': cross-server mode with a REDIS broker is required");
            plugin.getLocales().getLocale("error_rtp_randomization_timeout")
                    .ifPresent(executor::sendMessage);
            return;
        }
        switch (plugin.getServerState(targetServer)) {
            case STARTING -> {
                plugin.getLocales().getLocale("error_server_loading").ifPresent(executor::sendMessage);
                return;
            }
            case STOPPING, UNKNOWN -> {
                plugin.getLocales().getLocale("error_invalid_server").ifPresent(executor::sendMessage);
                return;
            }
            case READY -> {
            }
        }
        Message.builder()
                .type(Message.MessageType.REQUEST_RTP_LOCATION)
                .target(targetServer, Message.TargetType.SERVER)
                .payload(Payload.rtpLocationRequest(destination.getWorld(), options, executor.equals(teleporter)))
                .build().send(plugin.getBroker().orElseThrow(), teleporter);
    }

    private void performLocalRtp(@NotNull OnlineUser teleporter, @NotNull CommandUser executor,
                                 @NotNull World world, @NotNull RtpOptions options) {
        plugin.getRandomTeleportEngine().getRandomPosition(world, new String[0], options).thenAccept(position -> {
            if (position.isEmpty()) {
                plugin.getLocales().getLocale("error_rtp_randomization_timeout").ifPresent(executor::sendMessage);
                return;
            }
            Teleport.builder(plugin)
                    .teleporter(teleporter)
                    .type(Teleport.Type.RANDOM_TELEPORT)
                    .actions(TransactionResolver.Action.RANDOM_TELEPORT)
                    .rtpOptions(options)
                    .target(position.get())
                    .buildAndComplete(executor.equals(teleporter));
        });
    }

    private void sendSyntaxError(@NotNull CommandUser executor) {
        plugin.getLocales().getLocale("error_invalid_syntax", getUsage()).ifPresent(executor::sendMessage);
    }
}
