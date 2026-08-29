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
| `%huskhomes_status_<server>%`      | The readiness of `<server>`, as worded in your locales file&sect;   | Online              |
| `%huskhomes_raw_status_<server>%`  | The readiness of `<server>`, as a fixed keyword&sect;               | READY               |

&dagger;Only effective on servers that make use of the [[Economy Hook]].

&Dagger;See [Last world per server](#last-world-per-server) below.

&sect;See [Server status](#server-status) below.

## Server status

`%huskhomes_raw_status_<server>%` resolves to one of four keywords, and never to anything else:

| Value      | Meaning                                                                                    |
|------------|--------------------------------------------------------------------------------------------|
| `READY`    | The server is up and accepting teleports                                                     |
| `STARTING` | The server is up but still loading; teleports to it are refused until it finishes             |
| `STOPPING` | The server is shutting down                                                                  |
| `UNKNOWN`  | The server is down, has never been seen, or is not a server this network knows about          |

Because the value is a fixed keyword rather than a message, it is the one to compare against in conditions.

`%huskhomes_status_<server>%` resolves the same state to a message you set in your locales file, so it is the one to
display:

```yaml
server_status_ready: 'Online'
server_status_starting: 'Starting'
server_status_stopping: 'Stopping'
server_status_unknown: 'Unknown'
```

Set them to whatever you like — the value is passed on as written, so any formatting in it is only rendered if
whatever displays the placeholder understands that formatting. A state with no message set falls back to its keyword.

Both require [Redis](https://william278.net/docs/huskhomes/redis) as the broker; it is what carries server presence.
Servers announce themselves as they start, mark themselves ready once loaded, and refresh a short-lived lease every
few seconds, so a server that crashes or is killed drops to `UNKNOWN` on its own. On the plugin message broker there
is no presence to report and every server reads as `READY`. Asking for the current server always returns `READY`.

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
