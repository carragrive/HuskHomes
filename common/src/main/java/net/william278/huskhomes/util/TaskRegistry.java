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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/** Tracks both entity and global tasks so shutdown also completes waiting futures. */
public final class TaskRegistry implements AutoCloseable {

    private final Set<Task.Base> tasks = new HashSet<>();
    private boolean closed;

    public synchronized void register(Task.Base task) {
        if (closed || !task.getPlugin().isEnabled()) {
            task.cancel();
            throw new RejectedExecutionException("HuskHomes is disabled");
        }
        tasks.add(task);
        task.getCompletion().whenComplete((ignored, error) -> remove(task));
    }

    private synchronized void remove(Task.Base task) {
        tasks.remove(task);
    }

    @Override
    public void close() {
        final Set<Task.Base> pending;
        synchronized (this) {
            closed = true;
            pending = Set.copyOf(tasks);
            tasks.clear();
        }
        pending.forEach(Task.Base::cancel);
    }
}
