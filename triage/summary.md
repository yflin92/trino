# Triage summary — Trino fleet run sqlite-20261008-1731

**Connector:** SQLite · **PR:** yflin92/trino#1 @ `7ce55e6b32342d27fec58c7009f85abb1b0c6fe6`
**Build (pinned, verified by triage):** plugin from PR head → `trinodb/trino:483`, sqlite-jdbc `3.53.4.0`.
`sqlite_version()`: driver bundled engine **3.53.4** / native oracle **3.45.1** (python3 sqlite3).
**Parent task:** 875e5b37fa222f127c081367 · **Triage session:** cs_yBHttdXmo0

All **6** hunter areas processed. **5** candidate findings were **reproduced** against the pinned build (deterministic), 0 dropped. **3 tasks** created (2 of the read-side candidates grouped into one task — both are the same BOOLEAN-mapping root cause). The 5th candidate (concurrency-transactions: leftover temp table) reproduced but is **out of scope → summary.md only, no task** (wrong *schema* state, not wrong rows/DML/values; no cited correctness contract; inherited base-jdbc cleanup behavior). dml-write was clean.

---

## Per-area counts

| area | hypotheses tested | candidates | reproduced | confirmed (→ task) | dropped |
|---|---|---|---|---|---|
| filter | 7 seed families (COLLATE completeness, DATE window, doubles, bigint>2^53, decimal-as-float, booleans, int/real affinity) | 1 | 1 | 1 (grouped) | 0 |
| topn-limit | 9 (NULL ordering, COLLATE, supportsTopN=false gating, ±0.0/decimals, date text, storage-class, multi-col, OFFSET, unicode bytes) | 1 | 1 | 1 | 0 |
| type-mapping-read | 8 (affinity-edge declared types, INT-holds-REAL/TEXT/BLOB, REAL-holds-bigint, DECIMAL HALF_UP, BOOLEAN 2/-1/0/text, views, BLOB/TEXT cross, DECIMAL scale>precision) | 1 | 1 | 1 (grouped) | 0 |
| metadata-passthrough | ~5 (case-insensitive resolution, hidden/system tables, views/quoted names, query() safety, getColumns) | 1 | 1 | 1 | 0 |
| dml-write | all DML seed families (collation DELETE/UPDATE/IN/NOT IN/disjuncts, TRUNCATE, type round-trips, ALTER) | 0 (clean) | — | 0 | — |
| concurrency-transactions | 6 (parallelism>1, concurrent sessions, reads-during-write, mid-INSERT failure, START TX/ROLLBACK, native EXCLUSIVE lock) | 1 | 1 | 0 (out of scope → summary) | 0 |

**Totals:** 5 candidates → 5 reproduced → 4 confirmed in-scope, grouped into **3 tasks**; 1 reproduced-but-out-of-scope (concurrency leftover temp). 0 dropped.

---

## Tasks created (all parent 875e5b37fa222f127c081367)

1. **f201c44c53b498ef1891a47c** — `[sqlite][filter,type-mapping] BOOLEAN column mapping mishandles non-0/1 stored values: pushdown drops rows that project true, and fractional REALs read as false` — type `wrong_result`, priority **high**. Linked: cs_LWoPgj_O4I (filter), cs_ifcWbz4SDA (type-mapping-read).
   - **Grouping rationale:** two candidates, one root cause = the BOOLEAN column mapping (`SqliteClient.java:336 case BOOLEAN -> booleanColumnMapping()`) surfacing non-canonical stored values via `getBoolean` without SQLite-faithful normalization.
     - *Symptom A (filter, Tier 1 pushdown_mismatch):* stored int `2`/`-1` projects `true` (read `!=0`) but pushed `flag = true` binds literal `1` → SQLite `flag = 1` drops the row. `WHERE flag=true` ON `{5}` vs OFF `{1,2,5}`; EXPLAIN shows `constraint on [flag]`.
     - *Symptom B (type-mapping, Tier 2 read):* a REAL `|v|<1` (0.5/0.9/-0.3/0.001) in a BOOLEAN column reads `false` (driver `getBoolean` truncates to 0), while SQLite's own engine (bundled 3.53.4 via `system.query`) evaluates it truthy. Wrong with pushdown ON and OFF.
   - Fix direction: SQLite-faithful boolean read (`getDouble != 0.0`) + disable or rewrite (`<>0`/`=0`) boolean predicate pushdown.

