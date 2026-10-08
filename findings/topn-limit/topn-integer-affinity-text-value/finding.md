# Top-N pushdown returns the WRONG row for an INTEGER-affinity column holding text

**Area:** topn-limit
**Severity:** wrong rows (ORDER BY ... LIMIT returns a different, incorrectly-ordered row set with pushdown ON vs OFF)
**Catalog/table:** `mini.main.t` — `CREATE TABLE t (id INTEGER, v INTEGER)` with rows `(1, 5)` (stored integer) and `(2, 'zzz')` (non-numeric text stored in the INTEGER-affinity column).

## Hypothesis
`SqliteClient.supportsTopN` (lines ~291-299) only refuses top-N pushdown for columns whose **declared** type classifies as `ANY` or is force-mapped to varchar. A column declared `INTEGER` is always mapped to `bigint` and top-N is pushed with a numeric `ORDER BY`. But SQLite applies INTEGER *affinity*, not an INTEGER *constraint*: a non-numeric text value (`'zzz'`) is stored with TEXT storage class. SQLite's `ORDER BY` then sorts by **storage class** (all integers/reals before any text), whereas Trino reads that same cell as `0` (JDBC numeric coercion) and expects numeric ordering. The two orderings disagree.

## What differs (the bug)
Trino materializes identical values in both modes — `id=1 -> 5`, `id=2 -> 0` (see `reads.trino.csv`, reproduced in both `result.pushdown_on.csv` reads and the OFF path). So the only variable is *where the LIMIT-1 row comes from*.

`SELECT id, v FROM mini.main.t ORDER BY v ASC LIMIT 1`:

| mode | result | correct? |
|------|--------|----------|
| pushdown ON  (`result.pushdown_on.csv`)  | `id=1, v=5` | **WRONG** |
| pushdown OFF (`result.pushdown_off.csv`) | `id=2, v=0` | correct |

With pushdown OFF, Trino sorts its own materialized values and correctly puts `v=0` (id=2) first. With pushdown ON, SQLite executes `... ORDER BY "v" ASC NULLS LAST LIMIT 1`, and because `'zzz'` has TEXT storage class it sorts *after* the integer `5`, so SQLite returns id=1. Trino then reports `v=5` as the global minimum — a wrong row.

`EXPLAIN` (explain.txt) confirms the top-N is pushed: `TableScan[... sortOrder=[v:bigint:INTEGER ASC NULLS LAST] limit=1]` — no Trino-side TopN node.

The scan is deterministic (ran 3×, identical).

## Contradicted guarantee
`SqliteClient.isTopNGuaranteed(session)` returns `true` (lines ~317-321), promising the engine that the pushed-down top-N is complete and correctly ordered, so Trino does **no** post-sort. That guarantee is violated here: the remote ordering does not agree with the ordering Trino would compute over the values it actually receives from the connector. The result is wrong rows, not just a wrong scalar read.

Note this is subtly different from, and stronger than, the documented "values are assumed to be stored in the storage class of the column's affinity" read caveat (SqliteClient class javadoc, lines 99-104): even granting that caveat for the *value* `v=0`, an unconditional `isTopNGuaranteed=true` still yields an ordering that contradicts that very value. A correct implementation would either (a) not guarantee top-N for columns whose affinity permits mixed storage classes, or (b) coerce in the remote ORDER BY (e.g. `ORDER BY CAST("v" AS <numeric>)`), matching what Trino does with the read value.

## Suspected code path
- `plugin/trino-sqlite/src/main/java/io/trino/plugin/sqlite/SqliteClient.java`
  - `supportsTopN` lines ~291-299 — gates only on declared-type classification (`ANY` / forced varchar); does not account for affinity allowing off-class stored values in INTEGER/REAL/DECIMAL/DATE columns.
  - `topNFunction` lines ~301-315 — emits a bare `ORDER BY "v" ASC NULLS LAST` for non-text columns; no CAST to the mapped numeric type.
  - `isTopNGuaranteed` lines ~317-321 — returns `true`, so Trino trusts the remote order.

## Reproduce
`bash repro.sh` (self-contained). Native oracle sqlite 3.45.1; driver bundled engine 3.53.4 (both order by storage class identically, so this is not version-dependent).
