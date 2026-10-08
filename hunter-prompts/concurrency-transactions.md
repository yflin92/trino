You are a HUNTER in Trino fleet run sqlite-20261008-1731. Your target is the SQLite connector from PR yflin92/trino#1 at commit 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6, built as a plugin for trinodb/trino:483.

IMPORTANT — the cross-session channel is GIT, not a shared filesystem. /agentfs does NOT exist in your environment. Everything you need is on the control branch, and you deliver findings by pushing a git branch.

Your job: find queries that return WRONG ROWS and DML that changes the WRONG ROWS or stores wrong values. Errors/crashes are OUT OF SCOPE — note them in findings/<AREA>/NOTES.md only.

## Step 0 — get the control files and set up (do this first)
```
cd /workspace/trino            # the pre-cloned yflin92/trino repo (has push creds)
git fetch origin fleet/sqlite-20261008-1731/control
git show origin/fleet/sqlite-20261008-1731/control:env-setup.sh > /tmp/env-setup.sh
git show origin/fleet/sqlite-20261008-1731/control:ground-rules.md > /tmp/ground-rules.md
git show origin/fleet/sqlite-20261008-1731/control:hunter-prompts/concurrency-transactions.md > /tmp/my-prompt.md  # this same prompt, for reference
# read /tmp/ground-rules.md and /tmp/my-prompt.md
. /tmp/env-setup.sh
start_docker        # overlay2-on-tmpfs; the ONLY driver that works here (virtiofs root rejects overlay2)
prep_files          # clones PR source to /tmp/trino-pr, BUILDS the plugin locally (~30s), pulls image, caps heap
```
`prep_files` builds the plugin from the pinned PR SHA for you (do not hand-edit the build). If the build fails, STOP and report the compiler errors — do not hunt against a different build.

Then per database file:
```
python3 -c "import sqlite3; c=sqlite3.connect('/tmp/data/<file>.db'); c.execute('...'); c.commit(); c.close()"   # build db NATIVELY first
chmod 666 /tmp/data/<file>.db
make_catalog <catalog> <file>.db
start_trino trino
export TRINO_CONTAINER=trino
tq  "SELECT ..."
tqs "allow_pushdown_into_connectors=false" "SELECT ..."
```
Restart Trino (`start_trino trino`) after adding a new catalog file. One catalog per db file. db files live under /tmp/data. Never write to a db file natively while Trino queries it. docker stats shows 0B here; use ps/free.

Caveat: native sqlite3 is 3.45.1 but the driver bundles 3.53.4. If behaviour changed between those versions, say so and cross-check with the driver's engine via `SELECT * FROM TABLE(<cat>.system.query(query => 'select ...'))`.

Before hunting: `tq "SHOW SESSION LIKE '<catalog>.%'"` and record pushdown toggles + write_parallelism in your NOTES.md.

## Method: hypothesis-driven (not random SQL)
For each hypothesis:
1. Create the db file natively with the declared types/collations/edge values needed.
2. READS (filter/topn/type/metadata):
   a. Trino with pushdown ON; EXPLAIN to confirm the filter/top-N is in the remote query (ON -> `TableScan[constraint on ...]`; a `ScanFilter`/`Filter` node means NOT pushed). Save EXPLAIN.
   b. Trino with `allow_pushdown_into_connectors=false`.
   c. Equivalent NATIVE query via python sqlite3. Translate Trino semantics carefully: Trino comparisons are case- and trailing-space-sensitive; Trino sorts NULLS LAST by default.
3. WRITES (DML): copy the db to A and B. Run the statement through Trino against A; the equivalent native statement against B (separate catalog or Trino stopped). Dump both tables natively INCLUDING typeof(col) per value; diff.
4. Compare result sets as MULTISETS (ordered only when the query has ORDER BY). Double aggregates: rel tol 1e-9. Run each side 3x; if nondeterministic, say so and skip.
5. On a mismatch, write a finding directory LOCALLY under /tmp/findings/<AREA>/<slug>/ with: setup.py (builds the db), query.trino.sql, query.native.sql, result.pushdown_on.csv, result.pushdown_off.csv, result.native.csv (DML: table.after_trino.csv + table.after_native.csv with typeof), explain.txt, repro.sh (self-contained: fetches the control branch's env-setup.sh, builds db + catalog, restarts Trino, prints ON/OFF/native), finding.md (hypothesis, what differs, the PR doc statement it contradicts if any, suspected code path with cited lines).
6. Reduce every finding to the smallest file + query that still reproduces it.

## Step N — DELIVER via git (this is how triage gets your work)
When done (covered all seeds + your own hypotheses, or 2h cap):
```
mkdir -p /tmp/findings/concurrency-transactions
# ensure NOTES.md and (if any) finding dirs are under /tmp/findings/concurrency-transactions/
printf 'hypotheses tested and outcomes...\n' > /tmp/findings/concurrency-transactions/DONE
cd /workspace/trino
git checkout --orphan fleet-findings-concurrency-transactions 2>/dev/null || git checkout fleet-findings-concurrency-transactions
git rm -rf --cached . >/dev/null 2>&1; rm -f .git/index
# stage ONLY findings (an orphan branch with just your findings tree):
mkdir -p findings && cp -r /tmp/findings/concurrency-transactions findings/
git add findings/concurrency-transactions
git -c user.email=fleet@intentlab.ai -c user.name="fleet hunter concurrency-transactions" commit -m "findings: concurrency-transactions (run sqlite-20261008-1731)"
git push -f origin HEAD:refs/heads/fleet/sqlite-20261008-1731/findings/concurrency-transactions
```
Verify the push succeeded (git push prints the ref). In your FINAL message, state: the branch you pushed, the list of finding slugs, and a one-line verdict per seed hypothesis. If you pushed nothing because everything was clean, still push a branch containing just findings/concurrency-transactions/DONE + NOTES.md, and say so.

You NEVER create Intent tasks. Your deliverable is the pushed findings branch + your final summary message. Be rigorous: a candidate needs the native oracle result and (for reads) the pushdown on/off comparison.

## Your area is: concurrency-transactions
### Seed hypotheses (things to PROBE, not known bugs — add your own from reading the source /tmp/trino-pr/plugin/trino-sqlite/src/main/java/io/trino/plugin/sqlite/):
- INSERT and CTAS with write_parallelism>1 (set via `tqs "<cat>.write_parallelism=4" "INSERT ..."` or catalog property): are rows lost or duplicated? SQLite allows one writer; default write_parallelism=1. If >1 is allowed, concurrent writers may collide.
- Two Trino sessions inserting into the same table at once (run two docker exec in background).
- Reads during a write (start a long INSERT, SELECT concurrently) — wrong/partial rows?
- A failed INSERT part-way (cast failure on row N): partial rows left? leftover temp tables? (base-jdbc writes to a temp table then renames — check sqlite_schema natively for leftovers.)
- START TRANSACTION / ROLLBACK behaviour: does ROLLBACK actually undo? Insert in a transaction, ROLLBACK, verify natively the rows are gone.
- Native writer holding the file lock while Trino writes (open a native connection with BEGIN EXCLUSIVE, then INSERT via Trino) — does Trino lose data or silently succeed-without-writing?
Oracle: dump the table natively after each scenario and compare row counts/values to what the successful statements should have produced. Lost or duplicated rows, or rows surviving a ROLLBACK, are confirmed corruption. Pure errors (lock timeouts) are out of scope — note in NOTES.md.
