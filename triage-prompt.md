You are the TRIAGE session for Trino fleet run **sqlite-20261008-1731** (SQLite connector, PR yflin92/trino#1 @ `7ce55e6b32342d27fec58c7009f85abb1b0c6fe6`). Parent Intent task: **875e5b37fa222f127c081367**. ONLY you create tasks.

Cross-session channel is GIT (there is no shared /agentfs). Everything is on branches of `yflin92/trino`.

## Step 0 — get control files and the findings
```
cd /workspace/trino
CTRL=cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git fetch origin $CTRL
git show origin/$CTRL:env-setup.sh   > /tmp/env-setup.sh
git show origin/$CTRL:ground-rules.md > /tmp/ground-rules.md
git show origin/$CTRL:run.json        > /tmp/run.json   # area -> hunter session id map
# Discover all hunter findings branches:
git ls-remote origin '*fleet-sqlite-20261008-1731-findings-*' | awk '{print $2}' | sed 's#refs/heads/##'
```
For each findings branch `cs_<sid>/fleet-sqlite-20261008-1731-findings-<area>`, check it out into a worktree or fetch+`git show` its `findings/<area>/` tree:
```
git fetch origin <branch>
git checkout -b triage-<area> FETCH_HEAD   # or: git worktree add /tmp/tri-<area> FETCH_HEAD
```
Read every `findings/<area>/<slug>/finding.md`, and its NOTES.md (errors hunters noted) and DONE (hypotheses tested).

## Step 1 — reproduce
```
. /tmp/env-setup.sh
start_docker
prep_files          # builds the plugin once (~30s)
```
For every finding dir, run its `repro.sh` from scratch. If it does NOT reproduce, DROP the finding (list it in summary.md with a one-line reason). Only reproduced findings proceed.

## Step 2 — classify with the tiered oracle
- **Tier 1 (confirmed):** pushdown-ON result differs from pushdown-OFF result AND explain.txt shows the pushdown happened. → type `pushdown_mismatch`.
- **Tier 2:** ON and OFF agree, but the result differs from native SQLite / the native DML outcome. CONFIRMED ONLY IF you can cite the contract it violates:
  - Trino SQL semantics (docs/src/main/sphinx/language/, functions/)
  - the PR's type-mapping table / docs / description
  - a specific PR claim (e.g. "COLLATE BINARY ... also for columns declared with a different collation", "Nothing is executed", "Mapping them to varchar without pushdown is never wrong")
  Without a citation it is a DIALECT DIFFERENCE → summary.md only, NO task.
- **Data-corruption rule:** DML that changes rows the Trino statement did not select, or stores a value that does not read back as written → CONFIRMED (type `data_corruption`), regardless of tier.

## Step 3 — group by root cause
Group confirmed findings that share a code path or mapping rule. Each GROUP = one task.

## Step 4 — check against what is already known
- **Already known** (type `known_limitation`, priority `low`, quote the text): the PR's "Known limitations" section, its docs page (docs/src/main/sphinx/connector/sqlite.md), and its review comments — public GitHub API:
  `gh api repos/yflin92/trino/pulls/1/comments` and `gh api repos/yflin92/trino/issues/1/comments`.
- **Tested-as-intended:** if a test under `plugin/trino-sqlite/src/test/` asserts the behaviour as intended, say "tested-as-intended" and cite the test file + method.

IMPORTANT discrepancy to resolve: the ground-rules list UPDATE as a supported write, but a hunter observed Trino reports "This connector does not support modifying table rows" for UPDATE. Before filing anything about UPDATE, verify what the connector actually supports (check SqliteClient / base-jdbc merge support and the docs page). If UPDATE is genuinely unsupported-by-design and documented, any UPDATE-wrong-rows finding is moot; if it IS supposed to be supported, an UPDATE failure is a separate (likely error/functionality) issue — note it in summary.md (errors are out of scope for tasks).

## Step 5 — create one task per confirmed group (task_create)
- `parent_id`: 875e5b37fa222f127c081367
- **title:** `[sqlite][<area>] <wrong behaviour in one line>`
- **type:** `pushdown_mismatch` (Tier 1) | `wrong_result` (Tier 2 read) | `data_corruption` (write) | `known_limitation`
- **priority:** `critical` DML modifying wrong rows / corrupting stored values; `high` silently wrong/missing rows on a plausible query; `medium` edge-case-only values; `low` known_limitation
- **description:** minimal repro inline (native setup, the Trino statement, expected vs actual); the oracle tier + cited contract; pinned PR SHA, image + sqlite-jdbc versions, BOTH sqlite_version() values (driver 3.53.4 / native 3.45.1); the finding branch + dir path; suspected code path with file+line in the PR.
- Then `task_link-session` the hunter session that found it (look up the area→session id in /tmp/run.json). Do NOT comment on the GitHub PR — tasks are the output.

## Step 6 — write summary.md and deliver it
Write `/tmp/summary.md` covering: per-area counts (hypotheses tested / candidates / reproduced / confirmed / dropped); dropped findings + dialect differences each with a one-line reason; errors/crashes hunters noted; and a short verdict on each PR claim (collation handling, date bounds 0000-9999, NUMERIC→varchar safety, query() safety, single-writer default): held / broken (with task ids) / untested.

Deliver summary.md by pushing it on a branch (same git rules — remote ref must be `$SESSION_ID/<branch>`, default bot identity):
```
cd /workspace/trino
git stash -u 2>/dev/null; git checkout -q --orphan fl-triage
git rm -rqf --cached . 2>/dev/null; rm -f .git/index
mkdir -p triage && cp /tmp/summary.md triage/summary.md
git add triage/summary.md; git commit -q -m "triage summary (run sqlite-20261008-1731)"
git push -f origin "HEAD:refs/heads/$SESSION_ID/fleet-sqlite-20261008-1731-triage-summary"
```
In your FINAL message: list every task you created (id + title + priority), the dropped/dialect findings, and the per-claim verdict. The coordinator will post summary.md as a comment on the parent task.
