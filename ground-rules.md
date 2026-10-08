# Trino connector correctness fleet: SQLite connector (PR yflin92/trino#1)

This file holds the shared ground rules for the fleet run. The COORDINATOR builds
the plugin and spawns hunters + triage. Hunters find wrong-rows / wrong-DML bugs.
Only TRIAGE creates tasks.

## What this connector is

- SQLite is embedded: the "database" is a file the Trino server opens via
  `connection-url=jdbc:sqlite:/data/<file>.db`. No database container.
- The file is exposed as a single schema, `main`.
- Pushdown is limited to predicates, LIMIT and top-N (plus the `query` table
  function). No aggregation or join pushdown.
- Writes: CREATE TABLE, CTAS, INSERT, DELETE, UPDATE, TRUNCATE (as DELETE) and
  ALTER TABLE (add/rename/drop column; rename table). Write parallelism defaults
  to 1 (SQLite single writer).
- Types come from SQLite type-affinity rules applied to the *declared* column
  type, not stored values.
- PR says collation handled by adding `COLLATE BINARY` to pushed-down text
  comparisons, IN lists, top-N ORDER BY (SqliteQueryBuilder). Dates stored as
  `YYYY-MM-DD` text, pushdown limited to years 0000-9999.
- Documented "Known limitations" are part of the contract; matching them is not
  a new bug.

## Ground rules

- **Pinned target:** PR head SHA 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6,
  built as a plugin against released Trino 483, loaded into trinodb/trino:483.
- **Run directory:** /agentfs/shared/trino-fleet/<RUN_ID>/ is the only channel
  between sessions. Contains run.json, plugin/trino-sqlite-483/,
  findings/<area>/<slug>/, summary.md.
- Only TRIAGE calls task_create. Hunters never create tasks.
- **Docker:** daemon not running by default; /etc/docker/daemon.json pins vfs
  which won't fit the image. Start with overlay2:
  `sudo dockerd --config-file /dev/null --storage-driver=overlay2 --data-root=/var/lib/docker-ov --group docker >/tmp/dockerd.log 2>&1 &`
  Wait for `docker info` to report overlay2. Pull only trinodb/trino:483.
- **Running Trino:**
  - Run with `--ulimit nofile=131072:131072`.
  - Cap heap: copy /etc/trino/jvm.config out of image, replace
    -XX:InitialRAMPercentage / -XX:MaxRAMPercentage with -Xms1G / -Xmx2G, mount
    over /etc/trino/jvm.config.
  - Copy plugin dir to local disk (e.g. /tmp/plugin/trino-sqlite-483), bind-mount
    read-only at /usr/lib/trino/plugin/sqlite. Do NOT bind-mount /agentfs paths.
  - DB files in local dir (e.g. /tmp/data, mode 777, files 666; container uid
    1000) mounted at /data. Each catalog is a file in /etc/trino/catalog/ with
    connector.name=sqlite and connection-url=jdbc:sqlite:/data/<file>.db. One
    catalog per file. Restart Trino after adding catalogs.
  - docker stats reports 0 B here; measure memory with ps/free.
- **Native oracle:** Python sqlite3 module (or sqlite3 CLI) on the same file.
  Record sqlite_version() for it and for the driver's bundled engine via
  `SELECT * FROM TABLE(<catalog>.system.query(query => 'select sqlite_version()'))`.
  Never write to a file natively while Trino is querying it.
- **Source:** the PR branch is checked out; read:
  plugin/trino-sqlite/src/main/java/io/trino/plugin/sqlite/SqliteClient.java
  and SqliteQueryBuilder.java, tests in plugin/trino-sqlite/src/test/,
  docs/src/main/sphinx/connector/sqlite.md, plugin/trino-base-jdbc/.

See the pasted coordinator prompt (full HUNTER PROMPT and TRIAGE PROMPT) for the
per-area seed hypotheses and the oracle tiers.