2. **c57e3014ae7a5d088b20a896** — `[sqlite][topn-limit] Top-N pushdown returns wrong row for an INTEGER-affinity column holding off-affinity text (storage-class ordering vs isTopNGuaranteed=true)` — type `pushdown_mismatch`, priority **high**. Linked: cs_XbD4mb8lBB.
   - Tier 1: `ORDER BY v ASC LIMIT 1` on an INTEGER column holding text `'zzz'` → ON returns id=1 (v=5, WRONG), OFF returns id=2 (v=0, correct). EXPLAIN: `TableScan[... sortOrder=[v:bigint:INTEGER ASC NULLS LAST] limit=1]`. SQLite orders text after integers; `isTopNGuaranteed=true` makes Trino trust the wrong remote order. `supportsTopN` gates on declared type only, not affinity.

3. **6a00ea78cb5f62dca02a708d** — `[sqlite][metadata-passthrough] Unicode case-collision: non-ASCII case-paired tables collapse to one Trino identifier — one table hidden, queries to it silently return the other table's rows` — type `wrong_result`, priority **high**. Linked: cs_lcgHi6H_JM.
   - Tier 2 (wrong table + silent metadata loss): `KÖLN` and `köln` are distinct in SQLite (ASCII-only NOCASE dedup) but collide under Trino's Unicode `toLowerCase`. `SHOW TABLES` shows only `köln`; `SELECT * FROM "KÖLN"` returns `köln`'s rows. Root: `getTables` matches `name = ? COLLATE NOCASE` (SqliteClient.java:193, ASCII-only) vs Trino Unicode lowercasing. Contract: docs say all tables (except `sqlite_*`) are visible; nothing sanctions hiding/merging.

**Priority rationale:** all three are read-side *silently wrong/missing rows on a plausible query* → `high`. None are `critical` (no DML corrupts stored values or modifies unselected rows — see dml-write below). Triggers are edge-case stored values (non-0/1 booleans, off-affinity text, non-ASCII case-paired names) but the triggering *queries* are ordinary.

---

## Concurrency finding — reproduced but OUT OF SCOPE (summary only, no task)
**`leftover-temp-table-on-locked-insert`** (cs_2Rx8KFE7F4 → `findings/concurrency-transactions/leftover-temp-table-on-locked-insert/`). **Reproduced by triage against the pinned build (deterministic):** a native writer holds `BEGIN EXCLUSIVE` on the file 8s; a Trino `INSERT ... SELECT` creates its `tmp_trino_<hex>` staging table, then the page-sink write hits the lock and fails SQLITE_BUSY (~3s busy_timeout), and the rollback's `DROP TABLE tmp_trino_*` *also* times out against the still-held lock and is swallowed → the empty staging table is left permanently in the schema and shows up in `SHOW TABLES`. Control at 4s hold leaves no orphan (proving the leak is the rollback DROP timing out). Target table `big` stays at its correct committed count (0) — **no data rows lost or duplicated**. native 3.45.1 / bundled 3.53.4 (both reproduce).

