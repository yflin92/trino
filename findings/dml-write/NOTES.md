# Hunter NOTES — area `dml-write` — run sqlite-20261008-1731

Target: SQLite connector from PR yflin92/trino#1 @ `7ce55e6b32342d27fec58c7009f85abb1b0c6fe6`,
built as a plugin against Trino 483, loaded into `trinodb/trino:483`.

- Native sqlite3 CLI / python3 sqlite3: **3.45.1**
- Driver bundled engine (via `system.query('select sqlite_version()')`): **3.53.4**

## Verdict

**NO CONFIRMED wrong-rows / wrong-values bugs.** Every DML-write path I probed
stored and deleted/updated exactly the rows and values that Trino's own
(binary, case-/space-sensitive) semantics require, verified against a native
A/B diff with `typeof()` per value. The connector's `COLLATE BINARY` machinery
(`SqliteQueryBuilder`) is robust across DELETE/UPDATE, IN-lists, ranges,
negations, and combined disjuncts. Type round-trips are correct.

## Session / pushdown toggles

`SHOW SESSION LIKE '<cat>.%'` on the sqlite catalogs shows the standard JDBC
toggles: `allow_pushdown_into_connectors` (default true), and the catalog
`<cat>.*` write/pushdown knobs inherited from base-jdbc. Write parallelism is 1
(single SQLite writer), as documented. No surprises.

## Oracle method used

