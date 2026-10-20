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
import net.william278.huskhomes.config.Locales;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.user.CommandUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.PaginatedList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class PublicHomeListCommand extends ListCommand {

    protected PublicHomeListCommand(@NotNull HuskHomes plugin) {
        super(
                List.of("phomelist", "phomes", "publichomelist"),
                "[page]",
                plugin
        );
    }

    @Override
    public void execute(@NotNull CommandUser executor, @NotNull String[] args) {
        final int pageNumber = parseIntArg(args, 0).orElse(1);
        this.showPublicHomeList(executor, null, pageNumber);
    }

    public void showPublicHomeList(@NotNull CommandUser executor, @Nullable String nameFilter, int pageNumber) {
        if (nameFilter == null && executor instanceof OnlineUser user && cachedLists.containsKey(user.getUuid())) {
            executor.sendMessage(cachedLists.get(user.getUuid()).getNearestValidPage(pageNumber));
            return;
        }

        final List<Home> homes = nameFilter == null ? plugin.getDatabase().getPublicHomes()
                : plugin.getDatabase().getPublicHomes(nameFilter);
        plugin.fireEvent(plugin.getViewHomeListEvent(homes, executor, true),
                (event) -> this.generateList(executor, event.getHomes()).ifPresent(homeList -> {
                    if (nameFilter == null && executor instanceof OnlineUser onlineUser) {
                        cachedLists.put(onlineUser.getUuid(), homeList);
                    }
                    executor.sendMessage(homeList.getNearestValidPage(pageNumber));
                }));
    }

    private Optional<PaginatedList> generateList(@NotNull CommandUser executor, @NotNull List<Home> publicHomes) {
        if (publicHomes.isEmpty()) {
            plugin.getLocales().getLocale("error_no_public_homes_set")
                    .ifPresent(executor::sendMessage);
            return Optional.empty();
        }

        record HomeListItem(String name, String escapedName, String identifier, String owner, String description) {}

        // Use the same captured name for duplicate detection, display, and command links.
        final String delimiter = Home.getDelimiter();
        final List<HomeListItem> items = publicHomes.stream().map(home -> {
            final String name = home.getName();
            final String escapedName = Locales.escapeText(name);
            final String owner = home.getOwner().getName();
            final String identifier = owner + delimiter + (escapedName.equals(name) ? name : home.getUuid().toString());
            return new HomeListItem(name, escapedName, identifier, owner, home.getMeta().getDescription());
        }).toList();
        final Map<String, Long> nameCounts = items.stream()
                .collect(Collectors.groupingBy(HomeListItem::name, Collectors.counting()));
        final PaginatedList homeList = PaginatedList.of(items.stream().map(home ->
                        plugin.getLocales()
                                .getRawLocale("public_home_list_item",
                                        nameCounts.get(home.name()) > 1 ? home.identifier() : home.escapedName(),
                                        home.identifier(),
                                        Locales.escapeText(home.owner()),
                                        home.description().isBlank()
                                                ? plugin.getLocales().getNone()
                                                : Locales.escapeText(home.description())
                                )
                                .orElse(home.name())).sorted().collect(Collectors.toList()),
                plugin.getLocales()
                        .getBaseList(plugin.getSettings().getGeneral().getListItemsPerPage())
                        .setHeaderFormat(plugin.getLocales().getRawLocale("public_home_list_page_title",
                                        "%first_item_on_page_index%", "%last_item_on_page_index%", "%total_items%")
                                .orElse(""))
                        .setCommand("/huskhomes:phomelist").build());
        return Optional.of(homeList);
    }

}
