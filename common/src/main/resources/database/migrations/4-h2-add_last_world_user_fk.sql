-- Remove orphaned rows, align UUID types, and link last-world data to its owning user
DELETE FROM `%last_world_data%`
WHERE `user_uuid` NOT IN (SELECT `uuid` FROM `%player_data%`);

ALTER TABLE `%last_world_data%`
    ALTER COLUMN `user_uuid` UUID;

ALTER TABLE `%last_world_data%`
    ADD CONSTRAINT `%last_world_data%_user_uuid_fk`
        FOREIGN KEY (`user_uuid`) REFERENCES `%player_data%` (`uuid`)
            ON DELETE CASCADE ON UPDATE CASCADE;
