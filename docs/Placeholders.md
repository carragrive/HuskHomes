HuskHomes (v4.0.5+) can register a hook providing a number of placeholders that will be replaced with their appropriate values.

This requires [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI).

## List of placeholders
| Placeholder                        | Description                                                         | Example Value       |
|------------------------------------|---------------------------------------------------------------------|---------------------|
| `%huskhomes_homes_count%`          | The number of homes this user has set                               | 3                   |
| `%huskhomes_max_homes%`            | The maximum number of homes this user can set                       | 10                  |
| `%huskhomes_max_public_homes%`     | The number of homes this user can make public                       | 5                   |
| `%huskhomes_free_home_slots%`      | The number of homes this user can make for free&dagger;             | 5                   |
| `%huskhomes_home_slots%`           | The number of additional home slots this user has purchased&dagger; | 2                   |
| `%huskhomes_homes_list%`           | A comma-separated list of this user's homes                         | home, castle, tower |
| `%huskhomes_public_homes_count%`   | The number of homes this user has set to public                     | 3                   |
| `%huskhomes_public_homes_list%`    | A comma-separated list of this user's public homes                  | castle, tower       |
| `%huskhomes_ignoring_tp_requests%` | Whether this user is ignoring teleport requests                     | true                |
| `%huskhomes_last_world_<server>%`  | The world this user was last in on `<server>`&Dagger;               | minecraft:the_nether |

&dagger;Only effective on servers that make use of the [[Economy Hook]].

&Dagger;See [Last world per server](#last-world-per-server) below.

## Last world per server

`%huskhomes_last_world_<server>%` reports the world a player was last seen in on another server, e.g.
`%huskhomes_last_world_resources01%`. The server ID is the one in that backend's `server.yml`, and matching is exact.

Worlds are reported by their namespaced key (`minecraft:overworld`, `minecraft:the_nether`) where the server that
recorded them provided one, so the value does not depend on how each server names its world folders. If nothing is
known — an unrecognised server, or one this player has never joined — the placeholder resolves to `none`, never to a
blank or a leftover `%huskhomes_...%`, so it is safe to compare against in conditions.

Values are written when a player joins, changes world, or leaves a server. A proxy server switch counts as a leave, so
after someone moves from `resources01` to `lobby`, the lobby can see which world they came from. Asking for the
**current** server returns the player's live world rather than a stored value. Everything is read from a cache loaded
when the player joins, so resolving the placeholder never touches the database.

Requires cross-server mode with a shared database; the servers exchange nothing directly, they simply read and write
the same `huskhomes_last_worlds` table.

### Deleted worlds

Entries are keyed by the world's UUID, not its name, so a world that is deleted and replaced by a different world of
the same name is recognised as gone. Entries for a deleted world are removed by the server that owns it, in three
places:

* **While running** — deleting a world unloads it first, so HuskHomes re-checks a few seconds after any unload and
  removes the entries if the world is still unloaded *and* its folder is gone. A plain unload changes nothing.
* **After server load** — once Bukkit reports the server loaded, HuskHomes compares stored UUIDs with the loaded world
  set and deletes entries for worlds this server no longer has. World discovery happens on the server thread and the
  database cleanup runs asynchronously.

World managers that lazy-load worlds should arrange to load before HuskHomes performs this reconciliation. A pruned
entry reappears the next time a player visits that world.
