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

package net.william278.huskhomes.util;

import net.william278.huskhomes.BukkitHuskHomesPlugin;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.user.OnlineUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.arim.morepaperlib.scheduling.AttachedScheduler;
import space.arim.morepaperlib.scheduling.RegionalScheduler;
import space.arim.morepaperlib.scheduling.ScheduledTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BukkitTaskTests {

    private BukkitHuskHomesPlugin plugin;
    private TaskRegistry registry;
    private RegionalScheduler scheduler;
    private final AtomicReference<Runnable> queued = new AtomicReference<>();
    private BukkitTask.Supplier supplier;

    @BeforeEach
    void setUp() {
        plugin = mock(BukkitHuskHomesPlugin.class);
        registry = new TaskRegistry();
        scheduler = mock(RegionalScheduler.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getTasks()).thenReturn(registry);
        when(plugin.getSyncScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(Runnable.class))).thenAnswer(call -> {
            queued.set(call.getArgument(0));
            return mock(ScheduledTask.class);
        });
        supplier = new BukkitTask.Supplier() {
            @Override
            public HuskHomes getPlugin() {
                return plugin;
            }
        };
    }

    @Test
    void disabledPluginNeverRunsSyncOrAsyncCallbacksInline() {
        when(plugin.isEnabled()).thenReturn(false);
        final Runnable callback = mock(Runnable.class);

        assertThrows(RejectedExecutionException.class, () -> supplier.runSync(callback));
        assertThrows(RejectedExecutionException.class, () -> supplier.runAsync(callback));
        verifyNoInteractions(callback);
    }

    @Test
    void disablingBetweenSubmissionAndExecutionCancelsTheFuture() {
        final CompletableFuture<String> result = supplier.supplySync(() -> "unexpected", null);
        when(plugin.isEnabled()).thenReturn(false);

        queued.get().run();

        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void shutdownCancelsQueuedFuturesAndRejectsNewWork() {
        final Runnable callback = mock(Runnable.class);
        final CompletableFuture<Void> result = supplier.supplySync(() -> {
            callback.run();
            return null;
        }, null);

        registry.close();
        queued.get().run();

        assertTrue(result.isCompletedExceptionally());
        assertThrows(RejectedExecutionException.class, () -> supplier.runSync(callback));
        verifyNoInteractions(callback);
    }

    @Test
    void replacedPlayerSessionCancelsAnEntityCallback() {
        final OnlineUser old = mock(OnlineUser.class);
        final UUID uuid = UUID.randomUUID();
        when(old.getUuid()).thenReturn(uuid);
        when(plugin.getOnlineUserMap()).thenReturn(Map.of(uuid, mock(OnlineUser.class)));
        final AttachedScheduler entityScheduler = mock(AttachedScheduler.class);
        when(plugin.getUserSyncScheduler(old)).thenReturn(entityScheduler);
        when(entityScheduler.run(any(Runnable.class), any(Runnable.class))).thenAnswer(call -> {
            queued.set(call.getArgument(0));
            return mock(ScheduledTask.class);
        });
        final CompletableFuture<String> result = supplier.supplySync(() -> "unexpected", old);

        queued.get().run();

        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void retiredEntityCompletesTheFutureExceptionally() {
        final OnlineUser user = mock(OnlineUser.class);
        final AttachedScheduler entityScheduler = mock(AttachedScheduler.class);
        when(plugin.getUserSyncScheduler(user)).thenReturn(entityScheduler);

        assertTrue(supplier.supplySync(() -> "unexpected", user).isCompletedExceptionally());
    }

    @Test
    void supplierFailureIsDeliveredToItsFuture() {
        final IllegalStateException failure = new IllegalStateException("failed read");
        final CompletableFuture<String> result = supplier.supplySync(() -> { throw failure; }, null);

        queued.get().run();

        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }
}
