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

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.user.OnlineUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public interface Task extends Runnable {

    abstract class Base implements Task {

        protected final HuskHomes plugin;
        protected final Runnable runnable;
        protected volatile boolean cancelled = false;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        protected Base(@NotNull HuskHomes plugin, @NotNull Runnable runnable) {
            this.plugin = plugin;
            this.runnable = runnable;
        }

        public void cancel() {
            cancelled = true;
            completion.cancel(false);
        }

        public CompletableFuture<Void> getCompletion() {
            return completion;
        }

        protected final void execute() {
            if (cancelled || !plugin.isEnabled()) {
                cancel();
                return;
            }
            try {
                runnable.run();
                if (!(this instanceof Repeating)) {
                    completion.complete(null);
                }
            } catch (Throwable error) {
                completion.completeExceptionally(error);
                cancel();
                plugin.log(Level.SEVERE, "Failed to execute a scheduled task", error);
            }
        }

        @NotNull
        @Override
        public HuskHomes getPlugin() {
            return plugin;
        }

    }

    abstract class Async extends Base {

        protected long delayTicks;

        protected Async(@NotNull HuskHomes plugin, @NotNull Runnable runnable, long delayTicks) {
            super(plugin, runnable);
            this.delayTicks = delayTicks;
        }

    }

    abstract class Sync extends Base {

        protected long delayTicks;

        protected Sync(@NotNull HuskHomes plugin, @NotNull Runnable runnable, long delayTicks) {
            super(plugin, runnable);
            this.delayTicks = delayTicks;
        }

    }

    abstract class Repeating extends Base {

        protected final long repeatingTicks;

        protected Repeating(@NotNull HuskHomes plugin, @NotNull Runnable runnable, long repeatingTicks) {
            super(plugin, runnable);
            this.repeatingTicks = repeatingTicks;
        }

    }

    @SuppressWarnings("UnusedReturnValue")
    interface Supplier {

        @NotNull
        Task.Sync getSyncTask(@NotNull Runnable runnable, @Nullable OnlineUser user, long delayTicks);

        @NotNull
        Task.Async getAsyncTask(@NotNull Runnable runnable, long delayTicks);

        @NotNull
        Task.Repeating getRepeatingTask(@NotNull Runnable runnable, long repeatingTicks);

        @NotNull
        default Task.Sync runSyncDelayed(@NotNull Runnable runnable, @Nullable OnlineUser user, long delayTicks) {
            final Task.Sync task = getSyncTask(runnable, user, delayTicks);
            task.run();
            return task;
        }

        default Task.Async runAsyncDelayed(@NotNull Runnable runnable, long delayTicks) {
            final Task.Async task = getAsyncTask(runnable, delayTicks);
            task.run();
            return task;
        }

        @NotNull
        default Task.Sync runSync(@NotNull Runnable runnable) {
            return runSyncDelayed(runnable, null, 0);
        }

        @NotNull
        default Task.Sync runSync(@NotNull Runnable runnable, @NotNull OnlineUser user) {
            return runSyncDelayed(runnable, user, 0);
        }

        @NotNull
        default Task.Async runAsync(@NotNull Runnable runnable) {
            final Task.Async task = getAsyncTask(runnable, 0);
            task.run();
            return task;
        }

        default <T> CompletableFuture<T> supplyAsync(@NotNull java.util.function.Supplier<T> supplier) {
            return supply(supplier, false, null);
        }

        default <T> CompletableFuture<T> supplySync(@NotNull java.util.function.Supplier<T> supplier,
                                                   @Nullable OnlineUser user) {
            return supply(supplier, true, user);
        }

        private <T> CompletableFuture<T> supply(@NotNull java.util.function.Supplier<T> supplier,
                                               boolean sync, @Nullable OnlineUser user) {
            final CompletableFuture<T> future = new CompletableFuture<>();
            final Runnable action = () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            };
            final Base task = sync ? getSyncTask(action, user, 0) : getAsyncTask(action, 0);
            task.getCompletion().whenComplete((ignored, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                }
            });
            future.whenComplete((ignored, error) -> {
                if (future.isCancelled()) {
                    task.cancel();
                }
            });
            try {
                task.run();
            } catch (RuntimeException error) {
                task.cancel();
                future.completeExceptionally(error);
            }
            return future;
        }

        void cancelTasks();

        @NotNull
        HuskHomes getPlugin();

    }

    @NotNull
    HuskHomes getPlugin();

}
