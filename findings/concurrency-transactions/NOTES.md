# concurrency-transactions — NOTES (run sqlite-20261008-1731)

Target: SQLite connector PR yflin92/trino#1 @ 7ce55e6b3234..., plugin on trinodb/trino:483.
Native sqlite3 3.45.1; driver-bundled 3.53.4.
Default session props confirmed: `write_parallelism=1`, `write_batch_size=1000`,
`non_transactional_insert=false`, `non_transactional_merge=false`.
Observed driver busy_timeout ≈ 3s (a BUSY'd write waits ~3s then throws SQLITE_BUSY).

## Architecture (from source reading)

- INSERT (transactional, default): `beginInsertTable` does
  `CREATE TABLE tmp_trino_<hex> AS SELECT ... WHERE 0=1`; page sinks INSERT into the
  temp table (write_parallelism parallel connections); `finishInsertTable` runs a single
  `INSERT INTO target SELECT ... FROM temp` then `DROP TABLE temp`. Each step is a
  separate autocommit statement on its own JDBC connection.
- CTAS: temp table then `ALTER TABLE ... RENAME TO target` (commitCreateTable).
- DELETE: single predicate `DELETE FROM target WHERE ...` (SqliteClient uses base `delete`),
  not a row-by-row merge.
- UPDATE / MERGE row modification: **NOT SUPPORTED** — "This connector does not support
  modifying table rows" (out of scope here; see below).
- Multi-statement write transactions: **rejected** — "Catalog only supports writes using
  autocommit" (START TRANSACTION + INSERT + ROLLBACK is not possible).
- SQLite serialises all writers via a file-level lock; the ~3s busy_timeout usually lets
  concurrent Trino writers serialise instead of failing.

## CONFIRMED FINDING (see leftover-temp-table-on-locked-insert/)

A transactional INSERT that fails with SQLITE_BUSY while a native writer holds the file
lock long enough (≥ ~6s: page-sink busy_timeout + rollback DROP busy_timeout) leaves an
orphan `tmp_trino_*` staging table **permanently** in the schema, visible in
`SHOW TABLES` and queryable. No data rows lost/duplicated into the target, but wrong
persistent schema state that accumulates. Reproduced 3/3 at 8s hold; 0/3 at 4s hold
(control), proving the leak is the rollback `DROP TABLE` itself timing out. The DROP is
not retried and SQLITE_BUSY is not a SQLTransientException so the retry policy misses it.

## Seed hypotheses — verdicts

1. **INSERT/CTAS with write_parallelism>1 — rows lost/duplicated?**
   NO corruption. Ran `write_parallelism=4` and `=8`, 50k-row inserts, 8 repeats each:
   native count always exactly correct, 0 duplicate ids, min/max contiguous. SQLite's
   single-writer lock + ~3s busy_timeout serialise the parallel page sinks correctly.
   VERDICT: clean.

2. **Two (and three) concurrent Trino sessions inserting into the same table.**
   NO corruption. Two 25k inserts disjoint ranges ×6: native total=50000, A=25000,
   B=25000 every time. Three-way 10k×3 invariant check ×10: native count == sum of
   reported-successful inserts every time (0 mismatches). Larger (400k×2) concurrent
   inserts ×4: one fully succeeds, the other fails cleanly with BUSY during its page-sink
   phase, its temp table IS dropped (lock free after winner finishes), target has exactly
   the winner's rows, no leftover, no dup. VERDICT: clean (loser fails cleanly, no data corruption).

3. **Reads during a write.** Not pursued in depth; SELECTs during an INSERT read the
   pre-INSERT committed state (temp table isolation + autocommit means the target only
   changes atomically at finishInsertTable). No partial/wrong rows observed. VERDICT: no issue found.

4. **Failed INSERT part-way (cast failure on row N) — partial rows / leftover temp?**
   Clean when the DB is lock-free: forced a row to fail via out-of-range DATE; the whole
   INSERT rolled back, 0 partial rows, 0 leftover temp tables, previously-committed rows
   intact. VERDICT: clean WITHOUT lock contention. (Leftover temp only appears under the
   sustained-lock scenario above.)

5. **START TRANSACTION / ROLLBACK undo?** Not reachable: the connector rejects writes in
   an explicit transaction ("Catalog only supports writes using autocommit"). Could not
   test rows surviving a ROLLBACK via explicit transactions. VERDICT: N/A (feature disabled).

6. **Native writer holding EXCLUSIVE lock while Trino writes — lose data / silent success?**
   Trino does NOT silently succeed-without-writing. With the lock held, Trino's INSERT
   fails with SQLITE_BUSY after ~3s and the native row is intact; target unchanged.
   VERDICT: no silent data loss. BUT this scenario is what triggers the CONFIRMED finding
   (orphan temp table) when the lock is held long enough to also time out the rollback DROP.

## OUT OF SCOPE / errors only (noted, not findings)

- **SQLITE_BUSY lock-timeout errors** themselves are out of scope. They occur whenever an
  external writer holds the lock > ~3s, or (in principle) when a Trino writer's finish
  phase collides with another's long page-sink phase. These are clean failures (no data
  corruption) except insofar as they trigger the orphan-temp-table finding above.
- **UPDATE not supported** ("This connector does not support modifying table rows"). The
  ground-rules summary listed UPDATE as a supported write; in practice `UPDATE ...` errors
  out. This is a functionality gap / possible doc mismatch, not a concurrency corruption —
  flagging for triage awareness only. (A read-modify-via-merge path would be where UPDATE
  concurrency bugs would live, but the path is disabled.)

## Could NOT reproduce (tried, timing too tight)

- "Committed-but-reported-failed" INSERT (rows land in target but query errors → retry
  duplicates): the finish-phase `INSERT INTO target SELECT FROM temp` + `DROP temp` window
  is sub-millisecond; could not land an external lock inside it across many attempts. Not
  observed.
- Stranding the inserted ROWS in the leftover temp table (data recoverable but orphaned):
  same tight finish-phase window; the orphan temp observed was always empty (lock grabbed
  during/before page-sink flush). Not observed with rows.
