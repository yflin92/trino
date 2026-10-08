#!/usr/bin/env bash
# Self-contained repro: a Trino INSERT that fails with SQLITE_BUSY while a native
# writer holds the file lock leaves an orphan temp table (tmp_trino_*) permanently
# in the SQLite schema, exposed as a user table in SHOW TABLES.
#
# Requires: the fleet env-setup.sh already sourced and prep_files already run
# (docker up, plugin built, image pulled). This script builds the db + catalog,
# (re)starts Trino, and demonstrates the leftover.
set -u
. /tmp/env-setup.sh >/dev/null 2>&1

# --- db + catalog ---
python3 - <<'PY'
import sqlite3
c=sqlite3.connect('/tmp/data/conc.db')
for (n,) in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'").fetchall():
    c.execute(f'DROP TABLE "{n}"')
c.execute('DROP TABLE IF EXISTS big')
c.execute('CREATE TABLE big (id INTEGER)')
c.commit(); c.close()
print('native sqlite_version:', sqlite3.sqlite_version)
PY
chmod 666 /tmp/data/conc.db
make_catalog conc conc.db
start_trino trino >/dev/null 2>&1
export TRINO_CONTAINER=trino

echo "=== driver bundled sqlite_version ==="
tq "SELECT * FROM TABLE(conc.system.query(query => 'select sqlite_version()'))"

echo "=== SHOW TABLES before ==="
tq "SHOW TABLES FROM conc.main"

# Native writer: wait until the connector CREATEs its temp table, then grab an
# EXCLUSIVE lock and hold it 8s (> the driver busy_timeout of ~3s for BOTH the
# page-sink write AND the rollback's DROP TABLE).
python3 - <<'PY' >/dev/null 2>&1 &
import sqlite3,time
start=time.time()
while time.time()-start<30:
    try:
        c=sqlite3.connect('/tmp/data/conc.db', timeout=0)
        t=[r[0] for r in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'")]; c.close()
        if t: break
    except Exception:
        try: c.close()
        except: pass
    time.sleep(0.004)
for _ in range(500):
    try:
        c=sqlite3.connect('/tmp/data/conc.db', timeout=0)
        c.execute('BEGIN EXCLUSIVE'); c.execute('SELECT 1')
        time.sleep(8); c.commit(); c.close(); break
    except Exception:
        try: c.close()
        except: pass
        time.sleep(0.004)
PY
CHOP=$!

echo "=== Trino INSERT (fails BUSY) ==="
tq "INSERT INTO conc.main.big SELECT (a.x-1)*5000+b.x FROM UNNEST(sequence(1,20)) a(x) CROSS JOIN UNNEST(sequence(1,5000)) b(x)"
wait $CHOP 2>/dev/null

echo "=== SHOW TABLES after (orphan tmp_trino_* remains) ==="
tq "SHOW TABLES FROM conc.main"

echo "=== native sqlite_schema after ==="
python3 - <<'PY'
import sqlite3
c=sqlite3.connect('/tmp/data/conc.db',timeout=30)
for r in c.execute("SELECT type,name FROM sqlite_schema WHERE type='table'"): print(r)
print('big count:', c.execute('SELECT count(*) FROM big').fetchone()[0])
c.close()
PY

echo
echo "CONTROL (hold only 4s = past page-sink busy_timeout but before rollback DROP timeout -> NO leftover):"
python3 - <<'PY'
import sqlite3
c=sqlite3.connect('/tmp/data/conc.db',timeout=30)
for (n,) in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'").fetchall():
    c.execute(f'DROP TABLE "{n}"')
c.commit(); c.close()
PY
python3 - <<'PY' >/dev/null 2>&1 &
import sqlite3,time
start=time.time()
while time.time()-start<30:
    try:
        c=sqlite3.connect('/tmp/data/conc.db', timeout=0)
        t=[r[0] for r in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'")]; c.close()
        if t: break
    except Exception:
        try: c.close()
        except: pass
    time.sleep(0.004)
for _ in range(500):
    try:
        c=sqlite3.connect('/tmp/data/conc.db', timeout=0)
        c.execute('BEGIN EXCLUSIVE'); c.execute('SELECT 1')
        time.sleep(4); c.commit(); c.close(); break
    except Exception:
        try: c.close()
        except: pass
        time.sleep(0.004)
PY
CHOP=$!
tq "INSERT INTO conc.main.big SELECT (a.x-1)*5000+b.x FROM UNNEST(sequence(1,20)) a(x) CROSS JOIN UNNEST(sequence(1,5000)) b(x)" >/dev/null 2>&1
wait $CHOP 2>/dev/null
echo "leftover temp tables after 4s-hold control:"
python3 - <<'PY'
import sqlite3
c=sqlite3.connect('/tmp/data/conc.db',timeout=30)
print([r[0] for r in c.execute("SELECT name FROM sqlite_schema WHERE name LIKE 'tmp_%'")])
c.close()
PY
