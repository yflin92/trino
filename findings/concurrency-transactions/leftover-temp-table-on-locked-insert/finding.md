# Failed INSERT under sustained lock contention leaves an orphan `tmp_trino_*` table permanently in the schema

**Area:** concurrency-transactions
**Target:** SQLite connector, PR yflin92/trino#1 @ 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6, built as a plugin for trinodb/trino:483
**Versions:** native sqlite3 3.45.1 / driver-bundled 3.53.4 (behaviour identical for this finding — it is a locking/cleanup issue, not a version-dependent SQL semantics issue)
**Severity:** medium — not lost/duplicated *data rows*, but **wrong persistent schema state** (orphan user-visible table) that survives a failed statement and accumulates over repeated failures.

## Hypothesis

base-jdbc's transactional INSERT path stages rows in a temporary table
(`CREATE TABLE tmp_... AS SELECT ... WHERE 0=1`, then page-sink `INSERT`s, then
`finishInsertTable` copies into the target and `DROP`s the temp). When the INSERT
fails, the temp table must be dropped by the rollback action. If SQLite's single
writer lock is held by another writer long enough for the rollback's `DROP TABLE`
to *also* time out, the temp table is never removed and leaks into the schema.

## What differs (oracle = native dump of sqlite_schema)

1. Native writer opens `BEGIN EXCLUSIVE` on `/tmp/data/conc.db` and holds it 8s.
2. Trino runs `INSERT INTO conc.main.big SELECT ...` (100k rows).
   - The connector successfully runs `CREATE TABLE tmp_trino_<hex> AS SELECT ... WHERE 0=1`
     in the brief instant before the native lock is grabbed (confirmed by polling
     sqlite_schema — the tmp table appears).
   - The page-sink `INSERT` into the temp table then hits the native lock, waits
     ~3s (the xerial driver's default busy_timeout), and fails:
     `Query ... failed: [SQLITE_BUSY] The database file is locked (database is locked)`.
   - The rollback action runs `DROP TABLE tmp_trino_<hex>`, but the native lock is
     **still held** (8s > ~3s busy_timeout for the page-sink + another ~3s for the DROP),
     so the DROP *also* fails and is swallowed.
3. Native dump of `sqlite_schema` after:

```
('table', 'big')                      <- target, 0 rows (correct, no data lost/dup)
('table', 'tmp_trino_a755c15a_ce92198c')  <- ORPHAN, permanent
```

`SHOW TABLES FROM conc.main` now lists `tmp_trino_a755c15a_ce92198c` as a normal
user table, and Trino can `SELECT` from it. It never goes away; each failed INSERT
under these conditions adds another orphan.

## Control proving the mechanism (hold time vs busy_timeout)

| native lock hold | INSERT result | orphan tmp table? |
|------------------|---------------|-------------------|
| 4s  (past page-sink busy_timeout, released before DROP's timeout) | fails BUSY (3/3) | **no** (0/3) |
| 8s  (covers page-sink busy_timeout AND the rollback DROP's busy_timeout) | fails BUSY (3/3) | **yes** (3/3) |

The 4s case shows the design *does* clean up when the DROP can acquire the lock;
the leak is specifically the rollback `DROP` itself timing out. Reproduced 3/3 at 8s.

## Scope note

The triggering error (SQLITE_BUSY) is itself out of scope (lock timeout). What is
in scope is that a **failed** INSERT **leaves wrong persistent state**: an orphan
`tmp_trino_*` staging table that is permanent and exposed as a user table. The
hunter brief explicitly includes "leaves wrong state" and "leftover temp tables".
No data rows are lost or duplicated into the target (`big` stays at 0 / at its
correct committed count), so this is schema corruption, not row corruption.

## Suspected code path

- `plugin/trino-base-jdbc/.../BaseJdbcClient.java`
  - `beginInsertTable` (~L998): `copyTableSchema` creates `tmp_trino_<hex>`.
  - `finishInsertTable` (~L1167): page-sink writes + `INSERT INTO target SELECT FROM temp`.
  - `rollbackTemporaryTableCreation` (~L1466) -> `dropTable` (~L1460):
    `DROP TABLE tmp_...` via `execute(session, sql)` — a plain autocommit statement
    with no retry and no guarantee it can acquire the lock. When it throws SQLITE_BUSY
    the exception is collected in `DefaultJdbcMetadata.rollback()` (~L1278) and the temp
    table is simply left behind.
- `plugin/trino-base-jdbc/.../RetryingJdbcClient.java`: `dropTable`/`rollbackTemporaryTableCreation`
  are **not** retried; and SQLITE_BUSY surfaces as `org.sqlite.SQLiteException`, which is
  not a `SQLTransientException`, so even the retry policy would not cover it.
- The SQLite connector (`SqliteClientModule`) builds a plain `DriverConnectionFactory`
  with no explicit `busy_timeout` tuning, leaving the driver default (~3s) in play.

## Repro

`repro.sh` is self-contained (assumes env-setup.sh sourced + prep_files already run):
builds the db + catalog, starts Trino, demonstrates the 8s-hold leak and the 4s-hold
control. See `capture.txt` / `table.after.txt` for a recorded run and `expected.txt`
for expected vs observed.
