# Filter-area hunt notes — run sqlite-20261008-1731

Target: SQLite connector PR yflin92/trino#1 @ 7ce55e6b, built as plugin for trinodb/trino:483.
native sqlite3 = 3.45.1; driver bundled engine = 3.53.4.
Session pushdown toggles (defaults, unchanged): aggregation_pushdown_enabled=true,
topn_pushdown_enabled=true, domain_compaction_threshold=256, join_pushdown_enabled=false,
write_parallelism=1. Oracle = `allow_pushdown_into_connectors=false` (OFF) vs ON vs native python3 sqlite3.

## FINDINGS (wrong rows) — delivered
- **bool-pushdown-nonbinary-wrong-rows** — CONFIRMED WRONG ROWS.
  BOOLEAN column storing a non-0/1 value (int 2, int -1, or text 'true') reads as
  `true`/`false` via `getBoolean()` (= `value != 0`), but pushed-down `flag = true`
  binds literal `1`, so SQLite evaluates `flag = 1` and drops rows that project as
  true (and `flag <> true` wrongly returns them). ON and OFF disagree; ON contradicts
  the projection. Minimal: 1 row, flag=2, `WHERE flag=true` → ON {} vs OFF {1}.
  Root cause: read `!=0` vs pushed compare `=1` asymmetry; no normalization (unlike
  the text COLLATE BINARY path). See finding.md.

## Seed hypotheses — verdicts
- COLLATE BINARY completeness (FOCUS): **holds** for all forms tested.
  - `=`, `<>`, BETWEEN, ranges, IS DISTINCT FROM on NOCASE: binary semantics, pushed. OK.
  - IN list (>=2 values) on NOCASE & RTRIM: COLLATE BINARY applied (SqliteQueryBuilder
    lines 86-90). OK. Single-value IN falls to per-operator override which also adds
    COLLATE (line 103). OK.
  - NOT IN (complement/range path) on RTRIM: correctly binary (native RTRIM would match
    all, Trino/pushdown returns binary-distinct rows). OK.
  - Mixed range + IN (`s IN (..) OR s > ..`) on RTRIM: `otherRanges` disjunct path adds
    COLLATE via per-operator override. OK.
  - IN with NULL, NOT IN with NULL: ON==OFF. OK.
  - COLUMN-vs-COLUMN (`a = b`) on two NOCASE columns: **NOT pushed down**
    (`ScanFilterProject filterPredicate=(a=b)`), so Trino applies binary semantics
    itself — no collation leak. Seed concern does not reproduce. OK.
- DATE_PUSHDOWN window (FOCUS): in-window (0000-9999) range/eq/BETWEEN pushdown correct,
  ON==OFF==native at both boundaries (0000-01-01, 9999-12-31). Out-of-window literals
  (`< 10000-01-01`) correctly DISABLE pushdown (ScanFilterProject). **Latent bug, but
  ERROR not wrong-rows (OUT OF SCOPE):** the controller checks only `getSpan()`'s low/high.
  When the domain spans unbounded on BOTH sides but contains an out-of-window bound
  (e.g. `dt < DATE '-0001-01-01' OR dt > DATE '2000-01-01'`, or `dt <> DATE '10000-01-01'`),
  neither span bound is checked, pushdown is allowed, and dateWriteFunction throws
  "Date must be between 0000-01-01 and 9999-12-31". Query fails rather than returning
  wrong rows, so noted here only. A fix should scan every range bound, not just the span.
- Doubles -0.0/0.0, ±Infinity: `x = 0.0` matches both signed zeros; `x = infinity()`
  matches +Inf. ON==OFF. OK. (SQLite has no NaN; not exercised.)
- bigint > 2^53 (9007199254740992..93..94, LONG_MAX): INTEGER affinity, exact, no float
  coercion. `=` and `>` ON==OFF. OK.
- decimal(p,s) stored as 8-byte float (0.1, 0.3, 1.0, 123456789.12): equality ON==OFF
  (e.g. d=0.1 → {1}; d=123456789.12 → {} both sides, consistent). OK.
- integer/real affinity comparisons: consistent. OK.

## Caveats
- `SELECT sqlite_version()` is not a Trino function; use the `query` table function.
- docker stats shows 0 B here (expected per ground-rules).