**Why no task (same rigor as the other areas):**
1. **Not an in-scope correctness category.** In scope = wrong rows returned / wrong-DML (wrong rows changed) / wrong stored values. This loses/duplicates **zero** data rows and changes nothing in the target; it is wrong *schema* state (a leftover empty staging table), which the scope does not list.
2. **Trigger is an out-of-scope error.** The leak only follows a SQLITE_BUSY failure, itself explicitly out of scope; Trino fails cleanly and reports the error — no silent data loss.
3. **No cited correctness contract.** No connector doc, Trino SQL-semantics statement, PR claim, or test asserts that a *failed* INSERT leaves zero schema artifacts; the connector docs say nothing about atomicity/cleanup of failed writes.
4. **Inherited base-jdbc behavior, not connector code.** The temp-table staging + best-effort rollback `DROP` lives in `trino-base-jdbc` (`BaseJdbcClient.beginInsertTable`/`rollbackTemporaryTableCreation`→`dropTable`, exception swallowed in `DefaultJdbcMetadata.rollback()`), shared by every JDBC connector; SQLite only surfaces it because its single-writer lock can also time out the rollback DROP. The DROP is not retried and SQLITE_BUSY is not a `SQLTransientException`, so the retry policy misses it.
*(If the project later treats leftover-staging-on-failed-write as in scope, a hardening task would target the base-jdbc rollback DROP — e.g. retry/force the temp drop or set a longer `busy_timeout` via the connection URL — not the SQLite type/pushdown logic.)*

## Dropped findings
**None.** All 5 candidates reproduced against the pinned build.

## Dialect differences (summary only, no task)
**None.** No candidate was downgraded to a mere dialect difference — each confirmed finding cites a Tier-1 pushdown mismatch or a specific contract (docs type-mapping/visibility, SQLite boolean semantics, `isTopNGuaranteed`).

---

## Errors / crashes hunters noted (OUT OF SCOPE — not tasks)
- **DATE span-only pushdown check (filter):** when a domain is unbounded on both sides but contains an out-of-window bound (e.g. `dt <> DATE '10000-01-01'`), only `getSpan()` low/high are checked, pushdown is allowed, and `dateWriteFunction` throws "Date must be between 0000-01-01 and 9999-12-31". **Errors, not wrong rows.** (DATE_PUSHDOWN, SqliteClient.java:123-133.)
- **DECIMAL value exceeding declared precision (type-mapping):** reading `12345.67` from `DECIMAL(5,2)` → `SERIALIZATION_ERROR` / "Query is gone"; server stays up. Error, not wrong rows.
- **Unicode case-colliding *columns* in one table (metadata):** `SELECT *` → "Multiple entries with same key". Error (the table-level sibling is the filed finding).
- **PRAGMA via query() (metadata):** wrapping → "no such table: PRAGMA". Error.
- **DELETE/UPDATE on an ANY-classified column (dml-write):** "This connector does not support modifying table rows" — see UPDATE note below. Safe refusal (row untouched), error not corruption.
- **SQLITE_BUSY lock-timeout (concurrency):** a Trino write fails cleanly with `[SQLITE_BUSY] database is locked` when an external writer holds the file lock > ~3s. Clean failure, no data corruption (it is the trigger for the out-of-scope leftover-temp finding above).
- **Could not reproduce (concurrency hunter, tried):** a "committed-but-reported-failed" INSERT (rows land in target but the query errors → a retry would duplicate), and rows stranded in a leftover temp table — the finish-phase `INSERT INTO target SELECT FROM temp` + `DROP temp` window is sub-millisecond and no external lock could be landed inside it. **These would be genuine wrong-rows/duplication bugs if reproducible**; not observed here. Noted so a future run can target that finish-phase window.
- Engine-version float→text formatting (DECIMAL(16,2)-as-varchar `12345678901234.56` prints `...561` under 3.53.4 vs `...6` under 3.45.1): a SQLite **engine-version** difference consistent with the bundled driver, not a connector bug.

---

## UPDATE discrepancy — RESOLVED
Ground-rules list UPDATE as a supported write; a hunter saw "This connector does not support modifying table rows". **Verified by triage against the pinned build:** UPDATE **is** supported and correct — `UPDATE updchk.main.t SET amt=99 WHERE name='a'` updated exactly the 2 matching rows (ids 1,3), leaving id 2 untouched. Docs confirm `/sql/update` support with documented limitations (constant assignments/predicates only; `sql-update-limitation.fragment`). The observed refusal occurs **only** for an **ANY-classified column** (declared type like `NUM` forced to varchar with `DISABLE_PUSHDOWN`), where the row-locating predicate can't be pushed, so base-jdbc merge **refuses** (a safe error — the row is not corrupted). → UPDATE is supported-by-design; the refusal is an out-of-scope error, **no UPDATE task filed**, no wrong-rows UPDATE behavior found. (The concurrency hunter independently hit the same refusal on an unpushable-predicate UPDATE and flagged it as a possible doc mismatch — same explanation; no concurrency corruption in the UPDATE path, which is disabled for that case.)

