# type-mapping-read — hunter notes (run sqlite-20261008-1731)

Target: SQLite connector PR yflin92/trino#1 @ 7ce55e6b, plugin on trinodb/trino:483.
Native engine python sqlite3 **3.45.1**; driver bundles **3.53.4** (confirmed via
`system.query('select sqlite_version()')`).

Oracle used: Trino read (pushdown on + off) vs native stored value (python 3.45.1)
vs bundled driver 3.53.4 (`system.query`) vs the documented type-mapping table
(docs lines ~52-121).

## CONFIRMED FINDING (wrong rows)
- **boolean-real-fractional-misread** — a `BOOLEAN` column holding a REAL value
  with `|v| < 1` (e.g. `0.5`, `0.9`, `-0.3`, `0.001`) reads as `false` in Trino, but
  SQLite's own boolean semantics make every non-zero value `true`. Mechanism:
  `booleanColumnMapping()` uses `ResultSet::getBoolean`, and the SQLite JDBC driver
  truncates the REAL to an integer (`0.5 -> 0`) before the `!= 0` test. Confirmed
  pushdown ON and OFF, deterministic over 3 runs. See finding dir.
  - Side observation (NOT separately filed): on this row, predicate `flag = false`
    AND `flag = true` both return 0 rows — the pushed-down equality compares the
    stored `0.5` against `0`/`1` natively, matching neither. The *read value* is the
    in-scope bug; this filter mismatch corroborates that `0.5` is mishandled.

## Hypotheses tested that were CONSISTENT (no bug) — matched native + docs
- **INTEGER column holding REAL** (`intaff`, `intreal`): `3.7->3`, `2.5->2`,
  `9.5->9`, `-3.7->-3`. Trino `getLong` truncates toward zero, identical to SQLite
  `CAST(v AS INTEGER)`. Huge reals (`1e19`,`1e30`) saturate to Long.MAX/MIN,
  identical to SQLite CAST saturation (`intof`).
- **INTEGER column holding TEXT/BLOB** (`intaff`): `'hello'->0`, `x'41'->0` — matches
  documented "reads a text value in an INTEGER column as 0".
- **REAL column holding big integer** (`realaff`): `9223372036854775807 ->
  9.223372036854776E18` (double rounding), `'abc'->0.0`. Consistent.
- **DECIMAL(p,s) reading floats, HALF_UP** (`decrnd`, `dec2`): `1.005->1.01`,
  `2.675->2.68`, `-2.675->-2.68`, `0.00015->0.0002`, `123.455->123.46`. These match
  the **shortest-decimal-repr** of the double rounded HALF_UP (driver's
  `getBigDecimal` returns shortest repr, not the exact binary value). Self-consistent
  with the bundled driver; not a wrong row.
- **Affinity edge declared types** (`affedge`): `FLOATING POINT` (contains INT) ->
  bigint; `CHARINT` -> bigint; `STRING`/`NUMERIC`/`DATETIME`/empty -> varchar;
  `DECIMAL(16,2)` (p>15) -> varchar. All match SQLite's own affinity order AND the
  documented table.
- **BLOB in TEXT col / TEXT in BLOB col / int in BLOB col** (`blobtext`): values read
  as the same bytes/text that SQLite's own `CAST`/`hex` produce. e.g. integer 42 in a
  BLOB column -> `34 32` == SQLite `hex(v)='3432'`. Consistent.
- **BOOLEAN holding 2, -1, 0, text** (`bool2`, `boolval`): `2->true`, `-1->true`,
  `0->false`, text `'true'/'yes'->false`. All match SQLite truthiness. (Only the
  *fractional real* case diverges — see finding.)
- **Views with computed/expression columns** (`viewtest`): empty declared type ->
  varchar; values `11`, `25.0`, `x!`, `2` read as text repr, correct & matches docs
  (~84-87, ~174-175).
- **DECIMAL scale > precision** -> classify returns ANY/varchar (reads text repr).

## OUT OF SCOPE (errors/crashes — noted only)
- **DECIMAL(p,s) value exceeding declared precision** (`decof`): reading
  `12345.67` from a `DECIMAL(5,2)` column throws `SERIALIZATION_ERROR` and the
  CSV client reports "Query is gone (server restarted?)"; the server itself stays
  up (health check OK afterwards). This is an error, not a wrong row — out of scope.
  Values that fit (`999.99`) read fine.
- **Version note**: `DECIMAL(16,2)`-as-varchar value `12345678901234.56` reads as
  `12345678901234.561` under the bundled 3.53.4 driver vs `12345678901234.6` from
  the sqlite3 3.45.1 CLI. This is a SQLite **engine-version** difference in float->text
  formatting (3.53 uses shortest round-trippable repr), consistent with the bundled
  driver; NOT a connector bug.

## Session / pushdown toggles (recorded)
Reads here are not pushed down (TableScan with no ScanFilter for the bare select);
the boolean finding reproduces identically with
`allow_pushdown_into_connectors=false`, so it is a pure read/column-mapping bug.
