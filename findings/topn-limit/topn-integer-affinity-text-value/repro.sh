#!/usr/bin/env bash
# Self-contained repro for: top-N pushdown returns the WRONG row when an
# INTEGER-affinity column holds a non-numeric text value.
#
# Fetches the fleet control branch env-setup.sh, builds the plugin + db + catalog,
# starts Trino, and prints pushdown ON / pushdown OFF / native results.
set -eu

cd /workspace/trino
CTRL=origin/cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git fetch origin cs_BKgcmnVOmV/fleet-sqlite-20261008-1731-control
git show "$CTRL":env-setup.sh > /tmp/env-setup.sh
. /tmp/env-setup.sh

start_docker
prep_files

# Build the minimal db natively
python3 - <<'PY'
import sqlite3, os
p = '/tmp/data/mini.db'
if os.path.exists(p):
    os.remove(p)
c = sqlite3.connect(p); cur = c.cursor()
cur.execute("CREATE TABLE t (id INTEGER, v INTEGER)")
cur.execute("INSERT INTO t VALUES (1, 5)")
cur.execute("INSERT INTO t VALUES (2, 'zzz')")  # stays TEXT in an INTEGER-affinity column
c.commit()
for r in cur.execute("SELECT id, v, typeof(v) FROM t"):
    print("native row:", r)
c.close()
PY
chmod 666 /tmp/data/mini.db

make_catalog mini mini.db
start_trino trino
export TRINO_CONTAINER=trino

echo
echo "=== Trino reads both rows (note id=2 reads as 0 in BOTH modes) ==="
tq "SELECT id, v FROM mini.main.t ORDER BY id"

echo
echo "=== EXPLAIN (confirms top-N pushed into TableScan) ==="
tq "EXPLAIN SELECT id, v FROM mini.main.t ORDER BY v ASC LIMIT 1" | grep -iE "topn|tablescan"

echo
echo "=== ORDER BY v ASC LIMIT 1, pushdown ON (WRONG: returns id=1,v=5) ==="
tq "SELECT id, v FROM mini.main.t ORDER BY v ASC LIMIT 1"

echo
echo "=== ORDER BY v ASC LIMIT 1, pushdown OFF (CORRECT: returns id=2,v=0) ==="
tqs "allow_pushdown_into_connectors=false" "SELECT id, v FROM mini.main.t ORDER BY v ASC LIMIT 1"

echo
echo "Driver bundled sqlite version:"
tq "SELECT * FROM TABLE(mini.system.query(query => 'select sqlite_version()'))"
