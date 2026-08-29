# Add the world UUID column to the last worlds table
ALTER TABLE `%last_world_data%`
    ADD COLUMN `world_uuid` char(36) NULL;