Per the prompt: copy db to A and B. Run the Trino DML against catalog A; run the
*intended* native-equivalent (binary-exact) and the *naive* native statement
(column's own collation) against B / inspect. Dump both tables natively with
`typeof(col)` per value and diff. For DML correctness the oracle is **Trino's
documented binary, case-sensitive, trailing-space-sensitive semantics** — a
DELETE/UPDATE that changed rows those semantics don't select, or a value that
didn't read back as written, would be the bug. None did.

## Hypotheses tested and outcomes

### HIGHEST: DELETE / UPDATE touching WRONG ROWS (collation)
- **NOCASE DELETE** `WHERE col='abc'` on `TEXT COLLATE NOCASE` with rows
  ABC/abc/AbC/xyz: Trino deleted **1** row (binary-exact 'abc'), leaving ABC,
  AbC, xyz. A naive native NOCASE delete would have removed 3. **CORRECT.**
  EXPLAIN shows `TableDelete ... constraint on [col]` (predicate pushed).
- **RTRIM DELETE** `WHERE col='abc'` on `TEXT COLLATE RTRIM` with
  abc/'abc   '/'abc\t'/xyz: Trino deleted only the exact 'abc'; native RTRIM
  would also have removed 'abc   '. **CORRECT.**
- **NOCASE range DELETE** `WHERE col > 'B'` (rows A,B,a,b): Trino deleted a,b
  (binary > 'B'); native NOCASE would delete none. **CORRECT.**
- **IN-list DELETE** `WHERE col IN ('abc','def')` (ABC,abc,DEF,def,ghi): Trino
  deleted only abc,def (binary); kept ABC,DEF,ghi. **CORRECT.** Exercises the
  `SqliteQueryBuilder` IN-list reconstruction with `COLLATE BINARY` (lines
  73–95).
- **NOT IN** `WHERE col NOT IN ('abc','xyz')` (ABC,abc,xyz,XYZ): Trino kept
  ABC,XYZ (binary); native NOCASE would exclude all. **CORRECT.** Covers the
  negated-discrete-set branch (`SqliteQueryBuilder` line 58 -> super ->
  range `toPredicate` which still adds COLLATE BINARY).
- **`<>` single value** `WHERE col <> 'abc'`: binary-correct (excludes only the
  exact 'abc'). **CORRECT.**
- **Combined IN-list + range disjunct** `WHERE col IN ('abc','def') OR col >= 'mmm'`
  on ABC,abc,def,DEF,zzz,ZZZ,mmm: Trino deleted exactly abc,def,zzz,mmm (ids
  2,3,5,7), kept ABC,DEF,ZZZ; a native NOCASE delete would have removed ALL 7.
  **CORRECT** — this stresses the disjunct reconstruction path hardest.
- **UPDATE** `SET amt=99 WHERE cat='a'` on NOCASE: only the binary-exact 'a' row
  updated; A/'A'/'B'/'b' untouched. EXPLAIN shows pushed `constraint on [cat]`.
  **CORRECT.**

### TRUNCATE
- `TRUNCATE TABLE` empties the table (runs as `DELETE FROM`, `truncateTable`
  line 273–277). Row count 0 afterwards. **CORRECT.**

### Type round-trip (CTAS / INSERT) — stored typeof + read-back (Trino & native)
- **DECIMAL(p,s), p<=15**: declared `DECIMAL(p,s)` has NUMERIC affinity so the
  value is stored as **REAL** (float), NOT decimal text. Read applies
  `shortDecimalReadFunction(..., HALF_UP)`. Checked 123456789012.34,
  99999999999999.9, 999999999999.555, 0.0001, ties (2.5,-2.5), and
  native-written floats (2.675->2.68, 2.665->2.67, 0.125->0.13, 1.005->1.01):
  all read back correctly rounded to scale despite float storage error.
  **CORRECT** (matches documented "stored as floating point, rounded to scale").
- **CHAR(n)**: maps to TEXT; `charWriteFunction` trims trailing spaces per SQL
  CHAR semantics. 'ab'->'ab', 'ab  '->'ab', '   '->''. Column reads back as
  varchar. **CORRECT / documented** (CHAR->TEXT, no length/padding preserved).
- **VARCHAR**: exact round-trip incl. empty string (stored as text '' not NULL),
  unicode (café, 😀), quotes/tabs via UPDATE SET. **CORRECT.**
- **NULL vs empty string**: `''` stored as `typeof=text`; `NULL` stored as
  `typeof=null`. Distinguished correctly. **CORRECT.**
- **VARBINARY**: `X'00010203'`->blob len 4; `X''`->blob len 0 (not NULL).
  **CORRECT.**
- **BIGINT extremes**: ±9223372036854775807/8 round-trip exact. **CORRECT.**
- **BOOLEAN**: true/false stored as integer 1/0, read back as boolean.
  **CORRECT.**
- **REAL**: Trino REAL (float32) value stored as real; read back as DOUBLE
  (declared REAL affinity -> doubleColumnMapping). 0.1 -> 0.10000000149...
  consistently. Round-trip preserves the stored value. **CORRECT / documented.**
- **DATE in range**: stored as `YYYY-MM-DD` text; 0000-01-01, 2020-06-15,
  9999-12-31 all stored and read correctly. **CORRECT.**
- **DATE out of range**: `0000-01-01 - 1 day` and `9999-12-31 + 1 day` are
  **rejected** with INVALID_ARGUMENTS ("Date must be between 0000-01-01 and
  9999-12-31"), not silently stored. **CORRECT** (dateWriteFunction, line
  472–480).

### ALTER TABLE on a table with data + index + trigger
- ADD COLUMN extra, RENAME COLUMN name->fullname, DROP COLUMN val: all three
  preserved the 3 data rows; the index auto-followed the rename
  (`CREATE INDEX idx_name ON t("fullname")`); the AFTER UPDATE trigger stayed
  intact. **CORRECT.**

## Out of scope (errors/crashes — noted, not findings)
- **DELETE/UPDATE on an ANY-classified column** (declared type `NUM`, which does
  not match any affinity keyword and is not NUMERIC/DECIMAL/BOOLEAN/DATE, so it
  is forced to varchar with DISABLE_PUSHDOWN): `DELETE ... WHERE v='hello'`
  fails with `This connector does not support modifying table rows`. This is an
  **error, not wrong rows** — the row stayed intact. Out of scope per prompt.
  (Mechanism: the merge/row-id predicate can't be pushed for a DISABLE_PUSHDOWN
  column, so the engine refuses rather than corrupting.)

## Files / catalogs used (all under /tmp/data)
nocase*.db, ne.db, rt*.db, inl*.db, rng*.db, mix*.db, upd*.db, alt.db, w.db,
any*.db, tr.db, dt/dr/drn tables. Each catalog mounted one file; Trino restarted
after catalog changes.

## Suspected-code-path reading
`SqliteClient.java`: toWriteMapping (344), dateWriteFunction (472),
truncateTable (273), addColumn (252), classify (397), decimalColumnMapping (445).
`SqliteQueryBuilder.java`: IN-list rebuild with COLLATE BINARY (56–96), single
value/range COLLATE BINARY (99–106). All behaved as written; no defect located.
