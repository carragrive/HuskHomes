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

import de.exlll.configlib.YamlConfigurations;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.Locales;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.event.IHomeListEvent;
import net.william278.huskhomes.manager.Manager;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.PositionMeta;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeCommandTests {

    private HuskHomes plugin;
    private Database database;
    private OnlineUser user;

    @BeforeEach
    void setUp() {
        plugin = mock(HuskHomes.class);
        database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        final Settings settings = mock(Settings.class, RETURNS_DEEP_STUBS);
        when(plugin.getSettings()).thenReturn(settings);
        when(settings.getGeneral().getListItemsPerPage()).thenReturn(10);
        when(plugin.getLocales()).thenReturn(YamlConfigurations.read(new ByteArrayInputStream(
                "locales:\n  public_home_list_item: '%1%|%2%'\n".getBytes(StandardCharsets.UTF_8)), Locales.class));
        user = mock(OnlineUser.class);
        when(user.getUuid()).thenReturn(UUID.randomUUID());
        when(user.getName()).thenReturn("Alice");
    }

    @Test
    void personalHomeSuggestionsFollowCacheUpdatesAndEviction() {
        final Manager manager = new Manager(plugin);
        when(plugin.getManager()).thenReturn(manager);
        final PrivateHomeCommand command = new PrivateHomeCommand(plugin);
        assertTrue(command.getSuggestions(user, new String[]{""}).isEmpty());
        manager.homes().cacheUserHomes(user, List.of(home("Alice", "base"), home("Alice", "farm")));
        final Home other = home("Bob", "town");
        manager.homes().cacheUserHomes(other.getOwner(), List.of(other));

        assertEquals(List.of("base", "farm"), command.getSuggestions(user, new String[]{""}));
        assertEquals(List.of("base"), command.getSuggestions(user, new String[]{"ba"}));

        manager.homes().removeUserHomes(user);

        assertTrue(command.getSuggestions(user, new String[]{""}).isEmpty());
    }

    @Test
    void publicHomeListPreservesDisambiguationSortingAndEscaping() {
        final Home unsafe = home("Dave", "<red>unsafe");
        final List<Home> homes = List.of(unsafe, home("Bob", "base"), home("Carol", "Base"), home("Alice", "base"));
        when(database.getPublicHomes()).thenReturn(homes);
        final PublicHomeListCommand command = showPublicHomes(homes);

        assertEquals("Alice.base|Alice.base Base|Carol.Base Bob.base|Bob.base \\<red>unsafe|"
                + unsafe.getSafeIdentifier(), command.cachedLists.get(user.getUuid()).getRawPage(1));
    }

    @Test
    void publicHomeDisambiguationUsesTheListReturnedByTheEvent() {
        final Home alice = home("Alice", "base");
        when(database.getPublicHomes()).thenReturn(List.of(alice, home("Bob", "base")));
        final PublicHomeListCommand command = showPublicHomes(List.of(alice));

        assertEquals("base|Alice.base", command.cachedLists.get(user.getUuid()).getRawPage(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"farm", "<red>farm"})
    void publicHomeListKeepsNamesAndLinksConsistentDuringARename(String originalName) {
        final Home alice = spy(home("Alice", originalName));
        final List<Home> homes = List.of(alice, home("Bob", "base"));
        when(database.getPublicHomes()).thenReturn(homes);
        final AtomicBoolean renamed = new AtomicBoolean();
        doAnswer(call -> {
            final String name = (String) call.callRealMethod();
            if (renamed.compareAndSet(false, true)) {
                alice.getMeta().setName("base");
            }
            return name;
        }).when(alice).getName();

        final String aliceEntry = originalName.equals("farm")
                ? "farm|Alice.farm" : "\\<red>farm|Alice." + alice.getUuid();
        final String expected = String.join(" ", List.of(aliceEntry, "base|Bob.base").stream().sorted().toList());
        assertEquals(expected, showPublicHomes(homes).cachedLists.get(user.getUuid()).getRawPage(1));
        assertTrue(renamed.get());
        assertEquals("Alice.base|Alice.base Bob.base|Bob.base",
                showPublicHomes(homes).cachedLists.get(user.getUuid()).getRawPage(1));
    }

    private PublicHomeListCommand showPublicHomes(List<Home> eventHomes) {
        final IHomeListEvent event = mock(IHomeListEvent.class);
        when(event.getHomes()).thenReturn(eventHomes);
        when(plugin.getViewHomeListEvent(anyList(), eq(user), eq(true))).thenReturn(event);
        doAnswer(call -> {
            call.<Consumer<IHomeListEvent>>getArgument(1).accept(event);
            return null;
        }).when(plugin).fireEvent(eq(event), any());
        final PublicHomeListCommand command = new PublicHomeListCommand(plugin);
        command.showPublicHomeList(user, null, 1);
        return command;
    }

    private Home home(String owner, String name) {
        return Home.from(Position.at(0, 64, 0, World.from("world"), "server"),
                PositionMeta.create(name, ""), User.of(UUID.randomUUID(), owner));
    }
}
