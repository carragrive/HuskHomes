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

package net.william278.huskhomes.position;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PositionMetaTests {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "{}"})
    void absentTagsRemainAnEmptyMutableMap(String json) {
        final PositionMeta meta = from(json);
        assertTrue(meta.getTags().isEmpty());
        meta.getTags().put("key", "value");
        assertEquals(Map.of("key", "value"), from(meta.getSerializedTags()).getTags());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "\"text\"", "{\"key\":{}}", "{\"key\":[]}", "{\"key\":1}", "{key:value}"})
    void corruptTagsAreRejectedInsteadOfDiscarded(String json) {
        assertThrows(JsonParseException.class, () -> from(json));
    }

    @Test
    void nullTagValuesSurviveSerialization() {
        final PositionMeta meta = from("{\"key\":null}");
        final Map<String, String> tags = from(meta.getSerializedTags()).getTags();
        assertTrue(tags.containsKey("key"));
        assertNull(tags.get("key"));
    }

    @Test
    void copyingMetadataRetainsTagsWithoutSharingTheMap() {
        final PositionMeta original = from("{\"plugin:tag\":\"value\"}");
        final PositionMeta copy = original.copy();
        copy.getTags().put("plugin:tag", "changed");
        assertEquals("value", original.getTags().get("plugin:tag"));
        assertEquals("changed", from(copy.getSerializedTags()).getTags().get("plugin:tag"));
    }

    @Test
    void nullMapIsRejectedWithoutReplacingExistingTags() {
        final PositionMeta meta = from("{\"key\":\"value\"}");
        assertThrows(NullPointerException.class, () -> meta.setTags(null));
        assertEquals(Map.of("key", "value"), meta.getTags());
    }

    private static PositionMeta from(String tags) {
        return PositionMeta.from("home", "", Instant.EPOCH, tags);
    }
}
