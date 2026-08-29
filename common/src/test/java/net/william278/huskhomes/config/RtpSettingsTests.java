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

import de.exlll.configlib.YamlConfigurations;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpSettingsTests {

    @Test
    void loadsDestinationProfiles() {
        final Settings.RtpSettings settings = load("""
                rtp:
                  mode: CROSS_SERVER
                  default_destination: resources
                  destinations:
                    resources:
                      aliases:
                        - resources01
                      server: resources01
                      world: world_nether
                      profiles:
                        default:
                          priority: 0
                          options:
                            max_y: 255
                            allow_underground: true
                        bedrock:
                          priority: 100
                          conditions:
                            all:
                              - type: PERMISSION
                                value: huskhomes.rtp.bedrock
                          options:
                            max_y: 127
                            allow_underground: true
                """).getRtp();

        settings.validate();
        assertEquals(Settings.RtpSettings.Mode.CROSS_SERVER, settings.getMode());
        assertTrue(settings.findDestination("resources01").isPresent());
        assertEquals(127, settings.getDestinations().get("resources")
                .getProfiles().get("bedrock").getOptions().getMaxY());
    }

    @Test
    void rejectsDuplicateProfilePriorities() {
        final Settings.RtpSettings settings = load("""
                rtp:
                  destinations:
                    overworld:
                      world: world
                      profiles:
                        default:
                          priority: 0
                        other:
                          priority: 0
                          conditions:
                            all:
                              - type: PERMISSION
                                value: huskhomes.rtp.other
                """).getRtp();

        assertThrows(IllegalStateException.class, settings::validate);
    }

    @Test
    void acceptsConditionsOnTheDefaultProfile() {
        final Settings.RtpSettings settings = load("""
                rtp:
                  default_destination: overworld
                  destinations:
                    overworld:
                      world: world
                      profiles:
                        default:
                          priority: 0
                          conditions:
                            all:
                              - type: PERMISSION
                                value: huskhomes.rtp.overworld
                """).getRtp();

        settings.validate();
        assertEquals(1, settings.getDestinations().get("overworld")
                .getProfiles().get("default").getConditions().getAll().size());
    }

    @Test
    void treatsBlankAndNoneDefaultDestinationsAsUnset() {
        for (String value : new String[]{"''", "none", "NONE"}) {
            final Settings.RtpSettings settings = load("""
                    rtp:
                      default_destination: %s
                      destinations:
                        overworld:
                          world: world
                          profiles:
                            default:
                              priority: 0
                    """.formatted(value)).getRtp();

            settings.validate();
            assertFalse(settings.hasDefaultDestination());
            assertTrue(settings.getDefault().isEmpty());
        }
    }

    @Test
    void rejectsDefaultDestinationNamingNoConfiguredDestination() {
        final Settings.RtpSettings settings = load("""
                rtp:
                  default_destination: resources
                  destinations:
                    overworld:
                      world: world
                      profiles:
                        default:
                          priority: 0
                """).getRtp();

        assertThrows(IllegalStateException.class, settings::validate);
    }

    private Settings load(String yaml) {
        return YamlConfigurations.read(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                Settings.class,
                ConfigProvider.YAML_CONFIGURATION_PROPERTIES.build()
        );
    }
}
