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

package net.william278.huskhomes.user;

import net.william278.huskhomes.BukkitHuskHomesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BukkitUserTests {

    private BukkitHuskHomesPlugin plugin;
    private Player player;
    private BukkitUser user;

    @BeforeEach
    void setUp() {
        plugin = mock(BukkitHuskHomesPlugin.class);
        when(plugin.getName()).thenReturn("HuskHomesX");
        when(plugin.getServerName()).thenReturn("server");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Player");
        user = BukkitUser.adapt(player, plugin);
    }

    @Test
    void workerPositionReadIsDispatchedBeforeTouchingThePlayer() {
        final AtomicBoolean owner = new AtomicBoolean();
        final World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(player.getLocation()).thenAnswer(call -> {
            assertTrue(owner.get(), "location read outside the owning region");
            return new Location(world, 12, 64, -8);
        });
        when(plugin.supplySync(any(), eq(user))).thenAnswer(call -> {
            owner.set(true);
            try {
                return CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(0)).get());
            } finally {
                owner.set(false);
            }
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenAnswer(call -> owner.get());
            assertEquals(12, user.getPosition().getX());
        }
        verify(plugin).supplySync(any(), eq(user));
    }

    @Test
    void anotherTickThreadIsNeverBlockedWaitingForThePlayer() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            assertThrows(IllegalStateException.class, user::getPosition);
        }
        verify(player, never()).getLocation();
        verify(plugin, never()).supplySync(any(), any());
    }

    @Test
    void replacedSessionIsInvalidWithoutReadingItsEntity() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getOnlineUserMap()).thenReturn(Map.of(user.getUuid(), mock(OnlineUser.class)));

        assertFalse(user.isValid());

        verify(player, never()).getHealth();
    }

    @Test
    void invulnerabilityChangesAreDeferredToTheOwningRegion() {
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
            user.handleInvulnerability();
        }
        verify(plugin).runSync(any(), eq(user));
        verify(player, never()).setInvulnerable(anyBoolean());
        verify(player, never()).getPersistentDataContainer();
    }
}
