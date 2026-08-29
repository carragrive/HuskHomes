# Remove orphaned rows and link last-world data to its owning user
DELETE last_world
FROM `%last_world_data%` AS last_world
LEFT JOIN `%player_data%` AS player ON player.`uuid` = last_world.`user_uuid`
WHERE player.`uuid` IS NULL;

ALTER TABLE `%last_world_data%`
    ADD CONSTRAINT `%last_world_data%_user_uuid_fk`
        FOREIGN KEY (`user_uuid`) REFERENCES `%player_data%` (`uuid`)
            ON DELETE CASCADE ON UPDATE CASCADE;
