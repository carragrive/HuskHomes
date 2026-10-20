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

package net.william278.huskhomes.manager;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.PositionMeta;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.user.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HomesManagerTests {

    @Test
    void userHomeLookupReturnsOnlyThatUsersHomesAndTracksReplacement() {
        final Home alice = home("Alice", "base");
        final Home bob = home("Bob", "farm");
        final HomesManager homes = manager(List.of());
        homes.cacheUserHomes(alice.getOwner(), List.of(alice));
        homes.cacheUserHomes(bob.getOwner(), List.of(bob));
        final List<String> original = homes.getUserHomes(alice.getOwner());

        homes.cacheUserHomes(alice.getOwner(), List.of(home("Alice", "town")));

        assertEquals(List.of("base"), original);
        assertEquals(List.of("town"), homes.getUserHomes(alice.getOwner()));
        assertEquals(List.of("farm"), homes.getUserHomes(bob.getOwner()));
        assertTrue(homes.getUserHomes(User.of(UUID.randomUUID(), "Unknown")).isEmpty());
    }

    @Test
    void removingAUserEvictsOnlyTheirPrivateCache() {
        final Home alice = home("Alice", "base");
        final Home bob = home("Bob", "farm");
        final HomesManager homes = manager(List.of(alice));
        homes.cacheUserHomes(alice.getOwner(), List.of(alice));
        homes.cacheUserHomes(bob.getOwner(), List.of(bob));

        homes.removeUserHomes(alice.getOwner());
        homes.removeUserHomes(alice.getOwner());

        assertEquals(Map.of("Bob", List.of("farm")), homes.getUserHomes());
        assertTrue(homes.getUserHomes(alice.getOwner()).isEmpty());
        assertEquals(List.of("Bob.farm"), homes.getUserHomeIdentifiers());
        assertEquals(List.of("base"), homes.getPublicHomeNames());
    }

    @Test
    void publicHomesAreGroupedWithoutDroppingHomesFromTheSameOwner() {
        final HomesManager homes = manager(List.of(
                home("Alice", "base"), home("Bob", "town"), home("Alice", "farm")));

        assertEquals(Map.of("Alice", List.of("base", "farm"), "Bob", List.of("town")), homes.getPublicHomes());
    }

    @Test
    void publicHomeNamesQualifyOnlyExactDuplicatesAndFollowCacheChanges() {
        final Home alice = home("Alice", "base");
        final Home bob = home("Bob", "base");
        final HomesManager homes = manager(List.of(alice, bob, home("Carol", "Base"), home("Dave", "farm")));

        assertEquals(List.of("Alice.base", "Bob.base", "Base", "farm"), homes.getPublicHomeNames());

        homes.unCacheHome(bob.getUuid(), false);

        assertEquals(List.of("base", "Base", "farm"), homes.getPublicHomeNames());
    }

    @Test
    void publicHomeNamesStayConsistentWhenAHomeIsRenamedAfterItsNameIsRead() {
        final Home alice = spy(home("Alice", "farm"));
        final HomesManager homes = manager(List.of(alice, home("Bob", "base")));
        final AtomicBoolean renamed = new AtomicBoolean();
        doAnswer(call -> {
            final String name = (String) call.callRealMethod();
            if (renamed.compareAndSet(false, true)) {
                homes.setHomeName(alice, "base");
            }
            return name;
        }).when(alice).getName();

        assertEquals(List.of("farm", "base"), homes.getPublicHomeNames());
        assertTrue(renamed.get());
        assertEquals(List.of("Bob.base", "Alice.base"), homes.getPublicHomeNames());
    }

    @Test
    void emptyCachesReturnEmptyCollections() {
        final HomesManager homes = manager(List.of());

        assertTrue(homes.getUserHomes().isEmpty());
        assertTrue(homes.getPublicHomes().isEmpty());
        assertTrue(homes.getPublicHomeNames().isEmpty());
    }

    private HomesManager manager(List<Home> publicHomes) {
        final HuskHomes plugin = mock(HuskHomes.class);
        final Database database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        when(database.getPublicHomes()).thenReturn(publicHomes);
        return new HomesManager(plugin);
    }

    private Home home(String owner, String name) {
        final Home home = Home.from(Position.at(0, 64, 0, World.from("world"), "server"),
                PositionMeta.create(name, ""), User.of(UUID.randomUUID(), owner));
        home.setPublic(true);
        return home;
    }
}
