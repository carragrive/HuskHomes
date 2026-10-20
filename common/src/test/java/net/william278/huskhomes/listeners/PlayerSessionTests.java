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

package net.william278.huskhomes.listeners;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.manager.HomesManager;
import net.william278.huskhomes.manager.Manager;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.SavedUser;
import net.william278.huskhomes.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerSessionTests {

    private final UUID uuid = UUID.randomUUID();
    private final Queue<Runnable> async = new ArrayDeque<>();
    private final Queue<Runnable> sync = new ArrayDeque<>();
    private final Map<UUID, OnlineUser> online = new ConcurrentHashMap<>();
    private final Set<SavedUser> saved = ConcurrentHashMap.newKeySet();
    private HuskHomes plugin;
    private HomesManager homes;
    private EventListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(HuskHomes.class);
        when(plugin.getSettings()).thenReturn(mock(Settings.class, RETURNS_DEEP_STUBS));
        when(plugin.getServerName()).thenReturn("server");
        when(plugin.getOnlineUserMap()).thenReturn(online);
        when(plugin.getSavedUsers()).thenReturn(saved);
        when(plugin.getLastWorldCache()).thenReturn(new ConcurrentHashMap<>());
        when(plugin.getCurrentlyOnWarmup()).thenReturn(ConcurrentHashMap.newKeySet());
        when(plugin.getWarmupDamagedUsers()).thenReturn(ConcurrentHashMap.newKeySet());
        when(plugin.beginLastWorldCache(any())).thenCallRealMethod();
        when(plugin.cacheLastWorlds(any(), anyMap(), anyMap())).thenCallRealMethod();
        final Database database = mock(Database.class);
        when(plugin.getDatabase()).thenReturn(database);
        when(database.getJoinData(any())).thenAnswer(call -> new Database.JoinData(
                new SavedUser(User.of(uuid, "Player"), 1, false), Map.of(), List.of()));
        final Manager manager = mock(Manager.class);
        homes = mock(HomesManager.class);
        when(manager.homes()).thenReturn(homes);
        when(plugin.getManager()).thenReturn(manager);
        when(plugin.runAsync(any())).thenAnswer(call -> { async.add(call.getArgument(0)); return null; });
        when(plugin.runSync(any(), any())).thenAnswer(call -> { sync.add(call.getArgument(0)); return null; });
        listener = new EventListener(plugin) {
            @Override
            public void register() {}
        };
    }

    @Test
    void quitBeforeTheJoinCallbackDoesNotRepopulateCaches() {
        final OnlineUser user = user();
        online.put(uuid, user);
        listener.handlePlayerJoin(user);
        async.remove().run();
        listener.handlePlayerLeave(user);

        sync.remove().run();

        assertTrue(saved.isEmpty());
        assertFalse(online.containsKey(uuid));
        verify(homes, never()).cacheUserHomes(any(), anyList());
    }

    @Test
    void anOldJoinAndQuitCannotChangeAReplacementSession() {
        final OnlineUser old = user();
        online.put(uuid, old);
        listener.handlePlayerJoin(old);
        async.remove().run();
        final OnlineUser current = user();
        online.put(uuid, current);
        final SavedUser currentData = new SavedUser(User.of(uuid, "Player"), 9, false);
        saved.add(currentData);

        sync.remove().run();
        listener.handlePlayerLeave(old);

        assertSame(current, online.get(uuid));
        assertEquals(Set.of(currentData), saved);
        verifyNoInteractions(homes);
    }

    @Test
    void repeatedReconnectsEvictSavedDataImmediatelyOnQuit() {
        for (int i = 0; i < 10; i++) {
            final OnlineUser user = user();
            online.put(uuid, user);
            listener.handlePlayerJoin(user);
            async.remove().run();
            sync.remove().run();
            assertEquals(1, saved.size());

            listener.handlePlayerLeave(user);
            assertTrue(saved.isEmpty());
            async.remove().run();
        }
    }

    @Test
    void equalLastWorldMapsDoNotMakeAnOldSessionCurrent() {
        final OnlineUser user = user();
        final World world = user.getPosition().getWorld();
        final Map<String, World> old = new ConcurrentHashMap<>(Map.of("server", world));
        final Map<String, World> current = new ConcurrentHashMap<>(old);
        plugin.getLastWorldCache().put(uuid, current);

        assertTrue(plugin.cacheLastWorlds(user, old, Map.of()).isEmpty());
        assertSame(current, plugin.getLastWorldCache().get(uuid));
    }

    @Test
    void globalListUpdatesAcceptImmutableSnapshots() {
        final User remote = User.of(UUID.randomUUID(), "Remote");
        final Map<String, List<User>> lists = new ConcurrentHashMap<>();
        lists.put("old", List.of(remote));
        when(plugin.getGlobalUserList()).thenReturn(lists);
        doCallRealMethod().when(plugin).setUserList(anyString(), anyList());

        plugin.setUserList("new", List.of(remote));

        assertEquals(List.of(), lists.get("old"));
        assertEquals(List.of(remote), lists.get("new"));
    }

    private OnlineUser user() {
        final OnlineUser user = mock(OnlineUser.class);
        when(user.getUuid()).thenReturn(uuid);
        when(user.getName()).thenReturn("Player");
        when(user.getPosition()).thenReturn(Position.at(0, 64, 0,
                World.from("world", UUID.randomUUID()), "server"));
        return user;
    }
}
