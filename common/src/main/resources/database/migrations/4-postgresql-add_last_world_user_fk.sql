/* Remove orphaned rows and link last-world data to its owning user */
DELETE FROM "%last_world_data%" AS last_world
WHERE NOT EXISTS (
    SELECT 1 FROM "%player_data%" AS player WHERE player.uuid = last_world.user_uuid
);

ALTER TABLE "%last_world_data%"
    ADD CONSTRAINT "%last_world_data%_user_uuid_fk"
        FOREIGN KEY (user_uuid) REFERENCES "%player_data%" (uuid)
            ON DELETE CASCADE ON UPDATE CASCADE;
