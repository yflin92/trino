# metadata-passthrough — hunter notes (run sqlite-20261008-1731)

Target: SQLite connector PR yflin92/trino#1 @ 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6,
built as plugin for trinodb/trino:483.
native sqlite3 = 3.45.1 ; driver bundled engine = 3.53.4.
Session props recorded: write_parallelism=1 (default), standard JDBC pushdown toggles.

## CONFIRMED FINDING
- **unicode-case-collision-wrong-table** — two SQLite tables distinct only by a
  non-ASCII case pair (`KÖLN` vs `köln`) collide to one Trino identifier because
  Trino lowercases with Unicode rules while `getTables` matches with ASCII-only
  `COLLATE NOCASE`. `SHOW TABLES`/`information_schema` show only one; the other
  table is unreachable and a query naming it silently returns the wrong table's
  rows. See that dir's finding.md. In scope (wrong table / wrong rows).

## Seeds evaluated

### Case-insensitive name resolution
- "Two tables T and t" — **FALSE PREMISE**: SQLite rejects a second table whose
  name differs only by ASCII case (`CREATE TABLE "t"` fails when `"T"` exists:
  "table t already exists"). Likewise two columns `A`/`a` in one table are
  rejected ("duplicate column name: a"). So the pure-ASCII case-collision the
  seed imagined cannot be constructed in SQLite.
- The real, constructible variant is the **Unicode** one above: SQLite dedups
  identifiers with ASCII-only folding, so non-ASCII case pairs ARE allowed and
  DO collide under Trino → the confirmed finding.
- A single mixed-case table (`MixedCase`) resolves correctly from Trino
  (lowercased `mixedcase`), round-trips fine.
- Column-level Unicode variant (`GÖND` INTEGER + `göND` TEXT in one table):
  SQLite allows it; Trino `SELECT *` fails with
  `Multiple entries with same key: gönd=...`. That is an ERROR (out of scope),
  but `SHOW COLUMNS` shows two `gönd` columns of different types — misleading
  metadata. Noted here, not filed (error class).

### Hidden/system tables, views, quoted names
- `sqlite_*` tables correctly excluded; a user table named exactly `sqlite`
  (6 chars, no underscore) is correctly INCLUDED (filter requires `sqlite_`).
- Views resolve and return correct rows.
- Quoted mixed-case names resolve correctly.

### query() passthrough SAFETY — claim HOLDS in practice (but see latent risk)
Verified by running each write form through `query()` then dumping the file
natively (md5 unchanged, row counts unchanged) — nothing executed:
- bare `INSERT` / `CREATE TABLE` / writing `PRAGMA` → fail at the metadata probe
  (`getTableHandle`: "column 1 out of bounds" — no ResultSetMetaData).
- `SELECT 1; DROP TABLE t` / `SELECT 1; INSERT ...` (statements after `;`) →
  `near ";": syntax error` (driver prepares only the first statement).
- `INSERT/UPDATE/DELETE ... RETURNING` (DML that DOES return a result set, so it
  passes the metadata probe) → fail at EXECUTION because the data-fetch wraps the
  user query as `SELECT ... FROM (<query>) o`, and SQLite rejects DML inside a
  subquery (`near "INSERT"/"UPDATE"/"DELETE": syntax error`). No write happens.
- `query()` returns the SAME rows as the equivalent table scan; computed columns
  (`count(*)`, arithmetic) return as VARCHAR with correct values; `--` comments
  inside the query work.

  **LATENT RISK (not a confirmed wrong-rows/write bug, documented for triage):**
  The connector's JDBC connection is actually **READ-WRITE**, not read-only.
  `SqliteClient.getConnection(ConnectorSession)` (lines 148-154) OVERRIDES
  `BaseJdbcClient.getConnection` (which does `connection.setReadOnly(true)`, base
  line 660) and deliberately omits the `setReadOnly` call, with the comment that
  "the driver only supports opening a connection as read-only, not changing the
  read-only status of an open connection". Verified against sqlite-jdbc 3.53.4:
  `setReadOnly(true)` after connect throws
  ("Cannot change read-only flag after establishing a connection"), and the
  connection URL (`jdbc:sqlite:/data/<file>.db`, built in SqliteClientModule
  connectionFactory, lines 51-59) sets NO read-only mode — so the connection is
  read-write and a native write on it succeeds (verified with the bundled
  driver). Today the only thing preventing a `query()`-driven write is the
  base-jdbc subquery-wrapping of the passthrough query; the read-only safety net
  the base client provides is gone. If a future base-jdbc change (or a connector
  path that executes a passthrough query un-wrapped) exposes a statement that
  both yields ResultSetMetaData and writes, the write would land. Recommend
  pinning read-only via the JDBC URL (`?open_mode=1` / SQLiteConfig read-only)
  rather than relying solely on wrapping.

### getColumns
- `pragma_table_info` ordering by `cid` matches native column order.
- NULLABLE derived from `notnull` observed consistent in simple cases.
- No single-type mis-report found in the cases tried (BLOB/DATE/DECIMAL mapping
  looked correct for declared types).

## Out of scope seen (errors/crashes, noted only)
- `SELECT *` over a table with two Unicode-case-colliding columns →
  "Multiple entries with same key" (error).
- Reading `PRAGMA x` via query() → "no such table: PRAGMA" (wrapping; error).
