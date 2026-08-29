# Index last worlds by server and world for cleanup
CREATE INDEX `%last_world_data%_server_world`
    ON `%last_world_data%` (`server_name`, `world_uuid`);
