# Finding: Unicode case-collision → missing table + WRONG TABLE resolution

**Area:** metadata-passthrough
**Severity:** wrong rows / wrong table (in scope) — silent data loss and silent wrong result
**Determinism:** fully deterministic (3/3 runs identical)

## Hypothesis
Trino lowercases remote identifiers with Unicode-aware rules, while the
connector's `getTables` matches table names with SQLite's `COLLATE NOCASE`,
which folds **ASCII only**. Two SQLite tables that differ only by a non-ASCII
case pair are therefore *distinct in SQLite* but *collide to one identifier in
Trino*. The collision is resolved silently and incorrectly.

## Setup (native oracle)
SQLite's identifier dedup is ASCII-only, so `Ö` (U+00D6) and `ö` (U+00F6) are
**not** folded — these are two separate tables:

```
CREATE TABLE "KÖLN" (id INTEGER, label TEXT);  INSERT INTO "KÖLN" VALUES (1,'UPPER-umlaut');
CREATE TABLE "köln" (id INTEGER, label TEXT);  INSERT INTO "köln" VALUES (2,'lower-umlaut');
```

Native (`sqlite3` 3.45.1 / bundled 3.53.4 both agree):
```
tables (ORDER BY name): [('KÖLN',), ('köln',)]
KÖLN -> (1, 'UPPER-umlaut')
köln -> (2, 'lower-umlaut')
```

## What differs (Trino, catalog `unicode`)
| query | expected (native) | Trino actual |
|---|---|---|
| `SHOW TABLES FROM unicode.main` | `KÖLN`, `köln` (2 rows) | **`köln` only — `KÖLN` is missing** |
| `information_schema.tables` | 2 rows | **1 row (`köln`)** |
| `SELECT * FROM unicode.main."KÖLN"` | `(1,'UPPER-umlaut')` | **`(2,'lower-umlaut')` — WRONG TABLE** |
| `SELECT * FROM unicode.main."köln"` | `(2,'lower-umlaut')` | `(2,'lower-umlaut')` |

Consequences:
1. **Silent data loss in metadata:** the table `KÖLN` and all its rows are
   invisible to Trino — it appears in neither `SHOW TABLES` nor
   `information_schema.tables`.
2. **Silent wrong rows:** a user who writes `unicode.main."KÖLN"` expecting the
   `KÖLN` table gets the `köln` table's rows instead, with no error.

## Mechanism / root cause
Two layers disagree on case folding:

- Trino's `DefaultIdentifierMapping` lowercases remote identifiers with
  `String.toLowerCase(...)` (Unicode-aware): both `KÖLN` and `köln` →
  `köln`. The listing of the two distinct remote names therefore **collapses to
  one Trino table name**, and the lookup name sent back to the connector is the
  Unicode-lowercased `köln` for *either* spelling.
- The connector's `getTables` matches with SQLite `COLLATE NOCASE`
  (**ASCII-only**):
  `SqliteClient.getTables` lines 186–195
  ```sql
  ...
  AND (? IS NULL OR name = ? COLLATE NOCASE)
  ```
  `name = 'köln' COLLATE NOCASE` matches **only** the SQLite `köln` table
  (verified via `query()`), never `KÖLN`. So `KÖLN` is unreachable and every
  Trino reference resolves to `köln`.

The connector comment (lines 184–185) states:
> "SQLite resolves identifiers case-insensitively, so table names are matched
> the same way."

But SQLite's case-insensitivity here (`COLLATE NOCASE`) is ASCII-only and does
**not** agree with Trino's Unicode-aware lowercasing, so the two sides can
collide. The listing path (`getTables` returns both rows, then Trino dedups the
Unicode-lowercased names) drops one table without warning; the resolution path
picks the ASCII-NOCASE match of the lowercased name.

This is a genuine wrong-table/wrong-rows result for any SQLite file containing
two table names that differ only by a non-ASCII-case character — e.g. accented
Latin, Greek, Cyrillic. No exotic schema is required; the names are ordinary
identifiers.

## Suspected code path
- `SqliteClient.getTables()` — `/tmp/trino-pr/.../sqlite/SqliteClient.java:180-208`,
  specifically the `name = ? COLLATE NOCASE` match (line 193) and the lack of a
  collision guard when the ASCII-NOCASE listing is reduced by Trino's Unicode
  lowercasing.
- Interaction with `io.trino.plugin.base.mapping.DefaultIdentifierMapping`
  (Unicode `toLowerCase`), which the connector uses unmodified.

## Files in this finding
- `setup.py` — build the minimal 2-table db
- `query.trino.sql`, `query.native.sql`
- `result.show_tables.trino.csv`, `result.select_KOLN.trino.csv`,
  `result.select_koln.trino.csv`, `result.native.csv`
- `versions.txt`, `repro.sh` (self-contained)

## Reproduction
`bash repro.sh` (fetches control env-setup.sh, builds, prints Trino vs native).
