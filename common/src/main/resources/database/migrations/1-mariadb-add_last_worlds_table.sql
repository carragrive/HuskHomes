# Create the last worlds table if it does not exist
CREATE TABLE IF NOT EXISTS `%last_world_data%`
(
    `user_uuid`   char(36)     NOT NULL,
    `server_name` varchar(255) NOT NULL,
    `world_name`  varchar(255) NOT NULL,
    `world_key`   varchar(255) NULL,

    PRIMARY KEY (`user_uuid`, `server_name`),
    FOREIGN KEY (`user_uuid`) REFERENCES `%player_data%` (`uuid`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
