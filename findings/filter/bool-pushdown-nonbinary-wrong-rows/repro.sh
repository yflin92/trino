#!/usr/bin/env bash
# Self-contained repro for the boolean-pushdown wrong-rows finding.
# Fetches the fleet control env-setup.sh, builds the plugin + a 1-row db,
# starts Trino, and prints the ON / OFF / native results side by side.
set -eu

cd /workspace/trino
CTRL_BRANCH="cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control"
git fetch origin "$CTRL_BRANCH"
git show FETCH_HEAD:env-setup.sh > /tmp/env-setup.sh
. /tmp/env-setup.sh

start_docker
prep_files   # builds the pinned-SHA plugin, pulls trinodb/trino:483

# --- build the db NATIVELY (one row, flag stored as integer 2) ---
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/tmp/data/boolbug.db')
cur = c.cursor()
cur.execute("DROP TABLE IF EXISTS t")
cur.execute("CREATE TABLE t (id INTEGER, flag BOOLEAN)")
cur.execute("INSERT INTO t VALUES (1, 2)")
c.commit(); c.close()
print("built /tmp/data/boolbug.db")
PY
chmod 666 /tmp/data/boolbug.db

make_catalog boolbug boolbug.db
start_trino trino
export TRINO_CONTAINER=trino

echo "=== projection (flag reads as TRUE) ==="
tq "SELECT id, flag FROM boolbug.main.t ORDER BY id"
echo "=== WHERE flag = true : pushdown ON  (BUG: empty) ==="
tq "SELECT id FROM boolbug.main.t WHERE flag = true ORDER BY id"
echo "=== WHERE flag = true : pushdown OFF (correct: id 1) ==="
tqs 'allow_pushdown_into_connectors=false' "SELECT id FROM boolbug.main.t WHERE flag = true ORDER BY id"
echo "=== WHERE flag <> true : pushdown ON  (BUG: id 1) ==="
tq "SELECT id FROM boolbug.main.t WHERE flag <> true ORDER BY id"
echo "=== WHERE flag <> true : pushdown OFF (correct: empty) ==="
tqs 'allow_pushdown_into_connectors=false' "SELECT id FROM boolbug.main.t WHERE flag <> true ORDER BY id"
echo "=== EXPLAIN (ON): predicate pushed as 'constraint on [flag]' ==="
tq "EXPLAIN SELECT id FROM boolbug.main.t WHERE flag = true"
echo "=== native/driver passthrough: SQLite's own 'flag = 1' => empty ==="
tq "SELECT id FROM TABLE(boolbug.system.query(query => 'SELECT id FROM t WHERE flag = 1'))"
