# Boolean predicate pushdown drops/returns wrong rows for non-0/1 stored values

**Area:** filter · **Type:** WRONG ROWS (read) · **Catalog uses:** `sqlite` connector, default session.

## Summary
A `BOOLEAN`-declared SQLite column can store any integer (BOOLEAN has NUMERIC
affinity). The connector **reads** such a column with `ResultSet::getBoolean`,
which SQLite's JDBC driver evaluates as `value != 0` — so a stored `2` or `-1`
projects as `true`. But when a boolean equality predicate is **pushed down**,
`booleanWriteFunction` binds the Trino literal `true` as the integer `1`, so the
remote query becomes `flag = 1`. In SQLite `2 = 1` is false, so the row is
dropped. The pushed-down filter therefore disagrees with the column's own
projected value and with the non-pushdown result.

This is a correctness (wrong-rows) bug, not just an error: the *same query*
returns *different row sets* depending on whether predicate pushdown is enabled,
and the pushdown-ON answer contradicts what `SELECT flag` returns for the row.

## Minimal reproduction
Stored data: one row, `flag` = integer `2` in a `BOOLEAN` column.

| query | pushdown ON | pushdown OFF | correct (= projection) |
|---|---|---|---|
| `SELECT id, flag`                 | `(1, true)` | `(1, true)` | `(1, true)` |
| `SELECT id WHERE flag = true`     | `{}`  ❌    | `{1}`       | `{1}` |
| `SELECT id WHERE flag <> true`    | `{1}` ❌    | `{}`        | `{}`  |

`EXPLAIN` for the ON case shows the predicate is pushed:
`TableScan[... main.t constraint on [flag] ...]` (no `ScanFilterProject`).

With pushdown OFF the predicate is applied by Trino on the already-read value,
which is consistent with the projection. So pushdown ON is the wrong one.

Reproduced identically with stored values `2`, `-1` (any non-0/1 truthy int):
`flag = true` ON omits them, OFF keeps them.

## Why this is the connector's behavior, and the contradicted claim
The connector exposes these columns *as booleans* and chooses to read any
non-zero value as `true` (`SqliteClient.toColumnMapping` → `case BOOLEAN ->
booleanColumnMapping()`, SqliteClient.java:336). Having surfaced `2` as `true`,
a filter `flag = true` must match that row. The ground-rules/docs state booleans
are stored as 0/1, but the connector does not reject or normalize other values
on read — it silently maps them to `true`, then silently excludes them under
pushdown. The projection and the filter must agree; here they do not.

## Suspected code path
- Read: `SqliteClient.toColumnMapping` → `booleanColumnMapping()`
  (SqliteClient.java:336). `booleanColumnMapping` uses `ResultSet::getBoolean`
  (StandardColumnMappings.java:103), which for SQLite-JDBC is `value != 0`.
- Pushdown write: the same mapping's `booleanWriteFunction`
  (StandardColumnMappings.java:106-109) binds `true` via
  `PreparedStatement::setBoolean`, i.e. integer `1`.
- Predicate build: `SqliteQueryBuilder.toPredicate` (non-text branch) delegates
  to `DefaultQueryBuilder`, emitting `flag = ?` bound to `1`.
- Mismatch: read semantics are `!= 0`; pushed-down compare semantics are `= 1`.
  Any stored value in `{..,-2,-1,2,3,..}` reads `true` but fails `= 1`.

The connector adds `COLLATE BINARY` for the text path precisely so pushed-down
comparisons match Trino's read semantics; the analogous normalization is missing
for booleans. A correct fix would either disable boolean predicate pushdown, or
rewrite the pushed predicate to `flag <> 0` / `flag = 0` semantics rather than
`= 1` / `= 0`.

## sqlite versions
native builder sqlite3 3.45.1; driver bundled engine 3.53.4 (confirmed via
`SELECT * FROM TABLE(boolbug.system.query(query => 'select sqlite_version()'))`).
Behavior is not version-dependent: the read/write asymmetry is in the JDBC
mapping, and the driver passthrough `SELECT id FROM t WHERE flag = 1` returns `{}`
(agrees with pushdown-ON), confirming SQLite itself evaluates `2 = 1` as false.
