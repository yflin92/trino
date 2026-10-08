# BOOLEAN column holding a fractional REAL (|v| < 1) is misread as FALSE

## Verdict
**WRONG ROWS (read).** A `BOOLEAN`-declared column that stores a REAL value whose
absolute value is in `(0, 1)` — e.g. `0.5`, `0.9`, `-0.3`, `0.001` — is read back
by Trino as `false`, but SQLite's own boolean semantics evaluate every one of
those non-zero values as **true**.

## Hypothesis
`BOOLEAN` columns are mapped to `booleanColumnMapping()`
(`SqliteClient.toColumnMapping()` case `BOOLEAN`, SqliteClient.java:336), whose
read function is `ResultSet::getBoolean`
(`StandardColumnMappings.booleanColumnMapping()`, line 101-103). On a storage-class
REAL value the SQLite JDBC driver's `getBoolean` truncates toward zero first
(`0.5 -> 0 -> false`) rather than applying SQLite's "non-zero is true" rule, so any
fractional magnitude below 1 reads as `false`.

## Minimal reproduction
```sql
-- native: CREATE TABLE t(flag BOOLEAN); INSERT INTO t VALUES (0.5);
SELECT flag FROM boolf.main.t;
```

## What differs (the diff)
| source | flag value | notes |
|---|---|---|
| Native stored value (python sqlite3 3.45.1) | `0.5`, `typeof='real'` | `CASE WHEN flag THEN ... END` = **true** |
| Bundled driver 3.53.4 (`system.query`) | `CASE WHEN flag THEN 1 ELSE 0 END` = **1 (true)** | SQLite itself: 0.5 is truthy |
| **Trino read (pushdown ON)** | **`false`** | WRONG |
| **Trino read (pushdown OFF)** | **`false`** | WRONG, so not a pushdown artifact |

Broader sweep (table `boolmin`, same catalog build): real values `0.5, 0.9, -0.3,
0.001` all read as `false` in Trino while SQLite truthiness is `true` for all;
`1.5` reads as `true` (|v|>=1, truncates to 1). The boundary is exactly integer
truncation toward zero, confirming the mechanism.

## Documented contract it contradicts
`docs/src/main/sphinx/connector/sqlite.md` type-mapping table (lines ~73-75):
`BOOLEAN` declared type maps to Trino `BOOLEAN`, and the connector's own class
javadoc (SqliteClient.java:99-104) states "values are assumed to be stored in the
storage class of the column's affinity" and are read accordingly. SQLite's boolean
contract is that **any non-zero numeric value is true**
(https://www.sqlite.org/datatype3.html#boolean_datatype). Reading `0.5` as `false`
contradicts both SQLite's boolean semantics and the value SQLite itself computes for
the same column. The connector does not document that fractional reals in a BOOLEAN
column read as `false`; the type-mapping table's only `BOOLEAN` note is empty.

## Suspected code path (cited lines)
- `SqliteClient.toColumnMapping()` — `case BOOLEAN -> booleanColumnMapping();`
  (SqliteClient.java:336).
- `SqliteClient.classify()` — declared type `BOOLEAN` returns `SqliteType.BOOLEAN`
  (SqliteClient.java:417).
- `StandardColumnMappings.booleanColumnMapping()` uses `ResultSet::getBoolean`
  (trino-base-jdbc StandardColumnMappings.java:101-103). The SQLite JDBC driver's
  `getBoolean` on a REAL storage-class value truncates to an integer before the
  `!= 0` test, so `0.5 -> 0 -> false`, diverging from SQLite's native truthiness.

A correct boolean read function would mirror SQLite's rule (true iff the numeric
value is non-zero, i.e. `getDouble(col) != 0.0` for a REAL), rather than relying on
the driver's integer-truncating `getBoolean`.

## Scope / caveats
- Native engine here is sqlite3 3.45.1; the driver bundles 3.53.4. Both agree the
  value is truthy (`sqlite_truth=1`), so this is NOT a version artifact — the
  divergence is entirely in the connector's `getBoolean`-based read.
- Trigger requires a BOOLEAN column that actually stores a fractional REAL. SQLite
  permits this because BOOLEAN has NUMERIC affinity and a non-integer-coercible
  value is kept as REAL.
