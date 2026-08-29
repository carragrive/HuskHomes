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

package net.william278.huskhomes.config;

import com.google.gson.annotations.Expose;
import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Configuration
@NoArgsConstructor
public class RtpOptions {

    @Expose
    @Comment("Direction quadrant from the RTP center (RANDOM, NORTH, SOUTH, EAST, or WEST)")
    private Direction direction = Direction.RANDOM;
    @Expose
    @Comment("Minimum radial distance from the RTP center")
    private int minRadius = 500;
    @Expose
    @Comment("Maximum radial distance from the RTP center")
    private int maxRadius = 5000;
    @Expose
    @Comment("Minimum player-foot Y coordinate (inclusive)")
    private int minY = -64;
    @Expose
    @Comment("Maximum player-foot Y coordinate (inclusive)")
    private int maxY = 319;
    @Expose
    @Comment("Whether safe cave positions below the surface may be selected")
    private boolean allowUnderground = false;
    @Expose
    @Comment("Options for carving a safe space when the destination is solid")
    private Clearance clearance = new Clearance();

    void validate(String path) {
        if (minRadius < 0 || maxRadius <= minRadius) {
            throw new IllegalStateException(path + " must have 0 <= min_radius < max_radius");
        }
        if (maxY < minY) {
            throw new IllegalStateException(path + " must have min_y <= max_y");
        }
        if (clearance == null) {
            throw new IllegalStateException(path + ".clearance must be configured");
        }
        clearance.validate(path + ".clearance");
    }

    @Getter
    @Configuration
    @NoArgsConstructor
    public static class Clearance {
        @Expose
        @Comment("Whether solid occupation blocks may be carved before teleporting")
        private boolean enabled = false;
        @Expose
        @Comment("Whether BlockBreakEvent protection checks must permit every removed block")
        private boolean respectProtection = true;
        @Expose
        @Comment("Odd horizontal carving width centered on the player")
        private int width = 3;
        @Expose
        @Comment("Vertical carving height starting at the player's feet")
        private int height = 3;
        @Expose
        @Comment("Whether the four horizontal corners should be carved")
        private boolean includeCorners = false;

        private void validate(String path) {
            if (width < 1 || width > 15 || width % 2 == 0) {
                throw new IllegalStateException(path + ".width must be an odd number from 1 to 15");
            }
            if (height < 2 || height > 16) {
                throw new IllegalStateException(path + ".height must be from 2 to 16");
            }
        }
    }

    public enum Direction {
        RANDOM,
        NORTH,
        SOUTH,
        EAST,
        WEST
    }
}
