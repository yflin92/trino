#!/usr/bin/env bash
# Self-contained repro for: BOOLEAN column holding a fractional REAL (abs<1)
# is read as FALSE by Trino, but SQLite's own boolean semantics make it TRUE.
#
# Fetches the control-branch env-setup.sh, builds the plugin, builds the db
# natively, makes the catalog, restarts Trino, and prints Trino (pushdown
# on + off), the native value, and the bundled driver's own truthiness.
set -eu

cd /workspace/trino
CTRL_BRANCH=cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git fetch origin "$CTRL_BRANCH"
git show FETCH_HEAD:env-setup.sh > /tmp/env-setup.sh
. /tmp/env-setup.sh

start_docker
prep_files    # builds the PR plugin (~30s) if not already built

# --- build the db NATIVELY ---
python3 - <<'PY'
import sqlite3, os
p='/tmp/data/boolf.db'
if os.path.exists(p): os.remove(p)
c=sqlite3.connect(p); cur=c.cursor()
cur.execute("CREATE TABLE t(flag BOOLEAN)")
cur.execute("INSERT INTO t VALUES (0.5)")   # REAL 0.5 ; SQLite truthiness = TRUE
c.commit()
print("native:", cur.execute("SELECT flag, typeof(flag), CASE WHEN flag THEN 'true' ELSE 'false' END FROM t").fetchone())
c.close()
PY
chmod 666 /tmp/data/boolf.db

make_catalog boolf boolf.db
start_trino trino
export TRINO_CONTAINER=trino

echo "=== Trino (pushdown ON) ==="
tq "SELECT flag FROM boolf.main.t"
echo "=== Trino (pushdown OFF) ==="
tqs "allow_pushdown_into_connectors=false" "SELECT flag FROM boolf.main.t"
echo "=== Native value + SQLite truthiness (bundled driver 3.53.4) ==="
tq "SELECT * FROM TABLE(boolf.system.query(query => 'SELECT flag, typeof(flag) AS typeof, CASE WHEN flag THEN 1 ELSE 0 END AS sqlite_truth FROM t'))"

echo
echo "EXPECTED (bug present): Trino prints flag=false; SQLite sqlite_truth=1 (true)."
