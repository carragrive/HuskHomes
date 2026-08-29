-- Rebuild the table to add its user foreign key, dropping existing orphaned rows
PRAGMA foreign_keys = OFF;

CREATE TABLE `%last_world_data%_migration`
(
    `user_uuid`   char(36)     NOT NULL,
    `server_name` varchar(255) NOT NULL,
    `world_name`  varchar(255) NOT NULL,
    `world_uuid`  char(36)     NULL,
    `world_key`   varchar(255) NULL,

    PRIMARY KEY (`user_uuid`, `server_name`),
    FOREIGN KEY (`user_uuid`) REFERENCES `%player_data%` (`uuid`) ON DELETE CASCADE ON UPDATE CASCADE
);

INSERT INTO `%last_world_data%_migration` (`user_uuid`, `server_name`, `world_name`, `world_uuid`, `world_key`)
SELECT last_world.`user_uuid`, last_world.`server_name`, last_world.`world_name`,
       last_world.`world_uuid`, last_world.`world_key`
FROM `%last_world_data%` AS last_world
INNER JOIN `%player_data%` AS player ON player.`uuid` = last_world.`user_uuid`;

DROP TABLE `%last_world_data%`;

ALTER TABLE `%last_world_data%_migration` RENAME TO `%last_world_data%`;

CREATE INDEX IF NOT EXISTS `%last_world_data%_server_world`
    ON `%last_world_data%` (`server_name`, `world_uuid`);

PRAGMA foreign_keys = ON;
