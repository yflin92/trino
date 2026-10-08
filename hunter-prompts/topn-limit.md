You are a HUNTER in Trino fleet run sqlite-20261008-1731. Your target is the SQLite connector from PR yflin92/trino#1 at commit 7ce55e6b32342d27fec58c7009f85abb1b0c6fe6, built as a plugin for trinodb/trino:483.

IMPORTANT — the cross-session channel is GIT, not a shared filesystem. /agentfs does NOT exist here. Control files live on a git branch; you deliver findings by pushing a git branch. Your git pushes MUST use a ref shaped `cs_<YOUR_SESSION_ID>/<branch>` (a push guard rejects other shapes) and the default repo commit identity (do NOT override user.name/user.email — the guard requires the intentlab-ai[bot] identity).

YOUR SESSION ID: cs__R66Y1doWP
YOUR FINDINGS BRANCH (push here): cs__R66Y1doWP/fleet-sqlite-20261008-1731-findings-topn-limit

Your job: find queries that return WRONG ROWS and DML that changes the WRONG ROWS or stores wrong values. Errors/crashes are OUT OF SCOPE — note them in NOTES.md only.

## Step 0 — get control files, build, set up (do this first)
```
cd /workspace/trino            # pre-cloned yflin92/trino repo (has push creds)
CTRL=origin/cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git fetch origin cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git show $CTRL:env-setup.sh   > /tmp/env-setup.sh
git show $CTRL:ground-rules.md > /tmp/ground-rules.md
# (optional) your full prompt: git show $CTRL:hunter-prompts/topn-limit.md
# read /tmp/ground-rules.md
. /tmp/env-setup.sh
start_docker        # overlay2-on-tmpfs; the ONLY driver that works here (virtiofs root rejects overlay2)
prep_files          # clones PR source to /tmp/trino-pr, BUILDS the plugin locally (~30s), pulls image, caps heap
```
`prep_files` builds the plugin from the pinned PR SHA. If the build fails, STOP and report the compiler errors in your final message — do not hunt against a different build.

Per database file:
```
python3 -c "import sqlite3; c=sqlite3.connect('/tmp/data/<file>.db'); c.execute('...'); c.commit(); c.close()"   # build db NATIVELY first
chmod 666 /tmp/data/<file>.db
make_catalog <catalog> <file>.db
start_trino trino
export TRINO_CONTAINER=trino
tq  "SELECT ..."
tqs "allow_pushdown_into_connectors=false" "SELECT ..."
```
Restart Trino (`start_trino trino`) after adding a catalog. One catalog per db file. db files under /tmp/data. Never write to a db file natively while Trino queries it. docker stats shows 0B; use ps/free.

Caveat: native sqlite3 is 3.45.1 but the driver bundles 3.53.4 — if behaviour changed between them, say so and cross-check via `SELECT * FROM TABLE(<cat>.system.query(query => 'select ...'))`.

Before hunting: `tq "SHOW SESSION LIKE '<catalog>.%'"`; record pushdown toggles + write_parallelism in NOTES.md.

## Method: hypothesis-driven (not random SQL)
1. Create the db natively with the declared types/collations/edge values needed.
2. READS (filter/topn/type/metadata):
   a. Trino pushdown ON; EXPLAIN to confirm the filter/top-N is in the remote query (ON -> `TableScan[constraint on ...]`; a `ScanFilter`/`Filter` node = NOT pushed). Save EXPLAIN.
   b. Trino `allow_pushdown_into_connectors=false`.
   c. Equivalent NATIVE query via python sqlite3. Trino comparisons are case- and trailing-space-sensitive; Trino sorts NULLS LAST by default.
3. WRITES (DML): copy the db to A and B. Trino statement vs A; equivalent native statement vs B (separate catalog). Dump both tables natively WITH typeof(col) per value; diff.
4. Compare result sets as MULTISETS (ordered only with ORDER BY). Double aggregates rel tol 1e-9. Run each side 3x; nondeterministic -> say so and skip.
5. On a mismatch write /tmp/findings/topn-limit/<slug>/ with: setup.py, query.trino.sql, query.native.sql, result.pushdown_on.csv, result.pushdown_off.csv, result.native.csv (DML: table.after_trino.csv + table.after_native.csv with typeof), explain.txt, repro.sh (self-contained: fetches the control branch env-setup.sh, builds db+catalog, restarts Trino, prints ON/OFF/native), finding.md (hypothesis, what differs, the PR doc/claim it contradicts, suspected code path + cited lines).
6. Reduce each finding to the smallest file + query that still reproduces it.

## Step N — DELIVER via git
When done (all seeds + your own hypotheses, or 2h cap):
```
mkdir -p /tmp/findings/topn-limit
printf 'hypotheses tested and outcomes...\n' > /tmp/findings/topn-limit/DONE   # include NOTES.md too
cd /workspace/trino
git stash -u 2>/dev/null; git checkout -q --orphan fl-topn-limit
git rm -rqf --cached . 2>/dev/null; rm -f .git/index
mkdir -p findings && cp -r /tmp/findings/topn-limit findings/
git add findings/topn-limit
git commit -q -m "findings: topn-limit (run sqlite-20261008-1731)"   # uses default bot identity; do NOT pass -c user.*
git push -f origin HEAD:refs/heads/cs__R66Y1doWP/fleet-sqlite-20261008-1731-findings-topn-limit
```
Confirm the push printed the new ref. If everything was clean, still push a branch containing just findings/topn-limit/DONE + NOTES.md.

In your FINAL message state: the exact branch you pushed, the finding slugs, and a one-line verdict per seed hypothesis. You NEVER create Intent tasks. Deliverable = pushed findings branch + final summary.

## Your area is: topn-limit
### Seed hypotheses (PROBE, not known bugs — add your own from reading /tmp/trino-pr/plugin/trino-sqlite/src/main/java/io/trino/plugin/sqlite/):
- NULL ordering: SQLite sorts NULLs FIRST for ASC; Trino defaults NULLS LAST. topNFunction emits explicit "NULLS FIRST/LAST" (SqliteClient line ~304-314) — verify it's correct for every direction (ASC/DESC × nullsFirst/nullsLast).
- COLLATE BINARY ORDER BY on NOCASE/RTRIM columns (topNFunction adds COLLATE only when isText).
- Ties at the LIMIT boundary; LIMIT under a filter only partly pushed down; OFFSET.
- Ordering of -0.0, of decimals stored as floats, of date text with leading zeros.
- Top-N on columns mapped to varchar (NUMERIC, typeless, DATETIME): supportsTopN returns false for ANY/forced-varchar (line ~291-299) — verify it's actually NOT pushed down for those, and that when pushed for real columns the order matches Trino. SQLite sorts by storage class (numbers before text, 9 before 10).
Confirm each top-N is pushed (EXPLAIN shows Limit/TopN inside TableScan) before comparing.