---

## Per-PR-claim verdicts

| Claim | Verdict | Notes |
|---|---|---|
| **Collation handling** — `COLLATE BINARY` on pushed text comparisons, IN lists, top-N ORDER BY, "also for columns declared with a different collation" | **HELD (for text)** — but the analog is **BROKEN for BOOLEAN** → task **f201c44c53b498ef1891a47c** | filter/topn/dml hunters confirmed `=`, `<>`, IN, NOT IN, BETWEEN, ranges, mixed disjuncts, column collation NOCASE/RTRIM all get BINARY and are pushed; column-vs-column not pushed (no leak). The text path is solid. The connector adds no equivalent normalization to BOOLEAN predicate pushdown. |
| **Date bounds 0000-9999** | **HELD (correctness)** | In-window range/eq/BETWEEN pushdown correct at both boundaries; out-of-window literals disable pushdown; out-of-range writes rejected (`testDatesOutsideSupportedRange`). Latent *error* (span-only check leaks a bound on unbounded-both-sides domains) → throws, out of scope. |
| **NUMERIC→varchar safety** ("mapping them to varchar without pushdown is never wrong") | **HELD** | NUMERIC / typeless / DATETIME / DECIMAL(p>15) / DECIMAL(scale>precision) map to varchar with `DISABLE_PUSHDOWN`; read text repr correctly; top-N not pushed for forced-varchar (`testTopNOnColumnMappedToVarchar`). No wrong rows. |
| **query() safety** ("Nothing is executed") | **HELD in practice** (latent risk noted) | No write ever landed across all probed forms (bare INSERT/CREATE/PRAGMA fail at the metadata probe; `;`-trailing statements rejected; INSERT/UPDATE/DELETE…RETURNING fail because the fetch wraps the query as `FROM (<q>) o`). md5 + row counts unchanged. **Latent risk:** the connector's JDBC connection is actually READ-WRITE (`getConnection` override drops `setReadOnly(true)`, SqliteClient.java:148-154); safety rests solely on base-jdbc subquery-wrapping. Not a confirmed wrong-rows/write bug → documented, not filed. |
| **Single-writer default** (`write_parallelism=1`) | **HELD (no data corruption)** | concurrency-transactions hunter: with `write_parallelism=4` and `=8` (50k-row inserts ×8 each) native counts were always exact, 0 duplicate ids, contiguous ranges — SQLite's single-writer lock + ~3s busy_timeout serialise parallel page sinks correctly. Two/three concurrent Trino sessions into the same table: totals always exact, the loser fails cleanly with BUSY and its temp table is dropped; no lost/duplicated rows (invariant held 10/10). No silent succeed-without-writing under a native EXCLUSIVE lock. The only concurrency defect found is the out-of-scope leftover empty temp table (above); no wrong rows/values. (Not exhaustively proven: a committed-but-reported-failed INSERT in the sub-ms finish window — could not be reproduced.) |

---

## Notes on repro reproducibility
- filter / type-mapping / metadata / concurrency repro.sh ran clean against the pinned build (concurrency: orphan `tmp_trino_*` present at 8s hold, absent at 4s control, `big` count correct).
- topn-limit repro.sh has a cosmetic script defect (`CTRL=origin/<control-branch>` fails `git show`, because `git fetch` writes `FETCH_HEAD`, not a local ref); patched to `FETCH_HEAD` for the triage run. This is a script-only issue; the finding reproduces identically. (The same `origin/`-ref pattern is the reason the initial control-branch fetch needs `FETCH_HEAD` rather than `origin/<branch>`.)
