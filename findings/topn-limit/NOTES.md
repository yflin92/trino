# topn-limit hunt notes — run sqlite-20261008-1731

Target: PR yflin92/trino#1 @ 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6, plugin on trinodb/trino:483.
Native sqlite3 3.45.1; driver bundled engine 3.53.4 (both order identically in every test below).

## Session toggles (relevant to top-N / limit)
- `<cat>.topn_pushdown_enabled = true` (default)
- `allow_pushdown_into_connectors` (engine session prop) used to disable pushdown for the oracle comparison
- `<cat>.domain_compaction_threshold = 256`, `write_parallelism = 1` (default)
- No connector-specific top-N toggle beyond `topn_pushdown_enabled`.

## Method
For each case: EXPLAIN to confirm `TableScan[... sortOrder=... limit=N]` (pushed) vs a Trino-side `TopN` node
(not pushed); then compared pushdown ON vs OFF (and native sqlite3 as oracle) as ORDERED lists.

## Seed hypotheses — verdicts

1. **NULL ordering (ASC/DESC × NULLS FIRST/LAST), INTEGER and TEXT columns** — CORRECT.
   topNFunction emits explicit `NULLS FIRST/LAST`; all 4 directions on both `nums` (INTEGER) and
   `txt` (TEXT) agree ON vs OFF. (Differences only in tie-break order *between two NULL rows*, which
   is immaterial.)

2. **COLLATE BINARY on NOCASE / RTRIM columns** — CORRECT.
   `nocase_t` (TEXT COLLATE NOCASE) and `rtrim_t` (TEXT COLLATE RTRIM): pushed top-N adds
   `COLLATE BINARY`, overriding the column collation; ON == OFF == native-with-BINARY.
   (`Apple < BANANA < Cherry`; `ab < abc < 'abc ' < 'abc   ' < abd`.)

3. **supportsTopN == false for ANY / forced-varchar columns** — CORRECT.
   `numeric_t` (NUMERIC, no precision -> ANY), `typeless_t` (no declared type -> ANY),
   `dt_t` (DATETIME -> ANY) all map to varchar and top-N is NOT pushed (Trino-side `TopN` node
   above `TableScan`). Results correct (Trino sorts).

4. **Ordering of -0.0 / 0.0 and decimals stored as floats** — CORRECT.
   `real_t` (REAL with 0.0 and -0.0): both sort equal, ON == OFF. `dec.d` (DECIMAL(10,2), values
   stored as REAL/INTEGER): ON == OFF, correct numeric order incl. negative.

5. **Date text ordering (leading zeros, 0001..9999)** — CORRECT.
   `date_t` and `date2.t` (DATE, 4-digit years incl. 0001, 0999, 1000, 9999): ON == OFF; NULLS
   handling correct in both directions. (DATE classifies as DATE not TEXT, so no COLLATE added —
   fine because date text is ASCII-sortable for 4-digit years, which is the documented pushdown range.)

6. **Top-N on columns mapped to varchar / SQLite storage-class ordering** — see FINDING below.

7. **Multi-column top-N, mixed text+int keys, secondary NULLS FIRST** — CORRECT (`multi.t`).

8. **OFFSET + LIMIT** — CORRECT. Trino pushes `limit = offset + limit` and applies OFFSET itself
   (`nums` OFFSET 2 LIMIT 2: ON == OFF).

9. **Unicode byte ordering (2/3/4-byte UTF-8, empty string)** — CORRECT (`uni.t`); Trino VARCHAR and
   SQLite BINARY both compare UTF-8 bytes.

## FINDING (1): topn-integer-affinity-text-value  [WRONG ROWS]
INTEGER-affinity column holding a non-numeric text value ('zzz' stored as TEXT storage class).
`ORDER BY v ASC LIMIT 1`: pushdown ON returns id=1 (v=5), pushdown OFF returns id=2 (v=0). Trino
reads id=2's value as 0 in both modes, so this is a pure top-N ordering divergence: SQLite orders
by storage class (integer 5 before text 'zzz'); `isTopNGuaranteed=true` makes Trino trust the wrong
remote order. See findings/topn-limit/topn-integer-affinity-text-value/. Related to (not the same as)
the documented "value assumed stored in affinity storage class" READ caveat — here an unconditional
top-N guarantee contradicts the very value Trino materializes.

## Out of scope (errors/crashes) — none observed
No exceptions encountered during top-N/limit hunting.
